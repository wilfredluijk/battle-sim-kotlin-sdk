package examples

import io.github.wilfredluijk.navalsdk.*
import io.github.wilfredluijk.navalsdk.cli.ConnectionArguments

class MyBot : Bot() {
    override fun onTick(view: WorldView) = Command(throttle = 0.6, rudder = 0.2)
}

fun main(args: Array<String>) {
    run(MyBot(), ConnectionArguments.resolve(args, name = "my-kotlin-bot"))
}
