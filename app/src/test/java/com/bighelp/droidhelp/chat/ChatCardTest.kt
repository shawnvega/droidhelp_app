package com.bighelp.droidhelp.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatCardTest {

    @Test
    fun testChatCardAction() {
        val action = ChatCardAction("id_1", "Label", true)
        assertEquals("id_1", action.id)
        assertEquals("Label", action.label)
        assertEquals(true, action.isPrimary)
    }

    @Test
    fun testChatCardActionDefault() {
        val action = ChatCardAction("id_2", "Label")
        assertEquals(false, action.isPrimary)
    }

    @Test
    fun testChatCard() {
        val action = ChatCardAction("action_id", "Action Label")
        val card = ChatCard(
            id = "card_id",
            title = "Card Title",
            subtitle = "Card Subtitle",
            imageUrl = "https://example.com/image.png",
            actions = listOf(action)
        )

        assertEquals("card_id", card.id)
        assertEquals("Card Title", card.title)
        assertEquals("Card Subtitle", card.subtitle)
        assertEquals("https://example.com/image.png", card.imageUrl)
        assertEquals(listOf(action), card.actions)
    }

    @Test
    fun testChatCardEquality() {
        val action = ChatCardAction("a", "b")
        val card1 = ChatCard("c", "d", "e", "f", listOf(action))
        val card2 = ChatCard("c", "d", "e", "f", listOf(action))
        val card3 = ChatCard("x", "y")

        assertEquals(card1, card2)
        assert(card1 != card3)
    }
}
