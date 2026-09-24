package org.koitharu.kotatsu.readerjourney.domain

import dagger.hilt.android.ViewModelLifecycle
import dagger.hilt.android.scopes.ViewModelScoped
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.RetainedLifecycleCoroutineScope
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import javax.inject.Inject

/**
 * Validates reading progress before it reaches the persistent XP store.
 *
 * Manga completion is based on unique pages actually reported by the reader, not on the furthest
 * page reached. Novel completion uses real text progress. Merely opening a chapter, jumping straight
 * to the end, Mark-as-read operations and imported history never call this collector and therefore
 * never award XP.
 */
@ViewModelScoped
class ReaderJourneyCollector @Inject constructor(
	private val db: MangaDatabase,
	private val settings: AppSettings,
	lifecycle: ViewModelLifecycle,
) {

	private val scope = RetainedLifecycleCoroutineScope(lifecycle)
	private val entries = HashMap<Key, Entry>()
	private val activeByManga = HashMap<Long, Key>()

	@Synchronized
	fun onMangaProgress(
		mangaId: Long,
		chapterId: Long,
		page: Int,
		totalPages: Int,
	) {
		if (!settings.isReaderJourneyEnabled || totalPages <= 0) return
		val boundedPage = page.coerceIn(0, totalPages - 1)
		onProgress(
			Signal(
				mangaId = mangaId,
				chapterId = chapterId,
				isNovel = false,
				progressPermille = (((boundedPage + 1L) * 1000L) / totalPages)
					.toInt()
					.coerceIn(0, 1000),
				readingUnits = 0,
				pageIndex = boundedPage,
				totalPages = totalPages,
				positionBucket = NO_BUCKET,
			),
		)
	}

	@Synchronized
	fun onNovelProgress(
		mangaId: Long,
		chapterId: Long,
		progressPermille: Int,
		readingUnits: Int,
	) {
		if (!settings.isReaderJourneyEnabled) return
		val progress = progressPermille.coerceIn(0, 1000)
		onProgress(
			Signal(
				mangaId = mangaId,
				chapterId = chapterId,
				isNovel = true,
				progressPermille = progress,
				readingUnits = readingUnits.coerceAtLeast(0),
				pageIndex = NO_PAGE,
				totalPages = 0,
				positionBucket = progress / NOVEL_PROGRESS_BUCKET,
			),
		)
	}

	@Synchronized
	fun onPause(mangaId: Long) {
		activeByManga[mangaId]?.let { key ->
			entries[key]?.let(::tryAward)
		}
		activeByManga.remove(mangaId)
		entries.keys.removeAll { it.mangaId == mangaId }
	}

	private fun onProgress(signal: Signal) {
		val key = Key(signal.mangaId, signal.chapterId)
		val previousKey = activeByManga.put(signal.mangaId, key)
		if (previousKey != null && previousKey != key) {
			entries.remove(previousKey)?.let(::tryAward)
		}

		val now = System.currentTimeMillis()
		val entry = entries.getOrPut(key) {
			Entry(
				key = key,
				isNovel = signal.isNovel,
				startedAt = now,
				initialProgress = signal.progressPermille,
				lastProgress = signal.progressPermille,
				maxProgress = signal.progressPermille,
				readingUnits = signal.readingUnits,
				totalPages = signal.totalPages,
			)
		}
		entry.lastProgress = signal.progressPermille
		entry.maxProgress = maxOf(entry.maxProgress, signal.progressPermille)
		entry.readingUnits = maxOf(entry.readingUnits, signal.readingUnits)

		if (entry.isNovel) {
			if (signal.positionBucket != NO_BUCKET) {
				entry.novelPositionBuckets += signal.positionBucket
			}
			if (signal.progressPermille < ReaderJourneyRules.COMPLETION_PERMILLE) {
				entry.sawProgressBelowThreshold = true
			}
		} else {
			// Keep the largest observed page count so a transient smaller count cannot make coverage
			// easier to satisfy. Unique page indices are the actual anti-jump completion evidence.
			entry.totalPages = maxOf(entry.totalPages, signal.totalPages)
			if (signal.pageIndex != NO_PAGE && signal.pageIndex < entry.totalPages) {
				entry.uniquePages += signal.pageIndex
			}
		}

		if (!entry.awarded && isValidCompletion(entry, now)) {
			award(entry)
		}
	}

	private fun isValidCompletion(entry: Entry, now: Long): Boolean {
		val elapsed = now - entry.startedAt
		return if (entry.isNovel) {
			if (entry.maxProgress < ReaderJourneyRules.COMPLETION_PERMILLE) return false
			if (elapsed < ReaderJourneyRules.NOVEL_MIN_VALID_MS) return false
			if (entry.novelPositionBuckets.size < NOVEL_REQUIRED_POSITION_SAMPLES) return false
			entry.sawProgressBelowThreshold ||
				entry.maxProgress - entry.initialProgress >= MIN_ABOVE_THRESHOLD_ADVANCE
		} else {
			if (entry.totalPages <= 0) return false
			val requiredPages = ReaderJourneyRules.requiredMangaPages(entry.totalPages)
			if (entry.uniquePages.size < requiredPages) return false
			elapsed >= ReaderJourneyRules.mangaMinimumValidDurationMs(entry.totalPages)
		}
	}

	private fun tryAward(entry: Entry) {
		if (entry.awarded) return
		if (isValidCompletion(entry, System.currentTimeMillis())) {
			award(entry)
		}
	}

	private fun award(entry: Entry) {
		if (entry.awarded) return
		entry.awarded = true
		val baseXp = if (entry.isNovel) {
			ReaderJourneyRules.novelCompletionXp(entry.readingUnits)
		} else {
			ReaderJourneyRules.MANGA_COMPLETION_XP
		}
		scope.launch(Dispatchers.IO) {
			runCatchingCancellable {
				db.getReaderJourneyDao().awardCompletion(
					mangaId = entry.key.mangaId,
					chapterId = entry.key.chapterId,
					isNovel = entry.isNovel,
					readingUnits = entry.readingUnits,
					baseXp = baseXp,
					completedAt = System.currentTimeMillis(),
				)
			}.onFailure { error ->
				error.printStackTraceDebug()
			}
		}
	}

	private data class Key(
		val mangaId: Long,
		val chapterId: Long,
	)

	private data class Signal(
		val mangaId: Long,
		val chapterId: Long,
		val isNovel: Boolean,
		val progressPermille: Int,
		val readingUnits: Int,
		val pageIndex: Int,
		val totalPages: Int,
		val positionBucket: Int,
	)

	private class Entry(
		val key: Key,
		val isNovel: Boolean,
		val startedAt: Long,
		val initialProgress: Int,
		var lastProgress: Int,
		var maxProgress: Int,
		var readingUnits: Int,
		var totalPages: Int,
		var sawProgressBelowThreshold: Boolean = initialProgress < ReaderJourneyRules.COMPLETION_PERMILLE,
		var awarded: Boolean = false,
		val uniquePages: MutableSet<Int> = HashSet(),
		val novelPositionBuckets: MutableSet<Int> = HashSet(),
	)

	private companion object {
		const val NOVEL_PROGRESS_BUCKET = 50
		const val NOVEL_REQUIRED_POSITION_SAMPLES = 3
		const val MIN_ABOVE_THRESHOLD_ADVANCE = 100
		const val NO_PAGE = -1
		const val NO_BUCKET = -1
	}
}
