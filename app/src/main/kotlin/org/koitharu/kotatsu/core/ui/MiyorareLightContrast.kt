package org.koitharu.kotatsu.core.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Keeps small accent-labelled controls readable on Modern Light translucent/tinted surfaces.
 * Light surfaces use semantic neutral foregrounds; dark surfaces keep the authored accent so the
 * existing Modern Dark identity is unchanged.
 */
internal fun miyorareReadableAccentContentColor(
	colorScheme: ColorScheme,
	preferredAccent: Color,
	lightSurface: Boolean,
	emphasized: Boolean,
): Color {
	if (!lightSurface) return preferredAccent
	return if (emphasized) colorScheme.onSurface else colorScheme.onSurfaceVariant
}
