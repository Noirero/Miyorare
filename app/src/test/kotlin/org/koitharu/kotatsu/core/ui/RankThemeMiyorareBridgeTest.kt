package org.koitharu.kotatsu.core.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.koitharu.kotatsu.core.prefs.MiyorareAdaptivePalette
import org.koitharu.kotatsu.core.prefs.MiyorareAppearance
import org.koitharu.kotatsu.core.prefs.MiyorareThemePreset
import org.koitharu.kotatsu.core.prefs.VisualEffectLevel
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
			rankThemeTokens = null,
		)

		assertEquals(normal, explicitNull)
	}

	@Test
	fun `rank light tokens own authored background surface and semantic statuses`() {
		val tokens = RankThemeRegistry.resolveOrDefault(RankThemeId.FIRST_PAGE.stableId)
			.tokens(RankThemeVariant.LIGHT)
		val colors = miyorareThemeColors(
			preset = MiyorareThemePreset.MIYORARE,
			customAccent = MiyorareAppearance.DEFAULT_CUSTOM_ACCENT,
			darkTheme = false,
			amoled = false,
			effectLevel = VisualEffectLevel.BALANCED,
			rankThemeTokens = tokens,
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
		val dark = miyorareThemeColors(
			preset = MiyorareThemePreset.MIYORARE,
			customAccent = MiyorareAppearance.DEFAULT_CUSTOM_ACCENT,
			darkTheme = true,
			amoled = false,
			effectLevel = VisualEffectLevel.FULL,
			rankThemeTokens = darkTokens,
		)
		assertEquals(Color(darkTokens.background.toInt()), dark.colorScheme.background)
		assertEquals(Color(darkTokens.surface.toInt()), dark.colorScheme.surface)

		val oledTokens = definition.tokens(RankThemeVariant.OLED)
		val oled = miyorareThemeColors(
			preset = MiyorareThemePreset.MIYORARE,
			customAccent = MiyorareAppearance.DEFAULT_CUSTOM_ACCENT,
			darkTheme = true,
			amoled = true,
			effectLevel = VisualEffectLevel.FULL,
			rankThemeTokens = oledTokens,
		)
		assertEquals(Color.Black, oled.colorScheme.background)
		assertEquals(Color.Black, oled.colorScheme.surfaceContainer)
	}

	@Test
	fun `rank tokens override adaptive custom background identity`() {
		val tokens = RankThemeRegistry.resolveOrDefault(RankThemeId.NEON_ARCHIVE.stableId)
			.tokens(RankThemeVariant.DARK)
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
			rankThemeTokens = tokens,
		)

		assertEquals(Color(tokens.background.toInt()), colors.colorScheme.background)
		assertFalse(colors.visualPalette.adaptiveCustomBackground)
	}
}
