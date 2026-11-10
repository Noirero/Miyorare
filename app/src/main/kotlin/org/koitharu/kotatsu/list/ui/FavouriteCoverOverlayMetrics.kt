package org.koitharu.kotatsu.list.ui

/**
 * Visual-only sizing from the actual cover dimensions, never the user's nominal Grid Size.
 * The legacy curve is preserved exactly at scale >= 0.8 (normal/large covers).
 */
internal data class FavouriteCoverOverlayMetrics(val scale: Float) {
	private val compact: Boolean get() = scale < 0.8f
	// Blend to the exact legacy minima at 0.8; do not introduce visible jumps as size slides.
	private val compactProgress: Float get() = ((scale - 0.64f) / 0.16f).coerceIn(0f, 1f)
	val inset = (4f * scale).coerceIn(if (compact) 2f else 3f, 8f)
	val gap = (3f * scale).coerceIn(if (compact) 1.5f else 2f, 5f)
	val padding = if (compact) 1.5f + 0.5f * compactProgress else (2f * scale).coerceIn(2f, 4f)
	val radius = (7f * scale).coerceIn(if (compact) 4f else 5f, 10f)
	val statusSize = if (compact) 14f + 4f * compactProgress else (22f * scale).coerceIn(18f, 30f)
	val unreadSize = if (compact) 18f + 4f * compactProgress else (24f * scale).coerceIn(22f, 34f)
	val titleSp = if (compact) 9f + compactProgress else (10.5f * scale).coerceIn(10f, 15f)
	val badgeSp = if (compact) 9f + compactProgress else (10f * scale).coerceIn(10f, 14f)
	val metadataSp = if (compact) 8f + compactProgress else (9f * scale).coerceIn(9f, 12f)
	// The visual circle can shrink; the interactive 48dp target never does.
	val actionSize = if (compact) 24f + 8f * compactProgress else (34f * scale).coerceIn(32f, 48f)

	/**
	 * Only tightly packed compact covers may reduce status glyph tiles below the ordinary
	 * 14dp baseline, down to 12dp, to fit the unread badge and three permanent statuses
	 * on one row. Otherwise the existing vertical compaction and accessibility fallback
	 * remain authoritative; never shrink the 48dp Continue Reading touch target.
	 */
	fun fittedStatusSize(availableWidthDp: Float, leadingBadgeWidthDp: Float, statusCount: Int): Float {
		if (!compact || statusCount <= 1 || leadingBadgeWidthDp <= 0f) return statusSize
		val remaining = availableWidthDp - leadingBadgeWidthDp - gap -
			padding * 2 - gap * (statusCount - 1)
		return minOf(statusSize, (remaining / statusCount).coerceAtLeast(12f))
	}

	companion object {
		const val PROGRESS_RESERVED_DP = 18f
		const val ACTION_TOUCH_DP = 48f
		const val MIN_ACTION_TEXT_WIDTH_DP = 64f

		fun resolve(widthDp: Float, heightDp: Float) = FavouriteCoverOverlayMetrics(
			minOf(widthDp / 128f, heightDp / 152f).coerceIn(0.64f, 1.4f),
		)
	}
}
