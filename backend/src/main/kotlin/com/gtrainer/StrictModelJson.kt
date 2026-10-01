package com.gtrainer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** Model responses are untrusted: reject duplicate (including escaped) keys and deep/large JSON. */
internal object StrictModelJson {
    private val number = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")
    fun parse(body: String): JsonElement {
        require(body.length <= 32_768) { "Invalid model response" }
        var index = 0
        fun space() { while (index < body.length && body[index] in " \t\r\n") index++ }
        fun take(char: Char) {
            space()
            require(index < body.length && body[index++] == char) { "Invalid model response" }
        }
        fun string(): String {
            space()
            val start = index
            take('"')
            while (index < body.length) {
                when (body[index++]) {
                    '\\' -> { require(index < body.length); index++ }
                    '"' -> return Json.decodeFromString<String>(body.substring(start, index))
                }
            }
            throw IllegalArgumentException("Invalid model response")
        }
        fun value(depth: Int) {
            require(depth <= 16) { "Invalid model response" }
            space()
            require(index < body.length) { "Invalid model response" }
            when (body[index]) {
                '{' -> {
                    take('{'); space()
                    val keys = mutableSetOf<String>()
                    if (index < body.length && body[index] != '}') while (true) {
                        require(keys.add(string())) { "Invalid model response" }
                        take(':'); value(depth + 1); space()
                        if (index < body.length && body[index] == ',') { index++; continue }
                        break
                    }
                    take('}')
                }
                '[' -> {
                    take('['); space()
                    if (index < body.length && body[index] != ']') while (true) {
                        value(depth + 1); space()
                        if (index < body.length && body[index] == ',') { index++; continue }
                        break
                    }
                    take(']')
                }
                '"' -> string()
                else -> {
                    val start = index
                    while (index < body.length && body[index] !in ",]} \t\r\n") index++
                    require(index > start) { "Invalid model response" }
                    val token = body.substring(start, index)
                    require(token in setOf("true", "false", "null") || number.matches(token)) { "Invalid model response" }
                }
            }
        }
        value(0); space()
        require(index == body.length) { "Invalid model response" }
        return Json.parseToJsonElement(body)
    }
}
