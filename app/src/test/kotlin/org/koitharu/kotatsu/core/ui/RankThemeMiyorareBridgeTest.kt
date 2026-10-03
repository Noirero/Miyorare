package org.koitharu.kotatsu.core.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.koitharu.kotatsu.core.prefs.MiyorareAdaptivePalette
import org.koitharu.kotatsu.core.prefs.MiyorareAppearance
import org.koitharu.kotatsu.core.prefs.MiyorareThemePreset
import org.koitharu.kotatsu.core.prefs.VisualEffectLevel
import org.koitharu.kotatsu.readerjourney.domain.ReaderJourneyCosmeticLoadout
import org.koitharu.kotatsu.readerjourney.domain.ReaderJourneyCosmeticMode
import org.koitharu.kotatsu.readerjourney.theme.ExclusiveThemeContractResolver
import org.koitharu.kotatsu.readerjourney.theme.ExclusiveThemeMixerResolver
import org.koitharu.kotatsu.readerjourney.theme.RankThemeId
import org.koitharu.kotatsu.readerjourney.theme.RankThemeRegistry
import org.koitharu.kotatsu.readerjourney.theme.RankThemeVariant

class RankThemeMiyorareBridgeTest {

	@Test
	fun `null rank tokens preserve existing Miyorare palette path`() {
		val normal = miyorareThemeColors(
			preset = MiyorareThemePreset.MIYORARE,
			customAccent = MiyorareAppearance.DEFAULT_CUSTOM_ACCENT,
			darkTheme = true,
			amoled = false,
			effectLevel = VisualEffectLevel.BALANCED,
		)
		val explicitNull = miyorareThemeColors(
			preset = MiyorareThemePreset.MIYORARE,
			customAccent = MiyorareAppearance.DEFAULT_CUSTOM_ACCENT,
			darkTheme = true,
			amoled = false,
			effectLevel = VisualEffectLevel.BALANCED,
			exclusiveTheme = null,
		)

		assertEquals(normal.colorScheme.primary, explicitNull.colorScheme.primary)
		assertEquals(normal.colorScheme.secondary, explicitNull.colorScheme.secondary)
		assertEquals(normal.colorScheme.tertiary, explicitNull.colorScheme.tertiary)
		assertEquals(normal.colorScheme.background, explicitNull.colorScheme.background)
		assertEquals(normal.colorScheme.surface, explicitNull.colorScheme.surface)
		assertEquals(normal.colorScheme.surfaceContainer, explicitNull.colorScheme.surfaceContainer)
		assertEquals(normal.colorScheme.surfaceContainerHigh, explicitNull.colorScheme.surfaceContainerHigh)
		assertEquals(normal.colorScheme.onSurface, explicitNull.colorScheme.onSurface)
		assertEquals(normal.colorScheme.outline, explicitNull.colorScheme.outline)
		assertEquals(normal.colorScheme.error, explicitNull.colorScheme.error)
		assertEquals(normal.visualPalette, explicitNull.visualPalette)
	}

	@Test
	fun `rank light tokens own authored background surface and semantic statuses`() {
		val definition = RankThemeRegistry.resolveOrDefault(RankThemeId.FIRST_PAGE.stableId)
		val tokens = definition.tokens(RankThemeVariant.LIGHT)
		val exclusiveTheme = ExclusiveThemeContractResolver.resolve(definition, RankThemeVariant.LIGHT)
		val colors = miyorareThemeColors(
			preset = MiyorareThemePreset.MIYORARE,
			customAccent = MiyorareAppearance.DEFAULT_CUSTOM_ACCENT,
			darkTheme = false,
			amoled = false,
			effectLevel = VisualEffectLevel.BALANCED,
			exclusiveTheme = exclusiveTheme,
		)

		assertEquals(Color(tokens.background.toInt()), colors.colorScheme.background)
		assertEquals(Color(tokens.surface.toInt()), colors.colorScheme.surface)
		assertEquals(Color(tokens.errorColor.toInt()), colors.colorScheme.error)
		assertEquals(Color(tokens.warningColor.toInt()), colors.visualPalette.warning)
		assertEquals(Color(tokens.successColor.toInt()), colors.visualPalette.success)
		assertEquals(Color(tokens.destructiveColor.toInt()), colors.visualPalette.destructive)
		assertEquals(Color(tokens.focusIndicatorColor.toInt()), colors.visualPalette.focusIndicator)
	}

	@Test
	fun `rank dark and oled tokens stay on the same palette engine`() {
		val definition = RankThemeRegistry.resolveOrDefault(RankThemeId.NEON_ARCHIVE.stableId)
		val darkTokens = definition.tokens(RankThemeVariant.DARK)
		val darkExclusiveTheme = ExclusiveThemeContractResolver.resolve(definition, RankThemeVariant.DARK)
		val dark = miyorareThemeColors(
			preset = MiyorareThemePreset.MIYORARE,
			customAccent = MiyorareAppearance.DEFAULT_CUSTOM_ACCENT,
			darkTheme = true,
			amoled = false,
			effectLevel = VisualEffectLevel.FULL,
			exclusiveTheme = darkExclusiveTheme,
		)
		assertEquals(Color(darkTokens.background.toInt()), dark.colorScheme.background)
		assertEquals(Color(darkTokens.surface.toInt()), dark.colorScheme.surface)

		val oledTokens = definition.tokens(RankThemeVariant.OLED)
		val oledExclusiveTheme = ExclusiveThemeContractResolver.resolve(definition, RankThemeVariant.OLED)
		val oled = miyorareThemeColors(
			preset = MiyorareThemePreset.MIYORARE,
			customAccent = MiyorareAppearance.DEFAULT_CUSTOM_ACCENT,
			darkTheme = true,
			amoled = true,
			effectLevel = VisualEffectLevel.FULL,
			exclusiveTheme = oledExclusiveTheme,
		)
		assertEquals(Color.Black, oled.colorScheme.background)
		assertEquals(Color.Black, oled.colorScheme.surfaceContainer)
	}

	@Test
	fun `visual palette carries custom navigation identity independently from foundation`() {
		val foundationId = RankThemeId.FIRST_PAGE
		val navigationId = RankThemeId.GOLDEN_MANUSCRIPT
		val mixed = ExclusiveThemeMixerResolver.resolve(
			foundationTheme = foundationId,
			loadout = ReaderJourneyCosmeticLoadout(
				mode = ReaderJourneyCosmeticMode.CUSTOM,
				selectedThemeId = foundationId.stableId,
				navigationThemeId = navigationId.stableId,
			),
			variant = RankThemeVariant.DARK,
		)
		val colors = miyorareThemeColors(
			preset = MiyorareThemePreset.MIYORARE,
			customAccent = MiyorareAppearance.DEFAULT_CUSTOM_ACCENT,
			darkTheme = true,
			amoled = false,
			effectLevel = VisualEffectLevel.BALANCED,
			exclusiveTheme = mixed,
		)

		assertEquals(foundationId.stableId, colors.visualPalette.exclusiveTheme?.stableId)
		assertEquals(navigationId.stableId, colors.visualPalette.exclusiveTheme?.navigationStableId)
	}

	@Test
	fun `rank tokens override adaptive custom background identity`() {
		val definition = RankThemeRegistry.resolveOrDefault(RankThemeId.NEON_ARCHIVE.stableId)
		val tokens = definition.tokens(RankThemeVariant.DARK)
		val exclusiveTheme = ExclusiveThemeContractResolver.resolve(definition, RankThemeVariant.DARK)
		val colors = miyorareThemeColors(
			preset = MiyorareThemePreset.CUSTOM,
			customAccent = "#123456",
			adaptivePalette = MiyorareAdaptivePalette(
				primaryArgb = 0xFF111111.toInt(),
				secondaryArgb = 0xFF222222.toInt(),
				tertiaryArgb = 0xFF333333.toInt(),
			),
			darkTheme = true,
			amoled = false,
			effectLevel = VisualEffectLevel.BALANCED,
			exclusiveTheme = exclusiveTheme,
		)

		assertEquals(Color(tokens.background.toInt()), colors.colorScheme.background)
		assertFalse(colors.visualPalette.adaptiveCustomBackground)
	}
}
