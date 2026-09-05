package org.koitharu.kotatsu.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
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
	val selected = selectedFraction.coerceIn(0f, 1f)
	val start = lerp(palette.surfaceGradientStart, palette.accentGradientStart, selected)
	val middle = lerp(palette.surfaceGradientMiddle, palette.accentGradientMiddle, selected)
	val end = lerp(palette.surfaceGradientEnd, palette.accentGradientEnd, selected)
	var result = background(
		brush = Brush.horizontalGradient(listOf(start, middle, end)),
		shape = shape,
	)
	if (drawBorder) {
		val border = lerp(palette.borderHighlight, palette.glow, selected * 0.72f)
		result = result.border(1.dp, border, shape)
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
	val safeAlpha = alpha.coerceIn(0f, 1f)
	return background(
		brush = Brush.horizontalGradient(
			listOf(
				palette.accentGradientStart.copy(alpha = safeAlpha),
				palette.accentGradientMiddle.copy(alpha = safeAlpha),
				palette.accentGradientEnd.copy(alpha = safeAlpha),
			),
		),
		shape = shape,
	).border(
		width = 1.dp,
		color = lerp(palette.borderHighlight, palette.glow, 0.58f)
			.copy(alpha = (palette.borderHighlight.alpha + palette.glow.alpha).coerceAtMost(1f) * safeAlpha),
		shape = shape,
	)
}

/** Small inset surface used by icons so every Modern screen shares one premium icon language. */
fun Modifier.miyorareIconSurface(
	palette: MiyorareVisualPalette,
	shape: Shape,
	alpha: Float = 1f,
): Modifier {
	if (!palette.isModern) return this
	val safeAlpha = alpha.coerceIn(0f, 1f)
	return background(
		brush = Brush.linearGradient(
			listOf(
				palette.iconGradientStart.copy(alpha = safeAlpha),
				palette.selectedSurface.copy(alpha = safeAlpha),
				palette.iconGradientEnd.copy(alpha = safeAlpha),
			),
		),
		shape = shape,
	).border(
		width = 1.dp,
		color = palette.borderHighlight.copy(alpha = palette.borderHighlight.alpha * safeAlpha),
		shape = shape,
	)
}
