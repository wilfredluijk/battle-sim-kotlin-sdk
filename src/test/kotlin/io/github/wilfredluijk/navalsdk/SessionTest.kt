package io.github.wilfredluijk.navalsdk

import com.fasterxml.jackson.databind.node.ObjectNode
import io.github.wilfredluijk.navalsdk.internal.Wire
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SessionTest {
    @Test
    fun `multiple rounds reselect and acknowledge exact config hashes`() {
        var starts = 0
        val bot =
            object : Bot() {
                override fun choosePowerups(welcome: Welcome) = listOf("rapid_fire", "heavy_shell")

                override fun onGameStartEvent(start: GameStart) {
                    starts++
                    assertEquals(start.matchId, matchId)
                }

                override fun onGameOver(result: GameOver) = starts < 2
            }
        val session = Session(bot)
        val ready = session.handle(fixture("welcome"))
        assertEquals(listOf("select_powerups", "ready"), ready.map { it["type"].asText() })
        assertEquals("fixture-config-1", ready.last()["config_hash"].asText())
        for (round in 1..2) {
            session.handle(fixture("game_start").put("match_id", "round-$round"))
            val command = session.handle(fixture("tick").put("match_id", "round-$round")).single()
            assertEquals("round-$round", command["match_id"].asText())
            session.handle(fixture("game_over"))
            if (round == 1)
                assertEquals(2, session.handle(Wire.parse("""{"type":"lobby","tick":0}""")).size)
        }
        assertTrue(session.stop)
        assertEquals(2, bot.diagnostics.ticks)
    }

    @Test
    fun `configuration refusal or invalid loadout remains unready`() {
        val refuse =
            object : Bot() {
                override fun acceptConfiguration(configuration: ObjectNode, configHash: String) =
                    false
            }
        assertTrue(Session(refuse).handle(fixture("welcome")).isEmpty())
        val invalid =
            object : Bot() {
                override fun choosePowerups(welcome: Welcome) = listOf("rapid_fire", "rapid_fire")
            }
        assertTrue(Session(invalid).handle(fixture("welcome")).isEmpty())
    }

    @Test
    fun `callback errors and invalid commands fall back safely`() {
        val bot =
            object : Bot() {
                override fun onTick(view: WorldView): Command {
                    if (view.tick == 1) error("synthetic callback failure")
                    return Command(throttle = Double.NaN)
                }
            }
        val s = Session(bot)
        s.handle(fixture("welcome"))
        for (tick in 1..2) assertEquals(
            0.0,
            s.handle(fixture("tick").put("tick", tick)).single()["throttle"].asDouble(),
        )
        assertEquals(2, bot.diagnostics.callbackErrors)
        assertEquals(2, bot.diagnostics.ticks)
        assertNotNull(bot.diagnostics.lastTiming)
    }

    @Test
    fun `game start refreshes dt and specs before callback`() {
        val observed = mutableListOf<Double>()
        val bot =
            object : Bot() {
                override fun onGameStartEvent(start: GameStart) {
                    observed += welcome!!.simulationDt
                    observed += welcome!!.shipSpecs.shellSpeed
                }
            }
        val s = Session(bot)
        s.handle(fixture("welcome"))
        val start = fixture("game_start").put("simulation_dt", .2)
        (start["ship_specs"] as ObjectNode).put("shell_speed", 91)
        s.handle(start)
        start.remove("ship_specs")
        start.put("simulation_dt", .3)
        s.handle(start)
        assertEquals(listOf(.2, 91.0, .3, 91.0), observed)
    }

    @Test
    fun `configuration update permits explicit loadout clearing only with capability`() {
        var picks = listOf("rapid_fire", "heavy_shell")
        val bot =
            object : Bot() {
                override fun choosePowerups(welcome: Welcome) = picks
            }
        val s = Session(bot)
        s.handle(fixture("welcome"))
        picks = emptyList()
        val config = fixture("welcome").put("type", "configuration").put("config_hash", "new")
        val out = s.handle(config)
        assertEquals(0, out[0]["powerups"].size())
        assertEquals("new", out[1]["config_hash"].asText())
        val other =
            Session(
                object : Bot() {
                    override fun choosePowerups(welcome: Welcome) = picks
                }
            )
        picks = listOf("rapid_fire", "heavy_shell")
        other.handle(fixture("welcome"))
        picks = emptyList()
        ((config["configuration"] as ObjectNode)["capabilities"] as ObjectNode).put(
            "empty_loadout",
            false,
        )
        assertTrue(other.handle(config).isEmpty())
    }

    @Test
    fun `protocol mismatch is fatal while malformed frames are counted`() {
        val bot = Bot()
        val s = Session(bot)
        s.handle(Wire.parse("""{"type":"tick"}"""))
        assertEquals(1, bot.diagnostics.malformedFrames)
        assertThrows(ProtocolMismatch::class.java) {
            s.handle(fixture("welcome").put("protocol_version", "2.0"))
        }
        assertTrue(s.fatal)
        val errors = Session(bot)
        errors.handle(Wire.parse("""{"type":"error","code":"unauthorized","message":"denied"}"""))
        assertTrue(errors.fatal)
        assertEquals(1, bot.diagnostics.rejectedCommands["unauthorized"])
    }
}
