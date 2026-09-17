package io.github.wilfredluijk.navalsdk

import com.fasterxml.jackson.databind.node.ObjectNode
import java.net.http.WebSocket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class TickTiming(
    val matchId: String,
    val tick: Int,
    val elapsedMs: Double,
    val deadlineMs: Int,
    val overBudget: Boolean,
)

data class DisconnectInfo(val code: Int?, val reason: String, val phase: String)

class RuntimeDiagnostics {
    var ticks = 0
        internal set

    var overruns = 0
        internal set

    var callbackErrors = 0
        internal set

    var malformedFrames = 0
        internal set

    internal val rejections = linkedMapOf<String, Int>()
    val rejectedCommands: Map<String, Int>
        get() = rejections.toMap()

    var lastTiming: TickTiming? = null
        internal set

    var lastDisconnect: DisconnectInfo? = null
        internal set
}

/** Subclass [onTick]. All callbacks are synchronous and serialized on the runtime thread. */
open class Bot {
    @Volatile
    var welcome: Welcome? = null
        protected set

    @Volatile
    var lastTick: Int = 0
        internal set

    @Volatile
    var matchId: String = ""
        internal set

    @Volatile
    var phase: String = "disconnected"
        internal set

    @Volatile
    var diagnostics = RuntimeDiagnostics()
        internal set

    internal val running = AtomicBoolean()
    @Volatile internal var socket: WebSocket? = null

    internal fun setWelcome(value: Welcome) {
        welcome = value
    }

    internal fun clearWelcome() {
        welcome = null
    }

    open fun acceptConfiguration(configuration: ObjectNode, configHash: String): Boolean = true

    open fun onWelcome(welcome: Welcome) {}

    open fun choosePowerups(welcome: Welcome): List<String> = emptyList()

    open fun onGameStart(tick: Int, startingPosition: Vec2, startingHeadingDeg: Double) {}

    open fun onGameStartEvent(start: GameStart) =
        onGameStart(start.tick, start.startingPosition, start.startingHeadingDeg)

    open fun onTick(view: WorldView): Command? = Command()

    /** Return false to disconnect, true to participate in subsequent rounds. */
    open fun onGameOver(result: GameOver): Boolean = true

    open fun onLobby(tick: Int) {}

    open fun onError(code: String, message: String) {}

    open fun onTickTiming(timing: TickTiming) {}

    open fun onDisconnect(info: DisconnectInfo) {}

    /** Escape hatch: the caller must supply correct match/tick IDs for raw commands. */
    @Synchronized
    fun rawSend(payload: ObjectNode) {
        val ws = checkNotNull(socket) { "connection is not open" }
        ws.sendText(payload.toString(), true).get(10, TimeUnit.SECONDS)
    }
}

class ProtocolMismatch : IllegalArgumentException("this SDK supports server protocol 3.x")
