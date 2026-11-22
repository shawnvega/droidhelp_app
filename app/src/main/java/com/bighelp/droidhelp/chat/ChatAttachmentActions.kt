package com.bighelp.droidhelp.chat

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.File

interface ChatAttachmentActions {
    fun pickPhoto()
    fun capturePhoto()
    fun pickFile()
}

@Composable
fun rememberChatAttachmentActions(
    onAttachmentAdded: (ChatAttachment) -> Unit
): ChatAttachmentActions {
    val context = LocalContext.current
    val authority = "${context.packageName}.provider"

    // State to hold the URI for the camera capture
    var cameraUri by remember { mutableStateOf<Uri?>(null) }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let {
            val bytes = context.contentResolver.openInputStream(it)?.use { inputStream ->
                inputStream.readBytes()
            }
            if (bytes != null) {
                val fileName = it.lastPathSegment ?: "image.jpg"
                val mimeType = context.contentResolver.getType(it) ?: "image/jpeg"
                onAttachmentAdded(
                    ChatAttachment(
                        fileName = fileName,
                        mimeType = mimeType,
                        data = bytes,
                        kind = AttachmentKind.IMAGE
                    )
                )
            }
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            val bytes = context.contentResolver.openInputStream(it)?.use { inputStream ->
                inputStream.readBytes()
            }
            if (bytes != null) {
                val fileName = it.lastPathSegment ?: "file"
                val mimeType = context.contentResolver.getType(it) ?: "application/octet-stream"
                onAttachmentAdded(
                    ChatAttachment(
                        fileName = fileName,
                        mimeType = mimeType,
                        data = bytes,
                        kind = if (mimeType.startsWith("image/")) AttachmentKind.IMAGE else AttachmentKind.FILE
                    )
                )
            }
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && cameraUri != null) {
            val uri = cameraUri!!
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes != null) {
                val fileName = uri.lastPathSegment ?: "camera_capture.jpg"
                val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
                onAttachmentAdded(
                    ChatAttachment(
                        fileName = fileName,
                        mimeType = mimeType,
                        data = bytes,
                        kind = AttachmentKind.IMAGE
                    )
                )
            }
        }
    }

    return object : ChatAttachmentActions {
        override fun pickPhoto() {
            photoPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }

        override fun capturePhoto() {
            val tempFile = File(context.cacheDir, "camera_capture_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(context, authority, tempFile)
            cameraUri = uri
            cameraLauncher.launch(uri)
        }

        override fun pickFile() {
            filePickerLauncher.launch("*/*")
        }
    }
}
