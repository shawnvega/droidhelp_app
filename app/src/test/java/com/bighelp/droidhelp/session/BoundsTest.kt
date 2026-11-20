package com.bighelp.droidhelp.session

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class BoundsTest {

    @Nested
    inner class Utf8ByteLimitTests {

        @Test
        fun `default max utf8 bytes constant is 1024`() {
            assertEquals(1024, DEFAULT_MAX_UTF8_BYTES)
        }

        @Test
        fun `allows normal ascii and mixed utf8 strings within limits`() {
            assertDoesNotThrow { checkUtf8ByteLimit("hello, droidhelp!") }
            assertDoesNotThrow { checkUtf8ByteLimit("hello", maxBytes = 10) }

            // "Hi é 中 😀" = 2 + 1 + 2 + 1 + 3 + 1 + 4 = 14 UTF-8 bytes
            val mixed = "Hi é 中 😀"
            assertEquals(14, mixed.toByteArray(Charsets.UTF_8).size)
            assertDoesNotThrow { checkUtf8ByteLimit(mixed) }
            assertDoesNotThrow { checkUtf8ByteLimit(mixed, maxBytes = 14) }
            assertDoesNotThrow { checkUtf8ByteLimit(mixed, maxBytes = 20) }
        }

        @Test
        fun `allows empty string with default, zero, and max int limits`() {
            assertDoesNotThrow { checkUtf8ByteLimit("") }
            assertDoesNotThrow { checkUtf8ByteLimit("", maxBytes = 0) }
            assertDoesNotThrow { checkUtf8ByteLimit("", maxBytes = Int.MAX_VALUE) }
        }

        @Test
        fun `allows ascii string up to exact default limit and rejects one byte over`() {
            val exactLimit = "a".repeat(DEFAULT_MAX_UTF8_BYTES)
            assertDoesNotThrow { checkUtf8ByteLimit(exactLimit) }

            val overLimit = "a".repeat(DEFAULT_MAX_UTF8_BYTES + 1)
            val exception = assertThrows(IllegalArgumentException::class.java) {
                checkUtf8ByteLimit(overLimit)
            }
            val message = exception.message.orEmpty()
            assertTrue(message.contains("1025"))
            assertTrue(message.contains("1024"))
        }

        @Test
        fun `validates one to four byte utf8 characters at exact byte boundaries`() {
            // 1-byte ASCII boundary characters (including NUL, which is 1 byte in standard UTF-8)
            assertDoesNotThrow { checkUtf8ByteLimit("\u0000", maxBytes = 1) }
            assertDoesNotThrow { checkUtf8ByteLimit("\u007F", maxBytes = 1) }
            assertThrows(IllegalArgumentException::class.java) {
                checkUtf8ByteLimit("\u007F", maxBytes = 0)
            }

            // 2-byte UTF-8 characters
            assertDoesNotThrow { checkUtf8ByteLimit("\u0080", maxBytes = 2) }
            assertDoesNotThrow { checkUtf8ByteLimit("é", maxBytes = 2) }
            assertThrows(IllegalArgumentException::class.java) {
                checkUtf8ByteLimit("é", maxBytes = 1)
            }

            // 3-byte UTF-8 characters
            assertDoesNotThrow { checkUtf8ByteLimit("\u0800", maxBytes = 3) }
            assertDoesNotThrow { checkUtf8ByteLimit("中", maxBytes = 3) }
            assertThrows(IllegalArgumentException::class.java) {
                checkUtf8ByteLimit("中", maxBytes = 2)
            }

            // 4-byte UTF-8 character (2 UTF-16 code units / surrogate pair)
            assertDoesNotThrow { checkUtf8ByteLimit("😀", maxBytes = 4) }
            val emojiOver = assertThrows(IllegalArgumentException::class.java) {
                checkUtf8ByteLimit("😀", maxBytes = 3)
            }
            assertTrue(emojiOver.message.orEmpty().contains("4"))
            assertTrue(emojiOver.message.orEmpty().contains("3"))
        }

        @Test
        fun `validates zwj emoji sequences and whitespace control characters by utf8 bytes`() {
            val familyEmoji = "👨‍👩‍👧‍👦"
            val familyBytes = familyEmoji.toByteArray(Charsets.UTF_8).size
            assertEquals(25, familyBytes)
            assertDoesNotThrow { checkUtf8ByteLimit(familyEmoji, maxBytes = familyBytes) }
            val familyOver = assertThrows(IllegalArgumentException::class.java) {
                checkUtf8ByteLimit(familyEmoji, maxBytes = familyBytes - 1)
            }
            assertTrue(familyOver.message.orEmpty().contains(familyBytes.toString()))
            assertTrue(familyOver.message.orEmpty().contains((familyBytes - 1).toString()))

            val whitespace = "\r\n\t "
            assertDoesNotThrow { checkUtf8ByteLimit(whitespace, maxBytes = 4) }
            assertThrows(IllegalArgumentException::class.java) {
                checkUtf8ByteLimit(whitespace, maxBytes = 3)
            }
        }

        @Test
        fun `enforces default 1024 byte limit on multi-byte strings despite fewer than 1024 chars`() {
            // 512 * 2 bytes = 1024 bytes (exact default limit)
            assertDoesNotThrow { checkUtf8ByteLimit("é".repeat(512)) }
            // 256 * 4 bytes = 1024 bytes (exact default limit)
            assertDoesNotThrow { checkUtf8ByteLimit("😀".repeat(256)) }
            // 341 * 3 bytes = 1023 bytes (one byte below default limit)
            assertDoesNotThrow { checkUtf8ByteLimit("中".repeat(341)) }
            // 341 * 3 + 1 = 1024 bytes (exact default limit)
            assertDoesNotThrow { checkUtf8ByteLimit("中".repeat(341) + "a") }

            // 513 * 2 bytes = 1026 bytes (> 1024), even though length is 513 chars
            val twoByteOver = assertThrows(IllegalArgumentException::class.java) {
                checkUtf8ByteLimit("é".repeat(513))
            }
            assertTrue(twoByteOver.message.orEmpty().contains("1026"))
            assertTrue(twoByteOver.message.orEmpty().contains("1024"))

            val emojiOver = assertThrows(IllegalArgumentException::class.java) {
                checkUtf8ByteLimit("😀".repeat(256) + "a")
            }
            assertTrue(emojiOver.message.orEmpty().contains("1025"))
            assertTrue(emojiOver.message.orEmpty().contains("1024"))

            // 342 * 3 bytes = 1026 bytes (> 1024), even though length is 342 chars
            val cjkOver = assertThrows(IllegalArgumentException::class.java) {
                checkUtf8ByteLimit("中".repeat(342))
            }
            assertTrue(cjkOver.message.orEmpty().contains("1026"))
            assertTrue(cjkOver.message.orEmpty().contains("1024"))
        }

        @Test
        fun `allows custom maxBytes above default limit and rejects one byte over`() {
            val customLimit = DEFAULT_MAX_UTF8_BYTES * 2
            assertDoesNotThrow { checkUtf8ByteLimit("a".repeat(customLimit), maxBytes = customLimit) }
            assertDoesNotThrow { checkUtf8ByteLimit("😀".repeat(customLimit / 4), maxBytes = customLimit) }

            val overLimit = assertThrows(IllegalArgumentException::class.java) {
                checkUtf8ByteLimit("a".repeat(customLimit + 1), maxBytes = customLimit)
            }
            val message = overLimit.message.orEmpty()
            assertTrue(message.contains((customLimit + 1).toString()))
            assertTrue(message.contains(customLimit.toString()))
        }

        @Test
        fun `rejects non-empty input when maxBytes is zero`() {
            val exception = assertThrows(IllegalArgumentException::class.java) {
                checkUtf8ByteLimit("a", maxBytes = 0)
            }
            val message = exception.message.orEmpty()
            assertTrue(message.contains("1"))
            assertTrue(message.contains("0"))
        }

        @Test
        fun `throws when maxBytes is negative`() {
            val negOne = assertThrows(IllegalArgumentException::class.java) {
                checkUtf8ByteLimit("", maxBytes = -1)
            }
            assertTrue(negOne.message.orEmpty().contains("-1"))

            val negTen = assertThrows(IllegalArgumentException::class.java) {
                checkUtf8ByteLimit("hello", maxBytes = -10)
            }
            assertTrue(negTen.message.orEmpty().contains("-10"))

            val minInt = assertThrows(IllegalArgumentException::class.java) {
                checkUtf8ByteLimit("", maxBytes = Int.MIN_VALUE)
            }
            assertTrue(minInt.message.orEmpty().contains(Int.MIN_VALUE.toString()))
        }
    }

    @Nested
    inner class IntBoundsTests {

        @Test
        fun `allows normal and boundary values within default 32-bit int bounds`() {
            assertDoesNotThrow { checkIntBounds(0L) }
            assertDoesNotThrow { checkIntBounds(1L) }
            assertDoesNotThrow { checkIntBounds(-1L) }
            assertDoesNotThrow { checkIntBounds(42L) }
            assertDoesNotThrow { checkIntBounds(-42L) }
            assertDoesNotThrow { checkIntBounds(100_000L) }
            assertDoesNotThrow { checkIntBounds(-100_000L) }
            assertDoesNotThrow { checkIntBounds(Int.MIN_VALUE.toLong()) }
            assertDoesNotThrow { checkIntBounds(Int.MAX_VALUE.toLong()) }
        }

        @Test
        fun `throws when value is below default int min bound`() {
            val belowMin = Int.MIN_VALUE.toLong() - 1L
            val exception = assertThrows(IllegalArgumentException::class.java) {
                checkIntBounds(belowMin)
            }
            val message = exception.message.orEmpty()
            assertTrue(message.contains(belowMin.toString()))
            assertTrue(message.contains(Int.MIN_VALUE.toString()))
            assertTrue(message.contains(Int.MAX_VALUE.toString()))
        }

        @Test
        fun `throws when value is above default int max bound`() {
            val aboveMax = Int.MAX_VALUE.toLong() + 1L
            val exception = assertThrows(IllegalArgumentException::class.java) {
                checkIntBounds(aboveMax)
            }
            val message = exception.message.orEmpty()
            assertTrue(message.contains(aboveMax.toString()))
            assertTrue(message.contains(Int.MIN_VALUE.toString()))
            assertTrue(message.contains(Int.MAX_VALUE.toString()))
        }

        @Test
        fun `throws for extreme long values under default bounds`() {
            assertThrows(IllegalArgumentException::class.java) {
                checkIntBounds(Long.MIN_VALUE)
            }
            assertThrows(IllegalArgumentException::class.java) {
                checkIntBounds(Long.MAX_VALUE)
            }
        }

        @Test
        fun `validates positive, negative, and zero-spanning custom inclusive bounds`() {
            assertDoesNotThrow { checkIntBounds(10L, min = 10L, max = 20L) }
            assertDoesNotThrow { checkIntBounds(15L, min = 10L, max = 20L) }
            assertDoesNotThrow { checkIntBounds(20L, min = 10L, max = 20L) }

            val belowPositive = assertThrows(IllegalArgumentException::class.java) {
                checkIntBounds(9L, min = 10L, max = 20L)
            }
            assertTrue(belowPositive.message.orEmpty().contains("9"))
            assertTrue(belowPositive.message.orEmpty().contains("10"))
            assertTrue(belowPositive.message.orEmpty().contains("20"))

            val abovePositive = assertThrows(IllegalArgumentException::class.java) {
                checkIntBounds(21L, min = 10L, max = 20L)
            }
            assertTrue(abovePositive.message.orEmpty().contains("21"))
            assertTrue(abovePositive.message.orEmpty().contains("10"))
            assertTrue(abovePositive.message.orEmpty().contains("20"))

            assertDoesNotThrow { checkIntBounds(-20L, min = -20L, max = -10L) }
            assertDoesNotThrow { checkIntBounds(-15L, min = -20L, max = -10L) }
            assertDoesNotThrow { checkIntBounds(-10L, min = -20L, max = -10L) }
            assertThrows(IllegalArgumentException::class.java) {
                checkIntBounds(-21L, min = -20L, max = -10L)
            }
            assertThrows(IllegalArgumentException::class.java) {
                checkIntBounds(-9L, min = -20L, max = -10L)
            }

            assertDoesNotThrow { checkIntBounds(-5L, min = -5L, max = 5L) }
            assertDoesNotThrow { checkIntBounds(0L, min = -5L, max = 5L) }
            assertDoesNotThrow { checkIntBounds(5L, min = -5L, max = 5L) }
            assertThrows(IllegalArgumentException::class.java) {
                checkIntBounds(-6L, min = -5L, max = 5L)
            }
            assertThrows(IllegalArgumentException::class.java) {
                checkIntBounds(6L, min = -5L, max = 5L)
            }
        }

        @Test
        fun `validates single-point ranges where min equals max`() {
            assertDoesNotThrow { checkIntBounds(0L, min = 0L, max = 0L) }
            assertThrows(IllegalArgumentException::class.java) {
                checkIntBounds(-1L, min = 0L, max = 0L)
            }
            assertThrows(IllegalArgumentException::class.java) {
                checkIntBounds(1L, min = 0L, max = 0L)
            }

            assertDoesNotThrow { checkIntBounds(5L, min = 5L, max = 5L) }
            assertThrows(IllegalArgumentException::class.java) {
                checkIntBounds(4L, min = 5L, max = 5L)
            }
            assertThrows(IllegalArgumentException::class.java) {
                checkIntBounds(6L, min = 5L, max = 5L)
            }

            assertDoesNotThrow { checkIntBounds(Long.MIN_VALUE, min = Long.MIN_VALUE, max = Long.MIN_VALUE) }
            assertThrows(IllegalArgumentException::class.java) {
                checkIntBounds(Long.MIN_VALUE + 1L, min = Long.MIN_VALUE, max = Long.MIN_VALUE)
            }

            assertDoesNotThrow { checkIntBounds(Long.MAX_VALUE, min = Long.MAX_VALUE, max = Long.MAX_VALUE) }
            assertThrows(IllegalArgumentException::class.java) {
                checkIntBounds(Long.MAX_VALUE - 1L, min = Long.MAX_VALUE, max = Long.MAX_VALUE)
            }
        }

        @Test
        fun `supports 64-bit long ranges beyond 32-bit int limits`() {
            assertDoesNotThrow { checkIntBounds(Long.MIN_VALUE, min = Long.MIN_VALUE, max = Long.MAX_VALUE) }
            assertDoesNotThrow { checkIntBounds(0L, min = Long.MIN_VALUE, max = Long.MAX_VALUE) }
            assertDoesNotThrow { checkIntBounds(Long.MAX_VALUE, min = Long.MIN_VALUE, max = Long.MAX_VALUE) }

            val aboveIntMax = Int.MAX_VALUE.toLong() + 100L
            assertDoesNotThrow { checkIntBounds(aboveIntMax, min = 0L, max = 5_000_000_000L) }
            assertThrows(IllegalArgumentException::class.java) {
                checkIntBounds(5_000_000_001L, min = 0L, max = 5_000_000_000L)
            }

            val belowIntMin = Int.MIN_VALUE.toLong() - 100L
            assertDoesNotThrow { checkIntBounds(belowIntMin, min = -5_000_000_000L, max = 0L) }
            assertThrows(IllegalArgumentException::class.java) {
                checkIntBounds(-5_000_000_001L, min = -5_000_000_000L, max = 0L)
            }

            assertThrows(IllegalArgumentException::class.java) {
                checkIntBounds(Long.MIN_VALUE, min = Long.MIN_VALUE + 1L, max = Long.MAX_VALUE)
            }
            assertThrows(IllegalArgumentException::class.java) {
                checkIntBounds(Long.MAX_VALUE, min = Long.MIN_VALUE, max = Long.MAX_VALUE - 1L)
            }
        }

        @Test
        fun `supports combining one default bound with one custom bound`() {
            assertDoesNotThrow { checkIntBounds(Int.MIN_VALUE.toLong(), max = 100L) }
            assertDoesNotThrow { checkIntBounds(100L, max = 100L) }
            assertThrows(IllegalArgumentException::class.java) {
                checkIntBounds(101L, max = 100L)
            }

            assertDoesNotThrow { checkIntBounds(0L, min = 0L) }
            assertDoesNotThrow { checkIntBounds(Int.MAX_VALUE.toLong(), min = 0L) }
            assertThrows(IllegalArgumentException::class.java) {
                checkIntBounds(-1L, min = 0L)
            }
        }

        @Test
        fun `throws when min bound exceeds max bound`() {
            val inverted = assertThrows(IllegalArgumentException::class.java) {
                checkIntBounds(5L, min = 10L, max = 1L)
            }
            val message = inverted.message.orEmpty()
            assertTrue(message.contains("10"))
            assertTrue(message.contains("1"))

            assertThrows(IllegalArgumentException::class.java) {
                checkIntBounds(10L, min = 10L, max = 1L)
            }
            assertThrows(IllegalArgumentException::class.java) {
                checkIntBounds(1L, min = 1L, max = 0L)
            }
            assertThrows(IllegalArgumentException::class.java) {
                checkIntBounds(0L, min = Long.MAX_VALUE, max = Long.MIN_VALUE)
            }
            assertThrows(IllegalArgumentException::class.java) {
                checkIntBounds(0L, min = Int.MAX_VALUE.toLong() + 1L)
            }
            assertThrows(IllegalArgumentException::class.java) {
                checkIntBounds(0L, max = Int.MIN_VALUE.toLong() - 1L)
            }
        }
    }

    @Nested
    inner class EnsureIdNotNormalizedTests {

        @Test
        fun `preserves mixed case without case folding`() {
            assertEquals("CaseSensitive-ID", ensureIdNotNormalized("CaseSensitive-ID"))
            assertEquals("UPPERCASE_ID", ensureIdNotNormalized("UPPERCASE_ID"))
            assertEquals("lowercase_id", ensureIdNotNormalized("lowercase_id"))
            assertEquals("Straße", ensureIdNotNormalized("Straße"))
            assertEquals("İstanbul", ensureIdNotNormalized("İstanbul"))
            assertNotEquals(
                ensureIdNotNormalized("Session-ABC"),
                ensureIdNotNormalized("session-abc"),
            )
        }

        @Test
        fun `preserves leading trailing and internal whitespace without trimming`() {
            assertEquals("  padded-id  ", ensureIdNotNormalized("  padded-id  "))
            assertEquals(" x ", ensureIdNotNormalized(" x "))
            assertEquals("\tleading-tab", ensureIdNotNormalized("\tleading-tab"))
            assertEquals("trailing-newline\n", ensureIdNotNormalized("trailing-newline\n"))
            assertEquals("  mixed  spaces  ", ensureIdNotNormalized("  mixed  spaces  "))
        }

        @Test
        fun `preserves special characters and unicode forms verbatim without normalization`() {
            val unusual = "AbC-123_XyZ.!@#%^&*()[]{}|;:'\",./<>?-"
            assertEquals(unusual, ensureIdNotNormalized(unusual))

            val nfc = "caf\u00e9"
            val nfd = "cafe\u0301"
            assertEquals(nfc, ensureIdNotNormalized(nfc))
            assertEquals(nfd, ensureIdNotNormalized(nfd))
            assertNotEquals(ensureIdNotNormalized(nfc), ensureIdNotNormalized(nfd))

            assertEquals("00123", ensureIdNotNormalized("00123"))
            assertEquals("a/b//c", ensureIdNotNormalized("a/b//c"))
        }

        @Test
        fun `throws when id is empty or blank`() {
            val emptyException = assertThrows(IllegalArgumentException::class.java) {
                ensureIdNotNormalized("")
            }
            assertTrue(emptyException.message.orEmpty().isNotBlank())

            assertThrows(IllegalArgumentException::class.java) {
                ensureIdNotNormalized(" ")
            }
            assertThrows(IllegalArgumentException::class.java) {
                ensureIdNotNormalized("   ")
            }
            assertThrows(IllegalArgumentException::class.java) {
                ensureIdNotNormalized("\t\r\n")
            }
            assertThrows(IllegalArgumentException::class.java) {
                ensureIdNotNormalized("\u2000\u3000")
            }
        }
    }
}
