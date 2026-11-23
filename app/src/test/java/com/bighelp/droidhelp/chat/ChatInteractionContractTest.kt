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
        assertEquals(3, result.size)
        assertEquals(item1, result[0])
        assertEquals(item3, result[1])
        assertEquals(item2, result[2])
    }

    @Test
    fun testGroupConsecutiveBySender() {
        data class Message(val sender: String)
        val messages = listOf(
            Message("user"),
            Message("bot"),
            Message("bot"),
            Message("user"),
            Message("user")
        )
        val result = ChatInteractionContract.groupConsecutiveBySender(messages) { it.sender }
        assertEquals(3, result.size)
        assertEquals(listOf(Message("user")), result[0])
        assertEquals(listOf(Message("bot"), Message("bot")), result[1])
        assertEquals(listOf(Message("user"), Message("user")), result[2])
    }

    @Test
    fun testShouldAutoScrollToBottom() {
        assertEquals(true, ChatInteractionContract.shouldAutoScrollToBottom(false, true))
        assertEquals(false, ChatInteractionContract.shouldAutoScrollToBottom(true, true))
        assertEquals(false, ChatInteractionContract.shouldAutoScrollToBottom(false, false))
        assertEquals(false, ChatInteractionContract.shouldAutoScrollToBottom(true, false))
    }

    @Test
    fun testInMemoryDraftStore() {
        val store = ChatInteractionContract.InMemoryDraftStore()
        val hostKey = "host1"
        val convId1 = "conv1"
        val convId2 = "conv2"
        val text1 = "Hello"
        val text2 = "World"

        store.saveDraft(hostKey, convId1, text1)
        store.saveDraft(hostKey, convId2, text2)

        assertEquals(text1, store.loadDraft(hostKey, convId1))
        assertEquals(text2, store.loadDraft(hostKey, convId2))

        store.clearForHost(hostKey)
        assertEquals(null, store.loadDraft(hostKey, convId1))
        assertEquals(null, store.loadDraft(hostKey, convId2))
    }
}
