package io.github.wilfredluijk.navalsdk.tactical

import io.github.wilfredluijk.navalsdk.*
import kotlin.math.*

data class FireSolution(
    val bearingDeg: Double,
    val range: Double,
    val aimPos: Vec2,
    val targetId: Int,
)

data class EffectiveWeapons(
    val speed: Double,
    val maxRange: Double,
    val splashRadius: Double,
    val cooldownTicks: Int,
)

open class Gunner(
    val specs: ShipSpecs,
    val selfSplashMargin: Double = 1.5,
    val maxActiveAgeTicks: Int = 5,
    val requireRecentActive: Boolean = true,
    val powerups: PowerupConfig = PowerupConfig(),
    val simulationDt: Double = 0.1,
) {
    init {
        require(simulationDt.isFinite() && simulationDt > 0)
        require(selfSplashMargin.isFinite() && selfSplashMargin >= 1)
    }

    var nextFireTick: Int = 0
        private set

    private var pending: Pair<Int, Int>? = null

    fun effectiveWeapons(me: SelfState, activatePowerup: String? = null): EffectiveWeapons {
        fun active(name: String) =
            me.powerupActive(name) || (activatePowerup == name && me.powerupReady(name))
        val speed =
            specs.shellSpeed * if (active("long_range_salvo")) powerups.longRangeSpeedMult else 1.0
        val range =
            specs.maxShellRange *
                if (active("long_range_salvo")) powerups.longRangeRangeMult else 1.0
        val splash =
            specs.splashRadius * if (active("heavy_shell")) powerups.heavyShellSplashMult else 1.0
        var cooldown =
            specs.gunCooldownTicks *
                if (active("rapid_fire")) powerups.rapidFireCooldownMult else 1.0
        if (me.empTicksLeft > 0) cooldown *= powerups.empGunCooldownMult
        return EffectiveWeapons(
            speed,
            range,
            splash,
            floor(cooldown + 0.5).toInt().coerceAtLeast(1),
        )
    }

    open fun update(view: WorldView) {
        val left = view.me.gunCooldownTicksLeft
        if (left != null) {
            nextFireTick = view.tick + left
            pending = null
        } else
            pending?.let { (tick, ammo) ->
                if (view.tick > tick) {
                    if (view.me.ammo >= ammo) nextFireTick = view.tick
                    pending = null
                }
            }
    }

    fun canFire(view: WorldView, me: SelfState = view.me): Boolean =
        (me.gunCooldownTicksLeft?.let { it == 0 } ?: (view.tick >= nextFireTick)) && me.ammo > 0

    /** Pure: call noteFired only when the solution is attached to an outbound command. */
    open fun solve(
        me: SelfState,
        track: Track,
        view: WorldView,
        activatePowerup: String? = null,
    ): FireSolution? {
        if (!canFire(view, me)) return null
        if (requireRecentActive && view.tick - track.lastActiveTick > maxActiveAgeTicks) return null
        val weapons = effectiveWeapons(me, activatePowerup)
        val aim = leadTarget(me.pos, track.pos, track.vel, weapons.speed) ?: return null
        val range = distance(me.pos, aim)
        if (range > weapons.maxRange) return null
        val flight = ceil(range / (weapons.speed * simulationDt)).coerceAtLeast(1.0) * simulationDt
        val heading = Math.toRadians(me.headingDeg)
        val futureMe = me.pos + Vec2(sin(heading), -cos(heading)) * (me.speed * flight)
        if (
            min(range, distance(futureMe, aim)) <
                specs.hitRadius + weapons.splashRadius * selfSplashMargin
        )
            return null
        return FireSolution(bearingTo(me.pos, aim), range, aim, track.trackId)
    }

    fun attempt(cmd: Command, me: SelfState, track: Track, view: WorldView): Boolean {
        update(view)
        val solution = solve(me, track, view, cmd.activatePowerup) ?: return false
        cmd.fire = toFireCommand(solution)
        noteFired(view.tick, effectiveWeapons(me, cmd.activatePowerup).cooldownTicks, me.ammo)
        return true
    }

    fun noteFired(tick: Int, cooldownTicks: Int = specs.gunCooldownTicks, ammo: Int? = null) {
        nextFireTick = tick + cooldownTicks
        pending = ammo?.let { tick to it }
    }

    open fun reset() {
        nextFireTick = 0
        pending = null
    }

    companion object {
        fun toFireCommand(solution: FireSolution) = FireCommand(solution.bearingDeg, solution.range)
    }
}
