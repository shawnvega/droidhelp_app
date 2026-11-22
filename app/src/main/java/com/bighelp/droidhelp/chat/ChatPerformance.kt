package com.bighelp.droidhelp.chat

/**
 * Manages chat history with efficient large-history handling and duplicate prevention.
 * 
 * Requirements:
 * 1. Large-history efficiency: Uses a LinkedHashMap for O(1) lookups and O(1) appends,
 *    ensuring that history loading and updates do not degrade quadratically.
 * 2. No duplicate history appends: Appending a message whose ID already exists is a no-op,
 *    deduplicating based on the stable message identifier.
 */
class ChatPerformance {
    private val historyMap = LinkedHashMap<String, TimelineItem>()

    /**
     * Appends a message to the history. If a message with the same ID already exists,
     * this operation is a no-op.
     *
     * @param item The [TimelineItem] to append.
     */
    fun append(item: TimelineItem) {
        if (!historyMap.containsKey(item.id)) {
            historyMap[item.id] = item
        }
    }

    /**
     * Updates an existing item in the history.
     *
     * @param id The ID of the item to update.
     * @param item The updated [TimelineItem].
     */
    fun update(id: String, item: TimelineItem) {
        historyMap[id] = item
    }

    /**
     * Returns the current history as an ordered list of [TimelineItem]s.
     *
     * @return An ordered list of the chat history.
     */
    fun getHistory(): List<TimelineItem> {
        return historyMap.values.toList()
    }

    /**
     * Returns the number of items currently in the history.
     *
     * @return The size of the history.
     */
    fun getHistorySize(): Int {
        return historyMap.size
    }

    /**
     * Clears all items from the history.
     *
     */
    fun clear() {
        historyMap.clear()
    }
}
