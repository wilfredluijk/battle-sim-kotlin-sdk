package examples

import io.github.wilfredluijk.navalsdk.*
import io.github.wilfredluijk.navalsdk.cli.ConnectionArguments
import io.github.wilfredluijk.navalsdk.tactical.*

class HunterBot : TacticalBot() {
    override fun choosePowerups(welcome: Welcome) = listOf("repair_drones", "long_range_salvo")

    override fun onTacticalWelcome(welcome: Welcome) {
        sensorPolicy = PingWhenStale(staleThresholdTicks = 4)
    }

    override fun decide(ctx: TacticalContext): Intent {
        val target = ctx.threats.nearest()
        if (ctx.me.hp < 40 && ctx.me.powerupReady("repair_drones")) {
            val steering = helm!!.steerToPoint(ctx.me, Vec2(ctx.mapWidth / 2, ctx.mapHeight / 2))
            return Intent.custom(
                Command(steering.throttle, steering.rudder, activatePowerup = "repair_drones")
            )
        }
        return target?.let(Intent::engage)
            ?: Intent.patrol(
                PatrolRect(
                    ctx.mapWidth * .2,
                    ctx.mapHeight * .2,
                    ctx.mapWidth * .8,
                    ctx.mapHeight * .8,
                )
            )
    }
}

fun main(args: Array<String>) {
    run(HunterBot(), ConnectionArguments.resolve(args, name = "kotlin-hunter"))
}
