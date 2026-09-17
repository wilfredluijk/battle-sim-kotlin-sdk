package io.github.wilfredluijk.navalsdk.tactical

import io.github.wilfredluijk.navalsdk.*

fun interface SensorPolicy {
    fun choose(view: WorldView, tracker: Tracker): SensorMode

    fun reset() {}
}

class AlwaysActive : SensorPolicy {
    override fun choose(view: WorldView, tracker: Tracker) = SensorMode.ACTIVE
}

class AlwaysPassive : SensorPolicy {
    override fun choose(view: WorldView, tracker: Tracker) = SensorMode.PASSIVE
}

class DutyCycle(val activeTicks: Int = 10, val passiveTicks: Int = 20) : SensorPolicy {
    init {
        require(
            activeTicks >= 0 &&
                passiveTicks >= 0 &&
                activeTicks.toLong() + passiveTicks <= Int.MAX_VALUE
        )
    }

    override fun choose(view: WorldView, tracker: Tracker) =
        if (view.tick % (activeTicks + passiveTicks).coerceAtLeast(1) < activeTicks)
            SensorMode.ACTIVE
        else SensorMode.PASSIVE
}

class PingWhenStale(val staleThresholdTicks: Int = 4) : SensorPolicy {
    override fun choose(view: WorldView, tracker: Tracker): SensorMode {
        val ships = tracker.tracks.filter { it.kind == "ship" }
        return if (
            ships.isEmpty() || ships.any { view.tick - it.lastActiveTick >= staleThresholdTicks }
        )
            SensorMode.ACTIVE
        else SensorMode.PASSIVE
    }
}

enum class EvaderState {
    IDLE,
    EVADING,
    COOLDOWN,
}

open class Evader(
    val evasionTicks: Int = 15,
    val cooldownTicks: Int = 10,
    val throttle: Double = 1.0,
    initialRudderSign: Double = 1.0,
) {
    private val initialSign = if (initialRudderSign >= 0) 1.0 else -1.0
    private var sign = initialSign
    private var until = 0
    var state: EvaderState = EvaderState.IDLE
        private set

    init {
        require(evasionTicks > 0 && cooldownTicks >= 0)
    }

    open fun reset() {
        state = EvaderState.IDLE
        until = 0
        sign = initialSign
    }

    open fun update(view: WorldView): Command? {
        if (state != EvaderState.IDLE && view.tick >= until) {
            if (state == EvaderState.EVADING) {
                state = EvaderState.COOLDOWN
                until = view.tick + cooldownTicks
            } else state = EvaderState.IDLE
        }
        if (view.events.any { it is HitEvent } && state != EvaderState.EVADING) {
            if (state == EvaderState.COOLDOWN) sign = -sign
            state = EvaderState.EVADING
            until = view.tick + evasionTicks
        }
        return if (state == EvaderState.EVADING) Command(throttle, sign) else null
    }
}

class ThreatList(val tracks: List<Track>, val mePos: Vec2) : Iterable<Track> {
    val size: Int
        get() = tracks.size

    fun isEmpty() = tracks.isEmpty()

    override fun iterator() = tracks.iterator()

    fun nearest(): Track? = tracks.minByOrNull { distance(mePos, it.pos) }

    fun farthest(): Track? = tracks.maxByOrNull { distance(mePos, it.pos) }

    fun byId(trackId: Int): Track? = tracks.find { it.trackId == trackId }
}

data class TacticalContext(
    val view: WorldView,
    val me: SelfState,
    val specs: ShipSpecs,
    val tracker: Tracker,
    val threats: ThreatList,
    val mapWidth: Double,
    val mapHeight: Double,
)

data class PatrolRect(val x1: Double, val y1: Double, val x2: Double, val y2: Double)

/** Kotlin sealed intents make impossible combinations unrepresentable. */
sealed interface Intent {
    data class Engage(val target: Track) : Intent

    data class Patrol(val rect: PatrolRect) : Intent

    data class RetreatTo(val point: Vec2) : Intent

    data object Hold : Intent

    data class Custom(val command: Command) : Intent

    companion object {
        fun engage(target: Track): Intent = Engage(target)

        fun patrol(rect: PatrolRect): Intent = Patrol(rect)

        fun retreatTo(point: Vec2): Intent = RetreatTo(point)

        fun hold(): Intent = Hold

        fun custom(command: Command): Intent = Custom(command)
    }
}

/** Override [decide] and [onTacticalWelcome]; call super if overriding lifecycle callbacks. */
open class TacticalBot : Bot() {
    var tracker: Tracker? = null
    var gunner: Gunner? = null
    var helm: Helm? = null
    var evader: Evader? = null
    var sensorPolicy: SensorPolicy = AlwaysActive()
    private var patrolCorner = 0

    open fun decide(ctx: TacticalContext): Intent = Intent.Hold

    open fun onTacticalWelcome(welcome: Welcome) {}

    override fun onWelcome(welcome: Welcome) {
        this.welcome = welcome
        val p = welcome.rules?.powerups ?: PowerupConfig()
        tracker = Tracker(welcome.shipSpecs, welcome.tickHz, welcome.simulationDt)
        gunner = Gunner(welcome.shipSpecs, powerups = p, simulationDt = welcome.simulationDt)
        helm =
            Helm(
                welcome.shipSpecs,
                welcome.map.width.toDouble(),
                welcome.map.height.toDouble(),
                powerups = p,
            )
        if (evader == null) evader = Evader()
        onTacticalWelcome(welcome)
    }

    override fun onGameStart(tick: Int, startingPosition: Vec2, startingHeadingDeg: Double) {
        tracker?.reset()
        gunner?.reset()
        evader?.reset()
        sensorPolicy.reset()
        patrolCorner = 0
    }

    override fun onTick(view: WorldView): Command {
        val tracker = tracker ?: return Command()
        val gunner = gunner ?: return Command()
        val helm = helm ?: return Command()
        val evader = evader ?: return Command()
        val w = welcome ?: return Command()
        val tracks = tracker.update(view)
        gunner.update(view)
        val threats = ThreatList(tracks.filter { it.kind == "ship" }, view.me.pos)
        val ctx =
            TacticalContext(
                view,
                view.me,
                w.shipSpecs,
                tracker,
                threats,
                w.map.width.toDouble(),
                w.map.height.toDouble(),
            )
        evader.update(view)?.let {
            it.sensorMode = sensorPolicy.choose(view, tracker)
            return it
        }
        val intent = decide(ctx)
        if (intent is Intent.Custom) return intent.command
        val targetPoint =
            when (intent) {
                is Intent.Engage -> intent.target.pos
                is Intent.RetreatTo -> intent.point
                is Intent.Patrol -> {
                    val r = intent.rect
                    val corners =
                        listOf(
                            Vec2(r.x1, r.y1),
                            Vec2(r.x2, r.y1),
                            Vec2(r.x2, r.y2),
                            Vec2(r.x1, r.y2),
                        )
                    if (distance(view.me.pos, corners[patrolCorner]) < 25)
                        patrolCorner = (patrolCorner + 1) % 4
                    corners[patrolCorner]
                }
                else -> null
            }
        val cmd =
            if (targetPoint == null) Command()
            else helm.steerToPoint(view.me, targetPoint).let { Command(it.throttle, it.rudder) }
        cmd.sensorMode = sensorPolicy.choose(view, tracker)
        val target =
            when (intent) {
                is Intent.Engage -> intent.target
                Intent.Hold -> null
                else -> threats.nearest()
            }
        target?.let { gunner.attempt(cmd, view.me, it, view) }
        return cmd
    }
}
