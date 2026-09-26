package org.koitharu.kotatsu.core.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

/**
 * Reusable finite Modern surface treatment. The three-stop brush creates depth with static colour
 * math only; Classic returns unchanged and no continuous blur/shader is introduced.
 */
fun Modifier.miyorareSurface(
	palette: MiyorareVisualPalette,
	shape: Shape,
	selectedFraction: Float = 0f,
	drawBorder: Boolean = true,
): Modifier {
	if (!palette.isModern) return this
	val exclusiveShared = palette.exclusiveTheme?.shared
	val selected = selectedFraction.coerceIn(0f, 1f)
	val start = lerp(palette.surfaceGradientStart, palette.accentGradientStart, selected)
	val middle = lerp(palette.surfaceGradientMiddle, palette.accentGradientMiddle, selected)
	val end = lerp(palette.surfaceGradientEnd, palette.accentGradientEnd, selected)
	val lightSurface = palette.surfaceGradientStart.luminance() >= 0.5f
	val resolvedStart = if (lightSurface) start.copy(alpha = LIGHT_GLASS_START_ALPHA) else start
	val resolvedMiddle = if (lightSurface) middle.copy(alpha = LIGHT_GLASS_MIDDLE_ALPHA) else middle
	val resolvedEnd = if (lightSurface) end.copy(alpha = LIGHT_GLASS_END_ALPHA) else end
	var result = background(
		brush = Brush.horizontalGradient(listOf(resolvedStart, resolvedMiddle, resolvedEnd)),
		shape = shape,
	)
	if (drawBorder) {
		val border = lerp(palette.borderHighlight, palette.glow, selected * 0.72f)
		val resolvedBorder = if (lightSurface) {
			border.copy(alpha = border.alpha * LIGHT_GLASS_BORDER_ALPHA_FACTOR)
		} else {
			border
		}
		result = if (exclusiveShared?.borderStops?.size?.let { it >= 2 } == true) {
			result.border(
				BorderStroke(
					1.dp,
					Brush.horizontalGradient(
						exclusiveShared.borderStops.map { it.copy(alpha = it.alpha * resolvedBorder.alpha) },
					),
				),
				shape,
			)
		} else {
			result.border(1.dp, resolvedBorder, shape)
		}
	}
	return result
}

/** Static three-stop accent gradient for selected controls and primary actions. */
fun Modifier.miyorareAccentSurface(
	palette: MiyorareVisualPalette,
	shape: Shape,
	alpha: Float = 1f,
): Modifier {
	if (!palette.isModern) return this
	val exclusiveShared = palette.exclusiveTheme?.shared
	val safeAlpha = alpha.coerceIn(0f, 1f)
	val accentBrush = if (exclusiveShared?.selectedStops?.size?.let { it >= 2 } == true) {
		Brush.horizontalGradient(exclusiveShared.selectedStops.map { it.copy(alpha = it.alpha * safeAlpha) })
	} else {
		Brush.horizontalGradient(
			listOf(
				palette.accentGradientStart.copy(alpha = safeAlpha),
				palette.accentGradientMiddle.copy(alpha = safeAlpha),
				palette.accentGradientEnd.copy(alpha = safeAlpha),
			),
		)
	}
	val accentBorder = lerp(palette.borderHighlight, palette.glow, 0.58f)
		.copy(alpha = (palette.borderHighlight.alpha + palette.glow.alpha).coerceAtMost(1f) * safeAlpha)
	val decorated = background(
		brush = accentBrush,
		shape = shape,
	)
	return if (exclusiveShared?.borderStops?.size?.let { it >= 2 } == true) {
		decorated.border(
			BorderStroke(
				1.dp,
				Brush.horizontalGradient(
					exclusiveShared.borderStops.map { it.copy(alpha = it.alpha * accentBorder.alpha) },
				),
			),
			shape,
		)
	} else {
		decorated.border(1.dp, accentBorder, shape)
	}
}

/** Small inset surface used by icons so every Modern screen shares one premium icon language. */
fun Modifier.miyorareIconSurface(
	palette: MiyorareVisualPalette,
	shape: Shape,
	alpha: Float = 1f,
): Modifier {
	if (!palette.isModern) return this
	val exclusiveShared = palette.exclusiveTheme?.shared
	val safeAlpha = alpha.coerceIn(0f, 1f)
	val iconBrush = if (exclusiveShared?.iconStops?.size?.let { it >= 2 } == true) {
		Brush.linearGradient(
			exclusiveShared.iconStops.map { signature ->
				lerp(
					palette.selectedSurface,
					signature,
					exclusiveShared.iconMix,
				).copy(alpha = safeAlpha)
			},
		)
	} else {
		Brush.linearGradient(
			listOf(
				palette.iconGradientStart.copy(alpha = safeAlpha),
				palette.selectedSurface.copy(alpha = safeAlpha),
				palette.iconGradientEnd.copy(alpha = safeAlpha),
			),
		)
	}
	val iconBorder = palette.borderHighlight.copy(alpha = palette.borderHighlight.alpha * safeAlpha)
	val decorated = background(
		brush = iconBrush,
		shape = shape,
	)
	return if (exclusiveShared?.borderStops?.size?.let { it >= 2 } == true) {
		decorated.border(
			BorderStroke(
				1.dp,
				Brush.horizontalGradient(
					exclusiveShared.borderStops.map { it.copy(alpha = it.alpha * iconBorder.alpha * 0.86f) },
				),
			),
			shape,
		)
	} else {
		decorated.border(1.dp, iconBorder, shape)
	}
}


private const val LIGHT_GLASS_START_ALPHA = 0.84f
private const val LIGHT_GLASS_MIDDLE_ALPHA = 0.76f
private const val LIGHT_GLASS_END_ALPHA = 0.80f
private const val LIGHT_GLASS_BORDER_ALPHA_FACTOR = 0.72f
