package io.github.wilfredluijk.navalsdk.tactical

import io.github.wilfredluijk.navalsdk.*
import kotlin.math.abs

data class Track(
    val trackId: Int,
    var kind: String,
    var pos: Vec2,
    var observedPos: Vec2,
    var vel: Vec2,
    var lastSeenTick: Int,
    val firstSeenTick: Int,
    var lastActiveTick: Int,
    var confidence: Double,
    var source: String,
)

/** Associate unstable wire contact IDs using position/range or passive bearings. */
open class Tracker(
    val specs: ShipSpecs,
    val tickHz: Int = 10,
    val simulationDt: Double = 0.1,
    val activeGate: Double = 60.0,
    val passiveBearingGateDeg: Double = 20.0,
    val velocityAlpha: Double = 0.3,
    velocityWindowTicks: Int = 10,
    val stalenessTicks: Int = 40,
) {
    init {
        require(simulationDt.isFinite() && simulationDt > 0)
        require(
            activeGate.isFinite() &&
                activeGate > 0 &&
                passiveBearingGateDeg.isFinite() &&
                passiveBearingGateDeg > 0
        )
        require(velocityAlpha in 0.0..1.0 && stalenessTicks >= 0)
    }

    private val window = velocityWindowTicks.coerceAtLeast(2)
    private val tracked = linkedMapOf<Int, Track>()
    private val history = mutableMapOf<Int, MutableList<Pair<Int, Vec2>>>()
    private var nextId = 1
    private var lastTick: Int? = null
    private var matchId: String? = null
    val tracks: List<Track>
        get() = tracked.values.sortedBy { it.trackId }

    operator fun get(trackId: Int): Track? = tracked[trackId]

    open fun reset() {
        tracked.clear()
        history.clear()
        nextId = 1
        lastTick = null
        matchId = null
    }

    private fun predict(track: Track, tick: Int): Vec2 =
        track.observedPos +
            track.vel * ((tick - track.lastActiveTick).coerceAtLeast(0) * simulationDt)

    private fun compatible(a: String, b: String) = a == b || a == "unknown" || b == "unknown"

    open fun update(view: WorldView): List<Track> {
        val tick = view.tick
        if (lastTick?.let { tick < it } == true || (matchId != null && matchId != view.matchId))
            reset()
        lastTick = tick
        matchId = view.matchId
        // Prune before association so old or cross-match tracks cannot be revived.
        tracked.values
            .filter { tick - it.lastSeenTick !in 0..stalenessTicks }
            .map { it.trackId }
            .forEach {
                tracked.remove(it)
                history.remove(it)
            }
        val matched = mutableSetOf<Int>()
        for (c in view.contacts.filter { it.range != null }) {
            val best =
                tracked.values
                    .filter { it.trackId !in matched && compatible(it.kind, c.kind) }
                    .map { it to distance(predict(it, tick), c.pos) }
                    .filter { it.second < activeGate }
                    .minByOrNull { it.second }
                    ?.first
            if (best == null) {
                val id = nextId++
                tracked[id] =
                    Track(
                        id,
                        c.kind,
                        c.pos,
                        c.pos,
                        Vec2.ZERO,
                        tick,
                        tick,
                        tick,
                        c.confidence,
                        "active",
                    )
                history[id] = mutableListOf(tick to c.pos)
                matched += id
            } else {
                val h = history.getValue(best.trackId)
                if (h.lastOrNull()?.first == tick) h[h.lastIndex] = tick to c.pos
                else h += tick to c.pos
                if (h.size > window) h.removeAt(0)
                val dt = (tick - h.first().first) * simulationDt
                if (h.size >= 2 && dt > 0) {
                    val velocity = (c.pos - h.first().second) * (1.0 / dt)
                    best.vel =
                        if (best.vel == Vec2.ZERO) velocity
                        else velocity * velocityAlpha + best.vel * (1 - velocityAlpha)
                }
                best.observedPos = c.pos
                best.pos = c.pos
                best.lastSeenTick = tick
                best.lastActiveTick = tick
                best.confidence = c.confidence
                best.source = "active"
                if (best.kind == "unknown") best.kind = c.kind
                matched += best.trackId
            }
        }
        for (c in view.contacts.filter { it.range == null }) {
            val best =
                tracked.values
                    .filter { it.trackId !in matched && compatible(it.kind, c.kind) }
                    .map {
                        it to
                            abs(
                                signedBearingDelta(
                                    c.bearingDeg,
                                    bearingTo(view.me.pos, predict(it, tick)),
                                )
                            )
                    }
                    .filter { it.second < passiveBearingGateDeg }
                    .minByOrNull { it.second }
                    ?.first
            if (best != null) {
                best.lastSeenTick = tick
                best.confidence = c.confidence
                best.source = "passive"
                matched += best.trackId
            }
        }
        tracked.values
            .filter { it.lastActiveTick != tick }
            .forEach {
                it.pos = predict(it, tick)
                if (it.lastSeenTick != tick) it.source = "dead_reckoned"
            }
        return tracks
    }
}
