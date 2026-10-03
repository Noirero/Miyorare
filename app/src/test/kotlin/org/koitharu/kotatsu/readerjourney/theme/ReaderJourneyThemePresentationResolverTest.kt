package org.koitharu.kotatsu.readerjourney.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.koitharu.kotatsu.readerjourney.domain.ReaderJourneyCosmeticLoadout
import org.koitharu.kotatsu.readerjourney.domain.ReaderJourneyCosmeticMode
import org.koitharu.kotatsu.readerjourney.domain.ReaderJourneyRules

class ReaderJourneyThemePresentationResolverTest {

	@Test
	fun `default mode resolves to Miyorare default`() {
		val result = resolve(
			loadout = ReaderJourneyCosmeticLoadout(mode = ReaderJourneyCosmeticMode.DEFAULT),
			level = 50,
		)

		assertEquals(RankThemeSource.MIYORARE_DEFAULT, result.source)
		assertNull(result.theme)
	}

	@Test
	fun `auto mode follows rank derived from lifetime xp`() {
		val result = resolve(
			loadout = ReaderJourneyCosmeticLoadout(mode = ReaderJourneyCosmeticMode.AUTO),
			level = 50,
		)

		assertEquals(RankThemeSource.AUTO_RANK, result.source)
		assertEquals(RankThemeId.NEON_ARCHIVE, result.theme)
	}

	@Test
	fun `full set uses owned explicit theme`() {
		val result = resolve(
			loadout = ReaderJourneyCosmeticLoadout(
				mode = ReaderJourneyCosmeticMode.FULL_SET,
				selectedThemeId = RankThemeId.CYAN_CODEX.stableId,
			),
			level = 50,
		)

		assertEquals(RankThemeSource.EXPLICIT_RANK, result.source)
		assertEquals(RankThemeId.CYAN_CODEX, result.theme)
	}

	@Test
	fun `locked persisted full set never bypasses ownership`() {
		val result = resolve(
			loadout = ReaderJourneyCosmeticLoadout(
				mode = ReaderJourneyCosmeticMode.FULL_SET,
				selectedThemeId = RankThemeId.ETERNAL_LIBRARY.stableId,
			),
			level = 40,
		)

		assertEquals(RankThemeSource.AUTO_RANK, result.source)
		assertEquals(RankThemeId.ARCANE_SCHOLAR, result.theme)
	}

	@Test
	fun `custom mixer always has a foundation and old empty custom data follows current rank`() {
		val selected = resolve(
			loadout = ReaderJourneyCosmeticLoadout(
				mode = ReaderJourneyCosmeticMode.CUSTOM,
				selectedThemeId = RankThemeId.EMERALD_COMPASS.stableId,
			),
			level = 50,
		)
		val migratedEmptyCustom = resolve(
			loadout = ReaderJourneyCosmeticLoadout(mode = ReaderJourneyCosmeticMode.CUSTOM),
			level = 50,
		)

		assertEquals(RankThemeSource.EXPLICIT_RANK, selected.source)
		assertEquals(RankThemeId.EMERALD_COMPASS, selected.theme)
		assertEquals(RankThemeSource.EXPLICIT_RANK, migratedEmptyCustom.source)
		assertEquals(RankThemeId.NEON_ARCHIVE, migratedEmptyCustom.theme)
	}

	@Test
	fun `explicit custom appearance keeps precedence over rank cosmetics`() {
		val result = resolve(
			loadout = ReaderJourneyCosmeticLoadout(
				mode = ReaderJourneyCosmeticMode.FULL_SET,
				selectedThemeId = RankThemeId.NEON_ARCHIVE.stableId,
			),
			level = 50,
			explicitCustomAppearance = true,
		)

		assertEquals(RankThemeSource.USER_CUSTOM, result.source)
		assertNull(result.theme)
	}

	@Test
	fun `presentation emergency switch falls back without touching progression`() {
		val result = resolve(
			loadout = ReaderJourneyCosmeticLoadout(mode = ReaderJourneyCosmeticMode.AUTO),
			level = 100,
			presentationEnabled = false,
		)

		assertEquals(RankThemeSource.MIYORARE_DEFAULT, result.source)
		assertNull(result.theme)
	}

	private fun resolve(
		loadout: ReaderJourneyCosmeticLoadout,
		level: Int,
		explicitCustomAppearance: Boolean = false,
		presentationEnabled: Boolean = true,
	): RankThemeSourceResolution = ReaderJourneyThemePresentationResolver.resolve(
		ReaderJourneyThemePresentationRequest(
			loadout = loadout,
			lifetimeXp = xpForLevel(level),
			explicitCustomAppearance = explicitCustomAppearance,
			presentationEnabled = presentationEnabled,
		),
	)

	private fun xpForLevel(level: Int): Long =
		(1 until level.coerceIn(1, ReaderJourneyRules.MAX_LEVEL))
			.sumOf { ReaderJourneyRules.xpRequiredForNextLevel(it) }
}
