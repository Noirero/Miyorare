package org.koitharu.kotatsu.settings.compose

import androidx.annotation.DrawableRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import org.koitharu.kotatsu.core.ui.LocalMiyorareVisualPalette

/**
 * Central Classic/Modern bridge for short appearance choices. Classic keeps the existing dialog
 * row, while Modern exposes two or three options as an immediate segmented control.
 *
 * Entry/value lists are paired defensively. A stale stored value falls back to the first valid
 * option for presentation instead of leaving the active choice visually undefined.
 */
@Composable
fun MiyorareChoiceSettingsItem(
	title: String,
	entries: List<String>,
	entryValues: List<String>,
	selectedValue: String?,
	onValueChange: (String) -> Unit,
	modifier: Modifier = Modifier,
	@DrawableRes icon: Int? = null,
	shape: Shape = MaterialTheme.shapes.medium,
	enabled: Boolean = true,
) {
	val modern = LocalMiyorareVisualPalette.current.isModern
	val optionCount = minOf(entries.size, entryValues.size)
	val safeEntries = entries.take(optionCount)
	val safeEntryValues = entryValues.take(optionCount)
	val safeSelectedValue = selectedValue
		?.takeIf { it in safeEntryValues }
		?: safeEntryValues.firstOrNull()
	val selectedIndex = safeEntryValues.indexOf(safeSelectedValue).coerceAtLeast(0)
	val hasOptions = safeEntries.isNotEmpty()
	val itemEnabled = enabled && hasOptions

	if (modern && safeEntries.size in 2..3) {
		SegmentedSettingsItem(
			title = title,
			labels = safeEntries,
			selectedIndex = selectedIndex,
			onSelected = { index -> safeEntryValues.getOrNull(index)?.let(onValueChange) },
			modifier = modifier,
			icon = icon,
			shape = shape,
			enabled = itemEnabled,
		)
	} else {
		ListSettingsItem(
			title = title,
			entries = safeEntries,
			entryValues = safeEntryValues,
			selectedValue = safeSelectedValue,
			onValueChange = onValueChange,
			modifier = modifier,
			icon = icon,
			shape = shape,
			enabled = itemEnabled,
		)
	}
}
