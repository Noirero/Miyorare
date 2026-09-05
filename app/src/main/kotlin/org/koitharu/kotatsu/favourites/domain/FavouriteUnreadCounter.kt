package org.koitharu.kotatsu.favourites.domain

import dagger.Reusable
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.prefs.ProgressIndicatorMode
import org.koitharu.kotatsu.history.data.HistoryEntity
import org.koitharu.kotatsu.history.data.toMangaHistory
import org.koitharu.kotatsu.list.domain.ReadingProgress
import kotlin.math.ceil
import javax.inject.Inject

/**
 * Calculates the number shown by the Favourites "unread chapters" card option.
 *
 * Tracker counters only represent chapters discovered since the last tracker check, so they are
 * not suitable for an unread badge. This counter stays completely local: it uses the cached
 * chapter table plus reading history and never performs a source/network request.
 */
@Reusable
class FavouriteUnreadCounter @Inject constructor(
	private val database: MangaDatabase,
) {

	data class Snapshot internal constructor(
		private val histories: Map<Long, HistoryEntity>,
		val unreadCounts: Map<Long, Int>,
	) {
		fun hasHistory(mangaId: Long): Boolean = mangaId in histories

		fun getHistory(mangaId: Long) = histories[mangaId]?.toMangaHistory()

		fun getProgress(mangaId: Long, mode: ProgressIndicatorMode): ReadingProgress? {
			val history = histories[mangaId] ?: return null
			val fixedPercent = if (ReadingProgress.isCompleted(history.percent)) 1f else history.percent
			return ReadingProgress(
				percent = fixedPercent,
				totalChapters = history.chaptersCount,
				mode = mode,
			).takeIf { it.isValid() }
		}
	}

	/**
	 * Loads card metadata in two Room queries for a normal visible page. Very large Favourites pages
	 * are split into bounded chunks so the generated `IN (...)` statements stay below SQLite host
	 * parameter limits on older Android versions instead of failing after deep pagination.
	 */
	suspend fun getSnapshot(mangaIds: Collection<Long>, includeUnread: Boolean): Snapshot {
		if (mangaIds.isEmpty()) return Snapshot(emptyMap(), emptyMap())
		val ids = mangaIds.distinct()
		val historyDao = database.getHistoryDao()
		val histories = ids.chunked(DB_QUERY_BATCH_SIZE)
			.flatMap { historyDao.findByIds(it) }
			.associateBy { it.mangaId }
		if (!includeUnread) return Snapshot(histories, emptyMap())

		val chaptersDao = database.getChaptersDao()
		val logicalCounts = ids.chunked(DB_QUERY_BATCH_SIZE)
			.flatMap { chaptersDao.findLogicalCounts(it) }
			.associate { it.mangaId to it.chapterCount }
		val exactUnread = histories.values
			.chunked(DB_HISTORY_QUERY_BATCH_SIZE)
			.flatMap { chunk ->
				chaptersDao.findUnreadAfterCurrent(
					mangaIds = chunk.map { it.mangaId },
					chapterIds = chunk.map { it.chapterId },
				)
			}
			.associateBy { it.mangaId to it.chapterId }

		val unread = HashMap<Long, Int>(ids.size)
		for (mangaId in ids) {
			val logicalCount = logicalCounts[mangaId] ?: 0
			val history = histories[mangaId]
			unread[mangaId] = when {
				history == null -> logicalCount
				logicalCount <= 0 -> 0
				else -> exactUnread[mangaId to history.chapterId]?.unreadCount
					?: calculateFallbackUnread(logicalCount, history)
			}
		}
		return Snapshot(histories, unread)
	}

	suspend fun getUnreadCount(mangaId: Long): Int =
		getSnapshot(listOf(mangaId), includeUnread = true).unreadCounts[mangaId] ?: 0

	private fun calculateFallbackUnread(logicalCount: Int, history: HistoryEntity): Int {
		val total = history.chaptersCount.takeIf { it > 0 } ?: logicalCount
		if (total <= 0) return 0
		val read = ceil(history.percent.coerceIn(0f, 1f).toDouble() * total.toDouble())
			.toInt()
			.coerceIn(0, total)
		return (total - read).coerceAtLeast(0)
	}

	private companion object {
		// SQLite versions used by older supported Android releases can cap host parameters at 999.
		// Keep headroom for generated statements and future query changes.
		const val DB_QUERY_BATCH_SIZE = 500
		const val DB_HISTORY_QUERY_BATCH_SIZE = 400
	}
}
