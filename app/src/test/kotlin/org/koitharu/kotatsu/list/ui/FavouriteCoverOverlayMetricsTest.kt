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
				assertTrue(metrics.titleSp in 10f..15f)
				assertTrue(metrics.metadataSp in 9f..12f)
				assertTrue(metrics.statusSize in 18f..30f)
				assertTrue(metrics.actionSize in 32f..48f)
			}
		}
		assertEquals(48f, FavouriteCoverOverlayMetrics.ACTION_TOUCH_DP)
		assertEquals(18f, FavouriteCoverOverlayMetrics.PROGRESS_RESERVED_DP)
	}
}
