package com.bighelp.droidhelp.transport

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Parsed representation of a single newline-delimited JSON object frame.
 *
 * Extracts top-level [headers] and [payload] fields (falling back to JSON-RPC
 * envelope fields such as `params.payload`, `result`, `error`, or `params` when
 * top-level `payload` is absent), retains the full [rawObject], and attaches
 * the decoded [message] when the frame matches a typed [Message].
 */
data class ParsedJsonFrame(
    val headers: Map<String, String> = emptyMap(),
    val payload: JsonElement? = null,
    val rawObject: JsonObject,
    val message: Message? = null,
    val byteSize: Int = 0
) {
    /** Convenience view of [payload] when it is a JSON object. */
    val payloadObject: JsonObject?
        get() = payload as? JsonObject
}
