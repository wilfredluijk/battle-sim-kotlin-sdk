package io.github.wilfredluijk.navalsdk.internal

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.databind.node.ObjectNode

/** Strict wire parsing. Never use Jackson's coercing asDouble/asInt for required fields. */
internal object Wire {
    val mapper = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build()

    fun obj(): ObjectNode = mapper.createObjectNode()

    fun parse(text: String): ObjectNode =
        try {
            mapper.readTree(text) as? ObjectNode
                ?: throw IllegalArgumentException("expected JSON object")
        } catch (_: com.fasterxml.jackson.core.JacksonException) {
            throw IllegalArgumentException("invalid JSON object")
        }
}

internal fun finite(value: Double): Double {
    require(value.isFinite() && kotlin.math.abs(value) <= Float.MAX_VALUE.toDouble()) {
        "number must fit finite f32"
    }
    return value
}

internal fun positive(value: Double): Double =
    finite(value).also { require(it > 0) { "expected positive number" } }

internal fun JsonNode.obj(): ObjectNode =
    this as? ObjectNode ?: throw IllegalArgumentException("expected object")

internal fun JsonNode.required(key: String): JsonNode =
    get(key) ?: throw IllegalArgumentException("missing $key")

internal fun JsonNode.text(): String {
    require(isTextual) { "expected string" }
    return textValue()
}

internal fun JsonNode.str(key: String, default: String? = null): String =
    get(key)?.text() ?: default ?: throw IllegalArgumentException("missing $key")

internal fun JsonNode.number(): Double {
    require(isNumber) { "expected number" }
    return finite(doubleValue())
}

internal fun JsonNode.num(key: String, default: Double? = null): Double =
    get(key)?.number() ?: default ?: throw IllegalArgumentException("missing $key")

internal fun JsonNode.integer(): Int {
    val n = number()
    require(n >= Int.MIN_VALUE && n <= Int.MAX_VALUE && n == n.toInt().toDouble()) {
        "expected integer"
    }
    return n.toInt()
}

internal fun JsonNode.int(key: String, default: Int? = null): Int =
    get(key)?.integer() ?: default ?: throw IllegalArgumentException("missing $key")

internal fun JsonNode.bool(key: String, default: Boolean = false): Boolean =
    get(key)?.let {
        require(it.isBoolean) { "expected boolean" }
        it.booleanValue()
    } ?: default

internal fun JsonNode.array(): List<JsonNode> {
    require(isArray) { "expected array" }
    return toList()
}

internal fun JsonNode.items(key: String): List<JsonNode> = get(key)?.array() ?: emptyList()

internal fun JsonNode.strings(key: String): List<String> = items(key).map { it.text() }
