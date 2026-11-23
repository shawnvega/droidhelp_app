package com.bighelp.droidhelp.chat

/**
 * The contract for chat interaction, defining how transcript rows are identified and ordered.
 */
object ChatInteractionContract {

    /**
     * Generates a stable semantic key for a message within a conversation.
     */
    fun messageIdentityKey(conversationId: String, messageId: String): String {
        return "$conversationId:$messageId"
    }

    /**
     * Removes duplicate items based on a key, preserving the first-seen order but keeping the latest item.
     */
    fun <T> deduplicateByKey(items: List<T>, keySelector: (T) -> String): List<T> {
        val keyToLastItem = mutableMapOf<String, T>()
        val keyOrder = mutableListOf<String>()

        for (item in items) {
            val key = keySelector(item)
            if (!keyToLastItem.containsKey(key)) {
                keyOrder.add(key)
            }
            keyToLastItem[key] = item
        }

        return keyOrder.map { keyToLastItem[it]!! }
    }

    /**
     * Sorts items deterministically by timestamp, then by ID.
     */
    fun <T> orderByTimestamp(
        items: List<T>,
        timestampSelector: (T) -> Long,
        idSelector: (T) -> String
    ): List<T> {
        return items.sortedWith(compareBy({ timestampSelector(it) }, { idSelector(it) }))
    }

    /**
     * Groups consecutive items that have the same sender.
     */
    fun <T> groupConsecutiveBySender(items: List<T>, senderSelector: (T) -> String): List<List<T>> {
        if (items.isEmpty()) return emptyList()
        val result = mutableListOf<MutableList<T>>()
        var lastSender: String = senderSelector(items[0])
        result.add(mutableListOf(items[0]))

        for (i in 1 until items.size) {
            val item = items[i]
            val sender = senderSelector(item)
            if (sender == lastSender) {
                result.last().add(item)
            } else {
                result.add(mutableListOf(item))
                lastSender = sender
            }
        }
        return result
    }

    /**
     * Determines if the chat should automatically scroll to the bottom.
     * Auto-scroll occurs when the user is not actively dragging and the view is at the bottom.
     */
    fun shouldAutoScrollToBottom(isUserDragging: Boolean, isAtBottom: Boolean): Boolean {
        return !isUserDragging && isAtBottom
    }

    /**
     * An in-memory store for chat drafts.
     */
    class InMemoryDraftStore {
        private val drafts = mutableMapOf<String, String>()

        fun saveDraft(hostKey: String, conversationId: String, text: String) {
            drafts["$hostKey:$conversationId"] = text
        }

        fun loadDraft(hostKey: String, conversationId: String): String? {
            return drafts["$hostKey:$conversationId"]
        }

        fun clearForHost(hostKey: String) {
            val prefix = "$hostKey:"
            drafts.keys.removeIf { it.startsWith(prefix) }
        }
    }
}
