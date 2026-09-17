package io.github.wilfredluijk.navalsdk

import com.fasterxml.jackson.databind.node.ObjectNode
import io.github.wilfredluijk.navalsdk.internal.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class ProtocolTest {
    @Test
    fun `decode upstream golden protocol fixture`() {
        val w = welcomeFixture()
        assertEquals("3.0", w.protocolVersion)
        assertEquals(12, w.availablePowerups.size)
        assertEquals(70.0, w.rules!!.shipSpecs.shellSpeed)
        assertEquals(1.6, w.rules!!.powerups.longRangeSpeedMult)
        assertTrue(w.rules!!.emptyLoadout)
        assertTrue(w.rules!!.ownShipTelemetry)
        val v = viewFixture()
        assertEquals(Vec2(500.0, 500.0), v.me.pos)
        assertNull(v.me.gunCooldownTicksLeft)
        assertEquals("fixture-match-1", GameStart.fromJson(fixture("game_start")).matchId)
        assertEquals(142, GameOver.fromJson(fixture("game_over")).finalTick)
    }

    @Test
    fun `wire commands retain snake case and omit optional fields`() {
        assertEquals(
            Wire.parse(
                """{"type":"command","tick":4,"match_id":"match","throttle":0.5,"rudder":-0.2,"sensor_mode":"passive","fire":{"bearing_deg":90.0,"range":100.0},"activate_powerup":"overdrive"}"""
            ),
            Command(.5, -.2, SensorMode.PASSIVE, FireCommand(90.0, 100.0), "overdrive")
                .toJson(4, "match"),
        )
        assertFalse(Command().toJson(1).has("fire"))
        assertFalse(Command().toJson(1).has("match_id"))
    }

    @ParameterizedTest
    @ValueSource(doubles = [Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 1e39])
    fun `reject outbound nonfinite or out of f32 range`(value: Double) {
        assertThrows(IllegalArgumentException::class.java) { Command(throttle = value).toJson(1) }
        assertThrows(IllegalArgumentException::class.java) { FireCommand(value, 20.0).toJson() }
    }

    @Test
    fun `strict numbers and malformed events`() {
        assertThrows(IllegalArgumentException::class.java) {
            WorldView.fromJson(fixture("tick").put("tick", true))
        }
        assertThrows(IllegalArgumentException::class.java) { Wire.parse("{} {}") }
        assertThrows(IllegalArgumentException::class.java) { Wire.parse("[]") }
        val frame = fixture("tick")
        frame
            .putArray("events")
            .add(Wire.parse("""{"type":"hit","amount":9}"""))
            .add(Wire.parse("""{"type":"hit","amount":"bad"}"""))
            .add(Wire.parse("""{"type":"future_event","value":4}"""))
            .add(42)
        val events = WorldView.fromJson(frame).events
        assertEquals(HitEvent(9), events[0])
        assertTrue(events.drop(1).all { it is UnknownEvent })
    }

    @Test
    fun `configuration snapshots cannot be mutated through raw views`() {
        val frame = fixture("welcome")
        val w = Welcome.fromJson(frame)
        (frame["configuration"] as ObjectNode).put("tick_hz", 99)
        w.configuration.put("tick_hz", 100)
        w.rules!!.raw.put("tick_hz", 101)
        assertEquals(10, w.configuration["tick_hz"].intValue())
        assertEquals(10, w.rules!!.tickHz)
        assertThrows(IllegalArgumentException::class.java) {
            PowerupConfig(awacsSilentConfidence = 1.1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PowerupConfig(decoyFlareDistanceMin = 150.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ShipSpecs.fromJson(frame["ship_specs"].deepCopy<ObjectNode>().put("shell_speed", 0))
        }
        assertThrows(IllegalArgumentException::class.java) { SensorConfig(activeRadarRange = -1.0) }
    }

    @Test
    fun `own ship telemetry and powerup accessors`() {
        val me =
            viewFixture()
                .me
                .copy(
                    powerupStatus =
                        listOf(PowerupStatus("overdrive"), PowerupStatus("heavy_shell", true, 7))
                )
        assertTrue(me.powerupReady("overdrive"))
        assertTrue(me.powerupActive("heavy_shell"))
        assertFalse(me.powerupReady("heavy_shell"))
        assertNull(me.powerup("missing"))
        assertFalse(me.powerupReady("missing"))
    }

    @Test
    fun `compass and intercept edge cases`() {
        assertEquals(0.0, bearingTo(Vec2.ZERO, Vec2(0.0, -10.0)))
        assertEquals(90.0, bearingTo(Vec2.ZERO, Vec2(10.0, 0.0)))
        assertEquals(20.0, signedBearingDelta(10.0, 350.0))
        assertEquals(-180.0, signedBearingDelta(180.0, 0.0))
        assertNull(leadTarget(Vec2.ZERO, Vec2(100.0, 0.0), Vec2(100.0, 0.0), 70.0))
        assertEquals(Vec2.ZERO, leadTarget(Vec2.ZERO, Vec2.ZERO, Vec2(70.0, 0.0), 70.0))
        val aim = leadTarget(Vec2.ZERO, Vec2(100.0, 0.0), Vec2(0.0, 9.0), 70.0)!!
        assertEquals(distance(Vec2.ZERO, aim) / 70.0 * 9, aim.y, 1e-9)
    }
}
