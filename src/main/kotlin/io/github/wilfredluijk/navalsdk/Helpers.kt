package io.github.wilfredluijk.navalsdk

import com.fasterxml.jackson.databind.JsonNode
import io.github.wilfredluijk.navalsdk.internal.*
import kotlin.math.*

/** Coordinates use +x east and +y south; velocity is in simulation units/second. */
data class Vec2(val x: Double, val y: Double) {
    init {
        finite(x)
        finite(y)
    }

    operator fun plus(other: Vec2) = Vec2(x + other.x, y + other.y)

    operator fun minus(other: Vec2) = Vec2(x - other.x, y - other.y)

    operator fun times(scale: Double) = Vec2(x * scale, y * scale)

    fun toJson(): JsonNode = Wire.mapper.createArrayNode().add(x).add(y)

    companion object {
        val ZERO = Vec2(0.0, 0.0)

        fun fromJson(node: JsonNode): Vec2 {
            val a = node.array()
            require(a.size == 2) { "expected coordinate pair" }
            return Vec2(a[0].number(), a[1].number())
        }
    }
}

fun distance(a: Vec2, b: Vec2): Double = hypot(b.x - a.x, b.y - a.y)

fun wrapBearing(deg: Double): Double = ((deg % 360.0) + 360.0) % 360.0

/** Shortest clockwise turn, in [-180, 180). */
fun signedBearingDelta(targetDeg: Double, currentDeg: Double): Double =
    wrapBearing(targetDeg - currentDeg + 180.0) - 180.0

fun clamp(value: Double, lo: Double, hi: Double): Double = value.coerceIn(lo, hi)

fun bearingTo(from: Vec2, to: Vec2): Double =
    wrapBearing(Math.toDegrees(atan2(to.x - from.x, from.y - to.y)))

/** Solve the earliest nonnegative intercept; null means the target cannot be intercepted. */
fun leadTarget(shooterPos: Vec2, targetPos: Vec2, targetVel: Vec2, shellSpeed: Double): Vec2? {
    if (!shellSpeed.isFinite() || shellSpeed <= 0) return null
    val r = targetPos - shooterPos
    val a = targetVel.x * targetVel.x + targetVel.y * targetVel.y - shellSpeed * shellSpeed
    val b = 2 * (r.x * targetVel.x + r.y * targetVel.y)
    val c = r.x * r.x + r.y * r.y
    val t =
        if (abs(a) < 1e-9) {
            if (abs(b) < 1e-9) {
                if (c < 1e-9) 0.0 else return null
            } else (-c / b).takeIf { it >= 0 } ?: return null
        } else {
            val discriminant = b * b - 4 * a * c
            if (discriminant < 0) return null
            val root = sqrt(discriminant)
            listOf((-b - root) / (2 * a), (-b + root) / (2 * a)).filter { it >= 0 }.minOrNull()
                ?: return null
        }
    return targetPos + targetVel * t
}
