package com.bighelp.droidhelp.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatInteractionContractTest {

    @Test
    fun testMessageIdentityKey() {
        val conversationId = "conv-123"
        val messageId = "msg-456"
        val expected = "conv-123:msg-456"
        assertEquals(expected, ChatInteractionContract.messageIdentityKey(conversationId, messageId))
    }

    private data class TestItem(val key: String, val timestamp: Long, val id: String)

    @Test
    fun testDeduplicateByKey() {
        val item1 = TestItem("k1", 100L, "id1")
        val item2 = TestItem("k2", 200L, "id2")
        val item3 = TestItem("k1", 300L, "id3") // Duplicate key k1, should replace item1

        val items = listOf(item1, item2, item3)
        val result = ChatInteractionContract.deduplicateByKey(items) { it.key }

        // Expected order: k1 (first seen), then k2.
        // But since we keep latest, k1 is item3.
        // So [item3, item2]
        assertEquals(2, result.size)
        assertEquals(item3, result[0])
        assertEquals(item2, result[1])
    }

    @Test
    fun testOrderByTimestamp() {
        val item1 = TestItem("k1", 100L, "id1")
        val item2 = TestItem("k2", 200L, "id2")
        val item3 = TestItem("k3", 200L, "id0") // Same timestamp as item2, but smaller ID

        val items = listOf(item2, item1, item3)
        val result = ChatInteractionContract.orderByTimestamp(items, { it.timestamp }, { it.id })

        // Sorted by timestamp: 100, 200, 200
        // For 200: id0 < id2
        // Result: item1 (100), item3 (200, id0), item2 (200, id2)
        assertEquals(3, result.size)
        assertEquals(item1, result[0])
        assertEquals(item3, result[1])
        assertEquals(item2, result[2])
    }
}
