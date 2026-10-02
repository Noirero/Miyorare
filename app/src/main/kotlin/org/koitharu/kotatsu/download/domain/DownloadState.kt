package org.koitharu.kotatsu.download.domain

import androidx.work.Data
import org.koitharu.kotatsu.list.domain.ReadingProgress.Companion.PROGRESS_NONE
import org.koitharu.kotatsu.local.domain.model.LocalManga
import org.koitharu.kotatsu.parsers.model.Manga
import java.time.Instant

data class DownloadState(
	val manga: Manga,
	val isIndeterminate: Boolean,
	val isPaused: Boolean = false,
	val isFinalizing: Boolean = false,
	val phase: DownloadPhase = DownloadPhase.DOWNLOADING,
	val phaseChapter: Int = 0,
	val retryAttempt: Int = 0,
	val isStopped: Boolean = false,
	val error: Throwable? = null,
	val errorMessage: String? = null,
	val totalChapters: Int = 0,
	val currentChapter: Int = 0,
	val totalPages: Int = 0,
	val currentPage: Int = 0,
	val overallTotalPages: Int = 0,
	val completedPagesBeforeChapter: Int = 0,
	val eta: Long = -1L,
	val isStuck: Boolean = false,
	val localManga: LocalManga? = null,
	val downloadedChapters: Int = 0,
	val timestamp: Long = System.currentTimeMillis(),
) {

	val max: Int = overallTotalPages.takeIf { it > 0 } ?: (totalChapters * totalPages)

	val progress: Int = if (overallTotalPages > 0) {
		(completedPagesBeforeChapter.coerceAtLeast(0) + currentPage.coerceAtLeast(0)).coerceAtMost(overallTotalPages)
	} else {
		calculateDownloadProgress(totalPages, currentChapter, currentPage)
	}

	val percent: Float = if (max > 0) progress.toFloat() / max else PROGRESS_NONE

	val isFinalState: Boolean
		get() = localManga != null || (error != null && !isPaused)

	val isParticularProgress: Boolean
		get() = localManga == null && error == null && !isPaused && !isStopped && max > 0 && !isIndeterminate

	fun toWorkData() = Data.Builder()
		.putLong(DATA_MANGA_ID, manga.id)
		.putInt(DATA_MAX, max)
		.putInt(DATA_PROGRESS, progress)
		.putLong(DATA_ETA, eta)
		.putBoolean(DATA_STUCK, isStuck)
		.putLong(DATA_TIMESTAMP, timestamp)
		.putString(DATA_ERROR, errorMessage)
		.putInt(DATA_CHAPTERS, downloadedChapters)
		.putBoolean(DATA_INDETERMINATE, isIndeterminate)
		.putBoolean(DATA_PAUSED, isPaused)
		.putBoolean(DATA_FINALIZING, isFinalizing)
		.putString(DATA_PHASE, phase.name)
		.putInt(DATA_PHASE_CHAPTER, phaseChapter)
		.putInt(DATA_RETRY_ATTEMPT, retryAttempt)
		.build()

	companion object {

		private const val DATA_MANGA_ID = "manga_id"
		private const val DATA_MAX = "max"
		private const val DATA_PROGRESS = "progress"
		private const val DATA_CHAPTERS = "chapter_cnt"
		private const val DATA_ETA = "eta"
		private const val DATA_STUCK = "stuck"
		const val DATA_TIMESTAMP = "timestamp"
		private const val DATA_ERROR = "error"
		private const val DATA_INDETERMINATE = "indeterminate"
		private const val DATA_PAUSED = "paused"
		private const val DATA_FINALIZING = "finalizing"
		private const val DATA_PHASE = "phase"
		private const val DATA_PHASE_CHAPTER = "phase_chapter"
		private const val DATA_RETRY_ATTEMPT = "retry_attempt"

		fun getMangaId(data: Data): Long = data.getLong(DATA_MANGA_ID, 0L)

		fun isIndeterminate(data: Data): Boolean = data.getBoolean(DATA_INDETERMINATE, false)

		fun isPaused(data: Data): Boolean = data.getBoolean(DATA_PAUSED, false)

		fun isFinalizing(data: Data): Boolean = data.getBoolean(DATA_FINALIZING, false)

		fun getPhase(data: Data): DownloadPhase = data.getString(DATA_PHASE)?.let { runCatching { DownloadPhase.valueOf(it) }.getOrNull() } ?: DownloadPhase.DOWNLOADING

		fun getPhaseChapter(data: Data): Int = data.getInt(DATA_PHASE_CHAPTER, 0)

		fun getRetryAttempt(data: Data): Int = data.getInt(DATA_RETRY_ATTEMPT, 0)

		fun getMax(data: Data): Int = data.getInt(DATA_MAX, 0)

		fun getError(data: Data): String? = data.getString(DATA_ERROR)

		fun getProgress(data: Data): Int = data.getInt(DATA_PROGRESS, 0)

		fun getEta(data: Data): Long = data.getLong(DATA_ETA, -1L)

		fun isStuck(data: Data): Boolean = data.getBoolean(DATA_STUCK, false)

		fun getTimestamp(data: Data): Instant = Instant.ofEpochMilli(data.getLong(DATA_TIMESTAMP, 0L))

		fun getDownloadedChapters(data: Data): Int = data.getInt(DATA_CHAPTERS, 0)
	}
}

internal fun calculateDownloadProgress(totalPages: Int, currentChapter: Int, currentPage: Int): Int =
	(totalPages.coerceAtLeast(0) * currentChapter.coerceAtLeast(0) + currentPage.coerceAtLeast(0))


enum class DownloadPhase { RESOLVING, RETRYING, DOWNLOADING, FINALIZING }
