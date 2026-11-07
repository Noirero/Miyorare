package org.koitharu.kotatsu.favourites.domain

import dagger.Reusable
import javax.inject.Inject
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.ChapterEntity
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.ProgressIndicatorMode
import org.koitharu.kotatsu.core.prefs.ProgressIndicatorMode.CHAPTERS_READ
import org.koitharu.kotatsu.details.data.ChapterPersonalKey
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.history.data.HistoryEntity
import org.koitharu.kotatsu.history.data.toMangaHistory
import org.koitharu.kotatsu.list.domain.ReadingProgress

/**
 * The library's chapter-read badge is independent of story-position History.percent.
 * All data is local and fetched in bounded batches, never from individual chapter networks.
 */
@Reusable
class FavouriteUnreadCounter @Inject constructor(
    private val database: MangaDatabase,
    private val settings: AppSettings,
) {

    data class Snapshot internal constructor(
        private val histories: Map<Long, HistoryEntity>,
        val unreadCounts: Map<Long, Int>,
        private val unreadAuthority: Set<Long> = emptySet(),
        private val readCounts: Map<Long, Int> = emptyMap(),
        private val totalCounts: Map<Long, Int> = emptyMap(),
    ) {
        fun hasHistory(mangaId: Long): Boolean = mangaId in histories

        fun getHistory(mangaId: Long) = histories[mangaId]?.toMangaHistory()

        fun getUnreadCountOrNull(mangaId: Long): Int? =
            if (mangaId in unreadAuthority) unreadCounts[mangaId] else null

        internal fun merge(other: Snapshot): Snapshot = Snapshot(
            histories = histories + other.histories,
            unreadCounts = unreadCounts + other.unreadCounts,
            unreadAuthority = unreadAuthority + other.unreadAuthority,
            readCounts = readCounts + other.readCounts,
            totalCounts = totalCounts + other.totalCounts,
        )

        fun getProgress(mangaId: Long, mode: ProgressIndicatorMode): ReadingProgress? {
            if (mode == CHAPTERS_READ) {
                val total = totalCounts[mangaId]?.takeIf { it > 0 } ?: return null
                if (mangaId !in unreadAuthority) return null
                return ReadingProgress(
                    percent = (readCounts[mangaId] ?: 0).toFloat() / total,
                    totalChapters = total,
                    mode = mode,
                    hasUnreadAuthority = true,
                )
            }
            val history = histories[mangaId] ?: return null
            val fixedPercent = if (ReadingProgress.isCompleted(history.percent)) 1f else history.percent
            return ReadingProgress(
                percent = fixedPercent,
                totalChapters = history.chaptersCount,
                mode = mode,
                hasUnreadAuthority = mangaId in unreadAuthority,
            ).takeIf { it.isValid() }
        }
    }

    suspend fun getSnapshot(
        mangaIds: Collection<Long>,
        includeUnread: Boolean,
        space: FavouriteSpace = FavouriteSpace.NORMAL,
    ): Snapshot {
        if (mangaIds.isEmpty()) return Snapshot(emptyMap(), emptyMap())
        val ids = mangaIds.distinct()
        val histories = ids.chunked(DB_QUERY_BATCH_SIZE)
            .flatMap { database.getHistoryDao().findByIds(it) }
            .associateBy { it.mangaId }
        if (!includeUnread) return Snapshot(histories, emptyMap())

        val chapters = ids.chunked(DB_QUERY_BATCH_SIZE)
            .flatMap { database.getChaptersDao().findAll(it) }
            .groupBy(ChapterEntity::mangaId)
        val visited = ids.chunked(DB_QUERY_BATCH_SIZE)
            .flatMap { database.getChapterProgressDao().findAll(it, space.dbValue) }
            .groupBy { it.mangaId }
            .mapValues { (_, records) -> records.mapTo(HashSet()) { it.key() } }

        val unread = HashMap<Long, Int>(ids.size)
        val read = HashMap<Long, Int>(ids.size)
        val total = HashMap<Long, Int>(ids.size)
        val authority = HashSet<Long>(ids.size)
        for (id in ids) {
            val branches = chapters[id].orEmpty().groupBy { it.branch }
            val chosen = branches.values.maxByOrNull { it.size }.orEmpty()
            if (chosen.isEmpty()) continue // unknown chapter count is not authoritative
            val n = countReadChapters(
                chosen, visited[id].orEmpty(), settings.getChapterReadOverrides(id),
                histories[id]?.chapterId,
            )
            authority.add(id)
            total[id] = chosen.size
            read[id] = n
            unread[id] = chosen.size - n
        }
        return Snapshot(histories, unread, authority, read, total)
    }

    suspend fun getUnreadCount(mangaId: Long): Int =
        getSnapshot(listOf(mangaId), includeUnread = true).unreadCounts[mangaId] ?: 0

    private companion object {
        const val DB_QUERY_BATCH_SIZE = 500
    }
}

/** Count visited/explicitly-read chapters only; never infer predecessors from the History cursor. */
internal fun countReadChapters(
    chapters: List<ChapterEntity>,
    visited: Set<ChapterPersonalKey>,
    readOverrides: Map<Long, Boolean>,
    historyChapterId: Long?,
): Int = chapters.count { chapter ->
    readOverrides[chapter.chapterId]
        ?: (ChapterPersonalKey(chapter.source, chapter.url) in visited || chapter.chapterId == historyChapterId)
}
