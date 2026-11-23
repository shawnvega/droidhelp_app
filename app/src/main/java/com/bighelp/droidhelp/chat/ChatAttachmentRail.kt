package com.bighelp.droidhelp.chat

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp

@Composable
fun ChatAttachmentRail(
    attachments: List<ChatAttachment>,
    onRemove: (ChatAttachment) -> Unit,
    modifier: Modifier = Modifier
) {
    if (attachments.isEmpty()) return

    Row(
        modifier = modifier
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        attachments.forEach { attachment ->
            AttachmentThumbnail(attachment, onRemove = { onRemove(attachment) })
            Spacer(modifier = Modifier.width(4.dp))
        }
    }
}

@Composable
private fun AttachmentThumbnail(
    attachment: ChatAttachment,
    onRemove: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        when (attachment.kind) {
            AttachmentKind.IMAGE -> {
                val bitmap = remember(attachment.data) {
                    BitmapFactory.decodeByteArray(attachment.data, 0, attachment.data.size)
                }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Text(
                        text = attachment.fileName.take(1).uppercase(),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
            AttachmentKind.FILE -> {
                Text(
                    text = attachment.fileName.take(1).uppercase(),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        if (attachment.status is AttachmentStatus.Uploading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    progress = (attachment.status as AttachmentStatus.Uploading).progress,
                    modifier = Modifier.size(24.dp),
                    color = Color.White,
                    strokeWidth = 2.dp
                )
            }
        }

        if (attachment.status is AttachmentStatus.Failed) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Red.copy(alpha = 0.3f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = "Failed",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        // Remove button overlay
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(2.dp)
                .background(Color.Black.copy(alpha = 0.3f), RoundedCornerShape(2.dp))
                .clickable { onRemove() },
            contentAlignment = Alignment.CenterEnd
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Remove",
                tint = Color.White,
                modifier = Modifier.size(16.dp).padding(2.dp)
            )
        }
    }
}
