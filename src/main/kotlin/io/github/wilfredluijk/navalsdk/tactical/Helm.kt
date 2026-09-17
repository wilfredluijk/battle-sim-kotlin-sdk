package io.github.wilfredluijk.navalsdk.tactical

import io.github.wilfredluijk.navalsdk.*
import kotlin.math.*

data class Steering(val throttle: Double, val rudder: Double)

open class Helm(
    val specs: ShipSpecs,
    val mapWidth: Double = 700.0,
    val mapHeight: Double = 700.0,
    val wallMargin: Double = 30.0,
    val turnAggressionDeg: Double = 30.0,
    val alignThresholdDeg: Double = 10.0,
    val minTurnThrottle: Double = 0.55,
    val powerups: PowerupConfig = PowerupConfig(),
) {
    init {
        require(mapWidth > 0 && mapHeight > 0 && wallMargin >= 0 && turnAggressionDeg > 0)
    }

    open fun steerToBearing(
        me: SelfState,
        targetBearingDeg: Double,
        respectWalls: Boolean = true,
        desiredThrottle: Double = 1.0,
    ): Steering {
        val bearing = if (respectWalls) wallOverride(me, targetBearingDeg) else targetBearingDeg
        val delta = signedBearingDelta(bearing, me.headingDeg)
        val rudder = clamp(delta / turnAggressionDeg, -1.0, 1.0)
        val throttle =
            if (abs(delta) <= alignThresholdDeg) desiredThrottle
            else {
                val scale = clamp((180 - abs(delta)) / max(180 - alignThresholdDeg, 1e-6), 0.0, 1.0)
                minTurnThrottle + (desiredThrottle - minTurnThrottle) * scale
            }
        return Steering(throttle, rudder)
    }

    fun steerToPoint(
        me: SelfState,
        target: Vec2,
        respectWalls: Boolean = true,
        desiredThrottle: Double = 1.0,
    ): Steering = steerToBearing(me, bearingTo(me.pos, target), respectWalls, desiredThrottle)

    private fun wallOverride(me: SelfState, target: Double): Double {
        val overdrive = me.powerupActive("overdrive")
        val acceleration = specs.acceleration * if (overdrive) powerups.overdriveAccelMult else 1.0
        val turnRate = specs.turnRateDegPerS * if (overdrive) powerups.overdriveTurnMult else 1.0
        val maxSpeed = specs.maxForwardSpeed * if (overdrive) powerups.overdriveSpeedMult else 1.0
        val yaw = turnRate * abs(me.speed) / maxSpeed
        val horizon = max(abs(me.speed) / acceleration, min(90 / max(yaw, 1e-6), 3.0))
        val heading = Math.toRadians(me.headingDeg)
        val future = me.pos + Vec2(sin(heading), -cos(heading)) * (me.speed * horizon)
        val pushX =
            when {
                min(me.pos.x, future.x) < wallMargin -> 1.0
                max(me.pos.x, future.x) > mapWidth - wallMargin -> -1.0
                else -> 0.0
            }
        val pushY =
            when {
                min(me.pos.y, future.y) < wallMargin -> 1.0
                max(me.pos.y, future.y) > mapHeight - wallMargin -> -1.0
                else -> 0.0
            }
        if (pushX == 0.0 && pushY == 0.0) return target
        val inward = bearingTo(Vec2.ZERO, Vec2(pushX, pushY))
        return if (abs(signedBearingDelta(target, inward)) <= 90) target else inward
    }
}
