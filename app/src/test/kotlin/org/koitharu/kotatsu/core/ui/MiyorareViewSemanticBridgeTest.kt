package org.koitharu.kotatsu.core.ui

import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.koitharu.kotatsu.core.prefs.MiyorareAppearance
import org.koitharu.kotatsu.core.prefs.MiyorareModernPaletteSource
import org.koitharu.kotatsu.core.prefs.MiyorareThemePreset
import org.koitharu.kotatsu.core.prefs.VisualEffectLevel
import androidx.appcompat.R as appcompatR
import com.google.android.material.R as materialR

class MiyorareViewSemanticBridgeTest {

	@Test
	fun `Android View attrs map to the same resolved semantic roles as Compose`() {
		for (dark in listOf(false, true)) {
			val scheme = resolveMiyorareThemeColors(
				paletteSource = MiyorareModernPaletteSource.MATCH_THEME,
				preset = MiyorareThemePreset.SAKURA,
				customAccent = MiyorareAppearance.DEFAULT_CUSTOM_ACCENT,
				darkTheme = dark,
				amoled = false,
				effectLevel = VisualEffectLevel.BALANCED,
			).colorScheme

			val expected = listOf(
				android.R.attr.colorBackground to scheme.background.toArgb(),
				appcompatR.attr.colorPrimary to scheme.primary.toArgb(),
				appcompatR.attr.colorAccent to scheme.primary.toArgb(),
				materialR.attr.colorOnPrimary to scheme.onPrimary.toArgb(),
				materialR.attr.colorPrimaryContainer to scheme.primaryContainer.toArgb(),
				materialR.attr.colorOnPrimaryContainer to scheme.onPrimaryContainer.toArgb(),
				materialR.attr.colorPrimaryInverse to scheme.inversePrimary.toArgb(),
				materialR.attr.colorSecondary to scheme.secondary.toArgb(),
				materialR.attr.colorOnSecondary to scheme.onSecondary.toArgb(),
				materialR.attr.colorSecondaryContainer to scheme.secondaryContainer.toArgb(),
				materialR.attr.colorOnSecondaryContainer to scheme.onSecondaryContainer.toArgb(),
				materialR.attr.colorTertiary to scheme.tertiary.toArgb(),
				materialR.attr.colorOnTertiary to scheme.onTertiary.toArgb(),
				materialR.attr.colorTertiaryContainer to scheme.tertiaryContainer.toArgb(),
				materialR.attr.colorOnTertiaryContainer to scheme.onTertiaryContainer.toArgb(),
				materialR.attr.colorSurface to scheme.surface.toArgb(),
				materialR.attr.colorSurfaceDim to scheme.surfaceDim.toArgb(),
				materialR.attr.colorSurfaceBright to scheme.surfaceBright.toArgb(),
				materialR.attr.colorSurfaceContainerLowest to scheme.surfaceContainerLowest.toArgb(),
				materialR.attr.colorSurfaceContainerLow to scheme.surfaceContainerLow.toArgb(),
				materialR.attr.colorSurfaceContainer to scheme.surfaceContainer.toArgb(),
				materialR.attr.colorSurfaceContainerHigh to scheme.surfaceContainerHigh.toArgb(),
				materialR.attr.colorSurfaceContainerHighest to scheme.surfaceContainerHighest.toArgb(),
				materialR.attr.colorSurfaceVariant to scheme.surfaceVariant.toArgb(),
				materialR.attr.colorOnSurface to scheme.onSurface.toArgb(),
				materialR.attr.colorOnSurfaceVariant to scheme.onSurfaceVariant.toArgb(),
				materialR.attr.colorSurfaceInverse to scheme.inverseSurface.toArgb(),
				materialR.attr.colorOnSurfaceInverse to scheme.inverseOnSurface.toArgb(),
				materialR.attr.colorOnBackground to scheme.onBackground.toArgb(),
				materialR.attr.colorError to scheme.error.toArgb(),
				materialR.attr.colorErrorContainer to scheme.errorContainer.toArgb(),
				materialR.attr.colorOnError to scheme.onError.toArgb(),
				materialR.attr.colorOnErrorContainer to scheme.onErrorContainer.toArgb(),
				materialR.attr.colorOutline to scheme.outline.toArgb(),
				materialR.attr.colorOutlineVariant to scheme.outlineVariant.toArgb(),
			)

			for ((attr, color) in expected) {
				assertEquals("attr=$attr dark=$dark", color, scheme.miyorareColorForViewAttr(attr))
			}
			assertNull(scheme.miyorareColorForViewAttr(android.R.attr.textColor))
		}
	}
}
