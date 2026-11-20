package com.bighelp.droidhelp.transport

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal sealed interface FrameParseOutcome {
    data class Valid(val frame: ParsedJsonFrame) : FrameParseOutcome
    data object DefinitivelyMalformed : FrameParseOutcome
    data object IncompleteOrInvalid : FrameParseOutcome
}

/**
 * Stateless parser for a single JSON object frame with bounded nesting depth
 * and extraction of `headers`, `payload`, and optional typed [Message] fields.
 */
internal object JsonFrameParser {

    private const val HEADERS_KEY = "headers"
    private const val PAYLOAD_KEY = "payload"
    private const val PARAMS_KEY = "params"
    private const val RESULT_KEY = "result"
    private const val ERROR_KEY = "error"

    fun parse(trimmedText: String, json: Json, maxNestingDepth: Int): FrameParseOutcome {
        return when {
            trimmedText.isEmpty() -> FrameParseOutcome.IncompleteOrInvalid
            exceedsMaxNestingDepth(trimmedText, maxNestingDepth) -> FrameParseOutcome.DefinitivelyMalformed
            else -> parseElement(trimmedText, json)
        }
    }

    private class NestingScanner(private val maxDepth: Int) {
        private var depth = 0
        private var inString = false
        private var escaped = false
        var exceeded = false
            private set

        fun consume(ch: Char) {
            if (inString) {
                consumeStringChar(ch)
            } else {
                consumeStructureChar(ch)
            }
        }

        private fun consumeStringChar(ch: Char) {
            when {
                escaped -> escaped = false
                ch == '\\' -> escaped = true
                ch == '"' -> inString = false
            }
        }

        private fun consumeStructureChar(ch: Char) {
            when (ch) {
                '"' -> inString = true
                '{', '[' -> {
                    depth++
                    exceeded = depth > maxDepth
                }
                '}', ']' -> if (depth > 0) depth--
            }
        }
    }

    fun exceedsMaxNestingDepth(text: String, maxDepth: Int): Boolean {
        val scanner = NestingScanner(maxDepth)
        var index = 0
        while (index < text.length && !scanner.exceeded) {
            scanner.consume(text[index])
            index++
        }
        return scanner.exceeded
    }

    private fun parseElement(trimmedText: String, json: Json): FrameParseOutcome {
        val element = try {
            json.parseToJsonElement(trimmedText)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
        return when (element) {
            null -> FrameParseOutcome.IncompleteOrInvalid
            !is JsonObject -> FrameParseOutcome.DefinitivelyMalformed
            else -> extractFrame(element, json, trimmedText.toByteArray(Charsets.UTF_8).size)
        }
    }

    private fun extractFrame(jsonObject: JsonObject, json: Json, byteSize: Int): FrameParseOutcome {
        val headers = extractHeaders(jsonObject)
        return if (headers == null) {
            FrameParseOutcome.DefinitivelyMalformed
        } else {
            val payload = extractPayload(jsonObject)
            val message = tryDecodeMessage(jsonObject, json)
            FrameParseOutcome.Valid(
                ParsedJsonFrame(
                    headers = headers,
                    payload = payload,
                    rawObject = jsonObject,
                    message = message,
                    byteSize = byteSize
                )
            )
        }
    }

    private fun extractHeaders(jsonObject: JsonObject): Map<String, String>? {
        val rawHeaders = jsonObject[HEADERS_KEY]
        return when {
            rawHeaders == null || rawHeaders is JsonNull -> emptyMap()
            rawHeaders !is JsonObject -> null
            else -> extractHeaderEntries(rawHeaders)
        }
    }

    private fun extractHeaderEntries(headersObject: JsonObject): Map<String, String>? {
        val extracted = LinkedHashMap<String, String>(headersObject.size)
        var valid = true
        for ((key, value) in headersObject) {
            if (value is JsonPrimitive && value !is JsonNull) {
                extracted[key] = value.content
            } else {
                valid = false
                break
            }
        }
        return if (valid) extracted else null
    }

    private fun extractPayload(jsonObject: JsonObject): JsonElement? {
        val params = jsonObject[PARAMS_KEY]
        return when {
            PAYLOAD_KEY in jsonObject -> jsonObject[PAYLOAD_KEY]
            params is JsonObject && PAYLOAD_KEY in params -> params[PAYLOAD_KEY]
            RESULT_KEY in jsonObject -> jsonObject[RESULT_KEY]
            ERROR_KEY in jsonObject -> jsonObject[ERROR_KEY]
            PARAMS_KEY in jsonObject -> params
            else -> null
        }
    }

    private fun tryDecodeMessage(jsonObject: JsonObject, json: Json): Message? {
        val discriminator = json.configuration.classDiscriminator
        return if (discriminator !in jsonObject) {
            null
        } else {
            try {
                json.decodeFromJsonElement(Message.serializer(), jsonObject)
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }
}
