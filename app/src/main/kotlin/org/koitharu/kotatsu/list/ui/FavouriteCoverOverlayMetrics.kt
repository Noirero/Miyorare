package org.koitharu.kotatsu.list.ui

/** Calibration for cover chrome only. The existing 14dp progress + 4dp inset never scales. */
internal data class FavouriteCoverOverlayMetrics(val scale: Float) {
	val inset = (4f * scale).coerceIn(3f, 8f)
	val gap = (3f * scale).coerceIn(2f, 5f)
	val padding = (2f * scale).coerceIn(2f, 4f)
	val radius = (7f * scale).coerceIn(5f, 10f)
	val statusSize = (22f * scale).coerceIn(18f, 30f)
	val unreadSize = (24f * scale).coerceIn(22f, 34f)
	val titleSp = (10.5f * scale).coerceIn(10f, 15f)
	val badgeSp = (10f * scale).coerceIn(10f, 14f)
	val metadataSp = (9f * scale).coerceIn(9f, 12f)
	val actionSize = (34f * scale).coerceIn(32f, 48f)

	companion object {
		const val PROGRESS_RESERVED_DP = 18f
		const val ACTION_TOUCH_DP = 48f
		const val MIN_ACTION_TEXT_WIDTH_DP = 64f

		fun resolve(widthDp: Float, heightDp: Float) = FavouriteCoverOverlayMetrics(
			minOf(widthDp / 128f, heightDp / 152f).coerceIn(0.8f, 1.4f),
		)
	}
}
