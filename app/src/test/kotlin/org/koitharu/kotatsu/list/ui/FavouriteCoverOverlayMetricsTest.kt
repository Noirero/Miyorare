package org.koitharu.kotatsu.list.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FavouriteCoverOverlayMetricsTest {
	@Test
	fun chromeScalesWithBothCoverDimensionsAndStopsAtReadableBounds() {
		val small = FavouriteCoverOverlayMetrics.resolve(55f, 33f)
		val normal = FavouriteCoverOverlayMetrics.resolve(128f, 152f)
		val large = FavouriteCoverOverlayMetrics.resolve(194f, 344f)
		assertTrue(small.titleSp < normal.titleSp)
		assertTrue(normal.titleSp < large.titleSp)
		assertTrue(small.statusSize < normal.statusSize)
		assertTrue(normal.statusSize < large.statusSize)
		assertEquals(small, FavouriteCoverOverlayMetrics.resolve(128f, 33f))
		assertEquals(small, FavouriteCoverOverlayMetrics.resolve(0f, 0f))
		assertEquals(large, FavouriteCoverOverlayMetrics.resolve(2000f, 4000f))
		for (w in listOf(0f, 40f, 55f, 128f, 194f, 2000f)) {
			for (h in listOf(0f, 24f, 33f, 152f, 344f, 4000f)) {
				val metrics = FavouriteCoverOverlayMetrics.resolve(w, h)
				assertTrue(metrics.titleSp in 9f..15f)
				assertTrue(metrics.metadataSp in 8f..12f)
				assertTrue(metrics.statusSize in 14f..30f)
				assertTrue(metrics.actionSize in 24f..48f)
			}
		}
		assertEquals(48f, FavouriteCoverOverlayMetrics.ACTION_TOUCH_DP)
		assertEquals(18f, FavouriteCoverOverlayMetrics.PROGRESS_RESERVED_DP)
	}

	@Test
	fun compactCoversReduceUnreadAndStatusChromeWithoutChangingLargeCoverMetrics() {
		val compact = FavouriteCoverOverlayMetrics.resolve(70f, 93f)
		val narrow = FavouriteCoverOverlayMetrics.resolve(64f, 45f)
		val restored = FavouriteCoverOverlayMetrics.resolve(128f, 152f)
		assertTrue(compact.scale < 0.8f)
		assertTrue(compact.statusSize in 14f..16f)
		assertTrue(compact.unreadSize in 18f..20f)
		assertTrue(narrow.statusSize in 14f..16f)
		assertTrue(narrow.unreadSize in 18f..20f)
		assertEquals(22f, restored.statusSize)
		assertEquals(24f, restored.unreadSize)
		assertEquals(10f, restored.badgeSp)
		assertEquals(10.5f, restored.titleSp)
		assertEquals(9f, restored.metadataSp)
		assertEquals(34f, restored.actionSize)
		assertEquals(18f, FavouriteCoverOverlayMetrics.PROGRESS_RESERVED_DP)
		assertEquals(48f, FavouriteCoverOverlayMetrics.ACTION_TOUCH_DP)
	}

	@Test
	fun fittedThreeStatusTilesRespectMeasuredUnreadWidthButKeepNormalSizes() {
		val small = FavouriteCoverOverlayMetrics.resolve(70f, 95f)
		val dense = small.fittedStatusSize(66f, 22f, 3)
		assertTrue(dense in 12f..small.statusSize)
		assertTrue(dense < small.statusSize)
		assertEquals(small.statusSize, small.fittedStatusSize(66f, 0f, 3))
		assertEquals(small.statusSize, small.fittedStatusSize(66f, 22f, 1))
		val normal = FavouriteCoverOverlayMetrics.resolve(128f, 152f)
		assertEquals(normal.statusSize, normal.fittedStatusSize(90f, 22f, 3))
	}

	@Test
	fun compactCurveMeetsUnchangedNormalMetricsWithoutAVisibleSizeJump() {
		val justBelow = FavouriteCoverOverlayMetrics(0.799f)
		val normal = FavouriteCoverOverlayMetrics(0.8f)
		assertTrue(kotlin.math.abs(justBelow.unreadSize - normal.unreadSize) < 0.1f)
		assertTrue(kotlin.math.abs(justBelow.statusSize - normal.statusSize) < 0.1f)
		assertTrue(kotlin.math.abs(justBelow.actionSize - normal.actionSize) < 0.1f)
		assertTrue(kotlin.math.abs(justBelow.badgeSp - normal.badgeSp) < 0.1f)
		assertTrue(kotlin.math.abs(justBelow.padding - normal.padding) < 0.1f)
	}

	@Test
	fun modeledBadgeAndStatusAreaFallsAtCompactWidthsForFiveColumnGrid() {
		// Identical theoretical rectangles, independent of runtime visibility/overlap.
		// Android instrumentation separately measures actually visible overlay rectangles.
		fun oldMetric(width: Float, height: Float): Pair<Float, Float> {
			val s = minOf(width / 128f, height / 152f).coerceIn(0.8f, 1.4f)
			return (22f * s).coerceIn(18f, 30f) to (24f * s).coerceIn(22f, 34f)
		}
		for (size in listOf(50, 55, 60, 69, 70, 100)) {
			val width = 70f
			val height = width / org.koitharu.kotatsu.core.ui.MiyorareFavouritesVisualSpec.MANGA_CARD_ASPECT_RATIO * size / 100f
			val current = FavouriteCoverOverlayMetrics.resolve(width, height)
			val (beforeStatus, beforeUnread) = oldMetric(width, height)
			val before = 3f * beforeStatus * beforeStatus + beforeUnread * beforeUnread
			val after = 3f * current.statusSize * current.statusSize + current.unreadSize * current.unreadSize
			assertTrue("Compact visual chrome must not increase at $size%", after <= before)
			if (size <= 69) assertTrue("Compact chrome should shrink at $size%", after < before)
		}
	}
}
