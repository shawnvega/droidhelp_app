package com.bighelp.droidhelp.home

import com.bighelp.droidhelp.home.model.HomeTab
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeTabBarTest {

    @Test
    fun testTabOrderAndLabels() {
        val label = "Conversations"
        val items = getHomeTabItems(label)
        
        assertEquals(3, items.size)
        
        assertEquals(HomeTab.CONVERSATIONS, items[0].tab)
        assertEquals(label, items[0].label)
        
        assertEquals(HomeTab.TODAY, items[1].tab)
        assertEquals("Today", items[1].label)
        
        assertEquals(HomeTab.WORK, items[2].tab)
        assertEquals("Work", items[2].label)
    }
}
