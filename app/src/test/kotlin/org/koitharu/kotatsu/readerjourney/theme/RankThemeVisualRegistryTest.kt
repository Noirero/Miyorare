package org.koitharu.kotatsu.readerjourney.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RankThemeVisualRegistryTest {

	@Test
	fun `all 12 rank themes have one complete visual spec`() {
		assertTrue(RankThemeVisualRegistry.validate().isEmpty())
		assertEquals(RankThemeId.entries.size, RankThemeVisualRegistry.all.size)
		assertEquals(
			RankThemeId.entries.toSet(),
			RankThemeVisualRegistry.all.map { it.themeId }.toSet(),
		)
	}

	@Test
	fun `badge silhouettes stay unique across the 12 ranks`() {
		assertEquals(
			RankThemeId.entries.size,
			RankThemeVisualRegistry.all.map { it.badgeStyle }.distinct().size,
		)
	}

	@Test
	fun `wallpaper directions stay unique and optional`() {
		assertEquals(
			RankThemeId.entries.size,
			RankThemeVisualRegistry.all.map { it.wallpaperStyle }.distinct().size,
		)
		assertTrue(RankThemeVisualRegistry.all.all { it.wallpaperOptional })
	}

	@Test
	fun `card frame and progress identities cover every theme`() {
		assertEquals(
			RankThemeId.entries.size,
			RankThemeVisualRegistry.all.map { it.frameStyle }.distinct().size,
		)
		assertEquals(
			RankThemeId.entries.size,
			RankThemeVisualRegistry.all.map { it.cardStyle }.distinct().size,
		)
		assertEquals(
			RankThemeId.entries.size,
			RankThemeVisualRegistry.all.map { it.progressStyle }.distinct().size,
		)
	}

	@Test
	fun `all visual IDs are stable internal identifiers`() {
		val ids = RankThemeVisualRegistry.all.flatMap {
			listOf(it.badgeId, it.frameId, it.wallpaperId, it.cardId, it.progressId)
		}
		assertEquals(ids.size, ids.distinct().size)
		assertTrue(ids.all { id -> id == id.uppercase() })
		assertTrue(ids.none { id -> id.contains(' ') })
	}
}
