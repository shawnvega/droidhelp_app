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
}
