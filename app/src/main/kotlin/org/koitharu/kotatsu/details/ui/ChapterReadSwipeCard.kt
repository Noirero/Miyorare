package org.koitharu.kotatsu.details.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.details.ui.model.ChapterListItem
import kotlin.math.roundToInt

/**
 * Left: mark unread chapter as read. Right: mark read chapter as unread.
 * The content always settles back instead of being dismissed from the LazyColumn.
 * Status is supplied by the chapter presentation; this component owns no persisted state.
 */
@Composable
internal fun ChapterReadSwipeCard(
    item: ChapterListItem,
    selectionActive: Boolean = false,
    onMarkRead: () -> Unit,
    onMarkUnread: () -> Unit,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val threshold = with(density) { 72.dp.toPx() }
    val maxReveal = with(density) { 144.dp.toPx() }
    var dragX by remember(item.chapter.id) { mutableFloatStateOf(0f) }
    var dragging by remember(item.chapter.id) { mutableStateOf(false) }
    val translation by animateFloatAsState(
        targetValue = dragX,
        animationSpec = if (dragging) snap() else spring(stiffness = Spring.StiffnessMedium),
        label = "Chapter read swipe",
    )
    val markReadLabel = stringResource(R.string.mark_as_read)
    val markUnreadLabel = stringResource(R.string.mark_as_unread)
    val markingRead = translation < 0f
    val enabled = if (markingRead) item.isUnread else !item.isUnread
    val colors = MaterialTheme.colorScheme
    val actionColor = when {
        !enabled -> colors.surfaceVariant
        markingRead -> colors.primary
        else -> colors.tertiary
    }
    val actionForeground = when {
        !enabled -> colors.onSurfaceVariant
        markingRead -> colors.onPrimary
        else -> colors.onTertiary
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                customActions = if (selectionActive) emptyList() else listOf(
                    CustomAccessibilityAction(markReadLabel) {
                        if (item.isUnread) { onMarkRead(); true } else false
                    },
                    CustomAccessibilityAction(markUnreadLabel) {
                        if (!item.isUnread) { onMarkUnread(); true } else false
                    },
                )
            }
            .pointerInput(item.chapter.id, item.isUnread, selectionActive) {
                if (!selectionActive) detectHorizontalDragGestures(
                    onDragStart = { dragX = 0f; dragging = true },
                    onDragCancel = { dragging = false; dragX = 0f },
                    onDragEnd = {
                        val finalX = dragX
                        dragging = false
                        dragX = 0f
                        if (finalX <= -threshold && item.isUnread) onMarkRead()
                        if (finalX >= threshold && !item.isUnread) onMarkUnread()
                    },
                    onHorizontalDrag = { change, delta ->
                        change.consume()
                        dragX = (dragX + delta).coerceIn(-maxReveal, maxReveal)
                    },
                )
            },
    ) {
        if (translation != 0f) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .padding(horizontal = 16.dp, vertical = 2.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(actionColor),
                contentAlignment = if (markingRead) Alignment.CenterEnd else Alignment.CenterStart,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(if (markingRead) R.drawable.ic_eye_check else R.drawable.ic_eye_off),
                        contentDescription = null,
                        tint = actionForeground,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = if (markingRead) markReadLabel else markUnreadLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = actionForeground,
                    )
                }
            }
        }
        Box(Modifier.offset { IntOffset(translation.roundToInt(), 0) }) { content() }
    }
}
