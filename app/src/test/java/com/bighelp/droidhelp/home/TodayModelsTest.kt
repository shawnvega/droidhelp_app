package com.bighelp.droidhelp.home

import com.bighelp.droidhelp.home.model.TodayBoardEntry
import com.bighelp.droidhelp.home.model.TodayBoardKind
import com.bighelp.droidhelp.home.model.TodayPanelDescriptor
import com.bighelp.droidhelp.home.model.TodayPanelLayout
import com.bighelp.droidhelp.home.model.TodayPanelSize
import org.junit.Assert.assertEquals
import org.junit.Test

class TodayModelsTest {

    @Test
    fun testVisiblePanelsFilteringAndOrdering() {
        val p1 = TodayPanelDescriptor(
            id = "1",
            title = "P1",
            size = TodayPanelSize.COMPACT,
            isVisible = true,
            order = 2
        )
        val p2 = TodayPanelDescriptor(
            id = "2",
            title = "P2",
            size = TodayPanelSize.FULL,
            isVisible = false,
            order = 1
        )
        val p3 = TodayPanelDescriptor(
            id = "3",
            title = "P3",
            size = TodayPanelSize.COMPACT,
            isVisible = true,
            order = 0
        )

        val layout = TodayPanelLayout(listOf(p1, p2, p3))
        val visible = layout.visiblePanels()

        assertEquals(2, visible.size)
        assertEquals("3", visible[0].id) // Order 0
        assertEquals("1", visible[1].id) // Order 2
    }
}
