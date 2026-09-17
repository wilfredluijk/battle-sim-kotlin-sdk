package io.github.wilfredluijk.navalsdk.cli

import io.github.wilfredluijk.navalsdk.*
import io.github.wilfredluijk.navalsdk.tactical.*
import kotlin.system.exitProcess

class PatrolBot : TacticalBot() {
    override fun decide(ctx: TacticalContext): Intent =
        ctx.threats.nearest()?.let(Intent::engage)
            ?: Intent.patrol(
                PatrolRect(
                    ctx.mapWidth * .2,
                    ctx.mapHeight * .2,
                    ctx.mapWidth * .8,
                    ctx.mapHeight * .8,
                )
            )
}

fun main(args: Array<String>) {
    if (args.any { it == "--help" || it == "-h" }) {
        println("Battle Sim Kotlin SDK 0.1.0 — sample patrol bot")
        println(
            "Usage: java -jar naval-sdk-kotlin-0.1.0-all.jar [--url WS_URL | --host HOST --port PORT] [--env-file PATH]"
        )
        println(
            "Credentials: BATTLE_BOT_TOKEN or the participant --env-file. Default endpoint: ws://localhost:7878/bot"
        )
        return
    }
    try {
        val result = run(PatrolBot(), ConnectionArguments.resolve(args, name = "kotlin-patrol"))
        println(
            if (result == null) "Disconnected without a match result."
            else "Match ended at tick ${result.finalTick}; replay ${result.replayId}"
        )
    } catch (e: IllegalArgumentException) {
        System.err.println(e.message)
        exitProcess(2)
    } catch (_: Exception) {
        System.err.println("Bot connection failed.")
        exitProcess(1)
    }
}
