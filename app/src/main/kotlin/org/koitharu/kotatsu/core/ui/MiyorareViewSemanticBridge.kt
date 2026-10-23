package org.koitharu.kotatsu.core.ui

import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.toArgb
import androidx.appcompat.R as appcompatR
import com.google.android.material.R as materialR

/**
 * Material attribute bridge for Android Views. Keep this mapping on the same [ColorScheme] produced
 * by the canonical Modern resolver so Compose and Views cannot silently diverge by role.
 */
@ColorInt
internal fun ColorScheme.miyorareColorForViewAttr(@AttrRes resId: Int): Int? = when (resId) {
	android.R.attr.colorBackground -> background.toArgb()
	appcompatR.attr.colorPrimary -> primary.toArgb()
	appcompatR.attr.colorAccent -> primary.toArgb()
	appcompatR.attr.colorError -> error.toArgb()
	materialR.attr.colorOnPrimary -> onPrimary.toArgb()
	materialR.attr.colorPrimaryContainer -> primaryContainer.toArgb()
	materialR.attr.colorOnPrimaryContainer -> onPrimaryContainer.toArgb()
	materialR.attr.colorPrimaryInverse -> inversePrimary.toArgb()
	materialR.attr.colorSecondary -> secondary.toArgb()
	materialR.attr.colorOnSecondary -> onSecondary.toArgb()
	materialR.attr.colorSecondaryContainer -> secondaryContainer.toArgb()
	materialR.attr.colorOnSecondaryContainer -> onSecondaryContainer.toArgb()
	materialR.attr.colorTertiary -> tertiary.toArgb()
	materialR.attr.colorOnTertiary -> onTertiary.toArgb()
	materialR.attr.colorTertiaryContainer -> tertiaryContainer.toArgb()
	materialR.attr.colorOnTertiaryContainer -> onTertiaryContainer.toArgb()
	materialR.attr.colorSurface -> surface.toArgb()
	materialR.attr.colorSurfaceDim -> surfaceDim.toArgb()
	materialR.attr.colorSurfaceBright -> surfaceBright.toArgb()
	materialR.attr.colorSurfaceContainerLowest -> surfaceContainerLowest.toArgb()
	materialR.attr.colorSurfaceContainerLow -> surfaceContainerLow.toArgb()
	materialR.attr.colorSurfaceContainer -> surfaceContainer.toArgb()
	materialR.attr.colorSurfaceContainerHigh -> surfaceContainerHigh.toArgb()
	materialR.attr.colorSurfaceContainerHighest -> surfaceContainerHighest.toArgb()
	materialR.attr.colorSurfaceVariant -> surfaceVariant.toArgb()
	materialR.attr.colorOnSurface -> onSurface.toArgb()
	materialR.attr.colorOnSurfaceVariant -> onSurfaceVariant.toArgb()
	materialR.attr.colorSurfaceInverse -> inverseSurface.toArgb()
	materialR.attr.colorOnSurfaceInverse -> inverseOnSurface.toArgb()
	materialR.attr.colorOnBackground -> onBackground.toArgb()
	materialR.attr.colorErrorContainer -> errorContainer.toArgb()
	materialR.attr.colorOnError -> onError.toArgb()
	materialR.attr.colorOnErrorContainer -> onErrorContainer.toArgb()
	materialR.attr.colorOutline -> outline.toArgb()
	materialR.attr.colorOutlineVariant -> outlineVariant.toArgb()
	else -> null
}
