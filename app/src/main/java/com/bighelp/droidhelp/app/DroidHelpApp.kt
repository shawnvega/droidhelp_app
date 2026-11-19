package com.bighelp.droidhelp.app

import androidx.compose.runtime.*
import com.bighelp.droidhelp.home.model.*
import java.util.UUID

data class SuspendedChat(
    val chatID: String,
    val agentID: String,
    val text: String
)

data class PendingShareHhandoff(
    val id: UUID = UUID(),
    val text: String
)

class AppState(val home: HomeSelection) {
    var path by mutableStateOf<List<AppRoute>>(emptyList())

    var activeConversationID by mutableStateOf<String?>(null)
    var deviceToolConversationID by mutableStateOf<String?>(null)
    var pendingVoiceConversationID by mutableStateOf<String?>(null)
    var pendingReplyConversationID by mutableStateOf<String?>(null)
    var pendingComposerText by mutableStateOf<String?>(null)
    var suspendedChat by mutableStateOf<SuspendedChat?>(null)
    var pendingComposerFillsBlanks by mutableStateOf(false)
    var pendingComposerSendsAtOnce by mutableStateOf(false)
    var pendingComposerAgentID by mutableStateOf<String?>(null)
    var sendsDraftOnOpen by mutableStateOf<Set<String>>(emptySet())
    var pendingShare by mutableStateOf<PendingShareHhandoff?>(null)

    val selectedTab: HomeTab
        get() = home.tab

    val isSettingsPresented: Boolean
        get() = home.isSettingsPresented

    val settingsPath: List<AppRoute>
        get() = home.settingsPath

    val isNeedsYouRequested: Boolean
        get() = home.isNeedsYouRequested

    fun select(tab: HomeTab) {
        home.tab = tab
        path = emptyList()
    }

    fun open(route: AppRoute) {
        path = path + route
    }

    fun activateConversation(id: String) {
        activeConversationID = id
    }
}

@Composable
fun DroidHelpApp(appState: AppState) {
    AppComposition(appState = appState)
}
