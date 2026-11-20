package com.bighelp.droidhelp.session

/**
 * Default maximum UTF-8 byte length permitted by [checkUtf8ByteLimit].
 */
const val DEFAULT_MAX_UTF8_BYTES: Int = 1024

/**
 * Validates that [input] does not exceed [maxBytes] when encoded as UTF-8.
 *
 * @param input The string to validate.
 * @param maxBytes The maximum allowed UTF-8 byte length (defaults to [DEFAULT_MAX_UTF8_BYTES]).
 * @throws IllegalArgumentException if [maxBytes] is negative or if `input.toByteArray(Charsets.UTF_8).size > maxBytes`.
 */
fun checkUtf8ByteLimit(input: String, maxBytes: Int = DEFAULT_MAX_UTF8_BYTES) {
    require(maxBytes >= 0) {
        "maxBytes must be non-negative, but was $maxBytes"
    }
    val byteSize = input.toByteArray(Charsets.UTF_8).size
    require(byteSize <= maxBytes) {
        "Input exceeds UTF-8 byte limit: $byteSize bytes > $maxBytes bytes"
    }
}
