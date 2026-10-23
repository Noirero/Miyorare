package org.koitharu.kotatsu.core.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.core.prefs.MiyorareAppearance
import org.koitharu.kotatsu.core.prefs.MiyorareModernPaletteSource
import org.koitharu.kotatsu.core.prefs.MiyorareThemePreset
import org.koitharu.kotatsu.core.prefs.VisualEffectLevel

class MiyorareSemanticCompletenessTest {

	@Test
	fun `semantic completion preserves authored core palette and decorative identity`() {
		for (preset in MiyorareThemePreset.entries) {
			for (dark in listOf(false, true)) {
				val raw = raw(preset, dark)
				val resolved = resolved(preset, dark)

				assertEquals("$preset primary dark=$dark", raw.colorScheme.primary, resolved.colorScheme.primary)
				assertEquals("$preset secondary dark=$dark", raw.colorScheme.secondary, resolved.colorScheme.secondary)
				assertEquals("$preset tertiary dark=$dark", raw.colorScheme.tertiary, resolved.colorScheme.tertiary)
				assertEquals("$preset primaryContainer dark=$dark", raw.colorScheme.primaryContainer, resolved.colorScheme.primaryContainer)
				assertEquals("$preset secondaryContainer dark=$dark", raw.colorScheme.secondaryContainer, resolved.colorScheme.secondaryContainer)
				assertEquals("$preset background dark=$dark", raw.colorScheme.background, resolved.colorScheme.background)
				assertEquals("$preset surface dark=$dark", raw.colorScheme.surface, resolved.colorScheme.surface)
				assertEquals("$preset surfaceVariant dark=$dark", raw.colorScheme.surfaceVariant, resolved.colorScheme.surfaceVariant)
				assertEquals("$preset surfaceContainer dark=$dark", raw.colorScheme.surfaceContainer, resolved.colorScheme.surfaceContainer)
				assertEquals("$preset surfaceContainerHigh dark=$dark", raw.colorScheme.surfaceContainerHigh, resolved.colorScheme.surfaceContainerHigh)
				assertEquals("$preset outline dark=$dark", raw.colorScheme.outline, resolved.colorScheme.outline)
				assertEquals("$preset outlineVariant dark=$dark", raw.colorScheme.outlineVariant, resolved.colorScheme.outlineVariant)
				assertEquals("$preset visual palette dark=$dark", raw.visualPalette, resolved.visualPalette)
			}
		}
	}

	@Test
	fun `fixed semantic families stay identical between light and dark`() {
		for (preset in MiyorareThemePreset.entries) {
			val light = resolved(preset, dark = false).colorScheme
			val dark = resolved(preset, dark = true).colorScheme

			assertFixedFamilyEquals("$preset primary", light, dark, Family.PRIMARY)
			assertFixedFamilyEquals("$preset secondary", light, dark, Family.SECONDARY)
			assertFixedFamilyEquals("$preset tertiary", light, dark, Family.TERTIARY)
		}
	}

	@Test
	fun `new semantic foreground pairs remain readable`() {
		for (preset in MiyorareThemePreset.entries) {
			for (dark in listOf(false, true)) {
				val scheme = resolved(preset, dark).colorScheme
				assertContrast("$preset tertiaryContainer dark=$dark", scheme.onTertiaryContainer, scheme.tertiaryContainer, 4.5f)
				assertContrast("$preset inverseSurface dark=$dark", scheme.inverseOnSurface, scheme.inverseSurface, 4.5f)
				assertContrast("$preset inversePrimary dark=$dark", scheme.inversePrimary, scheme.inverseSurface, 3f)
				assertContrast("$preset error dark=$dark", scheme.onError, scheme.error, 4.5f)
				assertContrast("$preset errorContainer dark=$dark", scheme.onErrorContainer, scheme.errorContainer, 4.5f)
				assertContrast("$preset primaryFixed dark=$dark", scheme.onPrimaryFixed, scheme.primaryFixed, 4.5f)
				assertContrast("$preset primaryFixedDim dark=$dark", scheme.onPrimaryFixedVariant, scheme.primaryFixedDim, 4.5f)
				assertContrast("$preset secondaryFixed dark=$dark", scheme.onSecondaryFixed, scheme.secondaryFixed, 4.5f)
				assertContrast("$preset tertiaryFixed dark=$dark", scheme.onTertiaryFixed, scheme.tertiaryFixed, 4.5f)
			}
		}
	}

	@Test
	fun `authored presets drive tertiary semantic containers instead of generic defaults`() {
		val sakura = resolved(MiyorareThemePreset.SAKURA, dark = false).colorScheme
		val cyan = resolved(MiyorareThemePreset.CYAN, dark = false).colorScheme
		assertNotEquals(sakura.tertiaryContainer, cyan.tertiaryContainer)
		assertNotEquals(sakura.primaryFixed, cyan.primaryFixed)
	}

	@Test
	fun `amoled keeps foundational surfaces true black`() {
		val scheme = resolveMiyorareThemeColors(
			paletteSource = MiyorareModernPaletteSource.MATCH_THEME,
			preset = MiyorareThemePreset.MIYORARE,
			customAccent = MiyorareAppearance.DEFAULT_CUSTOM_ACCENT,
			darkTheme = true,
			amoled = true,
			effectLevel = VisualEffectLevel.BALANCED,
		).colorScheme

		assertEquals(Color.Black, scheme.background)
		assertEquals(Color.Black, scheme.surface)
		assertEquals(Color.Black, scheme.surfaceContainer)
		assertEquals(Color.Black, scheme.surfaceContainerLow)
		assertEquals(Color.Black, scheme.surfaceContainerLowest)
		assertEquals(Color.Black, scheme.surfaceDim)
	}

	private fun raw(preset: MiyorareThemePreset, dark: Boolean) = miyorareThemeColors(
		preset = preset,
		customAccent = "#A19292",
		darkTheme = dark,
		amoled = dark,
		effectLevel = VisualEffectLevel.BALANCED,
	)

	private fun resolved(preset: MiyorareThemePreset, dark: Boolean) = resolveMiyorareThemeColors(
		paletteSource = MiyorareModernPaletteSource.MATCH_THEME,
		preset = preset,
		customAccent = "#A19292",
		darkTheme = dark,
		amoled = dark,
		effectLevel = VisualEffectLevel.BALANCED,
	)

	private fun assertContrast(label: String, foreground: Color, background: Color, minimum: Float) {
		assertTrue(
			"$label contrast ${semanticContrastRatio(foreground, background)} < $minimum",
			semanticContrastRatio(foreground, background) >= minimum,
		)
	}

	private fun assertFixedFamilyEquals(
		label: String,
		light: ColorScheme,
		dark: ColorScheme,
		family: Family,
	) {
		val lightRoles = family.roles(light)
		val darkRoles = family.roles(dark)
		assertEquals("$label fixed", lightRoles[0], darkRoles[0])
		assertEquals("$label fixedDim", lightRoles[1], darkRoles[1])
		assertEquals("$label onFixed", lightRoles[2], darkRoles[2])
		assertEquals("$label onFixedVariant", lightRoles[3], darkRoles[3])
	}

	private enum class Family {
		PRIMARY,
		SECONDARY,
		TERTIARY,
		;

		fun roles(scheme: ColorScheme): List<Color> = when (this) {
			PRIMARY -> listOf(
				scheme.primaryFixed,
				scheme.primaryFixedDim,
				scheme.onPrimaryFixed,
				scheme.onPrimaryFixedVariant,
			)
			SECONDARY -> listOf(
				scheme.secondaryFixed,
				scheme.secondaryFixedDim,
				scheme.onSecondaryFixed,
				scheme.onSecondaryFixedVariant,
			)
			TERTIARY -> listOf(
				scheme.tertiaryFixed,
				scheme.tertiaryFixedDim,
				scheme.onTertiaryFixed,
				scheme.onTertiaryFixedVariant,
			)
		}
	}
}
