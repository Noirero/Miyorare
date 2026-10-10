package org.koitharu.kotatsu.details.ui

import org.koitharu.kotatsu.details.data.ChapterPersonalMetadata
import java.util.Locale

/** A derived rendering value; the existing personal metadata remains authoritative. */
data class ChapterPersonalPresentation(val hasNote: Boolean, val ratingText: String?) {
	val isRated: Boolean get() = ratingText != null
}

fun ChapterPersonalMetadata.presentation(locale: Locale): ChapterPersonalPresentation = ChapterPersonalPresentation(
	hasNote = note != null,
	ratingText = rating?.let { String.format(locale, "%d", it) },
)
