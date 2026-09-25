package org.koitharu.kotatsu.readerjourney.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.readerjourney.theme.RankThemeId
import org.koitharu.kotatsu.readerjourney.theme.RankThemeVisualRegistry

class ReaderJourneyCosmeticPolicyTest {

	@Test
	fun `collection exposes all 12 themes while ownership is derived from current rank`() {
		val entries = ReaderJourneyCosmeticPolicy.collection(ReaderRank.SCHOLAR)

		assertEquals(12, entries.size)
		assertEquals(RankThemeId.entries.toList(), entries.map { it.theme })
		assertTrue(entries.first { it.theme == RankThemeId.ARCANE_SCHOLAR }.unlocked)
		assertFalse(entries.first { it.theme == RankThemeId.NEON_ARCHIVE }.unlocked)
		assertEquals(50, entries.first { it.theme == RankThemeId.NEON_ARCHIVE }.unlockLevel)
	}

	@Test
	fun `next reward preview follows the first locked rank theme`() {
		val next = ReaderJourneyCosmeticPolicy.nextLockedTheme(ReaderRank.SCHOLAR)

		requireNotNull(next)
		assertEquals(RankThemeId.NEON_ARCHIVE, next.theme)
		assertEquals(50, next.unlockLevel)
	}

	@Test
	fun `locked theme and component ids are removed by ownership sanitization`() {
		val legend = RankThemeVisualRegistry.resolve(RankThemeId.ETERNAL_LIBRARY)!!
		val input = ReaderJourneyCosmeticLoadout(
			mode = ReaderJourneyCosmeticMode.CUSTOM,
			selectedThemeId = RankThemeId.ETERNAL_LIBRARY.stableId,
			selectedBadgeId = legend.badgeId,
			selectedWallpaperId = legend.wallpaperId,
			selectedReaderCardId = legend.cardId,
			selectedProgressStyleId = legend.progressId,
			frame = ReaderRank.LEGEND,
			glow = ReaderRank.LEGEND,
			background = ReaderRank.LEGEND,
			progressBar = ReaderRank.LEGEND,
			favoriteThemeIds = setOf(RankThemeId.ETERNAL_LIBRARY.stableId),
		)

		val safe = ReaderJourneyCosmeticPolicy.sanitizeForRank(input, ReaderRank.SCHOLAR)

		assertNull(safe.selectedThemeId)
		assertNull(safe.selectedBadgeId)
		assertNull(safe.selectedWallpaperId)
		assertNull(safe.selectedReaderCardId)
		assertNull(safe.selectedProgressStyleId)
		assertNull(safe.frame)
		assertNull(safe.glow)
		assertNull(safe.background)
		assertNull(safe.progressBar)
		assertTrue(safe.favoriteThemeIds.isEmpty())
	}

	@Test
	fun `full set apply atomically maps all primitives from one unlocked theme`() {
		val result = ReaderJourneyCosmeticPolicy.equipFullSet(
			loadout = ReaderJourneyCosmeticLoadout(),
			theme = RankThemeId.NEON_ARCHIVE,
			currentRank = ReaderRank.ARCHIVIST,
		)
		val spec = RankThemeVisualRegistry.resolve(RankThemeId.NEON_ARCHIVE)!!

		assertEquals(ReaderJourneyCosmeticMode.FULL_SET, result.mode)
		assertEquals(RankThemeId.NEON_ARCHIVE.stableId, result.selectedThemeId)
		assertEquals(spec.badgeId, result.selectedBadgeId)
		assertEquals(spec.wallpaperId, result.selectedWallpaperId)
		assertEquals(spec.cardId, result.selectedReaderCardId)
		assertEquals(spec.progressId, result.selectedProgressStyleId)
		assertEquals(ReaderRank.ARCHIVIST, result.frame)
		assertEquals(ReaderRank.ARCHIVIST, result.glow)
		assertEquals(ReaderRank.ARCHIVIST, result.background)
		assertEquals(ReaderRank.ARCHIVIST, result.progressBar)
	}

	@Test
	fun `locked full set cannot be equipped`() {
		val initial = ReaderJourneyCosmeticLoadout(mode = ReaderJourneyCosmeticMode.AUTO)

		val result = ReaderJourneyCosmeticPolicy.equipFullSet(
			loadout = initial,
			theme = RankThemeId.ETERNAL_LIBRARY,
			currentRank = ReaderRank.SCHOLAR,
		)

		assertEquals(ReaderJourneyCosmeticMode.AUTO, result.mode)
		assertNull(result.selectedThemeId)
	}

	@Test
	fun `default and auto clear equipped presentation but keep preferences`() {
		val first = RankThemeVisualRegistry.resolve(RankThemeId.FIRST_PAGE)!!
		val input = ReaderJourneyCosmeticLoadout(
			mode = ReaderJourneyCosmeticMode.CUSTOM,
			selectedThemeId = RankThemeId.FIRST_PAGE.stableId,
			selectedBadgeId = first.badgeId,
			selectedWallpaperId = first.wallpaperId,
			selectedReaderCardId = first.cardId,
			selectedProgressStyleId = first.progressId,
			frame = ReaderRank.NEWCOMER,
			glow = ReaderRank.NEWCOMER,
			background = ReaderRank.NEWCOMER,
			progressBar = ReaderRank.NEWCOMER,
			favoriteThemeIds = setOf(RankThemeId.FIRST_PAGE.stableId),
			autoEquipNewRankTheme = true,
		)

		val default = ReaderJourneyCosmeticPolicy.equipDefault(input, ReaderRank.READER)
		val auto = ReaderJourneyCosmeticPolicy.equipAuto(input, ReaderRank.READER)

		listOf(default, auto).forEach { result ->
			assertNull(result.selectedThemeId)
			assertNull(result.selectedBadgeId)
			assertNull(result.selectedWallpaperId)
			assertNull(result.selectedReaderCardId)
			assertNull(result.selectedProgressStyleId)
			assertNull(result.frame)
			assertNull(result.glow)
			assertNull(result.background)
			assertNull(result.progressBar)
			assertEquals(setOf(RankThemeId.FIRST_PAGE.stableId), result.favoriteThemeIds)
			assertTrue(result.autoEquipNewRankTheme)
		}
		assertEquals(ReaderJourneyCosmeticMode.DEFAULT, default.mode)
		assertEquals(ReaderJourneyCosmeticMode.AUTO, auto.mode)
	}

	@Test
	fun `favorites only accept unlocked themes and toggle deterministically`() {
		val initial = ReaderJourneyCosmeticLoadout()

		val locked = ReaderJourneyCosmeticPolicy.toggleFavorite(
			initial,
			RankThemeId.ETERNAL_LIBRARY,
			ReaderRank.READER,
		)
		assertTrue(locked.favoriteThemeIds.isEmpty())

		val added = ReaderJourneyCosmeticPolicy.toggleFavorite(
			initial,
			RankThemeId.FIRST_LIGHT,
			ReaderRank.READER,
		)
		assertEquals(setOf(RankThemeId.FIRST_LIGHT.stableId), added.favoriteThemeIds)

		val removed = ReaderJourneyCosmeticPolicy.toggleFavorite(
			added,
			RankThemeId.FIRST_LIGHT,
			ReaderRank.READER,
		)
		assertTrue(removed.favoriteThemeIds.isEmpty())
	}
}
