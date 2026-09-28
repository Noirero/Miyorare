package org.koitharu.kotatsu.readerjourney.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.readerjourney.domain.ReaderJourneyCosmeticLoadout
import org.koitharu.kotatsu.readerjourney.domain.ReaderJourneyCosmeticMode

class ExclusiveThemeQaTest {

	@Test
	fun `debug QA can force every exclusive theme without mutating production loadout`() {
		assertTrue(BuildConfig.EXCLUSIVE_THEME_QA_ENABLED)
		assertEquals(12, RankThemeId.entries.size)
		val production = ReaderJourneyCosmeticLoadout()
		val snapshot = production.copy()

		RankThemeId.entries.forEach { theme ->
			val state = ExclusiveThemeQaState(
				enabled = true,
				selectedThemeId = theme.stableId,
				fullTheme = true,
			)
			val effective = state.effectiveLoadout(
				production = production,
				fallbackTheme = RankThemeId.FIRST_PAGE,
			)
			val visual = requireNotNull(RankThemeVisualRegistry.resolve(theme))

			assertEquals(ReaderJourneyCosmeticMode.FULL_SET, effective.mode)
			assertEquals(theme.stableId, effective.selectedThemeId)
			assertEquals(visual.badgeId, effective.selectedBadgeId)
			assertEquals(visual.frameId, effective.selectedFrameId)
			assertEquals(visual.nameplateId, effective.selectedNameplateId)
			assertEquals(visual.wallpaperId, effective.selectedWallpaperId)
		}

		assertEquals(snapshot, production)
	}

	@Test
	fun `component QA changes only requested slots`() {
		val production = ReaderJourneyCosmeticLoadout(
			mode = ReaderJourneyCosmeticMode.CUSTOM,
			selectedThemeId = RankThemeId.FIRST_LIGHT.stableId,
			navigationThemeId = RankThemeId.FIRST_LIGHT.stableId,
			selectedBadgeId = RankThemeVisualRegistry.firstLight.badgeId,
			selectedFrameId = RankThemeVisualRegistry.firstLight.frameId,
			selectedWallpaperId = RankThemeVisualRegistry.firstLight.wallpaperId,
		)
		val state = ExclusiveThemeQaState(
			enabled = true,
			selectedThemeId = RankThemeId.ETERNAL_LIBRARY.stableId,
			fullTheme = false,
			components = setOf(
				ExclusiveThemeQaComponent.NAVIGATION,
				ExclusiveThemeQaComponent.BADGE,
			),
		)
		val effective = state.effectiveLoadout(production, RankThemeId.FIRST_PAGE)

		assertEquals(RankThemeId.ETERNAL_LIBRARY.stableId, effective.navigationThemeId)
		assertEquals(RankThemeVisualRegistry.eternalLibrary.badgeId, effective.selectedBadgeId)
		assertEquals(production.selectedFrameId, effective.selectedFrameId)
		assertEquals(production.selectedWallpaperId, effective.selectedWallpaperId)
		assertEquals(production.selectedThemeId, effective.selectedThemeId)
		assertNotEquals(production, effective)
	}


	@Test
	fun `all component slots use the selected QA theme without changing the production snapshot`() {
		val production = ReaderJourneyCosmeticLoadout(
			mode = ReaderJourneyCosmeticMode.CUSTOM,
			selectedThemeId = RankThemeId.FIRST_PAGE.stableId,
		)
		val snapshot = production.copy()
		val theme = RankThemeId.IMPERIAL_AURORA
		val visual = requireNotNull(RankThemeVisualRegistry.resolve(theme))
		val state = ExclusiveThemeQaState(
			enabled = true,
			selectedThemeId = theme.stableId,
			fullTheme = false,
			components = ExclusiveThemeQaComponent.entries.toSet(),
		)

		val effective = state.effectiveLoadout(production, RankThemeId.FIRST_PAGE)

		assertEquals(theme.stableId, effective.navigationThemeId)
		assertEquals(theme.stableId, effective.accentThemeId)
		assertEquals(theme.stableId, effective.glowThemeId)
		assertEquals(visual.badgeId, effective.selectedBadgeId)
		assertEquals(visual.wallpaperId, effective.selectedWallpaperId)
		assertEquals(visual.frameId, effective.selectedFrameId)
		assertEquals(visual.nameplateId, effective.selectedNameplateId)
		assertEquals(visual.cardId, effective.selectedReaderCardId)
		assertEquals(snapshot, production)
	}

	@Test
	fun `disabled QA returns exact production loadout`() {
		val production = ReaderJourneyCosmeticLoadout(
			mode = ReaderJourneyCosmeticMode.FULL_SET,
			selectedThemeId = RankThemeId.FIRST_LIGHT.stableId,
		)
		val state = ExclusiveThemeQaState(
			enabled = false,
			selectedThemeId = RankThemeId.ETERNAL_LIBRARY.stableId,
		)

		assertEquals(production, state.effectiveLoadout(production, RankThemeId.FIRST_PAGE))
	}

	@Test
	fun `motion overrides support system force on and force off`() {
		val base = ExclusiveThemeQaState(enabled = true)
		assertTrue(base.copy(reduceMotion = ExclusiveThemeQaMotionOverride.SYSTEM).effectiveReduceMotion(true))
		assertFalse(base.copy(reduceMotion = ExclusiveThemeQaMotionOverride.SYSTEM).effectiveReduceMotion(false))
		assertTrue(base.copy(reduceMotion = ExclusiveThemeQaMotionOverride.FORCE_ON).effectiveReduceMotion(false))
		assertFalse(base.copy(reduceMotion = ExclusiveThemeQaMotionOverride.FORCE_OFF).effectiveReduceMotion(true))
		assertTrue(base.copy(batterySaver = ExclusiveThemeQaMotionOverride.FORCE_ON).effectiveBatterySaver(false))
		assertFalse(base.copy(batterySaver = ExclusiveThemeQaMotionOverride.FORCE_OFF).effectiveBatterySaver(true))
	}
}
