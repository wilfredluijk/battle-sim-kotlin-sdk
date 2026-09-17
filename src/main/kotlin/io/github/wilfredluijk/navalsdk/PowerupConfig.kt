package io.github.wilfredluijk.navalsdk

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.github.wilfredluijk.navalsdk.internal.*

/** Typed server configuration; wire field names remain snake_case. */
data class PowerupConfig(
    val overdriveDurationTicks: Int = 50,
    val overdriveSpeedMult: Double = 1.6,
    val overdriveAccelMult: Double = 1.6,
    val overdriveTurnMult: Double = 1.5,
    val reinforcedHullDurationTicks: Int = 70,
    val reinforcedHullDamageMult: Double = 0.45,
    val repairDronesDurationTicks: Int = 50,
    val repairDronesHpPerTick: Int = 1,
    val repairDronesInstantHp: Int = 20,
    val smokeScreenDurationTicks: Int = 80,
    val smokeScreenRadius: Double = 70.0,
    val rapidFireDurationTicks: Int = 50,
    val rapidFireCooldownMult: Double = 0.5,
    val heavyShellDurationTicks: Int = 30,
    val heavyShellSplashMult: Double = 1.5,
    val heavyShellDamageMult: Double = 1.3,
    val longRangeDurationTicks: Int = 40,
    val longRangeRangeMult: Double = 1.5,
    val longRangeSpeedMult: Double = 1.6,
    val awacsDurationTicks: Int = 60,
    val awacsRangeMult: Double = 2.0,
    val awacsSilentJitter: Double = 15.0,
    val awacsSilentConfidence: Double = 0.6,
    val silentRunningDurationTicks: Int = 80,
    val silentRunningActiveRangeMult: Double = 0.5,
    val counterBatteryArmTicks: Int = 60,
    val counterBatteryRevealTicks: Int = 15,
    val empBurstDurationTicks: Int = 40,
    val empBurstRadius: Double = 130.0,
    val empGunCooldownMult: Double = 2.0,
    val decoyFlareDurationTicks: Int = 60,
    val decoyFlareDistanceMin: Double = 80.0,
    val decoyFlareDistanceMax: Double = 140.0,
) {
    init {
        positive(overdriveDurationTicks.toDouble())
        positive(overdriveSpeedMult)
        positive(overdriveAccelMult)
        positive(overdriveTurnMult)
        positive(reinforcedHullDurationTicks.toDouble())
        finite(reinforcedHullDamageMult)
        require(reinforcedHullDamageMult >= 0)
        positive(repairDronesDurationTicks.toDouble())
        finite(repairDronesHpPerTick.toDouble())
        require(repairDronesHpPerTick >= 0)
        finite(repairDronesInstantHp.toDouble())
        require(repairDronesInstantHp >= 0)
        positive(smokeScreenDurationTicks.toDouble())
        positive(smokeScreenRadius)
        positive(rapidFireDurationTicks.toDouble())
        positive(rapidFireCooldownMult)
        positive(heavyShellDurationTicks.toDouble())
        positive(heavyShellSplashMult)
        positive(heavyShellDamageMult)
        positive(longRangeDurationTicks.toDouble())
        positive(longRangeRangeMult)
        positive(longRangeSpeedMult)
        positive(awacsDurationTicks.toDouble())
        positive(awacsRangeMult)
        finite(awacsSilentJitter)
        require(awacsSilentJitter >= 0)
        finite(awacsSilentConfidence)
        require(awacsSilentConfidence >= 0)
        positive(silentRunningDurationTicks.toDouble())
        finite(silentRunningActiveRangeMult)
        require(silentRunningActiveRangeMult >= 0)
        positive(counterBatteryArmTicks.toDouble())
        positive(counterBatteryRevealTicks.toDouble())
        positive(empBurstDurationTicks.toDouble())
        positive(empBurstRadius)
        positive(empGunCooldownMult)
        positive(decoyFlareDurationTicks.toDouble())
        positive(decoyFlareDistanceMin)
        positive(decoyFlareDistanceMax)
        require(awacsSilentConfidence <= 1)
        require(decoyFlareDistanceMin <= decoyFlareDistanceMax)
    }

    private var original: ObjectNode = Wire.obj()
    val raw: ObjectNode
        get() = original.deepCopy()

    companion object {
        fun fromJson(node: JsonNode): PowerupConfig {
            node.obj()
            return PowerupConfig(
                    overdriveDurationTicks = node.int("overdrive_duration_ticks", 50),
                    overdriveSpeedMult = node.num("overdrive_speed_mult", 1.6),
                    overdriveAccelMult = node.num("overdrive_accel_mult", 1.6),
                    overdriveTurnMult = node.num("overdrive_turn_mult", 1.5),
                    reinforcedHullDurationTicks = node.int("reinforced_hull_duration_ticks", 70),
                    reinforcedHullDamageMult = node.num("reinforced_hull_damage_mult", 0.45),
                    repairDronesDurationTicks = node.int("repair_drones_duration_ticks", 50),
                    repairDronesHpPerTick = node.int("repair_drones_hp_per_tick", 1),
                    repairDronesInstantHp = node.int("repair_drones_instant_hp", 20),
                    smokeScreenDurationTicks = node.int("smoke_screen_duration_ticks", 80),
                    smokeScreenRadius = node.num("smoke_screen_radius", 70.0),
                    rapidFireDurationTicks = node.int("rapid_fire_duration_ticks", 50),
                    rapidFireCooldownMult = node.num("rapid_fire_cooldown_mult", 0.5),
                    heavyShellDurationTicks = node.int("heavy_shell_duration_ticks", 30),
                    heavyShellSplashMult = node.num("heavy_shell_splash_mult", 1.5),
                    heavyShellDamageMult = node.num("heavy_shell_damage_mult", 1.3),
                    longRangeDurationTicks = node.int("long_range_duration_ticks", 40),
                    longRangeRangeMult = node.num("long_range_range_mult", 1.5),
                    longRangeSpeedMult = node.num("long_range_speed_mult", 1.6),
                    awacsDurationTicks = node.int("awacs_duration_ticks", 60),
                    awacsRangeMult = node.num("awacs_range_mult", 2.0),
                    awacsSilentJitter = node.num("awacs_silent_jitter", 15.0),
                    awacsSilentConfidence = node.num("awacs_silent_confidence", 0.6),
                    silentRunningDurationTicks = node.int("silent_running_duration_ticks", 80),
                    silentRunningActiveRangeMult =
                        node.num("silent_running_active_range_mult", 0.5),
                    counterBatteryArmTicks = node.int("counter_battery_arm_ticks", 60),
                    counterBatteryRevealTicks = node.int("counter_battery_reveal_ticks", 15),
                    empBurstDurationTicks = node.int("emp_burst_duration_ticks", 40),
                    empBurstRadius = node.num("emp_burst_radius", 130.0),
                    empGunCooldownMult = node.num("emp_gun_cooldown_mult", 2.0),
                    decoyFlareDurationTicks = node.int("decoy_flare_duration_ticks", 60),
                    decoyFlareDistanceMin = node.num("decoy_flare_distance_min", 80.0),
                    decoyFlareDistanceMax = node.num("decoy_flare_distance_max", 140.0),
                )
                .also { it.original = node.obj().deepCopy() }
        }
    }
}
