package com.bighelp.droidhelp.chat

/**
 * Represents the author of a reaction.
 */
enum class ReactionAuthor {
    USER,
    AGENT
}

/**
 * Represents a reaction on a message.
 */
data class MessageReaction(
    val emoji: String,
    val author: ReactionAuthor
)

/**
 * State of chat reactions for a single message.
 *
 * @property reactions A map of emoji to its count.
 * @property selectedEmoji The emoji currently selected by the user, if any.
 */
data class ChatReactionState(
    val reactions: Map<String, Int> = emptyMap(),
    val selectedEmoji: String? = null
) {
    /**
     * Returns true if the given emoji is the one currently selected by the user.
     */
    fun isSelected(emoji: String): Boolean = emoji == selectedEmoji
}

/**
 * Reducer for updating [ChatReactionState].
 */
object ChatReactionReducer {
    /**
     * Adds a reaction. If the emoji is already selected, it remains selected and its count increases.
     * If a different emoji was selected, it replaces it.
     */
    fun addReaction(state: ChatReactionState, emoji: String): ChatReactionState {
        val newCount = (state.reactions[emoji] ?: 0) + 1
        return state.copy(
            reactions = state.reactions + (emoji to newCount),
            selectedEmoji = emoji
        )
    }

    /**
     * Removes a reaction. If the count reaches zero, the emoji is removed from the map.
     * If the removed emoji was the selected one, the selection is cleared.
     */
    fun removeReaction(state: ChatReactionState, emoji: String): ChatReactionState {
        val currentCount = state.reactions[emoji] ?: 0
        val newCount = currentCount - 1
        val newReactions = if (newCount <= 0) {
            state.reactions - emoji
        } else {
            state.reactions + (emoji to newCount)
        }
        return state.copy(
            reactions = newReactions,
            selectedEmoji = if (emoji == state.selectedEmoji) null else state.selectedEmoji
        )
    }

    /**
     * Toggles a reaction. If the emoji is already selected, it removes it.
     * Otherwise, it adds it.
     */
    fun toggleReaction(state: ChatReactionState, emoji: String): ChatReactionState {
        return if (state.selectedEmoji == emoji) {
            removeReaction(state, emoji)
        } else {
            addReaction(state, emoji)
        }
    }
}

/**
 * Projection used for the reaction picker.
 *
 * @property choices The list of available emoji choices.
 * @property selectedEmoji The currently selected emoji, if any.
 */
data class ChatReactionBarProjection(
    val choices: List<ChatReactionChoice>,
    val selectedEmoji: String?
) {
    /**
     * A choice in the reaction picker.
     *
     * @property emoji The emoji character.
     * @property name The human-readable name of the emoji.
     * @property isSelected Whether this emoji is currently selected.
     */
    data class ChatReactionChoice(
        val emoji: String,
        val name: String,
        val isSelected: Boolean
    )

    companion object {
        val COMMON = listOf(
            ChatReactionChoice("❤️", "heart", false),
            ChatReactionChoice("👍", "thumbs up", false),
            ChatReactionChoice("👎", "thumbs down", false),
            ChatReactionChoice("😂", "laughing", false),
            ChatReactionChoice("🎉", "party", false),
            ChatReactionChoice("🤔", "thinking", false)
        )

        /**
         * Creates a new projection based on the provided [selectedEmoji].
         */
        fun create(selectedEmoji: String?): ChatReactionBarProjection {
            val trimmed = selectedEmoji?.trim()
            // Use BreakIterator to count grapheme clusters
            val selected = if (trimmed != null && countGraphemes(trimmed) == 1) trimmed else null

            var choices = COMMON.map { reaction ->
                ChatReactionChoice(
                    emoji = reaction.emoji,
                    name = reaction.name,
                    isSelected = selected != null && sameEmoji(selected, reaction.emoji)
                )
            }

            if (selected != null && choices.none { it.isSelected }) {
                choices = choices + ChatReactionChoice(emoji = selected, name = selected, isSelected = true)
            }

            return ChatReactionBarProjection(choices = choices, selectedEmoji = selected)
        }

        private fun sameEmoji(lhs: String, rhs: String): Boolean {
            // Handle variation selector U+FE0F
            val lhsClean = lhs.replace("\uFE0F", "")
            val rhsClean = rhs.replace("\uFE0F", "")
            return lhsClean == rhsClean
        }

        private fun countGraphemes(text: String): Int {
            val it = java.text.BreakIterator.getCharacterInstance()
            it.setText(text)
            var count = 0
            while (it.next() != java.text.BreakIterator.DONE) {
                count++
            }
            return count
        }
    }

    /**
     * Determines the next selection after tapping [emoji].
     * If the tapped [0] is the current [selectedEmoji], returns null (to remove the reaction).
     * Otherwise, returns the tapped [emoji].
     */
    fun selectionAfterTapping(emoji: String): String? {
        return if (selectedEmoji != null && sameEmoji(selectedEmoji, emoji)) {
            null
        } else {
            emoji
        }
    }

    private fun sameEmoji(lhs: String, rhs: String): Boolean {
        val lhsClean = lhs.replace("\uFE0F", "")
        val rhsClean = rhs.replace("\uFE0F", "")
        return lhsClean == rhsClean
    }
}
