package org.koitharu.kotatsu.details.domain

import org.koitharu.kotatsu.parsers.model.Manga
import java.time.LocalDate

data class ChapterReleasePredictionItem(
	val manga: Manga,
	val prediction: NextChapterReleasePrediction,
	val releaseDates: List<LocalDate>,
	val branch: String?,
	val scanlator: String? = null,
)
