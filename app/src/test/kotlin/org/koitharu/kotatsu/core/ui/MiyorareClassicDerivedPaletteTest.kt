package org.koitharu.kotatsu.core.ui

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
	fun `Classic semantic source changes Material colours without changing Modern visual identity`() {
		val authored = resolve(
			paletteSource = MiyorareModernPaletteSource.MATCH_THEME,
			preset = MiyorareThemePreset.MIYORARE,
		)
		val classicScheme = lightColorScheme(
			primary = Color(0xFF353543),
			secondary = Color(0xFF353636),
			tertiary = Color(0xFF333737),
			background = Color(0xFFFCF8F9),
			surface = Color(0xFFFCF8F9),
		)
		val kanade = resolve(
			paletteSource = MiyorareModernPaletteSource.CLASSIC_KANADE,
			preset = MiyorareThemePreset.MIYORARE,
			classicScheme = classicScheme,
		)

		assertEquals(classicScheme.primary, kanade.colorScheme.primary)
		assertEquals(classicScheme.background, kanade.colorScheme.background)
		assertNotEquals(authored.colorScheme.primary, kanade.colorScheme.primary)
		assertEquals(authored.visualPalette, kanade.visualPalette)
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
		assertEquals(expected, restored)
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
		assertEquals(expected, fallback)
	}

	@Test
	fun `Classic-derived AMOLED preserves Modern true-black foundation`() {
		val classicDark = lightColorScheme(
			primary = Color(0xFFA6C8FF),
			background = Color(0xFF111318),
			surface = Color(0xFF111318),
			surfaceContainer = Color(0xFF1D2024),
		)
		val resolved = resolveMiyorareThemeColors(
			paletteSource = MiyorareModernPaletteSource.CLASSIC_TOTORO,
			preset = MiyorareThemePreset.MIYORARE,
			customAccent = MiyorareAppearance.DEFAULT_CUSTOM_ACCENT,
			darkTheme = true,
			amoled = true,
			effectLevel = VisualEffectLevel.BALANCED,
			classicDerivedColorScheme = classicDark,
		).colorScheme

		assertEquals(Color.Black, resolved.background)
		assertEquals(Color.Black, resolved.surface)
		assertEquals(Color.Black, resolved.surfaceDim)
		assertEquals(Color.Black, resolved.surfaceContainerLowest)
		assertEquals(Color.Black, resolved.surfaceContainerLow)
		assertEquals(Color.Black, resolved.surfaceContainer)
		assertEquals(Color(0xFF080808), resolved.surfaceContainerHigh)
		assertEquals(Color(0xFF101010), resolved.surfaceContainerHighest)
		assertEquals(classicDark.primary, resolved.primary)
	}

	private fun resolve(
		paletteSource: MiyorareModernPaletteSource,
		preset: MiyorareThemePreset,
		classicScheme: androidx.compose.material3.ColorScheme? = null,
	) = resolveMiyorareThemeColors(
		paletteSource = paletteSource,
		preset = preset,
		customAccent = MiyorareAppearance.DEFAULT_CUSTOM_ACCENT,
		darkTheme = false,
		amoled = false,
		effectLevel = VisualEffectLevel.BALANCED,
		classicDerivedColorScheme = classicScheme,
	)
}
