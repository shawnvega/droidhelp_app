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
}
