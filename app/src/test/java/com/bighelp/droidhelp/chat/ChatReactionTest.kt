package com.bighelp.droidhelp.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatReactionTest {

    @Test
    fun testAddReaction() {
        val state = ChatReactionState()
        val newState = ChatReactionReducer.addReaction(state, "❤️")

        assertEquals(1, newState.reactions["❤️"])
        assertEquals("❤️", newState.selectedEmoji)
    }

    @Test
    fun testAddMultipleReactions() {
        val state = ChatReactionState()
        val state1 = ChatReactionReducer.addReaction(state, "❤️")
        val state2 = ChatReactionReducer.addReaction(state1, "👍")

        assertEquals(1, state2.reactions["❤️"])
        assertEquals(1, state2.reactions["👍"])
        assertEquals("👍", state2.selectedEmoji)
    }

    @Test
    fun testRemoveReaction() {
        val state = ChatReactionState()
        val state1 = ChatReactionReducer.addReaction(state, "❤️")
        val state2 = ChatReactionReducer.removeReaction(state1, "❤️")

        assertFalse(state2.reactions.containsKey("❤️"))
        assertNull(state2.selectedEmoji)
    }

    @Test
    fun testRemoveReactionWithCount() {
        val state = ChatReactionState()
        val state1 = ChatReactionReducer.addReaction(state, "❤️")
        val state2 = ChatReactionReducer.addReaction(state1, "❤️")
        // state2: reactions={"❤️": 2}, selectedEmoji="❤️"
        val state3 = ChatReactionReducer.removeReaction(state2, "❤️")
        // state3: reactions={"❤️": 1}, selectedEmoji=null
        assertEquals(1, state3.reactions["❤️"])
        assertNull(state3.selectedEmoji)
    }

    @Test
    fun testRemoveOtherReaction() {
        val state = ChatReactionState()
        val state1 = ChatReactionReducer.addReaction(state, "❤️")
        val state2 = ChatReactionReducer.addReaction(state1, "👍")
        // state2: reactions={"❤️": 1, "👍": 1}, selectedEmoji="👍"
        val state3 = ChatReactionReducer.removeReaction(state2, "❤️")
        // state3: reactions={"👍": 1}, selectedEmoji="👍"
        assertEquals(1, state3.reactions["👍"])
        assertEquals("👍", state3.selectedEmoji)
        assertFalse(state3.reactions.containsKey("❤️"))
    }

    @Test
    fun testToggleReactionAdd() {
        val state = ChatReactionState()
        val newState = ChatReactionReducer.toggleReaction(state, "❤️")

        assertEquals(1, newState.reactions["❤️"])
        assertEquals("❤️", newState.selectedEmoji)
    }

    @Test
    fun testToggleReactionRemove() {
        val state = ChatReactionState()
        val state1 = ChatReactionReducer.addReaction(state, "❤️")
        val state2 = ChatReactionReducer.toggleReaction(state1, "❤️")

        assertFalse(state2.reactions.containsKey("❤️"))
        assertNull(state2.selectedEmoji)
    }

    @Test
    fun testToggleReactionSwitch() {
        val state = ChatReactionState()
        val state1 = ChatReactionReducer.addReaction(state, "❤️")
        val state2 = ChatReactionReducer.toggleReaction(state1, "👍")

        assertEquals(1, state2.reactions["❤️"])
        assertEquals(1, state2.reactions["👍"])
        assertEquals("👍", state2.selectedEmoji)
    }

    @Test
    fun testIsSelected() {
        val state = ChatReactionState(reactions = mapOf("❤️" to 1), selectedEmoji = "❤️")
        assertTrue(state.isSelected("❤️"))
        assertFalse(state.isSelected("👍"))
    }

    @Test
    fun testProjectionCreate() {
        val projection = ChatReactionBarProjection.create("❤️")
        
        assertEquals(1, projection.choices.count { it.isSelected })
        assertTrue(projection.choices.first { it.isSelected }.emoji == "❤️")
        assertEquals("❤️", projection.selectedEmoji)
    }

    @Test
    fun testProjectionCreateNoSelection() {
        val projection = ChatReactionBarProjection.create(null)
        
        assertEquals(0, projection.choices.count { it.isSelected })
        assertNull(projection.selectedEmoji)
    }

    @Test
    fun testProjectionCreateCustomEmoji() {
        val projection = ChatReactionBarProjection.create("⭐")
        
        assertEquals(1, projection.choices.count { it.isSelected })
        assertTrue(projection.choices.last { it.isSelected }.emoji == "⭐")
        assertEquals("⭐", projection.selectedEmoji)
    }

    @Test
    fun testProjectionSelectionAfterTapping() {
        val projection = ChatReactionBarProjection.create("❤️")
        
        // Tap same emoji -> remove
        assertEquals(null, projection.selectionAfterTapping("❤️"))
        
        // Tap different emoji -> add
        assertEquals("👍", projection.selectionAfterTapping("👍"))
    }
}
