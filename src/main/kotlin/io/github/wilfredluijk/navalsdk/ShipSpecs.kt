package io.github.wilfredluijk.navalsdk

import com.fasterxml.jackson.databind.JsonNode
import io.github.wilfredluijk.navalsdk.internal.*

/** Typed server configuration; wire field names remain snake_case. */
data class ShipSpecs(
    val maxForwardSpeed: Double,
    val maxReverseSpeed: Double,
    val acceleration: Double,
    val turnRateDegPerS: Double,
    val hullHp: Int,
    val maxAmmo: Int,
    val gunCooldownTicks: Int,
    val hitRadius: Double,
    val shellSpeed: Double,
    val maxShellRange: Double,
    val splashRadius: Double,
    val maxSplashDamage: Int,
) {
    init {
        positive(maxForwardSpeed)
        positive(maxReverseSpeed)
        positive(acceleration)
        positive(turnRateDegPerS)
        positive(hullHp.toDouble())
        positive(maxAmmo.toDouble())
        positive(gunCooldownTicks.toDouble())
        positive(hitRadius)
        positive(shellSpeed)
        positive(maxShellRange)
        positive(splashRadius)
        positive(maxSplashDamage.toDouble())
    }

    companion object {
        fun fromJson(node: JsonNode): ShipSpecs {
            node.obj()
            return ShipSpecs(
                maxForwardSpeed = node.num("max_forward_speed"),
                maxReverseSpeed = node.num("max_reverse_speed"),
                acceleration = node.num("acceleration"),
                turnRateDegPerS = node.num("turn_rate_deg_per_s"),
                hullHp = node.int("hull_hp"),
                maxAmmo = node.int("max_ammo"),
                gunCooldownTicks = node.int("gun_cooldown_ticks"),
                hitRadius = node.num("hit_radius"),
                shellSpeed = node.num("shell_speed"),
                maxShellRange = node.num("max_shell_range"),
                splashRadius = node.num("splash_radius"),
                maxSplashDamage = node.int("max_splash_damage"),
            )
        }
    }
}
