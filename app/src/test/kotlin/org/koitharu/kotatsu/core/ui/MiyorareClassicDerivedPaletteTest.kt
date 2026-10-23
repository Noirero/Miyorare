package org.koitharu.kotatsu.core.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.koitharu.kotatsu.core.prefs.ColorScheme as ClassicColorScheme
import org.koitharu.kotatsu.core.prefs.MiyorareAppearance
import org.koitharu.kotatsu.core.prefs.MiyorareModernPaletteSource
import org.koitharu.kotatsu.core.prefs.MiyorareThemePreset
import org.koitharu.kotatsu.core.prefs.VisualEffectLevel

class MiyorareClassicDerivedPaletteTest {

	@Test
	fun `each Classic scheme has a distinct Modern palette source identity`() {
		for (classic in ClassicColorScheme.entries) {
			val source = MiyorareModernPaletteSource.fromClassicScheme(classic)
			checkNotNull(source)
			assertEquals(classic, source.classicScheme)
		}
		assertEquals(ClassicColorScheme.SAKURA, MiyorareModernPaletteSource.CLASSIC_SAKURA.classicScheme)
		assertEquals(ClassicColorScheme.AMBER, MiyorareModernPaletteSource.CLASSIC_AMBER.classicScheme)
	}

	@Test
	fun `unavailable Expressive stored source falls back to Match Theme`() {
		val availableWithoutDynamic = ClassicColorScheme.entries.filterNot {
			it == ClassicColorScheme.EXPRESSIVE
		}.toSet()
		assertEquals(
			MiyorareModernPaletteSource.MATCH_THEME,
			MiyorareModernPaletteSource.CLASSIC_EXPRESSIVE.normalizeForAvailableClassic(availableWithoutDynamic),
		)
		assertEquals(
			MiyorareModernPaletteSource.CLASSIC_KANADE,
			MiyorareModernPaletteSource.CLASSIC_KANADE.normalizeForAvailableClassic(availableWithoutDynamic),
		)
	}

	@Test
	fun `Classic semantic source recolours Modern visual tokens while preserving mechanics`() {
		val authored = resolve(
			paletteSource = MiyorareModernPaletteSource.MATCH_THEME,
			preset = MiyorareThemePreset.MIYORARE,
		)
		val classicScheme = lightColorScheme(
			primary = Color(0xFF8D352C),
			onPrimary = Color.White,
			primaryContainer = Color(0xFFFFDAD4),
			secondary = Color(0xFF755651),
			secondaryContainer = Color(0xFFFFDAD4),
			tertiary = Color(0xFF6F5C2E),
			background = Color(0xFFFFF8F6),
			surface = Color(0xFFFFF8F6),
			surfaceContainer = Color(0xFFFCEAE6),
			surfaceContainerHigh = Color(0xFFF6E4E0),
			outline = Color(0xFF85736F),
		)
		val asuka = resolve(
			paletteSource = MiyorareModernPaletteSource.CLASSIC_ASUKA,
			preset = MiyorareThemePreset.MIYORARE,
			classicScheme = classicScheme,
		)
		val visual = asuka.visualPalette

		assertEquals(classicScheme.primary, asuka.colorScheme.primary)
		assertEquals(classicScheme.background, asuka.colorScheme.background)
		assertNotEquals(authored.colorScheme.primary, asuka.colorScheme.primary)
		assertNotEquals(authored.visualPalette, visual)
		assertEquals(classicScheme.primary, visual.primary)
		assertEquals(classicScheme.secondary, visual.secondary)
		assertEquals(classicScheme.tertiary, visual.accent)
		assertEquals(classicScheme.primaryContainer, visual.selectedSurface)
		assertEquals(classicScheme.secondaryContainer, visual.chip)
		assertEquals(classicScheme.outline, visual.border)
		assertEquals(classicScheme.primary, visual.button)
		assertEquals(classicScheme.onPrimary, visual.onButton)
		assertEquals(classicScheme.primary, visual.accentGradientStart)
		assertNotEquals(authored.visualPalette.surfaceGradientStart, visual.surfaceGradientStart)
		assertEquals(authored.visualPalette.isModern, visual.isModern)
		assertEquals(authored.visualPalette.effectLevel, visual.effectLevel)
		assertEquals(authored.visualPalette.gradientStrength, visual.gradientStrength)
		assertEquals(authored.visualPalette.adaptiveCustomBackground, visual.adaptiveCustomBackground)
	}

	@Test
	fun `Classic-derived dark visual tokens follow the resolved dark scheme`() {
		val classicDark = darkColorScheme(
			primary = Color(0xFF8AD6C8),
			onPrimary = Color(0xFF003731),
			primaryContainer = Color(0xFF145047),
			secondary = Color(0xFFB3CCC5),
			secondaryContainer = Color(0xFF344B46),
			tertiary = Color(0xFFD2C08E),
			background = Color(0xFF0F1513),
			surface = Color(0xFF0F1513),
			surfaceContainer = Color(0xFF1B211F),
			surfaceContainerHigh = Color(0xFF252B29),
			outline = Color(0xFF89938F),
		)
		val resolved = resolve(
			paletteSource = MiyorareModernPaletteSource.CLASSIC_MIKU,
			preset = MiyorareThemePreset.MIYORARE,
			classicScheme = classicDark,
			darkTheme = true,
		)
		val visual = resolved.visualPalette

		assertEquals(classicDark.primary, visual.primary)
		assertEquals(classicDark.secondary, visual.secondary)
		assertEquals(classicDark.tertiary, visual.accent)
		assertEquals(classicDark.primaryContainer, visual.selectedSurface)
		assertEquals(classicDark.secondaryContainer, visual.chip)
		assertEquals(classicDark.outline, visual.border)
		assertEquals(classicDark.primary, visual.accentGradientStart)
	}

	@Test
	fun `switching back to Match Theme restores authored Modern semantics`() {
		val foreign = lightColorScheme(primary = Color(0xFF010203))
		val expected = resolve(
			paletteSource = MiyorareModernPaletteSource.MATCH_THEME,
			preset = MiyorareThemePreset.SAKURA,
		)
		val restored = resolveMiyorareThemeColors(
			paletteSource = MiyorareModernPaletteSource.MATCH_THEME,
			preset = MiyorareThemePreset.SAKURA,
			customAccent = MiyorareAppearance.DEFAULT_CUSTOM_ACCENT,
			darkTheme = false,
			amoled = false,
			effectLevel = VisualEffectLevel.BALANCED,
			classicDerivedColorScheme = foreign,
		)
		assertThemeSemanticallyEquals(expected, restored)
	}

	@Test
	fun `missing Classic resolution safely falls back to authored Modern palette`() {
		val expected = resolve(
			paletteSource = MiyorareModernPaletteSource.MATCH_THEME,
			preset = MiyorareThemePreset.AMBER,
		)
		val fallback = resolve(
			paletteSource = MiyorareModernPaletteSource.CLASSIC_AMBER,
			preset = MiyorareThemePreset.AMBER,
			classicScheme = null,
		)
		assertThemeSemanticallyEquals(expected, fallback)
	}

	@Test
	fun `Classic-derived AMOLED preserves Modern true-black foundation`() {
		val classicDark = darkColorScheme(
			primary = Color(0xFFA6C8FF),
			background = Color(0xFF111318),
			surface = Color(0xFF111318),
			surfaceContainer = Color(0xFF1D2024),
			surfaceContainerHigh = Color(0xFF272A2F),
		)
		val resolved = resolveMiyorareThemeColors(
			paletteSource = MiyorareModernPaletteSource.CLASSIC_TOTORO,
			preset = MiyorareThemePreset.MIYORARE,
			customAccent = MiyorareAppearance.DEFAULT_CUSTOM_ACCENT,
			darkTheme = true,
			amoled = true,
			effectLevel = VisualEffectLevel.BALANCED,
			classicDerivedColorScheme = classicDark,
		)
		val scheme = resolved.colorScheme
		val visual = resolved.visualPalette

		assertEquals(Color.Black, scheme.background)
		assertEquals(Color.Black, scheme.surface)
		assertEquals(Color.Black, scheme.surfaceDim)
		assertEquals(Color.Black, scheme.surfaceContainerLowest)
		assertEquals(Color.Black, scheme.surfaceContainerLow)
		assertEquals(Color.Black, scheme.surfaceContainer)
		assertEquals(Color(0xFF080808), scheme.surfaceContainerHigh)
		assertEquals(Color(0xFF101010), scheme.surfaceContainerHighest)
		assertEquals(classicDark.primary, scheme.primary)
		assertEquals(Color.Black, visual.backgroundGradientStart)
		assertEquals(Color.Black, visual.backgroundGradientMiddle)
		assertEquals(Color.Black, visual.backgroundGradientEnd)
		assertEquals(classicDark.primary, visual.primary)
	}

	private fun resolve(
		paletteSource: MiyorareModernPaletteSource,
		preset: MiyorareThemePreset,
		classicScheme: ColorScheme? = null,
		darkTheme: Boolean = false,
		amoled: Boolean = false,
	) = resolveMiyorareThemeColors(
		paletteSource = paletteSource,
		preset = preset,
		customAccent = MiyorareAppearance.DEFAULT_CUSTOM_ACCENT,
		darkTheme = darkTheme,
		amoled = amoled,
		effectLevel = VisualEffectLevel.BALANCED,
		classicDerivedColorScheme = classicScheme,
	)

	private fun assertThemeSemanticallyEquals(expected: MiyorareThemeColors, actual: MiyorareThemeColors) {
		assertEquals(expected.visualPalette, actual.visualPalette)
		assertEquals(semanticRoles(expected.colorScheme), semanticRoles(actual.colorScheme))
	}

	private fun semanticRoles(scheme: ColorScheme): List<Color> = listOf(
		scheme.primary,
		scheme.onPrimary,
		scheme.primaryContainer,
		scheme.onPrimaryContainer,
		scheme.inversePrimary,
		scheme.secondary,
		scheme.onSecondary,
		scheme.secondaryContainer,
		scheme.onSecondaryContainer,
		scheme.tertiary,
		scheme.onTertiary,
		scheme.tertiaryContainer,
		scheme.onTertiaryContainer,
		scheme.error,
		scheme.onError,
		scheme.errorContainer,
		scheme.onErrorContainer,
		scheme.background,
		scheme.onBackground,
		scheme.surface,
		scheme.onSurface,
		scheme.surfaceVariant,
		scheme.onSurfaceVariant,
		scheme.outline,
		scheme.outlineVariant,
		scheme.inverseSurface,
		scheme.inverseOnSurface,
		scheme.surfaceTint,
		scheme.scrim,
		scheme.surfaceBright,
		scheme.surfaceDim,
		scheme.surfaceContainerLowest,
		scheme.surfaceContainerLow,
		scheme.surfaceContainer,
		scheme.surfaceContainerHigh,
		scheme.surfaceContainerHighest,
	)
}
