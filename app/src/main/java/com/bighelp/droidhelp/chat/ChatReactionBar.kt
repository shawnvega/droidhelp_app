package com.bighelp.droidhelp.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val CHAT_REACTION_SPACING = 4.dp
private const val MAX_VISIBLE_REACTIONS = 5
private val BADGE_CORNER_RADIUS = 16.dp
private val BADGE_PADDING_HORIZONTAL = 8.dp
private val BADGE_PADDING_VERTICAL = 4.dp
private val COUNT_FONT_SIZE = 12.sp
private val PICKER_PADDING = 16.dp
private val PICKER_SPACER_HEIGHT = 16.dp

@Composable
fun ChatReactionBar(
    state: ChatReactionState,
    onSelection: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    if (state.reactions.isEmpty()) return

    val sortedReactions = state.reactions.toList().sortedBy { it.first }
    val visibleCount = MAX_VISIBLE_REACTIONS
    val visibleReactions = sortedReactions.take(visibleCount)
    val hiddenCount = (sortedReactions.size - visibleCount).coerceAtLeast(0)

    Row(
        modifier = modifier.wrapContentWidth(),
        horizontalArrangement = Arrangement.spacedBy(CHAT_REACTION_SPACING),
        verticalAlignment = Alignment.CenterVertically
    ) {
        visibleReactions.forEach { (emoji, count) ->
            ChatReactionBadge(
                emoji = emoji,
                count = count,
                isSelected = state.isSelected(emoji),
                onClick = { onSelection(if (state.isSelected(emoji)) null else emoji) }
            )
        }

        if (hiddenCount > 0) {
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "+$hiddenCount",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ChatReactionBadge(
    emoji: String,
    count: Int,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(BADGE_CORNER_RADIUS))
            .background(
                if (isSelected) MaterialTheme.colorScheme.primaryContainer 
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
            .clickable { onClick() }
            .padding(horizontal = BADGE_PADDING_HORIZONTAL, vertical = BADGE_PADDING_VERTICAL),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = emoji, fontSize = 16.sp)
            if (count > 0) {
                Spacer(modifier = Modifier.width(CHAT_REACTION_SPACING))
                Text(
                    text = count.toString(),
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = COUNT_FONT_SIZE
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun ChatReactionPicker(
    selectedEmoji: String?,
    onSelection: (String?) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val projection = ChatReactionBarProjection.create(selectedEmoji)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .padding(PICKER_PADDING),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Choose a reaction",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
        )
        
        Spacer(modifier = Modifier.height(PICKER_SPACER_HEIGHT))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            projection.choices.forEach { choice ->
                    val badgeColor = if (choice.isSelected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        Color.Transparent
                    }
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(badgeColor)
                            .clickable { onSelection(choice.emoji) },
                        contentAlignment = Alignment.Center
                    ) {
                    Text(text = choice.emoji, fontSize = 24.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(PICKER_SPACER_HEIGHT))

        if (selectedEmoji != null) {
            Button(
                onClick = { onSelection(null) },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Text("Remove reaction", color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }

        Spacer(modifier = Modifier.height(PICKER_SPACER_HEIGHT))

        TextButton(onClick = onDismiss) {
            Text("Cancel")
        }
    }
}
