package com.bighelp.droidhelp.home

import org.junit.Assert.assertEquals
import org.junit.Test

class BotsViewTest {

    private val item1 = BotsItem(
        id = "1",
        title = "Alpha",
        role = "Role A",
        preview = "Preview Alpha",
        isPinned = false,
        isUnread = false,
        updatedAt = 1000L
    )

    private val item2 = BotsItem(
        id = "2",
        title = "Beta",
        role = "Role B",
        preview = "Preview Beta",
        isPinned = true,
        isUnread = false,
        updatedAt = 2000L
    )

    private val item3 = BotsItem(
        id = "3",
        title = "Gamma",
        role = "Role C",
        preview = "Preview Gamma",
        isPinned = false,
        isUnread = false,
        updatedAt = 1500L
    )

    @Test
    fun `filterAndSortBots returns all items when query is empty`() {
        val items = listOf(item1, item2, item3)
        val result = filterAndSortBots(items, "")
        // Note: sorting will still happen
        // item2 (pinned) should be first
        // item3 (updatedAt 1500) second
        // item1 (updatedAt 1000) third
        assertEquals(3, result.size)
        assertEquals("2", result[0].id)
        assertEquals("3", result[1].id)
        assertEquals("1", result[2].id)
    }

    @Test
    fun `filterAndSortBots filters by title`() {
        val items = listOf(item1, item2, item3)
        val result = filterAndSortBots(items, "Alpha")
        assertEquals(1, result.size)
        assertEquals("1", result[0].id)
    }

    @Test
    fun `filterAndSortBots filters by preview`() {
        val items = listOf(item1, item2, item3)
        val result = filterAndSortBots(items, "Beta")
        assertEquals(1, result.size)
        assertEquals("2", result[0].id)
    }

    @Test
    fun `filterAndSortBots sorts pinned first`() {
        val items = listOf(item1, item2, item3)
        val result = filterAndSortBots(items, "")
        // item2 is pinned
        assertEquals(true, result[0].isPinned)
        assertEquals("2", result[0].id)
    }

    @Test
    fun `filterAndSortBots sorts by updatedAt descending`() {
        val items = listOf(item1, item3) // both unpinned
        val result = filterAndSortBots(items, "")
        // item3 (1500) > item1 (1000)
        assertEquals("3", result[0].id)
        assertEquals("1", result[1].id)
    }

    @Test
    fun `filterAndSortBots sorts by title ascending as tie-breaker`() {
        val itemA = item1.copy(id = "A", title = "A", updatedAt = 1000L, isPinned = false)
        val itemB = item3.copy(id = "B", title = "B", updatedAt = 1000L, isPinned = false)
        val items = listOf(itemB, itemA)
        val result = filterAndSortBots(items, "")
        // Both unpinned, same updatedAt. Sort by title asc.
        assertEquals("A", result[0].title)
        assertEquals("B", result[1].title)
    }
}
