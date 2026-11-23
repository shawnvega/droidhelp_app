package com.bighelp.droidhelp.home

import com.bighelp.droidhelp.home.model.AppRoute
import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationTest {

    @Test
    fun testToRouteString() {
        assertEquals("chat/123", AppRoute.Chat("123").toRouteString())
        assertEquals("sessions", AppRoute.Sessions.toRouteString())
        assertEquals("scheduled_tasks", AppRoute.ScheduledTasks.toRouteString())
        assertEquals("workspace_connections", AppRoute.WorkspaceConnections.toRouteString())
        assertEquals("projects", AppRoute.Projects.toRouteString())
        assertEquals("kanban", AppRoute.Kanban.toRouteString())
        assertEquals("workflows", AppRoute.Workflows.toRouteString())
    }

    @Test
    fun testParseDeepLinkRoute() {
        // Valid routes
        assertEquals(AppRoute.Chat("123"), parseDeepLinkRoute("droidhelp://chat/123"))
        assertEquals(AppRoute.Sessions, parseDeepLinkRoute("droidhelp://sessions"))
        assertEquals(AppRoute.ScheduledTasks, parseDeepLinkRoute("droidhelp://scheduled_tasks"))
        assertEquals(AppRoute.WorkspaceConnections, parseDeepLinkRoute("droidhelp://workspace_connections"))
        assertEquals(AppRoute.Projects, parseDeepLinkRoute("droidhelp://projects"))
        assertEquals(AppRoute.Kanban, parseDeepLinkRoute("droidhelp://kanban"))
        assertEquals(AppRoute.Workflows, parseDeepLinkRoute("droidhelp://workflows"))

        // Invalid/Unknown routes
        assertEquals(null, parseDeepLinkRoute(""))
        assertEquals(null, parseDeepLinkRoute(" "))
        assertEquals(null, parseDeepLinkRoute("droidhelp://"))
        assertEquals(null, parseDeepLinkRoute("droidhelp://unknown"))
        assertEquals(null, parseDeepLinkRoute("https://google.com"))
        assertEquals(null, parseDeepLinkRoute("chat/123")) // missing scheme
        assertEquals(null, parseDeepLinkRoute("droidhelp://chat/123/extra")) // too many parts
    }
}
