package com.bighelp.droidhelp.chat

import java.util.*

enum class AttachmentKind { IMAGE, FILE }

sealed class AttachmentStatus {
    object Pending : AttachmentStatus()
    data class Uploading(val progress: Float) : AttachmentStatus()
    object Sent : AttachmentStatus()
    data class Failed(val error: String) : AttachmentStatus()
}

data class ChatAttachment(
    val id: String = UUID.randomUUID().toString(),
    val fileName: String,
    val mimeType: String,
    val data: ByteArray,
    val kind: AttachmentKind,
    val status: AttachmentStatus = AttachmentStatus.Pending
) {
    companion object {
        const val MAXIMUM_BYTES = 8 * 1024 * 1024L // 8 MiB
        const val MAXIMUM_AGENT_BYTES = 25 * 1024 * 1024L // 25 MiB
    }
}
