package org.koitharu.kotatsu.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.MihonExtensionNetworkSettings
import org.koitharu.kotatsu.settings.compose.SettingsItem
import org.koitharu.kotatsu.settings.compose.rememberStringPref

@Composable
internal fun MihonConnectTimeoutSettingsItem(shape: Shape) {
	var savedMode by rememberStringPref(
		MihonExtensionNetworkSettings.KEY_CONNECT_TIMEOUT_MODE,
		MihonExtensionNetworkSettings.MODE_STANDARD,
	)
	var savedCustomSeconds by rememberStringPref(
		MihonExtensionNetworkSettings.KEY_CUSTOM_CONNECT_TIMEOUT_SECONDS,
		MihonExtensionNetworkSettings.DEFAULT_SECONDS.toString(),
	)
	var showDialog by remember { mutableStateOf(false) }

	val normalizedMode = MihonExtensionNetworkSettings.normalizeMode(savedMode)
	val effectiveSeconds = MihonExtensionNetworkSettings.resolveSeconds(normalizedMode, savedCustomSeconds)
	val summary = when (normalizedMode) {
		MihonExtensionNetworkSettings.MODE_AGGRESSIVE -> stringResource(R.string.mihon_timeout_aggressive)
		MihonExtensionNetworkSettings.MODE_TOLERANT -> stringResource(R.string.mihon_timeout_tolerant)
		MihonExtensionNetworkSettings.MODE_CUSTOM -> stringResource(
			R.string.mihon_timeout_custom_value,
			effectiveSeconds,
		)
		else -> stringResource(R.string.mihon_timeout_standard)
	}

	SettingsItem(
		title = stringResource(R.string.mihon_connect_timeout_title),
		subtitle = summary,
		icon = R.drawable.ic_timer,
		shape = shape,
		onClick = { showDialog = true },
	)

	if (showDialog) {
		MihonConnectTimeoutDialog(
			initialMode = normalizedMode,
			initialCustomSeconds = savedCustomSeconds,
			onApply = { mode, customSeconds ->
				savedMode = mode
				if (mode == MihonExtensionNetworkSettings.MODE_CUSTOM) {
					savedCustomSeconds = customSeconds.toString()
				}
			},
			onDismiss = { showDialog = false },
		)
	}
}

@Composable
private fun MihonConnectTimeoutDialog(
	initialMode: String,
	initialCustomSeconds: String,
	onApply: (String, Int) -> Unit,
	onDismiss: () -> Unit,
) {
	var mode by remember { mutableStateOf(MihonExtensionNetworkSettings.normalizeMode(initialMode)) }
	var customText by remember {
		mutableStateOf(
			initialCustomSeconds.toIntOrNull()
				?.coerceIn(
					MihonExtensionNetworkSettings.MIN_SECONDS,
					MihonExtensionNetworkSettings.MAX_SECONDS,
				)
				?.toString()
				?: MihonExtensionNetworkSettings.DEFAULT_SECONDS.toString(),
		)
	}
	val customSeconds = customText.toIntOrNull()
	val customValid = customSeconds != null &&
		customSeconds in MihonExtensionNetworkSettings.MIN_SECONDS..MihonExtensionNetworkSettings.MAX_SECONDS
	val canApply = mode != MihonExtensionNetworkSettings.MODE_CUSTOM || customValid

	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(stringResource(R.string.mihon_connect_timeout_title)) },
		text = {
			Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
				TimeoutModeRow(
					label = stringResource(R.string.mihon_timeout_aggressive),
					selected = mode == MihonExtensionNetworkSettings.MODE_AGGRESSIVE,
					onClick = { mode = MihonExtensionNetworkSettings.MODE_AGGRESSIVE },
				)
				TimeoutModeRow(
					label = stringResource(R.string.mihon_timeout_standard),
					selected = mode == MihonExtensionNetworkSettings.MODE_STANDARD,
					onClick = { mode = MihonExtensionNetworkSettings.MODE_STANDARD },
				)
				TimeoutModeRow(
					label = stringResource(R.string.mihon_timeout_tolerant),
					selected = mode == MihonExtensionNetworkSettings.MODE_TOLERANT,
					onClick = { mode = MihonExtensionNetworkSettings.MODE_TOLERANT },
				)
				TimeoutModeRow(
					label = stringResource(R.string.mihon_timeout_custom),
					selected = mode == MihonExtensionNetworkSettings.MODE_CUSTOM,
					onClick = { mode = MihonExtensionNetworkSettings.MODE_CUSTOM },
				)

				if (mode == MihonExtensionNetworkSettings.MODE_CUSTOM) {
					OutlinedTextField(
						value = customText,
						onValueChange = { input ->
							customText = input.filter(Char::isDigit).take(2)
						},
						label = { Text(stringResource(R.string.mihon_timeout_custom_field)) },
						isError = !customValid,
						supportingText = if (!customValid) {
							{ Text(stringResource(R.string.mihon_timeout_custom_error)) }
						} else null,
						singleLine = true,
						modifier = Modifier
							.fillMaxWidth()
							.padding(top = 8.dp),
					)
				}

				Text(
					text = stringResource(R.string.mihon_timeout_explanation),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier.padding(top = 8.dp),
				)
			}
		},
		confirmButton = {
			TextButton(
				enabled = canApply,
				onClick = {
					onApply(
						mode,
						customSeconds ?: MihonExtensionNetworkSettings.DEFAULT_SECONDS,
					)
					onDismiss()
				},
			) {
				Text(stringResource(R.string.settings_apply))
			}
		},
		dismissButton = {
			TextButton(onClick = onDismiss) {
				Text(stringResource(android.R.string.cancel))
			}
		},
	)
}

@Composable
private fun TimeoutModeRow(
	label: String,
	selected: Boolean,
	onClick: () -> Unit,
) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.clickable(onClick = onClick)
			.padding(vertical = 4.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		RadioButton(selected = selected, onClick = onClick)
		Spacer(Modifier.width(8.dp))
		Text(text = label, style = MaterialTheme.typography.bodyLarge)
	}
}
