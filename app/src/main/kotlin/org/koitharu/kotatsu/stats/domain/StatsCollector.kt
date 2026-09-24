package org.koitharu.kotatsu.stats.domain

import androidx.collection.LongSparseArray
import androidx.collection.set
import dagger.hilt.android.ViewModelLifecycle
import dagger.hilt.android.scopes.ViewModelScoped
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.RetainedLifecycleCoroutineScope
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.reader.ui.ReaderState
import org.koitharu.kotatsu.stats.data.StatsEntity
import javax.inject.Inject

@ViewModelScoped
class StatsCollector @Inject constructor(
	private val db: MangaDatabase,
	private val settings: AppSettings,
	lifecycle: ViewModelLifecycle,
) {

	private val viewModelScope = RetainedLifecycleCoroutineScope(lifecycle)
	private val stats = LongSparseArray<Entry>(1)

	@Synchronized
	fun onStateChanged(mangaId: Long, state: ReaderState, totalPages: Int) {
		if (!settings.isStatsEnabled) {
			return
		}
		val now = System.currentTimeMillis()
		val entry = stats[mangaId]
		val pagesCount = totalPages.coerceAtLeast(0)
		if (entry == null) {
			stats[mangaId] = Entry(
				state = state,
				totalPages = pagesCount,
				stats = StatsEntity(
					mangaId = mangaId,
					startedAt = now,
					duration = 0,
					pages = 0,
				),
			)
			return
		}
		val pagesDelta = if (entry.state.page != state.page || entry.state.chapterId != state.chapterId) 1 else 0
		val chaptersDelta = entry.getChaptersDelta(state, pagesCount)
		val newEntry = entry.copy(
			state = state,
			totalPages = pagesCount,
			stats = StatsEntity(
				mangaId = mangaId,
				startedAt = entry.stats.startedAt,
				duration = now - entry.stats.startedAt,
				pages = entry.stats.pages + pagesDelta,
				chapters = entry.stats.chapters + chaptersDelta,
			),
		)
		stats[mangaId] = newEntry
		commit(newEntry.stats)
	}

	@Synchronized
	fun onNovelProgress(mangaId: Long, chapterId: Long, progressPermille: Int) {
		if (!settings.isStatsEnabled || progressPermille < NOVEL_COMPLETION_PERMILLE) {
			return
		}
		val entry = stats[mangaId] ?: return
		if (entry.countChapter(chapterId) == 0) {
			return
		}
		val now = System.currentTimeMillis()
		val updated = entry.copy(
			stats = entry.stats.copy(
				duration = now - entry.stats.startedAt,
				chapters = entry.stats.chapters + 1,
			),
		)
		stats[mangaId] = updated
		commit(updated.stats)
	}

	@Synchronized
	fun onPause(mangaId: Long) {
		val entry = stats[mangaId]
		if (entry != null) {
			commit(
				entry.stats.copy(
					duration = System.currentTimeMillis() - entry.stats.startedAt,
				),
			)
		}
		stats.remove(mangaId)
	}

	private fun commit(entity: StatsEntity) {
		viewModelScope.launch(Dispatchers.Default) {
			runCatchingCancellable {
				db.getStatsDao().upsert(entity)
			}.onFailure { e ->
				e.printStackTraceDebug()
			}
		}
	}

	private data class Entry(
		val state: ReaderState,
		val totalPages: Int,
		val stats: StatsEntity,
		val countedChapters: MutableSet<Long> = HashSet(),
	) {

		fun getChaptersDelta(newState: ReaderState, newTotalPages: Int): Int {
			var result = 0
			if (state.chapterId != newState.chapterId && isChapterCompleted(state, totalPages)) {
				result += countChapter(state.chapterId)
			}
			if (
				state.chapterId == newState.chapterId &&
				!isChapterCompleted(state, totalPages) &&
				isChapterCompleted(newState, newTotalPages)
			) {
				result += countChapter(newState.chapterId)
			}
			return result
		}

		fun countChapter(chapterId: Long): Int {
			return if (countedChapters.add(chapterId)) 1 else 0
		}
	}

	private companion object {
		const val NOVEL_COMPLETION_PERMILLE = 850

		fun isChapterCompleted(state: ReaderState, totalPages: Int): Boolean {
			return totalPages > 0 && state.page >= totalPages - 1
		}
	}
}
