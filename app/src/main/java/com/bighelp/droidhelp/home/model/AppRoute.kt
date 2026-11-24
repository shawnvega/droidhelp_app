package com.bighelp.droidhelp.home.model

sealed class AppRoute {
    data class Chat(val conversationID: String) : AppRoute()
    object Sessions : AppRoute()
    object ScheduledTasks : AppRoute()
    object WorkspaceConnections : AppRoute()
    object Projects : AppRoute()
    object Kanban : AppRoute()
    object Workflows : AppRoute()
    object About : AppRoute()
}
