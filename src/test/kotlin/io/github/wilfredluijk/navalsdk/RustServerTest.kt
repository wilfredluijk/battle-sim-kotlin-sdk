package io.github.wilfredluijk.navalsdk

import com.fasterxml.jackson.databind.node.ObjectNode
import io.github.wilfredluijk.navalsdk.internal.Wire
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

/** Opt in with BATTLE_SIM_SERVER=/absolute/path/to/naval-server. */
class RustServerTest {
    @TempDir lateinit var directory: Path
    private var base = ""
    private var adminToken: String? = null
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()

    private fun request(method: String, path: String, body: ObjectNode? = null): ObjectNode {
        val builder =
            HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(3))
                .header("Content-Type", "application/json")
        adminToken?.let { builder.header("Authorization", "Bearer $it") }
        builder.method(
            method,
            if (body == null) HttpRequest.BodyPublishers.noBody()
            else HttpRequest.BodyPublishers.ofString(body.toString()),
        )
        val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        assertTrue(response.statusCode() in 200..299, "HTTP ${response.statusCode()} at $path")
        return if (response.body().isBlank()) Wire.obj() else Wire.parse(response.body())
    }

    private fun await(condition: () -> Boolean) {
        repeat(200) {
            if (condition()) return
            Thread.sleep(25)
        }
        fail<Unit>("server lifecycle did not reach expected state")
    }

    private fun ready(): ObjectNode {
        var state = Wire.obj()
        await {
            state = request("GET", "/api/room")
            val bots = state.path("bots")
            bots.size() == 2 && bots.all { it.path("ready").asBoolean() }
        }
        return state
    }

    private class Observer : Bot() {
        val starts = CopyOnWriteArrayList<GameStart>()
        val views = CopyOnWriteArrayList<WorldView>()
        val hashes = CopyOnWriteArrayList<String>()
        @Volatile var rounds = 0

        override fun choosePowerups(welcome: Welcome) = listOf("rapid_fire", "heavy_shell")

        override fun onWelcome(welcome: Welcome) {
            hashes += welcome.configHash
        }

        override fun onGameStartEvent(start: GameStart) {
            starts += start
        }

        override fun onTick(view: WorldView): Command {
            views += view
            return Command(throttle = .1)
        }

        override fun onGameOver(result: GameOver): Boolean {
            rounds++
            return rounds < 2
        }
    }

    @Test
    @Timeout(40)
    fun `real server configuration telemetry recording and two rounds`() {
        val binary = System.getenv("BATTLE_SIM_SERVER")
        assumeTrue(
            !binary.isNullOrBlank(),
            "set BATTLE_SIM_SERVER to enable actual-server integration",
        )
        val port = ServerSocket(0).use { it.localPort }
        base = "http://127.0.0.1:$port"
        val builder =
            ProcessBuilder(
                Path.of(binary!!).toAbsolutePath().toString(),
                "--port",
                port.toString(),
                "--tick-hz",
                "20",
                "--tick-deadline-ms",
                "40",
                "--allow-unauthenticated-bots",
                "--replay-dir",
                directory.resolve("replays").toString(),
            )
        builder.environment()["BATTLE_ADMIN_PASSWORD"] = "synthetic-kotlin-integration-password"
        builder.environment().remove("BATTLE_ADMIN_PASSWORD_FILE")
        builder.environment().remove("BATTLE_BOT_CREDENTIALS_FILE")
        builder.redirectErrorStream(true).redirectOutput(directory.resolve("server.log").toFile())
        val server = builder.start()
        val tasks = mutableListOf<CompletableFuture<GameOver?>>()
        try {
            repeat(100) {
                assertTrue(
                    server.isAlive,
                    "local Rust process exited; see ${directory.resolve("server.log")}",
                )
                if (adminToken == null)
                    try {
                        adminToken =
                            request(
                                    "POST",
                                    "/api/login",
                                    Wire.obj()
                                        .put("password", "synthetic-kotlin-integration-password"),
                                )["token"]
                                .asText()
                    } catch (_: java.io.IOException) {
                        Thread.sleep(50)
                    }
            }
            assertNotNull(adminToken)
            val bots = listOf(Observer(), Observer())
            val recording = directory.resolve("kotlin-views.jsonl")
            BotRecorder(recording).use { recorder ->
                bots.forEachIndexed { i, bot ->
                    tasks +=
                        runAsync(
                            bot,
                            RunOptions(
                                url = "ws://127.0.0.1:$port/bot",
                                token = "",
                                name = "kotlin-integration-$i",
                                recorder = if (i == 0) recorder else null,
                            ),
                        )
                }
                val state = ready()
                val config =
                    (state.get("config") ?: bots[0].welcome!!.configuration["sim_config"])
                        .deepCopy<ObjectNode>()
                        .put("shell_speed", 91)
                request("PUT", "/api/room/config", config)
                await { bots.all { it.hashes.size >= 2 } }
                ready()
                for (round in 1..2) {
                    request("POST", "/api/room/start", Wire.obj())
                    await { bots.all { it.starts.size == round && it.lastTick >= 3 } }
                    request("POST", "/api/room/abort", Wire.obj())
                    await { bots.all { it.rounds == round } }
                    if (round == 1) {
                        request("POST", "/api/room/reset", Wire.obj())
                        ready()
                    }
                }
                tasks.forEach { assertNotNull(it.get(5, TimeUnit.SECONDS)) }
            }
            bots.forEach { bot ->
                assertEquals(0, bot.diagnostics.callbackErrors)
                assertEquals(0, bot.diagnostics.malformedFrames)
                assertTrue(bot.diagnostics.rejectedCommands.isEmpty())
                assertEquals(2, bot.starts.size)
                assertNotEquals(bot.starts[0].matchId, bot.starts[1].matchId)
                assertTrue(bot.starts.all { it.shipSpecs!!.shellSpeed == 91.0 })
                assertTrue(
                    bot.views.all {
                        it.me.gunCooldownTicksLeft != null &&
                            it.me.selectedPowerups == listOf("rapid_fire", "heavy_shell")
                    }
                )
            }
            val decisions = replay(Bot(), recording)
            assertEquals(bots[0].views.size, decisions.size)
            assertEquals(2, decisions.map { it.matchId }.distinct().size)
        } finally {
            tasks.forEach { it.cancel(true) }
            server.destroy()
            if (!server.waitFor(5, TimeUnit.SECONDS)) {
                server.destroyForcibly()
                server.waitFor(5, TimeUnit.SECONDS)
            }
        }
    }
}
