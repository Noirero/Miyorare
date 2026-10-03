package org.koitharu.kotatsu.download.ui.worker

internal data class ChapterFlushAccounting(
	val requestedChapterCompleted: Boolean,
	val durableArtifactAvailable: Boolean,
)

internal fun accountStagedChapter(flushCreatedArtifact: Boolean): ChapterFlushAccounting =
	ChapterFlushAccounting(
		requestedChapterCompleted = true,
		durableArtifactAvailable = flushCreatedArtifact,
	)
