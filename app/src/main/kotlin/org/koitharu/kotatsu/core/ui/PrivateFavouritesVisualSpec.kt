package org.koitharu.kotatsu.core.ui

import android.content.Context
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.core.graphics.ColorUtils
import androidx.preference.PreferenceManager
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.MiyorareAppearance
import org.koitharu.kotatsu.core.prefs.MiyorareThemePreset
import org.koitharu.kotatsu.core.prefs.PrivateFavouritesThemePreset

/**
 * Authored visual identity for an explicit Private Favourites theme.
 *
 * FOLLOW_NORMAL intentionally has no spec: it stays on the exact Normal renderer/palette path.
 * Explicit Private themes own their artwork and palette seeds, so their identity is never produced
 * by tinting or hue-shifting Normal Favourites artwork at runtime.
 */
data class PrivateFavouritesVisualSpec(
	val theme: PrivateFavouritesThemePreset,
	val palettePreset: MiyorareThemePreset,
	@DrawableRes val artworkRes: Int,
	@ColorInt val background: Int,
	@ColorInt val surface: Int,
	@ColorInt val surfaceHigh: Int,
	@ColorInt val primary: Int,
	@ColorInt val onPrimary: Int,
	@ColorInt val secondary: Int,
	@ColorInt val accent: Int,
	@ColorInt val onSurface: Int,
	@ColorInt val onSurfaceVariant: Int,
	@ColorInt val outline: Int,
	@ColorInt val scrim: Int,
	val focalX: Float,
	val zoom: Float,
	val verticalShift: Float,
)

object PrivateFavouritesVisualResolver {

	fun resolve(theme: PrivateFavouritesThemePreset): PrivateFavouritesVisualSpec? = when (theme) {
		PrivateFavouritesThemePreset.FOLLOW_NORMAL -> null
		PrivateFavouritesThemePreset.MIYORARE -> PrivateFavouritesVisualSpec(
			theme = theme,
			palettePreset = MiyorareThemePreset.MIYORARE,
			artworkRes = R.drawable.miyorare_favourites_private_miyorare,
			background = 0xFF050818.toInt(),
			surface = 0xFF0B1027.toInt(),
			surfaceHigh = 0xFF111B3B.toInt(),
			primary = 0xFF6B83FF.toInt(),
			onPrimary = 0xFFFFFFFF.toInt(),
			secondary = 0xFF28D5EC.toInt(),
			accent = 0xFF9A6CFF.toInt(),
			onSurface = 0xFFF3F6FF.toInt(),
			onSurfaceVariant = 0xFFBCC7EA.toInt(),
			outline = 0xFF455A8B.toInt(),
			scrim = 0x66030816,
			focalX = 0.50f,
			zoom = 1.00f,
			verticalShift = 0.000f,
		)
		PrivateFavouritesThemePreset.SAKURA -> PrivateFavouritesVisualSpec(
			theme = theme,
			palettePreset = MiyorareThemePreset.SAKURA,
			artworkRes = R.drawable.miyorare_favourites_private_sakura,
			background = 0xFF160711.toInt(),
			surface = 0xFF24101C.toInt(),
			surfaceHigh = 0xFF351527.toInt(),
			primary = 0xFFFF62AE.toInt(),
			onPrimary = 0xFF250611.toInt(),
			secondary = 0xFFFF829F.toInt(),
			accent = 0xFFD94786.toInt(),
			onSurface = 0xFFFFF1F8.toInt(),
			onSurfaceVariant = 0xFFE6B7CD.toInt(),
			outline = 0xFF7C3D5F.toInt(),
			scrim = 0x60110610,
			focalX = 0.50f,
			zoom = 1.00f,
			verticalShift = 0.000f,
		)
		PrivateFavouritesThemePreset.VIOLET -> PrivateFavouritesVisualSpec(
			theme = theme,
			palettePreset = MiyorareThemePreset.VIOLET,
			artworkRes = R.drawable.miyorare_favourites_private_violet,
			background = 0xFF0D0718.toInt(),
			surface = 0xFF17102B.toInt(),
			surfaceHigh = 0xFF251844.toInt(),
			primary = 0xFFA273FF.toInt(),
			onPrimary = 0xFFFFFFFF.toInt(),
			secondary = 0xFFC47CFF.toInt(),
			accent = 0xFF6E63FF.toInt(),
			onSurface = 0xFFF8F1FF.toInt(),
			onSurfaceVariant = 0xFFD1BDEA.toInt(),
			outline = 0xFF654C86.toInt(),
			scrim = 0x620A0614,
			focalX = 0.50f,
			zoom = 1.00f,
			verticalShift = 0.000f,
		)
		PrivateFavouritesThemePreset.CYAN -> PrivateFavouritesVisualSpec(
			theme = theme,
			palettePreset = MiyorareThemePreset.CYAN,
			artworkRes = R.drawable.miyorare_favourites_private_cyan,
			background = 0xFF041315.toInt(),
			surface = 0xFF082329.toInt(),
			surfaceHigh = 0xFF0B343C.toInt(),
			primary = 0xFF3DE6EE.toInt(),
			onPrimary = 0xFF031315.toInt(),
			secondary = 0xFF51BFFF.toInt(),
			accent = 0xFF28CDB9.toInt(),
			onSurface = 0xFFE9FEFF.toInt(),
			onSurfaceVariant = 0xFFA9D5D8.toInt(),
			outline = 0xFF347078.toInt(),
			scrim = 0x60031114,
			focalX = 0.50f,
			zoom = 1.00f,
			verticalShift = 0.000f,
		)
		PrivateFavouritesThemePreset.EMERALD -> PrivateFavouritesVisualSpec(
			theme = theme,
			palettePreset = MiyorareThemePreset.EMERALD,
			artworkRes = R.drawable.miyorare_favourites_private_emerald,
			background = 0xFF06140F.toInt(),
			surface = 0xFF0A251B.toInt(),
			surfaceHigh = 0xFF0F3728.toInt(),
			primary = 0xFF4BDAA4.toInt(),
			onPrimary = 0xFF04150E.toInt(),
			secondary = 0xFF7DE0A9.toInt(),
			accent = 0xFF31AF80.toInt(),
			onSurface = 0xFFECFFF6.toInt(),
			onSurfaceVariant = 0xFFADD8C4.toInt(),
			outline = 0xFF3B715B.toInt(),
			scrim = 0x6005120D,
			focalX = 0.50f,
			zoom = 1.00f,
			verticalShift = 0.000f,
		)
		PrivateFavouritesThemePreset.AMBER -> PrivateFavouritesVisualSpec(
			theme = theme,
			palettePreset = MiyorareThemePreset.AMBER,
			artworkRes = R.drawable.miyorare_favourites_private_amber,
			background = 0xFF171006.toInt(),
			surface = 0xFF291B0B.toInt(),
			surfaceHigh = 0xFF3C290F.toInt(),
			primary = 0xFFFFB84C.toInt(),
			onPrimary = 0xFF211303.toInt(),
			secondary = 0xFFF1D37B.toInt(),
			accent = 0xFFD98A2A.toInt(),
			onSurface = 0xFFFFF7E9.toInt(),
			onSurfaceVariant = 0xFFE1C7A2.toInt(),
			outline = 0xFF82613A.toInt(),
			scrim = 0x60130D05,
			focalX = 0.50f,
			zoom = 1.00f,
			verticalShift = 0.000f,
		)
	}

	fun readTheme(context: Context): PrivateFavouritesThemePreset {
		val prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
		return prefs.getString(MiyorareAppearance.KEY_PRIVATE_FAVOURITES_THEME, null)
			?.let { value -> PrivateFavouritesThemePreset.entries.firstOrNull { it.name == value } }
			?: PrivateFavouritesThemePreset.FOLLOW_NORMAL
	}
}

internal fun Context.privateFavouritesThemeFromPreferences(): PrivateFavouritesThemePreset =
	PrivateFavouritesVisualResolver.readTheme(this)

internal fun Context.privateFavouritesVisualSpecFromPreferences(): PrivateFavouritesVisualSpec? =
	PrivateFavouritesVisualResolver.resolve(privateFavouritesThemeFromPreferences())

internal fun MiyorareViewPalette.applyPrivateFavouritesVisualSpec(
	spec: PrivateFavouritesVisualSpec,
): MiyorareViewPalette {
	fun blend(first: Int, second: Int, ratio: Float) = ColorUtils.blendARGB(first, second, ratio)
	val container = blend(spec.surface, spec.background, 0.18f)
	val containerHigh = blend(spec.surfaceHigh, spec.primary, 0.06f)
	val primaryContainer = blend(spec.surfaceHigh, spec.primary, 0.34f)
	val outlineVariant = blend(spec.outline, spec.background, 0.34f)
	return copy(
		preset = spec.palettePreset,
		background = spec.background,
		surface = spec.surface,
		surfaceContainer = container,
		surfaceContainerHigh = containerHigh,
		primary = spec.primary,
		onPrimary = spec.onPrimary,
		primaryContainer = primaryContainer,
		onPrimaryContainer = spec.onSurface,
		secondary = spec.secondary,
		accent = spec.accent,
		onSurface = spec.onSurface,
		onSurfaceVariant = spec.onSurfaceVariant,
		outline = spec.outline,
		outlineVariant = outlineVariant,
		selectedSurface = blend(containerHigh, spec.primary, 0.18f),
		borderHighlight = ColorUtils.setAlphaComponent(spec.primary, 0xB8),
		glow = ColorUtils.setAlphaComponent(spec.primary, 0x78),
		button = blend(spec.primary, spec.surfaceHigh, 0.16f),
		onButton = spec.onPrimary,
		backgroundGradientStart = spec.background,
		backgroundGradientMiddle = blend(spec.background, spec.secondary, 0.08f),
		backgroundGradientEnd = blend(spec.background, spec.surface, 0.46f),
		surfaceGradientStart = spec.surfaceHigh,
		surfaceGradientMiddle = spec.surface,
		surfaceGradientEnd = spec.background,
		activeGradientStart = primaryContainer,
		activeGradientEnd = blend(primaryContainer, spec.accent, 0.22f),
	)
}
