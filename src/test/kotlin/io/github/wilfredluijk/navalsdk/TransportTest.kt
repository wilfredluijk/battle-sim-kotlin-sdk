package io.github.wilfredluijk.navalsdk

import io.github.wilfredluijk.navalsdk.internal.Wire
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

@Timeout(15)
class TransportTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `live fragmented websocket handles bad frames pings and records replayable observations`() {
        val path = directory.resolve("recording.jsonl")
        LoopbackServer().use { server ->
            BotRecorder(path).use { recorder ->
                val bot =
                    object : Bot() {
                        override fun onTick(view: WorldView) = Command(throttle = .6)

                        override fun onGameOver(result: GameOver) = false
                    }
                val future =
                    runAsync(
                        bot,
                        RunOptions(
                            url = server.url,
                            token = "synthetic-transport-secret",
                            recorder = recorder,
                        ),
                    )
                try {
                    server.accept()
                    val hello = server.receive()
                    assertEquals("hello", hello["type"].asText())
                    assertEquals("synthetic-transport-secret", hello["token"].asText())
                    server.sendText("bad json")
                    server.sendText("[]")
                    server.binary()
                    server.ping()
                    server.fragmented(fixture("welcome"))
                    assertEquals("ready", server.receive()["type"].asText())
                    server.send(fixture("game_start"))
                    server.fragmented(fixture("tick"))
                    val command = server.receive()
                    assertEquals(.6, command["throttle"].asDouble())
                    assertEquals("fixture-match-1", command["match_id"].asText())
                    server.send(fixture("game_over"))
                    assertEquals(142, future.get(5, TimeUnit.SECONDS)!!.finalTick)
                    assertEquals(3, bot.diagnostics.malformedFrames)
                    assertEquals("disconnected", bot.phase)
                    assertNull(bot.socket)
                    assertFalse(bot.running.get())
                } finally {
                    future.cancel(true)
                }
            }
        }
        assertFalse(Files.readString(path).contains("synthetic-transport-secret"))
        assertEquals(1, replay(Bot(), path).size)
    }

    @Test
    fun `lobby disconnection can reconnect but running match cannot`() {
        LoopbackServer().use { server ->
            val bot = Bot()
            val task =
                runAsync(
                    bot,
                    RunOptions(
                        url = server.url,
                        reconnectAttempts = 2,
                        reconnectDelay = Duration.ZERO,
                    ),
                )
            try {
                server.accept()
                server.receive()
                server.send(fixture("welcome"))
                server.receive()
                server.closePeer()
                server.accept()
                server.receive()
                server.send(fixture("welcome"))
                server.receive()
                server.send(fixture("game_start"))
                server.send(fixture("tick"))
                server.receive()
                server.closePeer()
                assertNull(task.get(5, TimeUnit.SECONDS))
                assertEquals("running", bot.diagnostics.lastDisconnect!!.phase)
            } finally {
                task.cancel(true)
            }
        }
    }

    @Test
    fun `fatal authentication errors do not retry`() {
        LoopbackServer().use { server ->
            val bot = Bot()
            val task =
                runAsync(
                    bot,
                    RunOptions(
                        url = server.url,
                        reconnectAttempts = 2,
                        reconnectDelay = Duration.ZERO,
                    ),
                )
            try {
                server.accept()
                server.receive()
                server.send(Wire.parse("""{"type":"error","code":"unauthorized"}"""))
                assertNull(task.get(5, TimeUnit.SECONDS))
                assertEquals(1, bot.diagnostics.rejectedCommands["unauthorized"])
            } finally {
                task.cancel(true)
            }
        }
    }

    @Test
    fun `cancel releases connection and duplicate run is rejected`() {
        LoopbackServer().use { server ->
            val bot = Bot()
            val task = runAsync(bot, RunOptions(url = server.url))
            try {
                server.accept()
                server.receive()
                assertThrows(IllegalStateException::class.java) {
                    runAsync(bot, RunOptions(url = server.url))
                }
                task.cancel(true)
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                while (bot.running.get() && System.nanoTime() < deadline) Thread.sleep(10)
                assertFalse(bot.running.get())
                assertNull(bot.socket)
                assertEquals("disconnected", bot.phase)
            } finally {
                task.cancel(true)
            }
        }
    }
}
