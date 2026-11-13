package org.koitharu.kotatsu.explore.ui

internal fun exploreGridColumns(widthPx: Int, targetWidthPx: Int, compactLandscape: Boolean): Int? {
	if (widthPx <= 0 || targetWidthPx <= 0) return null
	// Round up as before, but keep source names readable on landscape phones.
	val columns = (widthPx - 1) / targetWidthPx + 1
	return if (compactLandscape) columns.coerceAtMost(7) else columns
}
