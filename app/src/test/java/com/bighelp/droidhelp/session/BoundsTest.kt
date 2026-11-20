package com.bighelp.droidhelp.session

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BoundsTest {

    @Test
    fun `allows empty string with default and zero limits`() {
        assertDoesNotThrow { checkUtf8ByteLimit("") }
        assertDoesNotThrow { checkUtf8ByteLimit("", maxBytes = 0) }
    }

    @Test
    fun `allows ascii string up to exact default limit`() {
        val exactLimit = "a".repeat(1024)
        assertDoesNotThrow { checkUtf8ByteLimit(exactLimit) }
    }

    @Test
    fun `throws when ascii string exceeds default limit by one byte`() {
        val overLimit = "a".repeat(1025)
        val exception = assertThrows(IllegalArgumentException::class.java) {
            checkUtf8ByteLimit(overLimit)
        }
        assertTrue(exception.message.orEmpty().contains("1025"))
        assertTrue(exception.message.orEmpty().contains("1024"))
    }

    @Test
    fun `validates against utf8 byte length rather than character count`() {
        // 'é' is 2 bytes in UTF-8, '中' is 3 bytes, '😀' is 4 bytes (2 UTF-16 code units).
        assertDoesNotThrow { checkUtf8ByteLimit("é", maxBytes = 2) }
        assertThrows(IllegalArgumentException::class.java) {
            checkUtf8ByteLimit("é", maxBytes = 1)
        }

        assertDoesNotThrow { checkUtf8ByteLimit("中", maxBytes = 3) }
        assertThrows(IllegalArgumentException::class.java) {
            checkUtf8ByteLimit("中", maxBytes = 2)
        }

        assertDoesNotThrow { checkUtf8ByteLimit("😀", maxBytes = 4) }
        val emojiOver = assertThrows(IllegalArgumentException::class.java) {
            checkUtf8ByteLimit("😀", maxBytes = 3)
        }
        assertTrue(emojiOver.message.orEmpty().contains("4"))
        assertTrue(emojiOver.message.orEmpty().contains("3"))
    }

    @Test
    fun `throws when multi-byte characters exceed default 1024 byte limit despite fewer than 1024 chars`() {
        // 342 * 3 bytes = 1026 bytes (> 1024), even though length is 342 chars.
        val cjkInput = "中".repeat(342)
        val exception = assertThrows(IllegalArgumentException::class.java) {
            checkUtf8ByteLimit(cjkInput)
        }
        assertTrue(exception.message.orEmpty().contains("1026"))
        assertTrue(exception.message.orEmpty().contains("1024"))
    }

    @Test
    fun `throws when maxBytes is negative`() {
        assertThrows(IllegalArgumentException::class.java) {
            checkUtf8ByteLimit("", maxBytes = -1)
        }
    }

    @Test
    fun `allows values within default 32-bit int bounds`() {
        assertDoesNotThrow { checkIntBounds(0L) }
        assertDoesNotThrow { checkIntBounds(Int.MIN_VALUE.toLong()) }
        assertDoesNotThrow { checkIntBounds(Int.MAX_VALUE.toLong()) }
        assertDoesNotThrow { checkIntBounds(-42L) }
        assertDoesNotThrow { checkIntBounds(42L) }
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
    fun `validates against custom inclusive bounds`() {
        assertDoesNotThrow { checkIntBounds(10L, min = 10L, max = 20L) }
        assertDoesNotThrow { checkIntBounds(15L, min = 10L, max = 20L) }
        assertDoesNotThrow { checkIntBounds(20L, min = 10L, max = 20L) }
        assertDoesNotThrow { checkIntBounds(5L, min = 5L, max = 5L) }
        assertDoesNotThrow { checkIntBounds(Long.MIN_VALUE, min = Long.MIN_VALUE, max = Long.MAX_VALUE) }
        assertDoesNotThrow { checkIntBounds(Long.MAX_VALUE, min = Long.MIN_VALUE, max = Long.MAX_VALUE) }

        val belowCustom = assertThrows(IllegalArgumentException::class.java) {
            checkIntBounds(9L, min = 10L, max = 20L)
        }
        assertTrue(belowCustom.message.orEmpty().contains("9"))
        assertTrue(belowCustom.message.orEmpty().contains("10"))
        assertTrue(belowCustom.message.orEmpty().contains("20"))

        val aboveCustom = assertThrows(IllegalArgumentException::class.java) {
            checkIntBounds(21L, min = 10L, max = 20L)
        }
        assertTrue(aboveCustom.message.orEmpty().contains("21"))
        assertTrue(aboveCustom.message.orEmpty().contains("10"))
        assertTrue(aboveCustom.message.orEmpty().contains("20"))
    }

    @Test
    fun `throws when min bound exceeds max bound`() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            checkIntBounds(5L, min = 10L, max = 1L)
        }
        val message = exception.message.orEmpty()
        assertTrue(message.contains("10"))
        assertTrue(message.contains("1"))
    }
}
