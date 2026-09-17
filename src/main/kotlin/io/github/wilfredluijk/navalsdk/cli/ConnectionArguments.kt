package io.github.wilfredluijk.navalsdk.cli

import io.github.wilfredluijk.navalsdk.RunOptions
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path

/** CLI flags and bounded, literal participant-file parsing shared by custom bot entry points. */
object ConnectionArguments {
    fun resolve(
        args: Array<String>,
        environment: Map<String, String> = System.getenv(),
        name: String = "bot",
    ): RunOptions {
        val flags = linkedMapOf<String, String>()
        require(args.size % 2 == 0) { "missing connection option value" }
        args.toList().chunked(2).forEach { (key, value) ->
            require(key in setOf("--url", "--host", "--port", "--env-file")) {
                "unknown connection option"
            }
            require(flags.putIfAbsent(key, value) == null) { "duplicate connection option" }
        }
        require("--url" !in flags || ("--host" !in flags && "--port" !in flags)) {
            "use --url or --host/--port, not both"
        }
        val settings =
            flags["--env-file"]?.let { participantEnvironment(Path.of(it)) } ?: environment
        val url =
            if ("--host" in flags || "--port" in flags) {
                var host = flags["--host"] ?: "localhost"
                if (':' in host && !host.startsWith('[')) host = "[$host]"
                val port = (flags["--port"] ?: "7878").toIntOrNull()
                require(port != null && port in 1..65535) {
                    "port must be an integer between 1 and 65535"
                }
                "ws://$host:$port/bot"
            } else
                flags["--url"]
                    ?: settings["BATTLE_SERVER_URL"]?.takeIf { it.isNotEmpty() }
                    ?: "ws://localhost:7878/bot"
        val uri = RunOptions.validateUrl(url)
        require(uri.rawPath == "/bot") { "server URL must end in /bot" }
        val token = settings["BATTLE_BOT_TOKEN"] ?: ""
        require(uri.scheme != "wss" || token.isNotBlank()) {
            "participant credential required: use --env-file or BATTLE_BOT_TOKEN"
        }
        return RunOptions(url = url, token = token, name = name)
    }

    /** Never evaluates shell variables or commands. Errors never include file contents. */
    fun participantEnvironment(path: Path): Map<String, String> {
        val content =
            try {
                Files.newInputStream(path).use {
                    val bytes = it.readNBytes(16_385)
                    require(bytes.size <= 16_384) {
                        "participant --env-file must be at most 16 KiB"
                    }
                    Charsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes))
                        .toString()
                }
            } catch (_: java.io.IOException) {
                throw IllegalArgumentException("could not read UTF-8 participant --env-file")
            }
        val values = linkedMapOf<String, String>()
        content.lineSequence().forEachIndexed { index, line ->
            val words = words(line, index + 1).toMutableList()
            if (words.firstOrNull() == "export") words.removeAt(0)
            if (words.isEmpty()) return@forEachIndexed
            require(words.size == 1 && '=' in words[0]) {
                "invalid participant --env-file line ${index + 1}"
            }
            val (key, value) = words[0].split('=', limit = 2)
            require(key in setOf("BATTLE_SERVER_URL", "BATTLE_BOT_TOKEN") && key !in values) {
                "invalid participant --env-file line ${index + 1}"
            }
            values[key] = value
        }
        require(
            listOf("BATTLE_SERVER_URL", "BATTLE_BOT_TOKEN").all { !values[it].isNullOrBlank() }
        ) {
            "--env-file must define BATTLE_SERVER_URL and BATTLE_BOT_TOKEN"
        }
        return values
    }

    private fun words(line: String, number: Int): List<String> {
        val result = mutableListOf<String>()
        val word = StringBuilder()
        var quote: Char? = null
        var started = false
        var i = 0
        while (i < line.length) {
            val c = line[i++]
            if (quote == null && c == '#') break
            if (c == '\\' && quote != '\'') {
                require(i < line.length) { "invalid participant --env-file line $number" }
                val next = line[i++]
                if (quote == '"' && next != '"' && next != '\\') word.append('\\')
                word.append(next)
                started = true
            } else if (quote != null) {
                if (c == quote) quote = null else word.append(c)
            } else if (c == '\'' || c == '"') {
                quote = c
                started = true
            } else if (c.isWhitespace()) {
                if (started) {
                    result += word.toString()
                    word.setLength(0)
                    started = false
                }
            } else {
                word.append(c)
                started = true
            }
        }
        require(quote == null) { "invalid participant --env-file line $number" }
        if (started) result += word.toString()
        return result
    }
}
