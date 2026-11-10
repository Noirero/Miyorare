package org.koitharu.kotatsu.list.ui

/**
 * Visual-only sizing from the actual cover dimensions, never the user's nominal Grid Size.
 * The legacy curve is preserved exactly at scale >= 0.8 (normal/large covers).
 */
internal data class FavouriteCoverOverlayMetrics(val scale: Float) {
	private val compact: Boolean get() = scale < 0.8f
	val inset = (4f * scale).coerceIn(if (compact) 2f else 3f, 8f)
	val gap = (3f * scale).coerceIn(if (compact) 1.5f else 2f, 5f)
	val padding = (2f * scale).coerceIn(if (compact) 1.5f else 2f, 4f)
	val radius = (7f * scale).coerceIn(if (compact) 4f else 5f, 10f)
	val statusSize = (22f * scale).coerceIn(if (compact) 14f else 18f, 30f)
	val unreadSize = (24f * scale).coerceIn(if (compact) 18f else 22f, 34f)
	val titleSp = (10.5f * scale).coerceIn(if (compact) 9f else 10f, 15f)
	val badgeSp = (10f * scale).coerceIn(if (compact) 9f else 10f, 14f)
	val metadataSp = (9f * scale).coerceIn(if (compact) 8f else 9f, 12f)
	// The visual circle can shrink; the interactive 48dp target never does.
	val actionSize = (34f * scale).coerceIn(if (compact) 24f else 32f, 48f)

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
