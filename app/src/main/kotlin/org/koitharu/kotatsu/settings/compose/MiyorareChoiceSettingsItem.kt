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
	val selectedIndex = entryValues.indexOf(selectedValue).coerceAtLeast(0)
	if (modern && entries.size in 2..3 && entries.size == entryValues.size) {
		SegmentedSettingsItem(
			title = title,
			labels = entries,
			selectedIndex = selectedIndex,
			onSelected = { index -> entryValues.getOrNull(index)?.let(onValueChange) },
			modifier = modifier,
			icon = icon,
			shape = shape,
			enabled = enabled,
		)
	} else {
		ListSettingsItem(
			title = title,
			entries = entries,
			entryValues = entryValues,
			selectedValue = selectedValue,
			onValueChange = onValueChange,
			modifier = modifier,
			icon = icon,
			shape = shape,
			enabled = enabled,
		)
	}
}
