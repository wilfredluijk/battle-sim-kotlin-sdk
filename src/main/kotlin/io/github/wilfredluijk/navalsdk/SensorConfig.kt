package io.github.wilfredluijk.navalsdk

import com.fasterxml.jackson.databind.JsonNode
import io.github.wilfredluijk.navalsdk.internal.*

/** Typed server configuration; wire field names remain snake_case. */
data class SensorConfig(
    val activeRadarRange: Double = 350.0,
    val activeRadarNoise: Double = 2.0,
    val passiveHearActiveRange: Double = 500.0,
    val passiveHearNearbyRange: Double = 150.0,
    val passiveBearingNoiseDeg: Double = 5.0,
) {
    init {
        positive(activeRadarRange)
        finite(activeRadarNoise)
        require(activeRadarNoise >= 0)
        positive(passiveHearActiveRange)
        positive(passiveHearNearbyRange)
        finite(passiveBearingNoiseDeg)
        require(passiveBearingNoiseDeg >= 0)
    }

    companion object {
        fun fromJson(node: JsonNode): SensorConfig {
            node.obj()
            return SensorConfig(
                activeRadarRange = node.num("active_radar_range", 350.0),
                activeRadarNoise = node.num("active_radar_noise", 2.0),
                passiveHearActiveRange = node.num("passive_hear_active_range", 500.0),
                passiveHearNearbyRange = node.num("passive_hear_nearby_range", 150.0),
                passiveBearingNoiseDeg = node.num("passive_bearing_noise_deg", 5.0),
            )
        }
    }
}
