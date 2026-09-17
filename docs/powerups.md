# Powerups

Return two distinct IDs from `welcome.availablePowerups`, or an empty list, in
`choosePowerups`. Activate one by setting `Command.activatePowerup` to its ID.
Selection repeats after configuration updates and between rounds. Each pick is single-use;
`view.me.powerupReady(id)` and `powerupActive(id)` reflect server status.

| ID | Effect | Main `PowerupConfig` fields |
| --- | --- | --- |
| `overdrive` | Speed, acceleration, and turning boost | `overdriveDurationTicks`, `overdriveSpeedMult`, `overdriveAccelMult`, `overdriveTurnMult` |
| `reinforced_hull` | Reduced incoming damage | `reinforcedHullDurationTicks`, `reinforcedHullDamageMult` |
| `repair_drones` | Immediate and gradual hull repair | `repairDronesDurationTicks`, `repairDronesHpPerTick`, `repairDronesInstantHp` |
| `smoke_screen` | Smoke concealment | `smokeScreenDurationTicks`, `smokeScreenRadius` |
| `rapid_fire` | Reduced gun cooldown | `rapidFireDurationTicks`, `rapidFireCooldownMult` |
| `heavy_shell` | Larger splash and damage | `heavyShellDurationTicks`, `heavyShellSplashMult`, `heavyShellDamageMult` |
| `long_range_salvo` | Increased shell speed and range | `longRangeDurationTicks`, `longRangeRangeMult`, `longRangeSpeedMult` |
| `awacs_scan` | Enhanced sensor reach and silent-target detection | `awacsDurationTicks`, `awacsRangeMult`, `awacsSilentJitter`, `awacsSilentConfidence` |
| `silent_running` | Reduced active-radar detection range | `silentRunningDurationTicks`, `silentRunningActiveRangeMult` |
| `counter_battery_trace` | Reveal attackers during its armed window | `counterBatteryArmTicks`, `counterBatteryRevealTicks` |
| `emp_burst` | Nearby enemies' gun cooldown penalty | `empBurstDurationTicks`, `empBurstRadius`, `empGunCooldownMult` |
| `decoy_flare` | Sensor decoy | `decoyFlareDurationTicks`, `decoyFlareDistanceMin`, `decoyFlareDistanceMax` |

Read balance values from `welcome.rules?.powerups`; server configuration is authoritative.
Future catalog IDs pass through selection and activation without a hard-coded enum.
The server validates loadout membership and whether an activation has already been used.

```kotlin
override fun choosePowerups(welcome: Welcome) = listOf("repair_drones", "long_range_salvo")

override fun onTick(view: WorldView): Command {
    val cmd = Command(throttle = 0.8)
    if (view.me.hp < 50 && view.me.powerupReady("repair_drones")) {
        cmd.activatePowerup = "repair_drones"
    }
    return cmd
}
```

For combined activation and firing, set `activatePowerup` before calling `Gunner.attempt`.
The gunner includes a ready same-command activation in its solution. An activation event
contains `own`, optional `contactId`, and `powerup`; opponent events are limited to what the
server reveals through the bot's sensors.
