package io.github.wilfredluijk.navalsdk

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.github.wilfredluijk.navalsdk.internal.*

data class MapInfo(val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0)
    }

    companion object {
        fun fromJson(d: JsonNode) = MapInfo(d.int("width"), d.int("height"))
    }
}

/** A defensive snapshot of acknowledged rules, including unknown fields in [raw]. */
class MatchConfiguration private constructor(data: ObjectNode) {
    private val original = data.deepCopy()
    val raw: ObjectNode
        get() = original.deepCopy()

    val protocolVersion = data.str("protocol_version")
    val revision = data.int("revision", 1).also { require(it >= 0) }
    val simulationDt = positive(data.num("simulation_dt"))
    val tickHz = data.int("tick_hz").also { require(it > 0) }
    val deadlineMs = data.int("deadline_ms").also { require(it > 0) }
    val map = MapInfo.fromJson(data.required("map"))
    val shipSpecs = ShipSpecs.fromJson(data.required("ship_specs"))
    private val sim = data.required("sim_config").obj()
    val sensors = SensorConfig.fromJson(sim)
    val powerups = PowerupConfig.fromJson(sim.get("powerups") ?: Wire.obj())
    val availablePowerups: List<String> =
        data.required("available_powerups").array().map {
            it.text().also { s -> require(s.isNotEmpty()) }
        }
    val matchTimeoutTicks = data.int("match_timeout_ticks", 3000).also { require(it > 0) }
    val wallBumpDamage = sim.int("wall_bump_damage", 2).also { require(it >= 0) }
    private val capabilities = (data.get("capabilities") ?: Wire.obj()).obj()
    val emptyLoadout = capabilities.bool("empty_loadout")
    val ownShipTelemetry = capabilities.bool("own_ship_telemetry")

    companion object {
        fun fromJson(data: JsonNode) = MatchConfiguration(data.obj())
    }
}

class Welcome(
    val botId: String,
    val shipId: String,
    val map: MapInfo,
    val tickHz: Int,
    val shipSpecs: ShipSpecs,
    val availablePowerups: List<String> = emptyList(),
    val simulationDt: Double = 0.1,
    val protocolVersion: String = "1.0",
    val configHash: String = "",
    configuration: ObjectNode = Wire.obj(),
) {
    private val original = configuration.deepCopy()
    val configuration: ObjectNode
        get() = original.deepCopy()

    val rules: MatchConfiguration? =
        if (original.isEmpty) null else MatchConfiguration.fromJson(original)

    init {
        positive(simulationDt)
        require(tickHz > 0)
    }

    fun withConfiguration(configuration: ObjectNode, configHash: String): Welcome {
        require(configHash.isNotEmpty()) { "config_hash must be nonempty" }
        val rules = MatchConfiguration.fromJson(configuration)
        return Welcome(
            botId,
            shipId,
            rules.map,
            rules.tickHz,
            rules.shipSpecs,
            rules.availablePowerups,
            rules.simulationDt,
            rules.protocolVersion,
            configHash,
            configuration,
        )
    }

    fun withMatchSpecs(specs: ShipSpecs, simulationDt: Double) =
        Welcome(
            botId,
            shipId,
            map,
            tickHz,
            specs,
            availablePowerups,
            simulationDt,
            protocolVersion,
            configHash,
            original,
        )

    companion object {
        fun fromJson(d: JsonNode) =
            Welcome(
                d.str("bot_id"),
                d.str("ship_id"),
                MapInfo.fromJson(d.required("map")),
                d.int("tick_hz"),
                ShipSpecs.fromJson(d.required("ship_specs")),
                d.strings("available_powerups"),
                d.num("simulation_dt", 0.1),
                d.str("protocol_version", "1.0"),
                d.str("config_hash", ""),
                (d.get("configuration") ?: Wire.obj()).obj(),
            )
    }
}

data class PowerupStatus(val id: String, val used: Boolean = false, val activeTicksLeft: Int = 0) {
    companion object {
        fun fromJson(d: JsonNode) =
            PowerupStatus(d.str("id"), d.bool("used"), d.int("active_ticks_left", 0))
    }
}

data class GameStart(
    val tick: Int,
    val startingPosition: Vec2,
    val startingHeadingDeg: Double,
    val shipSpecs: ShipSpecs? = null,
    val simulationDt: Double = 0.1,
    val matchId: String = "",
) {
    init {
        positive(simulationDt)
        finite(startingHeadingDeg)
    }

    companion object {
        fun fromJson(d: JsonNode) =
            GameStart(
                d.int("tick"),
                Vec2.fromJson(d.required("starting_position")),
                d.num("starting_heading_deg"),
                d.get("ship_specs")?.let(ShipSpecs::fromJson),
                d.num("simulation_dt", 0.1),
                d.str("match_id", ""),
            )
    }
}

data class SelfState(
    val pos: Vec2,
    val headingDeg: Double,
    val speed: Double,
    val hp: Int,
    val ammo: Int,
    val rudder: Double,
    val throttle: Double,
    val selectedPowerups: List<String> = emptyList(),
    val powerupStatus: List<PowerupStatus> = emptyList(),
    val gunCooldownTicksLeft: Int? = null,
    val empTicksLeft: Int = 0,
) {
    fun powerup(id: String): PowerupStatus? = powerupStatus.find { it.id == id }

    fun powerupReady(id: String): Boolean = powerup(id)?.used == false

    fun powerupActive(id: String): Boolean = (powerup(id)?.activeTicksLeft ?: 0) > 0

    companion object {
        fun fromJson(d: JsonNode) =
            SelfState(
                Vec2.fromJson(d.required("pos")),
                d.num("heading_deg"),
                d.num("speed"),
                d.int("hp"),
                d.int("ammo"),
                d.num("rudder"),
                d.num("throttle"),
                d.strings("selected_powerups"),
                d.items("powerup_status").map(PowerupStatus::fromJson),
                d.get("gun_cooldown_ticks_left")?.integer()?.coerceAtLeast(0),
                d.int("emp_ticks_left", 0).coerceAtLeast(0),
            )
    }
}

/** Kind stays a string so future server contact kinds can pass through unchanged. */
data class Contact(
    val id: String,
    val kind: String,
    val pos: Vec2,
    val bearingDeg: Double,
    val range: Double?,
    val confidence: Double,
) {
    companion object {
        fun fromJson(d: JsonNode) =
            Contact(
                d.str("id"),
                d.str("kind", "unknown"),
                Vec2.fromJson(d.required("pos")),
                d.num("bearing_deg"),
                d.get("range")?.takeUnless { it.isNull }?.number(),
                d.num("confidence", 0.0),
            )
    }
}

sealed interface TickEvent {
    companion object {
        fun fromJson(d: JsonNode): TickEvent =
            try {
                when (d.str("type", "unknown")) {
                    "hit" -> HitEvent(d.int("amount"))
                    "shell_splash" -> ShellSplashEvent(Vec2.fromJson(d.required("pos")))
                    "powerup_activated" ->
                        PowerupActivatedEvent(
                            d.required("own").let {
                                require(it.isBoolean)
                                it.booleanValue()
                            },
                            d.get("contact_id")?.takeUnless { it.isNull }?.text(),
                            d.str("powerup"),
                        )
                    else -> UnknownEvent(d.deepCopy())
                }
            } catch (_: IllegalArgumentException) {
                UnknownEvent(d.deepCopy())
            }
    }
}

data class HitEvent(val amount: Int) : TickEvent

data class ShellSplashEvent(val pos: Vec2) : TickEvent

data class PowerupActivatedEvent(val own: Boolean, val contactId: String?, val powerup: String) :
    TickEvent

data class UnknownEvent(val raw: JsonNode) : TickEvent

data class WorldView(
    val tick: Int,
    val deadlineMs: Int,
    val selfState: SelfState,
    val contacts: List<Contact> = emptyList(),
    val events: List<TickEvent> = emptyList(),
    val matchId: String = "",
) {
    val me: SelfState
        get() = selfState

    fun nearestContact(): Contact? = contacts.filter { it.range != null }.minByOrNull { it.range!! }

    companion object {
        fun fromJson(d: JsonNode) =
            WorldView(
                d.int("tick"),
                d.int("deadline_ms"),
                SelfState.fromJson(d.required("self")),
                d.items("contacts").map(Contact::fromJson),
                d.items("events").map(TickEvent::fromJson),
                d.str("match_id", ""),
            )
    }
}

data class GameOver(val winner: String?, val finalTick: Int, val replayId: String) {
    companion object {
        fun fromJson(d: JsonNode) =
            GameOver(
                d.get("winner")?.takeUnless { it.isNull }?.text(),
                d.int("final_tick"),
                d.str("replay_id"),
            )
    }
}

enum class SensorMode(val wireValue: String) {
    ACTIVE("active"),
    PASSIVE("passive"),
}

data class FireCommand(val bearingDeg: Double, val range: Double) {
    fun toJson(): ObjectNode =
        Wire.obj().put("bearing_deg", finite(bearingDeg)).put("range", finite(range))
}

/**
 * Mutable per-tick command. The server clamps throttle/rudder; serialization rejects nonfinite
 * numbers.
 */
data class Command(
    var throttle: Double = 0.0,
    var rudder: Double = 0.0,
    var sensorMode: SensorMode = SensorMode.ACTIVE,
    var fire: FireCommand? = null,
    var activatePowerup: String? = null,
) {
    fun fireAt(
        targetPos: Vec2,
        shooterPos: Vec2 = Vec2.ZERO,
        targetVel: Vec2? = null,
        shellSpeed: Double = 70.0,
        range: Double? = null,
        lead: Boolean = true,
    ): Command {
        val aim =
            if (lead && targetVel != null)
                leadTarget(shooterPos, targetPos, targetVel, shellSpeed) ?: targetPos
            else targetPos
        fire = FireCommand(bearingTo(shooterPos, aim), range ?: distance(shooterPos, aim))
        return this
    }

    fun toJson(tick: Int, matchId: String = ""): ObjectNode =
        Wire.obj().apply {
            put("type", "command")
            put("tick", tick)
            put("throttle", finite(throttle))
            put("rudder", finite(rudder))
            put("sensor_mode", sensorMode.wireValue)
            if (matchId.isNotEmpty()) put("match_id", matchId)
            fire?.let { set<ObjectNode>("fire", it.toJson()) }
            activatePowerup?.let {
                require(it.isNotEmpty()) { "powerup ID must be nonempty" }
                put("activate_powerup", it)
            }
        }
}
