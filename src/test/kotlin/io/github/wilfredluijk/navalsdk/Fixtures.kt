package io.github.wilfredluijk.navalsdk

import com.fasterxml.jackson.databind.node.ObjectNode
import io.github.wilfredluijk.navalsdk.internal.Wire

internal fun fixture(name: String): ObjectNode =
    Wire.parse(
            checkNotNull(object {}.javaClass.getResourceAsStream("/protocol3.json"))
                .bufferedReader()
                .use { it.readText() }
        )
        .get(name)
        .deepCopy()

internal fun welcomeFixture() = Welcome.fromJson(fixture("welcome"))

internal fun viewFixture(tick: Int = 1) = WorldView.fromJson(fixture("tick").put("tick", tick))
