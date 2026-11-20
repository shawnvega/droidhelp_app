/**
 * Session input bounds validation utilities.
 *
 * Provides stateless precondition checks that enforce:
 * - **UTF-8 byte length limits** via [checkUtf8ByteLimit] (defaulting to [DEFAULT_MAX_UTF8_BYTES] = `1024` bytes),
 *   measuring encoded UTF-8 byte size rather than UTF-16 code unit count.
 * - **Inclusive integer range bounds** via [checkIntBounds], accepting 64-bit [Long] values and defaulting to the
 *   signed 32-bit [Int] range (`[Int.MIN_VALUE, Int.MAX_VALUE]`).
 * - **Verbatim identifier preservation** via [ensureIdNotNormalized], validating that an identifier is non-empty and
 *   non-blank while returning it unchanged without case-folding, trimming, or Unicode normalization.
 *
 * All validation failures throw [IllegalArgumentException] with descriptive diagnostic messages.
 */
package com.bighelp.droidhelp.session

/**
 * Default maximum UTF-8 encoded byte length (`1024` bytes / 1 KiB) enforced by [checkUtf8ByteLimit]
 * when callers do not supply a custom `maxBytes` limit.
 */
const val DEFAULT_MAX_UTF8_BYTES: Int = 1024

/**
 * Validates that [input] does not exceed [maxBytes] bytes when encoded as UTF-8.
 *
 * ### Validation Rules
 * - **UTF-8 byte measurement**: Evaluates the exact UTF-8 byte count via
 *   `input.toByteArray(Charsets.UTF_8).size`, rather than [String.length] (UTF-16 code units).
 *   Multi-byte Unicode characters (2-, 3-, and 4-byte code points, surrogate pairs, and ZWJ sequences)
 *   contribute their full UTF-8 byte length toward the limit.
 * - **Default limit**: When [maxBytes] is omitted, defaults to [DEFAULT_MAX_UTF8_BYTES] (`1024` bytes).
 * - **Inclusive upper bound**: Succeeds when the encoded UTF-8 byte count is less than or equal to
 *   [maxBytes] (for example, `""` is valid when `maxBytes == 0`).
 * - **Limit precondition**: [maxBytes] must be non-negative (`maxBytes >= 0`).
 *
 * @param input The string to validate.
 * @param maxBytes The maximum permitted UTF-8 byte length, inclusive (must be `>= 0`; defaults to
 *   [DEFAULT_MAX_UTF8_BYTES], i.e. `1024` bytes).
 * @throws IllegalArgumentException if [maxBytes] is negative (`maxBytes < 0`) or if the UTF-8 encoded
 *   length of [input] strictly exceeds [maxBytes] (`input.toByteArray(Charsets.UTF_8).size > maxBytes`),
 *   with a message reporting the invalid limit or the actual and maximum byte counts.
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
 * Validates that [value] lies within the inclusive integer range `[min, max]`.
 *
 * ### Validation Rules
 * - **Supported range**: Uses 64-bit signed [Long] parameters so values across the full
 *   `[Long.MIN_VALUE, Long.MAX_VALUE]` range—including values outside the 32-bit [Int] range—can be
 *   validated safely without overflow or narrowing.
 * - **Default range**: Defaults to the signed 32-bit [Int] range
 *   `[Int.MIN_VALUE, Int.MAX_VALUE]` (`-2_147_483_648L` to `2_147_483_647L`).
 * - **Inclusive bounds**: Succeeds when `min <= value && value <= max`, including boundary values
 *   (`value == min` or `value == max`) and single-point ranges (`min == max`). Callers may also
 *   override only one bound while keeping the other at its default, provided `min <= max`.
 * - **Range precondition**: The lower bound must not exceed the upper bound (`min <= max`).
 *
 * @param value The 64-bit signed integer value to validate.
 * @param min The inclusive lower bound (defaults to [Int.MIN_VALUE] as a [Long]).
 * @param max The inclusive upper bound (defaults to [Int.MAX_VALUE] as a [Long]).
 * @throws IllegalArgumentException if the bounds are inverted (`min > max`) or if [value] falls
 *   outside `[min, max]` (`value < min || value > max`), with a message reporting the offending value
 *   and bounds.
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

/**
 * Validates that [id] is a valid non-empty, non-blank identifier and returns it verbatim without
 * normalization.
 *
 * ### Validation Rules
 * - **Verbatim preservation**: Returns [id] unchanged — no case-folding (uppercase/lowercase letters
 *   remain intact), no trimming of leading or trailing whitespace, and no Unicode canonicalization
 *   (NFC/NFD forms are preserved as provided).
 * - **Non-empty / non-blank check**: Rejects empty (`""`) and whitespace-only strings, matching the
 *   session identifier contract in [SessionCoordinator].
 *
 * @param id The identifier string to validate.
 * @return [id] verbatim without modification.
 * @throws IllegalArgumentException if [id] is empty or blank (contains only whitespace).
 */
fun ensureIdNotNormalized(id: String): String {
    require(id.isNotBlank()) {
        "ID must not be empty or blank"
    }
    return id
}
