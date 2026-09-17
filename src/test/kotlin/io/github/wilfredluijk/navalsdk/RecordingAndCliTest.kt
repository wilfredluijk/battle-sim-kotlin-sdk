package io.github.wilfredluijk.navalsdk

import io.github.wilfredluijk.navalsdk.cli.ConnectionArguments
import io.github.wilfredluijk.navalsdk.internal.Wire
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class RecordingAndCliTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `recorder omits hello redacts nested keys and refuses overwrite`() {
        val path = directory.resolve("views.jsonl")
        BotRecorder(path).use {
            it.record(Wire.parse("""{"type":"hello","token":"hello-secret"}"""))
            it.record(
                Wire.parse(
                    """{"type":"error","code":"test","nested":[{"access_token":"nested-secret","password":"pw"}]}"""
                )
            )
            it.record(fixture("welcome"))
            it.record(fixture("game_start"))
            it.record(fixture("tick"))
            it.record(fixture("game_over"))
        }
        val text = Files.readString(path)
        assertFalse(text.contains("hello-secret"))
        assertFalse(text.contains("nested-secret"))
        assertTrue(text.contains("[redacted]"))
        assertThrows(FileAlreadyExistsException::class.java) { BotRecorder(path) }
        val decisions = replay(Bot(), path)
        assertEquals(1, decisions.size)
        assertEquals("fixture-match-1", decisions.single().matchId)
    }

    @Test
    fun `replay validates format and releases exclusive ownership on failure`() {
        val bot = Bot()
        val path = directory.resolve("bad.jsonl")
        Files.writeString(path, "{}\n")
        assertThrows(IllegalArgumentException::class.java) { replay(bot, path) }
        assertFalse(bot.running.get())
        assertEquals("disconnected", bot.phase)
    }

    @Test
    fun `participant file replaces ambient credentials with literal quoted values`() {
        val path = directory.resolve("participant.env")
        Files.writeString(
            path,
            "export BATTLE_SERVER_URL='wss://example.test/bot' # comment\nBATTLE_BOT_TOKEN='literal ${'$'}(never-execute)'\n",
        )
        val options =
            ConnectionArguments.resolve(
                arrayOf("--env-file", path.toString(), "--host", "::1", "--port", "8000"),
                mapOf("BATTLE_BOT_TOKEN" to "wrong"),
            )
        assertEquals("ws://[::1]:8000/bot", options.url.toString())
        assertEquals("literal ${'$'}(never-execute)", options.token)
        assertFalse(options.toString().contains("never-execute"))
    }

    @Test
    fun `CLI rejects duplicate mixed credential bearing or missing settings without leaking values`() {
        val secretUrl = "wss://user:synthetic-secret@example.test/bot"
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                ConnectionArguments.resolve(arrayOf("--url", secretUrl), emptyMap())
            }
        assertFalse(error.message!!.contains("synthetic-secret"))
        assertThrows(IllegalArgumentException::class.java) {
            ConnectionArguments.resolve(
                arrayOf("--url", "ws://localhost/bot", "--host", "localhost")
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ConnectionArguments.resolve(arrayOf("--url", "wss://example.test/bot"), emptyMap())
        }
        assertThrows(IllegalArgumentException::class.java) {
            ConnectionArguments.resolve(arrayOf("--token", "secret"))
        }
        val path = directory.resolve("bad.env")
        Files.writeString(
            path,
            "BATTLE_SERVER_URL=ws://localhost/bot\nBATTLE_BOT_TOKEN=a\nBATTLE_BOT_TOKEN=b",
        )
        assertThrows(IllegalArgumentException::class.java) {
            ConnectionArguments.participantEnvironment(path)
        }
        Files.writeString(path, "a".repeat(16_385))
        assertThrows(IllegalArgumentException::class.java) {
            ConnectionArguments.participantEnvironment(path)
        }
    }
}
