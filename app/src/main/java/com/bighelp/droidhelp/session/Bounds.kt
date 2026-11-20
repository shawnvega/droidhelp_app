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

/**
 * Validates that [value] lies within the inclusive integer bounds `[min, max]`.
 *
 * Uses [Long] parameters so values outside the 32-bit [Int] range can be validated.
 *
 * @param value The integer value to validate.
 * @param min The inclusive lower bound (defaults to [Int.MIN_VALUE]).
 * @param max The inclusive upper bound (defaults to [Int.MAX_VALUE]).
 * @throws IllegalArgumentException if `min > max` or if `value < min || value > max`.
 */
fun checkIntBounds(
    value: Long,
    min: Long = Int.MIN_VALUE.toLong(),
    max: Long = Int.MAX_VALUE.toLong(),
) {
    require(min <= max) {
        "Invalid integer bounds: min ($min) must be <= max ($max)"
    }
    require(value >= min && value <= max) {
        "Value $value is out of integer bounds [$min, $max]"
    }
}
