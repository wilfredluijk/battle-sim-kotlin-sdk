package io.github.wilfredluijk.navalsdk

import io.github.wilfredluijk.navalsdk.internal.Wire
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.*

/** Connection settings. Credentials are never included in toString(). */
class RunOptions(
    url: String =
        System.getenv("BATTLE_SERVER_URL")?.takeIf { it.isNotEmpty() } ?: "ws://localhost:7878/bot",
    internal val token: String = System.getenv("BATTLE_BOT_TOKEN") ?: "",
    val name: String = "bot",
    val version: String = "naval-sdk-kotlin/0.1.0",
    val reconnectAttempts: Int = 0,
    val reconnectDelay: Duration = Duration.ofSeconds(1),
    val recorder: BotRecorder? = null,
) {
    val url: URI = validateUrl(url)

    init {
        require(name.isNotBlank()) { "bot name is required" }
        require(
            reconnectAttempts >= 0 &&
                !reconnectDelay.isNegative &&
                reconnectDelay <= Duration.ofSeconds(60)
        ) {
            "retries must be nonnegative; delay must be between zero and 60 seconds"
        }
    }

    override fun toString() = "RunOptions[name=$name, credentials redacted]"

    companion object {
        fun validateUrl(value: String): URI =
            try {
                URI.create(value).also {
                    require(
                        it.scheme in setOf("ws", "wss") &&
                            it.host != null &&
                            it.rawUserInfo == null &&
                            it.rawQuery == null &&
                            it.rawFragment == null &&
                            (it.port == -1 || it.port in 1..65535) &&
                            value.none(Char::isWhitespace)
                    )
                }
            } catch (_: IllegalArgumentException) {
                throw IllegalArgumentException(
                    "server URL must be ws:// or wss:// with a valid host/port and no credentials, query or fragment"
                )
            }
    }
}

/** Run until disconnected or onGameOver returns false. An active match is never reconnected. */
fun run(bot: Bot, options: RunOptions = RunOptions()): GameOver? {
    claim(bot)
    return runClaimed(bot, options)
}

/** Dedicated daemon thread; cancel(true) interrupts the runtime and aborts its socket. */
fun runAsync(bot: Bot, options: RunOptions = RunOptions()): CompletableFuture<GameOver?> {
    claim(bot)
    val future = CompletableFuture<GameOver?>()
    val worker =
        Thread(
                {
                    try {
                        future.complete(runClaimed(bot, options))
                    } catch (e: Throwable) {
                        if (e is InterruptedException) Thread.currentThread().interrupt()
                        future.completeExceptionally(e)
                    }
                },
                "naval-sdk-kotlin-bot",
            )
            .apply { isDaemon = true }
    future.whenComplete { _, _ ->
        if (future.isCancelled) {
            worker.interrupt()
            bot.socket?.abort()
        }
    }
    try {
        worker.start()
    } catch (e: Throwable) {
        bot.running.set(false)
        throw e
    }
    return future
}

internal fun claim(bot: Bot) {
    check(bot.running.compareAndSet(false, true)) { "bot already has an active runtime or replay" }
}

private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

private fun runClaimed(bot: Bot, options: RunOptions): GameOver? {
    bot.diagnostics = RuntimeDiagnostics()
    var lastResult: GameOver? = null
    try {
        for (attempt in 0..options.reconnectAttempts) {
            bot.phase = "connecting"
            bot.matchId = ""
            bot.lastTick = 0
            bot.clearWelcome()
            val session = Session(bot)
            val inbox = Inbox()
            var closeCode: Int? = null
            var reason = "client stopped"
            try {
                val connection =
                    http
                        .newWebSocketBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .buildAsync(options.url, inbox)
                val ws =
                    try {
                        connection.get(11, TimeUnit.SECONDS)
                    } catch (e: Exception) {
                        connection.whenComplete { opened, _ -> opened?.abort() }
                        throw e
                    }
                bot.socket = ws
                bot.rawSend(
                    Wire.obj()
                        .put("type", "hello")
                        .put("name", options.name)
                        .put("version", options.version)
                        .put("token", options.token)
                )
                val welcomeDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (!session.stop && !session.fatal) {
                    val frame =
                        if (bot.phase == "connecting")
                            inbox.queue.poll(
                                (welcomeDeadline - System.nanoTime()).coerceAtLeast(0),
                                TimeUnit.NANOSECONDS,
                            )
                        else inbox.queue.take()
                    when (frame) {
                        null -> throw TimeoutException()
                        is Closed -> {
                            closeCode = frame.code
                            reason = frame.reason
                            break
                        }
                        Failure -> throw IOException("WebSocket connection lost")
                        is String -> {
                            val msg =
                                try {
                                    Wire.parse(frame)
                                } catch (_: IllegalArgumentException) {
                                    bot.diagnostics.malformedFrames++
                                    continue
                                }
                            options.recorder?.record(msg)
                            session.handle(msg).forEach(bot::rawSend)
                        }
                        else -> bot.diagnostics.malformedFrames++
                    }
                }
            } catch (e: Exception) {
                if (
                    e !is ExecutionException &&
                        e !is CompletionException &&
                        e !is TimeoutException &&
                        e !is IOException
                )
                    throw e
                reason = "connection failed or handshake timed out"
                if (attempt >= options.reconnectAttempts || bot.phase == "running")
                    throw IOException(reason)
            } finally {
                val ws = bot.socket
                bot.socket = null
                if (ws != null) {
                    try {
                        ws.sendClose(WebSocket.NORMAL_CLOSURE, "client stopped")
                            .get(1, TimeUnit.SECONDS)
                    } catch (e: Exception) {
                        if (e is InterruptedException) Thread.currentThread().interrupt()
                    } finally {
                        ws.abort()
                    }
                }
                lastResult = session.result ?: lastResult
                val info = DisconnectInfo(closeCode, reason, bot.phase)
                bot.diagnostics.lastDisconnect = info
                session.call { bot.onDisconnect(info) }
            }
            if (
                session.stop ||
                    session.fatal ||
                    bot.phase == "running" ||
                    attempt >= options.reconnectAttempts
            )
                return lastResult
            TimeUnit.NANOSECONDS.sleep(options.reconnectDelay.toNanos())
        }
        return lastResult
    } finally {
        bot.phase = "disconnected"
        bot.running.set(false)
    }
}

private data class Closed(val code: Int, val reason: String)

private object Failure

private class Inbox : WebSocket.Listener {
    val queue = ArrayBlockingQueue<Any>(512)
    private val text = StringBuilder()

    private fun offer(ws: WebSocket, value: Any) {
        if (!queue.offer(value)) {
            queue.clear()
            queue.offer(Failure)
            ws.abort()
        }
    }

    override fun onOpen(ws: WebSocket) {
        ws.request(1)
    }

    override fun onText(ws: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
        if (text.length + data.length > 1_048_576) {
            offer(ws, Failure)
            ws.abort()
            return null
        }
        text.append(data)
        if (last) {
            offer(ws, text.toString())
            text.setLength(0)
        }
        ws.request(1)
        return null
    }

    override fun onBinary(ws: WebSocket, data: ByteBuffer, last: Boolean): CompletionStage<*>? {
        if (last) offer(ws, false)
        ws.request(1)
        return null
    }

    override fun onPing(ws: WebSocket, message: ByteBuffer): CompletionStage<*> {
        ws.request(1)
        return ws.sendPong(message)
    }

    override fun onPong(ws: WebSocket, message: ByteBuffer): CompletionStage<*>? {
        ws.request(1)
        return null
    }

    override fun onClose(ws: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
        offer(ws, Closed(statusCode, reason))
        return null
    }

    override fun onError(ws: WebSocket, error: Throwable) {
        offer(ws, Failure)
    }
}
