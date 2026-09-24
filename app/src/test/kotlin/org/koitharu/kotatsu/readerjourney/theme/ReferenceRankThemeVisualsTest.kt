package org.koitharu.kotatsu.readerjourney.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferenceRankThemeVisualsTest {

	@Test
	fun `reference registry is intentionally limited to first page and neon archive`() {
		assertTrue(ReferenceRankThemeVisualRegistry.validate().isEmpty())
		assertEquals(
			setOf(RankThemeId.FIRST_PAGE, RankThemeId.NEON_ARCHIVE),
			ReferenceRankThemeVisualRegistry.all.map { it.themeId }.toSet(),
		)
		assertEquals(2, ReferenceRankThemeVisualRegistry.all.size)
	}

	@Test
	fun `reference themes have distinct badge silhouette and visual primitives`() {
		val first = ReferenceRankThemeVisualRegistry.firstPage
		val neon = ReferenceRankThemeVisualRegistry.neonArchive

		assertNotEquals(first.badgeStyle, neon.badgeStyle)
		assertNotEquals(first.frameStyle, neon.frameStyle)
		assertNotEquals(first.wallpaperStyle, neon.wallpaperStyle)
		assertNotEquals(first.cardStyle, neon.cardStyle)
		assertNotEquals(first.progressStyle, neon.progressStyle)
	}

	@Test
	fun `reference themes remain valid with wallpaper off`() {
		assertTrue(ReferenceRankThemeVisualRegistry.all.all { it.wallpaperOptional })
	}

	@Test
	fun `visual ids are stable internal ids rather than display names`() {
		ReferenceRankThemeVisualRegistry.all.forEach { spec ->
			val ids = listOf(spec.badgeId, spec.frameId, spec.wallpaperId, spec.cardId, spec.progressId)
			assertTrue(ids.all { it == it.uppercase() })
			assertTrue(ids.none { it.contains(' ') })
			assertTrue(ids.none { it == spec.themeId.displayName })
		}
	}
}
