package org.koitharu.kotatsu.details.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.details.ui.model.ChapterListItem
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable

/**
 * Only shown for indexed on-device manga. The chapter list, sort/filter state and reader routing
 * are shared with Details; the grid does not create separate manga or chapter identities.
 */
@Composable
internal fun LocalChapterGallery(
    chapters: List<ChapterListItem>,
    mangaTitle: String,
    bottomPadding: Dp,
    topPadding: Dp,
    coverForChapter: suspend (MangaChapter) -> ByteArray?,
    onChapterClick: (ChapterListItem) -> Unit,
) {
    val context = LocalContext.current
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp, top = topPadding + 64.dp,
            bottom = bottomPadding + 86.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = mangaTitle, style = MaterialTheme.typography.titleLarge)
                Text(
                    text = stringResource(R.string.local_gallery_chapter_count, chapters.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(chapters, key = { it.chapter.url + ":" + it.chapter.id }) { item ->
            val cover by produceState<androidx.compose.ui.graphics.ImageBitmap?>(
                initialValue = null,
                key1 = item.chapter.url,
                key2 = item.chapter.id,
            ) {
                value = runCatchingCancellable {
                    withContext(Dispatchers.IO) {
                        coverForChapter(item.chapter)?.let { bytes ->
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                        }
                    }
                }.getOrNull()
            }
            Column(
                modifier = Modifier.fillMaxWidth().clickable { onChapterClick(item) },
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (cover != null) {
                            Image(
                                bitmap = cover!!,
                                contentDescription = item.getTitle(context.resources),
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)),
                            )
                        } else {
                            Text(
                                text = "📖",
                                style = MaterialTheme.typography.headlineLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (!item.isUnread) {
                            Text(
                                text = if (item.isCurrent) stringResource(R.string.local_gallery_reading)
                                    else stringResource(R.string.local_gallery_read),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.align(Alignment.BottomStart)
                                    .padding(8.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.primary)
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
                Text(
                    text = item.chapter.title?.takeIf { it.isNotBlank() } ?: item.getTitle(context.resources),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    minLines = 2,
                )
            }
        }
    }
}
