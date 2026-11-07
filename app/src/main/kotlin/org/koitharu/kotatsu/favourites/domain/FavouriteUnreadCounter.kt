package org.koitharu.kotatsu.favourites.domain

import dagger.Reusable
import javax.inject.Inject
import kotlin.math.ceil
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
        private val legacyNovelIds: Set<Long> = emptySet(),
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
            legacyNovelIds = legacyNovelIds + other.legacyNovelIds,
        )

        fun getProgress(mangaId: Long, mode: ProgressIndicatorMode): ReadingProgress? {
            if (mode == CHAPTERS_READ && mangaId !in legacyNovelIds) {
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
        legacyNovelIds: Set<Long> = emptySet(),
    ): Snapshot {
        if (mangaIds.isEmpty()) return Snapshot(emptyMap(), emptyMap())
        val ids = mangaIds.distinct()
        val histories = ids.chunked(DB_QUERY_BATCH_SIZE)
            .flatMap { database.getHistoryDao().findByIds(it) }
            .associateBy { it.mangaId }
        if (!includeUnread) return Snapshot(histories, emptyMap(), legacyNovelIds = legacyNovelIds)

        val chapters = ids.chunked(DB_QUERY_BATCH_SIZE)
            .flatMap { database.getChaptersDao().findAll(it) }
            .groupBy(ChapterEntity::mangaId)
        val visited = ids.chunked(DB_QUERY_BATCH_SIZE)
            .flatMap { database.getChapterProgressDao().findAll(it, space.dbValue) }
            .groupBy { it.mangaId }
            .mapValues { (_, records) -> records.mapTo(HashSet()) { it.key() } }

        // Novel/EPUB reader positions are not stored in chapter_progress. Preserve the
        // old History-index indicator for those titles, including its exact SQL count.
        val legacyHistories = histories.values.filter { it.mangaId in legacyNovelIds }
        val legacyUnread = legacyHistories.chunked(DB_HISTORY_QUERY_BATCH_SIZE)
            .flatMap { chunk -> database.getChaptersDao().findUnreadAfterCurrent(
                mangaIds = chunk.map { it.mangaId }, chapterIds = chunk.map { it.chapterId },
            ) }
            .associateBy { it.mangaId to it.chapterId }

        val unread = HashMap<Long, Int>(ids.size)
        val read = HashMap<Long, Int>(ids.size)
        val total = HashMap<Long, Int>(ids.size)
        val authority = HashSet<Long>(ids.size)
        for (id in ids) {
            val branches = chapters[id].orEmpty().groupBy { it.branch }
            val chosen = branches.values.maxByOrNull { it.size }.orEmpty()
            if (chosen.isEmpty()) continue // unknown chapter count is not authoritative
            val n = if (id in legacyNovelIds) {
                val oldUnread = histories[id]?.let { history ->
                    legacyUnread[id to history.chapterId]?.unreadCount
                        ?: legacyFallbackUnread(chosen.size, history)
                } ?: chosen.size
                (chosen.size - oldUnread).coerceIn(0, chosen.size)
            } else {
                countReadChapters(
                    chosen, visited[id].orEmpty(), settings.getChapterReadOverrides(id),
                    histories[id]?.chapterId,
                )
            }
            authority.add(id)
            total[id] = chosen.size
            read[id] = n
            unread[id] = chosen.size - n
        }
        return Snapshot(histories, unread, authority, read, total, legacyNovelIds.intersect(ids.toSet()))
    }

    suspend fun getUnreadCount(mangaId: Long): Int =
        getSnapshot(listOf(mangaId), includeUnread = true).unreadCounts[mangaId] ?: 0

    private fun legacyFallbackUnread(count: Int, history: HistoryEntity): Int {
        val total = history.chaptersCount.takeIf { it > 0 } ?: count
        if (total <= 0) return 0
        val read = ceil(history.percent.coerceIn(0f, 1f).toDouble() * total.toDouble())
            .toInt().coerceIn(0, total)
        return (total - read).coerceAtLeast(0)
    }

    private companion object {
        const val DB_QUERY_BATCH_SIZE = 500
        // Two IN-list parameters per History entry: <= 400 + 400 on older SQLite.
        const val DB_HISTORY_QUERY_BATCH_SIZE = 400
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
