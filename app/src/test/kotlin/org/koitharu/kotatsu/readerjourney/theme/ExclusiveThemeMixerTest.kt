package org.koitharu.kotatsu.readerjourney.theme

import org.junit.Assert.assertEquals
import org.junit.Test
import org.koitharu.kotatsu.readerjourney.domain.ReaderJourneyCosmeticLoadout
import org.koitharu.kotatsu.readerjourney.domain.ReaderJourneyCosmeticMode

class ExclusiveThemeMixerTest {

	@Test
	fun `custom loadout mixes foundation wallpaper navigation accent and glow centrally`() {
		val foundationId = RankThemeId.GOLDEN_MANUSCRIPT
		val navigationId = RankThemeId.ETERNAL_LIBRARY
		val wallpaperId = RankThemeId.EMBER_VETERAN
		val accentId = RankThemeId.IMPERIAL_AURORA
		val glowId = RankThemeId.NEON_ARCHIVE
		val wallpaperSpec = checkNotNull(RankThemeVisualRegistry.resolve(wallpaperId))
		val loadout = ReaderJourneyCosmeticLoadout(
			mode = ReaderJourneyCosmeticMode.CUSTOM,
			selectedThemeId = foundationId.stableId,
			navigationThemeId = navigationId.stableId,
			accentThemeId = accentId.stableId,
			glowThemeId = glowId.stableId,
			selectedWallpaperId = wallpaperSpec.wallpaperId,
		)

		val resolved = ExclusiveThemeMixerResolver.resolve(
			foundationTheme = foundationId,
			loadout = loadout,
			variant = RankThemeVariant.DARK,
		)
		val foundation = resolve(foundationId)
		val navigation = resolve(navigationId)
		val accent = resolve(accentId)
		val glow = resolve(glowId)

		assertEquals(foundationId, resolved.id)
		assertEquals(foundation.tokens.surface, resolved.tokens.surface)
		assertEquals(foundation.tokens.surfaceVariant, resolved.tokens.surfaceVariant)
		// Wallpaper is a cosmetic asset source only. Global foundation/background stays owned by Base Theme.
		assertEquals(foundation.tokens.background, resolved.tokens.background)
		assertEquals(foundation.tokens.backgroundGradientStart, resolved.tokens.backgroundGradientStart)
		assertEquals(foundation.tokens.backgroundGradientMiddle, resolved.tokens.backgroundGradientMiddle)
		assertEquals(foundation.tokens.backgroundGradientEnd, resolved.tokens.backgroundGradientEnd)
		assertEquals(accent.tokens.primaryAccent, resolved.tokens.primaryAccent)
		assertEquals(accent.tokens.secondaryAccent, resolved.tokens.secondaryAccent)
		assertEquals(glow.tokens.glowColor, resolved.tokens.glowColor)
		assertEquals(navigation.navigation, resolved.navigation)

		assertEquals(foundation.details.borderStops, resolved.details.borderStops)
		assertEquals(accent.details.selectedStops, resolved.details.selectedStops)
		assertEquals(accent.details.iconStops, resolved.details.iconStops)
		assertEquals(accent.details.interactiveText, resolved.details.interactiveText)
		assertEquals(glow.details.glowStops, resolved.details.glowStops)

		assertEquals(foundation.favourites.borderStops, resolved.favourites.borderStops)
		assertEquals(accent.favourites.selectedStops, resolved.favourites.selectedStops)
		assertEquals(glow.favourites.glowStops, resolved.favourites.glowStops)
	}

	@Test
	fun `null custom overrides follow base theme exactly`() {
		val foundationId = RankThemeId.CRIMSON_LIBRARY
		val loadout = ReaderJourneyCosmeticLoadout(
			mode = ReaderJourneyCosmeticMode.CUSTOM,
			selectedThemeId = foundationId.stableId,
		)

		val mixed = ExclusiveThemeMixerResolver.resolve(
			foundationTheme = foundationId,
			loadout = loadout,
			variant = RankThemeVariant.DARK,
		)

		assertEquals(resolve(foundationId), mixed)
	}

	@Test
	fun `full set ignores custom override fields`() {
		val foundationId = RankThemeId.GOLDEN_MANUSCRIPT
		val loadout = ReaderJourneyCosmeticLoadout(
			mode = ReaderJourneyCosmeticMode.FULL_SET,
			selectedThemeId = foundationId.stableId,
			navigationThemeId = RankThemeId.ETERNAL_LIBRARY.stableId,
			accentThemeId = RankThemeId.IMPERIAL_AURORA.stableId,
			glowThemeId = RankThemeId.NEON_ARCHIVE.stableId,
		)

		assertEquals(
			resolve(foundationId),
			ExclusiveThemeMixerResolver.resolve(
				foundationTheme = foundationId,
				loadout = loadout,
				variant = RankThemeVariant.DARK,
			),
		)
	}

	private fun resolve(id: RankThemeId): ResolvedExclusiveTheme =
		ExclusiveThemeContractResolver.resolve(
			definition = RankThemeRegistry.resolveOrDefault(id.stableId),
			variant = RankThemeVariant.DARK,
		)
}
