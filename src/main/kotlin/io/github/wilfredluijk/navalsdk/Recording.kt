package io.github.wilfredluijk.navalsdk

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.github.wilfredluijk.navalsdk.internal.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE

/**
 * Python-compatible JSONL observations. Existing files are never overwritten; use with .use { }.
 */
class BotRecorder(val path: Path) : AutoCloseable {
    private val writer = Files.newBufferedWriter(path, Charsets.UTF_8, CREATE_NEW, WRITE)
    private var closed = false

    init {
        writer.write("{\"format\":\"naval-sdk-bot-views\",\"version\":1}\n")
        writer.flush()
    }

    @Synchronized
    fun record(message: ObjectNode) {
        check(!closed) { "recorder is closed" }
        if (message.str("type", "") in TYPES) {
            writer.write(redact(message).toString())
            writer.newLine()
            writer.flush()
        }
    }

    @Synchronized
    override fun close() {
        closed = true
        writer.close()
    }

    companion object {
        private val TYPES =
            setOf("welcome", "configuration", "game_start", "tick", "game_over", "lobby", "error")

        private fun redact(node: JsonNode): JsonNode =
            when {
                node.isObject ->
                    Wire.obj().apply {
                        node.properties().forEach { (key, value) ->
                            if (
                                listOf("token", "password", "credential", "authorization").any {
                                    it in key.lowercase()
                                }
                            )
                                put(key, "[redacted]")
                            else set<JsonNode>(key, redact(value))
                        }
                    }
                node.isArray ->
                    Wire.mapper.createArrayNode().apply { node.forEach { add(redact(it)) } }
                else -> node.deepCopy()
            }
    }
}

data class ReplayDecision(val matchId: String, val tick: Int, val command: ObjectNode)

/** Fixed observations: changing decisions does not re-simulate physics. */
fun replay(bot: Bot, path: Path): List<ReplayDecision> = buildList { replay(bot, path) { add(it) } }

/** Stream decisions without retaining the recording or leaking a lazy reader. */
fun replay(bot: Bot, path: Path, onDecision: (ReplayDecision) -> Unit) {
    claim(bot)
    try {
        bot.clearWelcome()
        bot.matchId = ""
        bot.lastTick = 0
        bot.phase = "disconnected"
        bot.diagnostics = RuntimeDiagnostics()
        val session = Session(bot)
        Files.newBufferedReader(path, Charsets.UTF_8).use { reader ->
            val header =
                Wire.parse(reader.readLine() ?: throw IllegalArgumentException("empty recording"))
            require(header == Wire.parse("{\"format\":\"naval-sdk-bot-views\",\"version\":1}")) {
                "unsupported bot-view recording"
            }
            while (true) {
                val line = reader.readLine() ?: break
                session
                    .handle(Wire.parse(line))
                    .filter { it.str("type") == "command" }
                    .forEach {
                        onDecision(
                            ReplayDecision(it.str("match_id"), it.int("tick"), it.deepCopy())
                        )
                    }
                if (session.stop || session.fatal) break
            }
        }
    } finally {
        bot.phase = "disconnected"
        bot.running.set(false)
    }
}
