package org.koitharu.kotatsu.details.ui.pager

enum class ChapterGroupingMode {
	AUTOMATIC, CHAPTERS_25, CHAPTERS_50, CHAPTERS_100, OFF;

	fun rangeSize(chapterCount: Int): Int? = when (this) {
		AUTOMATIC -> when {
			chapterCount <= 50 -> null
			chapterCount <= 500 -> 50
			else -> 100
		}
		CHAPTERS_25 -> 25
		CHAPTERS_50 -> 50
		CHAPTERS_100 -> 100
		OFF -> null
	}
}
