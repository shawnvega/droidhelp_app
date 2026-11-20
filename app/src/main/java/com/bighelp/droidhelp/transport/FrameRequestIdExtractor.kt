package com.bighelp.droidhelp.transport

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Extracts outgoing request correlation identifiers and error status from a [ParsedJsonFrame].
 *
 * Follows the Hermes JSON-RPC 2.0 wire contract:
 * - Frames containing a `method` field (server events or server-to-client requests) are never
 *   correlated against outgoing client request IDs, preventing ID collisions from hijacking
 *   pending client RPCs.
 * - JSON-RPC 2.0 replies must carry a string `id`, omit `params`, and include exactly one of
 *   `result` or an object `error`.
 * - Typed [Message.Receipt] frames correlate via [Message.Receipt.entryId].
 * - Generic JSON frames without `method` may correlate via top-level string `id`, `requestId`,
 *   `request_id`, or `request-id` / `x-request-id` headers.
 */
internal object FrameRequestIdExtractor {

    const val MAX_REQUEST_ID_BYTES = 1_024

    private const val JSONRPC_KEY = "jsonrpc"
    private const val METHOD_KEY = "method"
    private const val ID_KEY = "id"
    private const val PARAMS_KEY = "params"
    private const val RESULT_KEY = "result"
    private const val ERROR_KEY = "error"
    private const val REQUEST_ID_CAMEL_KEY = "requestId"
    private const val REQUEST_ID_SNAKE_KEY = "request_id"
    private val REQUEST_ID_HEADER_KEYS = setOf("request-id", "x-request-id", "requestid")

    fun extractRequestId(frame: ParsedJsonFrame): String? {
        val raw = frame.rawObject
        if (METHOD_KEY in raw) {
            return null
        }
        val receiptId = (frame.message as? Message.Receipt)?.entryId
        return when {
            receiptId != null -> receiptId.takeIf(::isValidRequestId)
            JSONRPC_KEY in raw -> extractJsonRpcReplyId(raw)
            else -> extractGenericFrameId(frame)
        }
    }

    fun isErrorFrame(frame: ParsedJsonFrame): Boolean {
        val errorElement = frame.rawObject[ERROR_KEY]
        return errorElement != null && errorElement !is JsonNull
    }

    private fun extractJsonRpcReplyId(raw: JsonObject): String? {
        val hasResult = RESULT_KEY in raw
        val hasError = ERROR_KEY in raw
        val validError = !hasError || raw[ERROR_KEY] is JsonObject
        val validReplyShape = PARAMS_KEY !in raw && (hasResult xor hasError) && validError
        return if (validReplyShape) {
            extractStringField(raw, ID_KEY)
        } else {
            null
        }
    }

    private fun extractGenericFrameId(frame: ParsedJsonFrame): String? {
        val raw = frame.rawObject
        return extractStringField(raw, ID_KEY)
            ?: extractStringField(raw, REQUEST_ID_CAMEL_KEY)
            ?: extractStringField(raw, REQUEST_ID_SNAKE_KEY)
            ?: extractHeaderRequestId(frame.headers)
    }

    private fun extractStringField(jsonObject: JsonObject, key: String): String? {
        val primitive = jsonObject[key] as? JsonPrimitive ?: return null
        return if (primitive.isString && isValidRequestId(primitive.content)) {
            primitive.content
        } else {
            null
        }
    }

    private fun extractHeaderRequestId(headers: Map<String, String>): String? {
        for ((key, value) in headers) {
            if (key.lowercase() in REQUEST_ID_HEADER_KEYS && isValidRequestId(value)) {
                return value
            }
        }
        return null
    }

    private fun isValidRequestId(id: String): Boolean {
        return id.isNotEmpty() && id.toByteArray(Charsets.UTF_8).size <= MAX_REQUEST_ID_BYTES
    }
}
