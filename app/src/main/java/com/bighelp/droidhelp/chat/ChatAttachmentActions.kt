package com.bighelp.droidhelp.chat

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
            readAttachmentFromUri(
                context = context,
                uri = it,
                defaultFileName = "image.jpg",
                defaultMimeType = "image/jpeg",
                forceImage = true,
            )?.let(onAttachmentAdded)
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            readAttachmentFromUri(
                context = context,
                uri = it,
                defaultFileName = "file",
                defaultMimeType = "application/octet-stream",
            )?.let(onAttachmentAdded)
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        val capturedUri = cameraUri
        if (success && capturedUri != null) {
            readAttachmentFromUri(
                context = context,
                uri = capturedUri,
                defaultFileName = "camera_capture.jpg",
                defaultMimeType = "image/jpeg",
                forceImage = true,
            )?.let(onAttachmentAdded)
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

private fun readAttachmentFromUri(
    context: Context,
    uri: Uri,
    defaultFileName: String,
    defaultMimeType: String,
    forceImage: Boolean = false,
): ChatAttachment? {
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    val fileName = uri.lastPathSegment ?: defaultFileName
    val mimeType = context.contentResolver.getType(uri) ?: defaultMimeType
    val kind = if (forceImage || mimeType.startsWith("image/")) {
        AttachmentKind.IMAGE
    } else {
        AttachmentKind.FILE
    }
    return ChatAttachment(
        fileName = fileName,
        mimeType = mimeType,
        data = bytes,
        kind = kind,
    )
}
