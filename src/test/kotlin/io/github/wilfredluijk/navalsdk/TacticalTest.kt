package io.github.wilfredluijk.navalsdk

import io.github.wilfredluijk.navalsdk.tactical.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class TacticalTest {
    private val specs = welcomeFixture().shipSpecs

    private fun contact(x: Double, kind: String = "ship", range: Double? = 100.0) =
        Contact("unstable", kind, Vec2(x, 400.0), 0.0, range, 1.0)

    private fun view(tick: Int, vararg contacts: Contact) =
        viewFixture(tick).copy(contacts = contacts.toList())

    private fun track(pos: Vec2 = Vec2(500.0, 400.0)) =
        Track(1, "ship", pos, pos, Vec2.ZERO, 1, 1, 1, 1.0, "active")

    @ParameterizedTest
    @ValueSource(ints = [5, 10, 20, 60])
    fun `velocity uses simulation time independent of tick pacing`(hz: Int) {
        val tracker = Tracker(specs, tickHz = hz)
        for (tick in 1..99) tracker.update(view(tick, contact(100.0 + 9.0 * tick * .1)))
        assertEquals(1, tracker.tracks.size)
        assertEquals(9.0, tracker.tracks.single().vel.x, 1e-8)
    }

    @Test
    fun `passive observations never spawn or replace active range anchors`() {
        val tracker = Tracker(specs)
        assertTrue(tracker.update(view(1, contact(500.0, range = null))).isEmpty())
        tracker.update(view(2, contact(500.0)))
        tracker.update(view(3, contact(501.0)))
        val anchor = tracker.tracks.single().observedPos
        tracker.update(view(4, contact(600.0, range = null)))
        val t = tracker.tracks.single()
        assertEquals(anchor, t.observedPos)
        assertEquals(502.0, t.pos.x, 1e-8)
        assertEquals("passive", t.source)
        tracker.update(view(5))
        assertEquals(503.0, t.pos.x, 1e-8)
        assertEquals("dead_reckoned", t.source)
    }

    @Test
    fun `stale tracks and match resets cannot revive ghost contacts`() {
        val tracker = Tracker(specs, stalenessTicks = 2)
        tracker.update(view(10, contact(500.0)))
        assertEquals(2, tracker.update(view(14, contact(500.0))).single().trackId)
        assertTrue(tracker.update(view(1)).isEmpty())
        assertEquals(1, tracker.update(view(2, contact(500.0))).single().trackId)
        assertTrue(tracker.update(view(3).copy(matchId = "new")).isEmpty())
    }

    @Test
    fun `shell and ship contacts cannot be merged`() {
        val tracker = Tracker(specs)
        tracker.update(view(1, contact(500.0, "ship")))
        assertEquals(2, tracker.update(view(2, contact(500.0, "shell"))).size)
    }

    @Test
    fun `gunner rejects stale out of range and self splash shots`() {
        val g = Gunner(specs)
        val v = view(1)
        assertNotNull(g.solve(v.me, track(), v))
        assertEquals(0, g.nextFireTick)
        assertNull(g.solve(v.me, track(v.me.pos), v))
        assertNull(g.solve(v.me, track(Vec2(500.0, 0.0)), v))
        assertNull(g.solve(v.me, track(), view(20)))
        assertNull(g.solve(v.me.copy(ammo = 0), track(), v))
        assertNull(g.solve(v.me.copy(gunCooldownTicksLeft = 2), track(), v))
        val closing = v.me.copy(speed = 50.0)
        assertNull(g.solve(closing, track(), v))
    }

    @Test
    fun `weapon powerups and emp use server rounding`() {
        val g = Gunner(specs)
        val me =
            view(1)
                .me
                .copy(
                    powerupStatus =
                        listOf(
                            PowerupStatus("rapid_fire"),
                            PowerupStatus("long_range_salvo", true, 10),
                            PowerupStatus("heavy_shell", true, 10),
                        )
                )
        val w = g.effectiveWeapons(me, "rapid_fire")
        assertEquals(112.0, w.speed)
        assertEquals(450.0, w.maxRange)
        assertEquals(22.5, w.splashRadius)
        assertEquals(8, w.cooldownTicks)
        assertEquals(15, g.effectiveWeapons(me.copy(empTicksLeft = 2), "rapid_fire").cooldownTicks)
    }

    @Test
    fun `cooldown reconciles rejected shots and authoritative telemetry`() {
        val g = Gunner(specs)
        val v = view(1)
        assertTrue(g.attempt(Command(), v.me, track(), v))
        assertEquals(16, g.nextFireTick)
        g.update(view(2))
        assertEquals(2, g.nextFireTick)
        g.noteFired(2, ammo = 20)
        g.update(view(3).copy(selfState = v.me.copy(ammo = 19)))
        assertEquals(17, g.nextFireTick)
        g.update(view(4).copy(selfState = v.me.copy(gunCooldownTicksLeft = 2)))
        assertEquals(6, g.nextFireTick)
        g.reset()
        assertEquals(0, g.nextFireTick)
    }

    @Test
    fun `helm turns inward before hitting walls and tapers throttle`() {
        val h = Helm(specs)
        val me = view(1).me.copy(pos = Vec2(350.0, 20.0), headingDeg = 0.0, speed = 9.0)
        val turn = h.steerToBearing(me, 0.0)
        assertEquals(-1.0, turn.rudder)
        assertEquals(.55, turn.throttle)
        assertEquals(0.0, h.steerToBearing(me, 0.0, respectWalls = false).rudder)
        assertEquals(1.0, h.steerToBearing(me.copy(pos = Vec2(350.0, 350.0)), 0.0).throttle)
    }

    @Test
    fun `evader transitions reverses and resets`() {
        val evader = Evader(evasionTicks = 2, cooldownTicks = 2)
        assertNull(evader.update(view(1)))
        assertEquals(1.0, evader.update(view(2).copy(events = listOf(HitEvent(5))))!!.rudder)
        assertNull(evader.update(view(4)))
        assertEquals(-1.0, evader.update(view(5).copy(events = listOf(HitEvent(5))))!!.rudder)
        evader.reset()
        assertEquals(EvaderState.IDLE, evader.state)
        assertEquals(1.0, evader.update(view(1).copy(events = listOf(HitEvent(5))))!!.rudder)
    }

    @Test
    fun `sensor refresh uses oldest active ship fix`() {
        val tracker = Tracker(specs)
        tracker.update(view(1, contact(100.0), contact(500.0)))
        tracker.update(view(5, contact(501.0)))
        assertEquals(SensorMode.ACTIVE, PingWhenStale().choose(view(5), tracker))
        assertEquals(SensorMode.PASSIVE, DutyCycle(1, 2).choose(view(2), tracker))
        assertEquals(SensorMode.ACTIVE, DutyCycle(1, 2).choose(view(3), tracker))
    }

    @Test
    fun `tactical orchestration honors evasion custom intents and round resets`() {
        var decisions = 0
        val bot =
            object : TacticalBot() {
                override fun decide(ctx: TacticalContext): Intent {
                    decisions++
                    return Intent.engage(ctx.threats.nearest()!!)
                }
            }
        bot.onWelcome(welcomeFixture())
        bot.onGameStart(0, Vec2.ZERO, 0.0)
        val command = bot.onTick(view(1, contact(500.0)))
        assertNotNull(command.fire)
        assertEquals(1, decisions)
        assertNull(bot.onTick(view(2).copy(events = listOf(HitEvent(2)))).fire)
        assertEquals(1, decisions)
        bot.onGameStart(0, Vec2.ZERO, 0.0)
        assertTrue(bot.tracker!!.tracks.isEmpty())
        assertEquals(0, bot.gunner!!.nextFireTick)
        assertEquals(EvaderState.IDLE, bot.evader!!.state)
        val custom =
            object : TacticalBot() {
                override fun decide(ctx: TacticalContext) =
                    Intent.custom(Command(sensorMode = SensorMode.PASSIVE))
            }
        custom.onWelcome(welcomeFixture())
        assertEquals(SensorMode.PASSIVE, custom.onTick(view(1)).sensorMode)
    }
}
