package com.bighelp.droidhelp.chat

import java.util.*

enum class TimelineRole {
    HUMAN, ASSISTANT
}

data class TimelineSenderSnapshot(
    val name: String,
    val avatarFileName: String? = null,
    val imageURL: String? = null
)

data class TimelineSender(
    val id: String,
    val kind: SenderKind,
    val snapshot: TimelineSenderSnapshot
) {
    enum class SenderKind { USER, AGENT, SYSTEM }

    companion object {
        fun user(name: String): TimelineSender {
            return TimelineSender(
                id = "user",
                kind = SenderKind.USER,
                snapshot = TimelineSenderSnapshot(name = name)
            )
        }

        fun agent(id: String, name: String): TimelineSender {
            return TimelineSender(
                id = id,
                kind = SenderKind.AGENT,
                snapshot = TimelineSenderSnapshot(name = name)
            )
        }
    }
}

sealed class TimelineContent {
    data class Message(val text: String) : TimelineContent()
    data class Reasoning(val text: String) : TimelineContent()
    enum class ToolCallStatus {
        RUNNING, SUCCESS, ERROR
    }

    data class ToolCall(
        val name: String,
        val arguments: String,
        val result: String? = null,
        val status: ToolCallStatus = ToolCallStatus.RUNNING
    ) : TimelineContent()
}

data class TimelineMetadata(
    val timestamp: Date? = null,
    val sourceOrder: Int? = null,
    val isInterimReply: Boolean = false,
    val isStreaming: Boolean = false
)

data class TimelineItem(
    val id: String,
    val role: TimelineRole,
    val sender: TimelineSender,
    val content: TimelineContent,
    val metadata: TimelineMetadata,
    val attachments: List<ChatAttachment> = emptyList()
)
