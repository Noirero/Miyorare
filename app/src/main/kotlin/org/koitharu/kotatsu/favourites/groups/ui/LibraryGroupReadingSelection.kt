package org.koitharu.kotatsu.favourites.groups.ui

import org.koitharu.kotatsu.favourites.groups.domain.LibraryGroupTimelineItem
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.history.data.HistoryEntity

/**
 * Select a single saved timeline entry, never infer a cross-manga sequence from a collection.
 * Missing chapters fail closed instead of silently skipping to a different source/chapter.
 */
internal fun selectGroupContinueEntry(
    timeline: List<LibraryGroupTimelineItem>,
    lastReadPosition: Int,
    availableChapters: Set<Pair<Long, Long>>,
): LibraryGroupTimelineItem? {
    if (timeline.isEmpty()) return null
    val index = lastReadPosition.coerceIn(0, timeline.lastIndex)
    val entry = timeline[index]
    return entry.takeIf { it.mangaId to it.chapterId in availableChapters }
}

internal fun unresolvedGroupTimelineCount(
    timeline: List<LibraryGroupTimelineItem>,
    availableChapters: Set<Pair<Long, Long>>,
): Int = timeline.count { it.mangaId to it.chapterId !in availableChapters }

/** A history row from the other library space must never drive Group resume. */
internal fun isGroupHistoryEligible(history: HistoryEntity, space: FavouriteSpace): Boolean =
    history.space == space.dbValue && history.deletedAt == 0L
