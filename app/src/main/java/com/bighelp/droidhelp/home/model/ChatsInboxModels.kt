package com.bighelp.droidhelp.home.model

import java.util.*

enum class ChatsInboxFilter {
    ALL,
    COMPUTER,
    UNREAD
}

sealed class ChatsInboxSource {
    data class Session(val id: String, val title: String, val preview: String, val isCode: Boolean) : ChatsInboxSource()
    data class FleetChat(val id: String, val title: String, val preview: String, val hostID: UUID, val computerTag: String?) : ChatsInboxSource()
    data class FleetGroup(val id: String, val name: String, val memberNames: List<String>, val hostID: UUID, val computerTag: String?) : ChatsInboxSource()
}

data class ChatsInboxRow(
    val id: String,
    val source: ChatsInboxSource,
    val hostID: UUID?,
    val isOnComputerInUse: Boolean,
    val title: String,
    val preview: String,
    val updatedAt: Date,
    val isPinned: Boolean,
    val isUnread: Boolean,
    val tracksUnread: Boolean,
    val hasDraft: Boolean,
    val isActive: Boolean,
    val isGroup: Boolean,
    val isCode: Boolean,
    val memberIDs: List<String>,
    val origin: String?,
    val computerTag: String?,
    val projectName: String? = null,
    val projectID: String? = null
)

data class ChatsInboxSection(
    val kind: Kind,
    val rows: List<ChatsInboxRow>
) {
    enum class Kind {
        PINNED, TODAY, YESTERDAY, EARLIER
    }
}
