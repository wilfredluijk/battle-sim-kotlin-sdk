package io.github.wilfredluijk.navalsdk

import com.fasterxml.jackson.databind.node.ObjectNode
import io.github.wilfredluijk.navalsdk.internal.*

/** One dispatcher for live transport and Python-compatible JSONL replay. */
internal class Session(private val bot: Bot) {
    private var readySent = false
    private var loadout: List<String>? = null
    var result: GameOver? = null
        private set

    var stop = false
        private set

    var fatal = false
        private set

    fun <T> call(callback: () -> T): T? =
        try {
            callback()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        } catch (_: Exception) {
            bot.diagnostics.callbackErrors++
            null
        }

    private fun checkProtocol(version: String) {
        if (!Regex("3\\.[0-9]+").matches(version)) throw ProtocolMismatch()
    }

    private fun ready(): List<ObjectNode> {
        val w = bot.welcome ?: return emptyList()
        readySent = false
        if (call { bot.acceptConfiguration(w.configuration, w.configHash) } != true)
            return emptyList()
        val picks = call { bot.choosePowerups(w).toList() } ?: return emptyList()
        if (
            picks.size !in listOf(0, 2) ||
                picks.toSet().size != picks.size ||
                picks.any { it !in w.availablePowerups }
        )
            return emptyList()
        if (picks.isEmpty() && !loadout.isNullOrEmpty() && w.rules?.emptyLoadout != true)
            return emptyList()
        val out = mutableListOf<ObjectNode>()
        if (picks.isNotEmpty() || !loadout.isNullOrEmpty()) {
            out +=
                Wire.obj().put("type", "select_powerups").apply {
                    putArray("powerups").also { a -> picks.forEach(a::add) }
                }
        }
        loadout = picks
        readySent = true
        out += Wire.obj().put("type", "ready").put("config_hash", w.configHash)
        return out
    }

    fun handle(msg: ObjectNode): List<ObjectNode> {
        try {
            when (msg.str("type", "unknown")) {
                "welcome" -> {
                    val initial = Welcome.fromJson(msg)
                    checkProtocol(initial.protocolVersion)
                    val w =
                        initial.withConfiguration(
                            msg.required("configuration").obj(),
                            msg.str("config_hash"),
                        )
                    checkProtocol(w.protocolVersion)
                    bot.setWelcome(w)
                    bot.phase = "lobby"
                    call { bot.onWelcome(w) }
                    return if (readySent) emptyList() else ready()
                }
                "configuration" -> {
                    val w =
                        (bot.welcome ?: return emptyList()).withConfiguration(
                            msg.required("configuration").obj(),
                            msg.str("config_hash"),
                        )
                    checkProtocol(w.protocolVersion)
                    bot.setWelcome(w)
                    readySent = false
                    call { bot.onWelcome(w) }
                    return ready()
                }
                "game_start" -> {
                    val start = GameStart.fromJson(msg)
                    val current = bot.welcome
                    require(start.matchId.isNotEmpty() && current != null)
                    // simulation_dt is authoritative even when a frame omits ship_specs.
                    val specs = start.shipSpecs ?: current.shipSpecs
                    if (specs != current.shipSpecs || start.simulationDt != current.simulationDt) {
                        val w = current.withMatchSpecs(specs, start.simulationDt)
                        bot.setWelcome(w)
                        call { bot.onWelcome(w) }
                    }
                    bot.phase = "running"
                    bot.matchId = start.matchId
                    bot.lastTick = start.tick
                    call { bot.onGameStartEvent(start) }
                }
                "tick" -> {
                    val view = WorldView.fromJson(msg)
                    require(view.matchId.isNotEmpty() && bot.welcome != null)
                    bot.lastTick = view.tick
                    bot.matchId = view.matchId
                    bot.phase = "running"
                    return listOf(command(view))
                }
                "game_over" -> {
                    val over = GameOver.fromJson(msg)
                    result = over
                    bot.phase = "ended"
                    stop = call { bot.onGameOver(over) } == false
                    readySent = false
                }
                "lobby" -> {
                    val tick = msg.int("tick", 0)
                    bot.phase = "lobby"
                    bot.lastTick = tick
                    bot.matchId = ""
                    call { bot.onLobby(tick) }
                    if (!readySent) {
                        loadout = null
                        return ready()
                    }
                }
                "error" -> {
                    val code = msg.str("code", "unknown")
                    bot.diagnostics.rejections.merge(code, 1, Int::plus)
                    fatal =
                        fatal ||
                            code in
                                setOf(
                                    "unauthorized",
                                    "invalid_name",
                                    "duplicate_name",
                                    "rate_limited",
                                )
                    call { bot.onError(code, msg.str("message", "")) }
                }
            }
        } catch (e: ProtocolMismatch) {
            fatal = true
            throw e
        } catch (_: IllegalArgumentException) {
            bot.diagnostics.malformedFrames++
        }
        return emptyList()
    }

    private fun command(view: WorldView): ObjectNode {
        val started = System.nanoTime()
        val cmd = call { bot.onTick(view) } ?: Command()
        val payload =
            try {
                require(
                    cmd.activatePowerup == null ||
                        cmd.activatePowerup in bot.welcome!!.availablePowerups
                )
                cmd.toJson(view.tick, view.matchId)
            } catch (_: IllegalArgumentException) {
                bot.diagnostics.callbackErrors++
                Command().toJson(view.tick, view.matchId)
            }
        val elapsed = (System.nanoTime() - started) / 1_000_000.0
        val timing =
            TickTiming(view.matchId, view.tick, elapsed, view.deadlineMs, elapsed > view.deadlineMs)
        bot.diagnostics.ticks++
        bot.diagnostics.lastTiming = timing
        if (timing.overBudget) bot.diagnostics.overruns++
        call { bot.onTickTiming(timing) }
        return payload
    }
}
