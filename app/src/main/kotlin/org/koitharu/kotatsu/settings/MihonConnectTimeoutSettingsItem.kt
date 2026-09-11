package org.koitharu.kotatsu.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.android.material.snackbar.Snackbar
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.MihonExtensionNetworkSettings
import org.koitharu.kotatsu.settings.compose.SettingsItem
import org.koitharu.kotatsu.settings.compose.rememberStringPref

/**
 * Entry point kept under the old function name so the Storage & Network screen does not need a
 * structural rewrite. The row is now the complete "Mihon Extensions" network profile controller.
 */
@Composable
internal fun MihonConnectTimeoutSettingsItem(shape: Shape) {
	val context = LocalContext.current
	val view = LocalView.current
	val initialProfile = remember { MihonExtensionNetworkSettings.getGlobalProfile(context) }
	var savedProfile by rememberStringPref(MihonExtensionNetworkSettings.KEY_PROFILE, initialProfile)
	var customConnectTimeout by rememberStringPref(
		MihonExtensionNetworkSettings.KEY_CUSTOM_CONNECT_TIMEOUT_SECONDS,
		MihonExtensionNetworkSettings.STANDARD_CONNECT_TIMEOUT_SECONDS.toString(),
	)
	var customRetryCount by rememberStringPref(
		MihonExtensionNetworkSettings.KEY_CUSTOM_RETRY_COUNT,
		MihonExtensionNetworkSettings.ADAPTIVE_RETRY_COUNT.toString(),
	)
	var customRetryDelay by rememberStringPref(
		MihonExtensionNetworkSettings.KEY_CUSTOM_RETRY_DELAY_SECONDS,
		MihonExtensionNetworkSettings.ADAPTIVE_RETRY_DELAY_SECONDS.toString(),
	)
	var customBackoff by rememberStringPref(MihonExtensionNetworkSettings.KEY_CUSTOM_BACKOFF_ENABLED, "true")
	var customMaxBackoff by rememberStringPref(
		MihonExtensionNetworkSettings.KEY_CUSTOM_MAX_BACKOFF_SECONDS,
		MihonExtensionNetworkSettings.ADAPTIVE_MAX_BACKOFF_SECONDS.toString(),
	)
	var showProfileDialog by remember { mutableStateOf(false) }
	var showOverridesDialog by remember { mutableStateOf(false) }

	val profile = MihonExtensionNetworkSettings.normalizeProfile(savedProfile)
	val profileLabel = stringResource(profileLabelRes(profile))
	SettingsItem(
		title = stringResource(R.string.mihon_extensions_network_title),
		subtitle = stringResource(R.string.mihon_profile_active_summary, profileLabel),
		icon = R.drawable.ic_web,
		shape = shape,
		onClick = { showProfileDialog = true },
	)

	if (showProfileDialog) {
		MihonNetworkProfileDialog(
			initial = ProfileDraft(
				profile = profile,
				connectTimeout = customConnectTimeout,
				retryCount = customRetryCount,
				retryDelay = customRetryDelay,
				backoffEnabled = customBackoff.toBooleanStrictOrNull() ?: true,
				maxBackoff = customMaxBackoff,
			),
			onApply = { draft ->
				savedProfile = draft.profile
				customConnectTimeout = draft.connectTimeout
				customRetryCount = draft.retryCount
				customRetryDelay = draft.retryDelay
				customBackoff = draft.backoffEnabled.toString()
				customMaxBackoff = draft.maxBackoff
				val label = context.getString(profileLabelRes(draft.profile))
				Snackbar.make(
					view,
					context.getString(R.string.mihon_profile_applied, label),
					Snackbar.LENGTH_SHORT,
				).show()
			},
			onOpenSourceOverrides = {
				showProfileDialog = false
				showOverridesDialog = true
			},
			onDismiss = { showProfileDialog = false },
		)
	}

	if (showOverridesDialog) {
		MihonSourceOverridesDialog(
			onDismiss = { showOverridesDialog = false },
			onChanged = { messageRes ->
				Snackbar.make(view, messageRes, Snackbar.LENGTH_SHORT).show()
			},
		)
	}
}

private data class ProfileDraft(
	val profile: String,
	val connectTimeout: String,
	val retryCount: String,
	val retryDelay: String,
	val backoffEnabled: Boolean,
	val maxBackoff: String,
)

@Composable
private fun MihonNetworkProfileDialog(
	initial: ProfileDraft,
	onApply: (ProfileDraft) -> Unit,
	onOpenSourceOverrides: () -> Unit,
	onDismiss: () -> Unit,
) {
	var profile by remember { mutableStateOf(MihonExtensionNetworkSettings.normalizeProfile(initial.profile)) }
	var connectTimeout by remember { mutableStateOf(initial.connectTimeout) }
	var retryCount by remember { mutableStateOf(initial.retryCount) }
	var retryDelay by remember { mutableStateOf(initial.retryDelay) }
	var backoffEnabled by remember { mutableStateOf(initial.backoffEnabled) }
	var maxBackoff by remember { mutableStateOf(initial.maxBackoff) }
	var showDetails by remember { mutableStateOf(false) }

	val connectValue = connectTimeout.toIntOrNull()
	val retryValue = retryCount.toIntOrNull()
	val delayValue = retryDelay.toIntOrNull()
	val maxBackoffValue = maxBackoff.toIntOrNull()
	val connectValid = connectValue?.let {
		it in MihonExtensionNetworkSettings.MIN_CONNECT_TIMEOUT_SECONDS..MihonExtensionNetworkSettings.MAX_CONNECT_TIMEOUT_SECONDS
	} == true
	val retryValid = retryValue?.let {
		it in MihonExtensionNetworkSettings.MIN_RETRY_COUNT..MihonExtensionNetworkSettings.MAX_RETRY_COUNT
	} == true
	val delayValid = delayValue?.let {
		it in MihonExtensionNetworkSettings.MIN_RETRY_DELAY_SECONDS..MihonExtensionNetworkSettings.MAX_RETRY_DELAY_SECONDS
	} == true
	val maxBackoffValid = maxBackoffValue?.let {
		it in MihonExtensionNetworkSettings.MIN_MAX_BACKOFF_SECONDS..MihonExtensionNetworkSettings.MAX_MAX_BACKOFF_SECONDS
	} == true
	val customValid = connectValid && retryValid && delayValid && (!backoffEnabled || maxBackoffValid)
	val canApply = profile != MihonExtensionNetworkSettings.PROFILE_CUSTOM || customValid
	val scrollState = rememberScrollState()

	fun copyPreset(source: String) {
		when (source) {
			MihonExtensionNetworkSettings.PROFILE_AGGRESSIVE -> {
				connectTimeout = MihonExtensionNetworkSettings.AGGRESSIVE_CONNECT_TIMEOUT_SECONDS.toString()
				retryCount = "0"
				retryDelay = "0"
				backoffEnabled = false
				maxBackoff = MihonExtensionNetworkSettings.ADAPTIVE_MAX_BACKOFF_SECONDS.toString()
			}
			MihonExtensionNetworkSettings.PROFILE_STANDARD -> {
				connectTimeout = MihonExtensionNetworkSettings.STANDARD_CONNECT_TIMEOUT_SECONDS.toString()
				retryCount = "0"
				retryDelay = "0"
				backoffEnabled = false
				maxBackoff = MihonExtensionNetworkSettings.ADAPTIVE_MAX_BACKOFF_SECONDS.toString()
			}
			else -> {
				connectTimeout = MihonExtensionNetworkSettings.ADAPTIVE_CONNECT_TIMEOUT_SECONDS.toString()
				retryCount = MihonExtensionNetworkSettings.ADAPTIVE_RETRY_COUNT.toString()
				retryDelay = MihonExtensionNetworkSettings.ADAPTIVE_RETRY_DELAY_SECONDS.toString()
				backoffEnabled = true
				maxBackoff = MihonExtensionNetworkSettings.ADAPTIVE_MAX_BACKOFF_SECONDS.toString()
			}
		}
	}

	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(stringResource(R.string.mihon_extensions_network_title)) },
		text = {
			Column(
				modifier = Modifier
					.heightIn(max = 500.dp)
					.verticalScroll(scrollState),
				verticalArrangement = Arrangement.spacedBy(4.dp),
			) {
				ProfileRow(
					label = stringResource(R.string.mihon_profile_adaptive),
					summary = stringResource(R.string.mihon_profile_adaptive_summary),
					selected = profile == MihonExtensionNetworkSettings.PROFILE_ADAPTIVE,
					onClick = { profile = MihonExtensionNetworkSettings.PROFILE_ADAPTIVE },
				)
				ProfileRow(
					label = stringResource(R.string.mihon_profile_standard),
					summary = stringResource(R.string.mihon_profile_standard_summary),
					selected = profile == MihonExtensionNetworkSettings.PROFILE_STANDARD,
					onClick = { profile = MihonExtensionNetworkSettings.PROFILE_STANDARD },
				)
				ProfileRow(
					label = stringResource(R.string.mihon_profile_aggressive),
					summary = stringResource(R.string.mihon_profile_aggressive_summary),
					selected = profile == MihonExtensionNetworkSettings.PROFILE_AGGRESSIVE,
					onClick = { profile = MihonExtensionNetworkSettings.PROFILE_AGGRESSIVE },
				)
				ProfileRow(
					label = stringResource(R.string.mihon_profile_custom),
					summary = stringResource(R.string.mihon_profile_custom_summary),
					selected = profile == MihonExtensionNetworkSettings.PROFILE_CUSTOM,
					onClick = { profile = MihonExtensionNetworkSettings.PROFILE_CUSTOM },
				)

				if (profile == MihonExtensionNetworkSettings.PROFILE_CUSTOM) {
					Text(
						text = stringResource(R.string.mihon_copy_from),
						style = MaterialTheme.typography.labelLarge,
						modifier = Modifier.padding(top = 8.dp),
					)
					Row(modifier = Modifier.fillMaxWidth()) {
						TextButton(onClick = { copyPreset(MihonExtensionNetworkSettings.PROFILE_ADAPTIVE) }) {
							Text(stringResource(R.string.mihon_profile_adaptive_short))
						}
						TextButton(onClick = { copyPreset(MihonExtensionNetworkSettings.PROFILE_STANDARD) }) {
							Text(stringResource(R.string.mihon_profile_standard_short))
						}
					}
					TextButton(onClick = { copyPreset(MihonExtensionNetworkSettings.PROFILE_AGGRESSIVE) }) {
						Text(stringResource(R.string.mihon_profile_aggressive))
					}
					NumberField(
						value = connectTimeout,
						onValueChange = { connectTimeout = it },
						label = stringResource(R.string.mihon_custom_connect_timeout),
						maxDigits = 2,
						isError = !connectValid,
					)
					NumberField(
						value = retryCount,
						onValueChange = { retryCount = it },
						label = stringResource(R.string.mihon_custom_retry_count),
						maxDigits = 1,
						isError = !retryValid,
					)
					NumberField(
						value = retryDelay,
						onValueChange = { retryDelay = it },
						label = stringResource(R.string.mihon_custom_retry_delay),
						maxDigits = 1,
						isError = !delayValid,
					)
					Row(
						modifier = Modifier
							.fillMaxWidth()
							.clickable { backoffEnabled = !backoffEnabled }
							.padding(vertical = 6.dp),
						verticalAlignment = Alignment.CenterVertically,
					) {
						Text(stringResource(R.string.mihon_custom_backoff))
						Spacer(Modifier.width(12.dp))
						Switch(checked = backoffEnabled, onCheckedChange = { backoffEnabled = it })
					}
					if (backoffEnabled) {
						NumberField(
							value = maxBackoff,
							onValueChange = { maxBackoff = it },
							label = stringResource(R.string.mihon_custom_max_backoff),
							maxDigits = 2,
							isError = !maxBackoffValid,
						)
					}
				}

				TextButton(onClick = { showDetails = !showDetails }) {
					Text(stringResource(R.string.mihon_profile_details))
				}
				if (showDetails) {
					Text(
						text = profileDetails(
							profile = profile,
							connectTimeout = connectValue,
							retryCount = retryValue,
							retryDelay = delayValue,
							backoffEnabled = backoffEnabled,
							maxBackoff = maxBackoffValue,
						),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
				TextButton(onClick = onOpenSourceOverrides) {
					Text(stringResource(R.string.mihon_source_overrides))
				}
				TextButton(onClick = {
					profile = MihonExtensionNetworkSettings.PROFILE_ADAPTIVE
					copyPreset(MihonExtensionNetworkSettings.PROFILE_ADAPTIVE)
				}) {
					Text(stringResource(R.string.mihon_reset_recommended))
				}
				Text(
					text = stringResource(R.string.mihon_profile_request_next),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		},
		confirmButton = {
			TextButton(
				enabled = canApply,
				onClick = {
					onApply(
						ProfileDraft(
							profile = profile,
							connectTimeout = connectTimeout,
							retryCount = retryCount,
							retryDelay = retryDelay,
							backoffEnabled = backoffEnabled,
							maxBackoff = maxBackoff,
						),
					)
					onDismiss()
				},
			) {
				Text(stringResource(R.string.settings_apply))
			}
		},
		dismissButton = {
			TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
		},
	)
}

@Composable
private fun ProfileRow(
	label: String,
	summary: String,
	selected: Boolean,
	onClick: () -> Unit,
) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.clickable(onClick = onClick)
			.padding(vertical = 5.dp),
		verticalAlignment = Alignment.Top,
	) {
		RadioButton(selected = selected, onClick = onClick)
		Spacer(Modifier.width(8.dp))
		Column {
			Text(text = label, style = MaterialTheme.typography.bodyLarge)
			Text(
				text = summary,
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
	}
}

@Composable
private fun NumberField(
	value: String,
	onValueChange: (String) -> Unit,
	label: String,
	maxDigits: Int,
	isError: Boolean,
) {
	OutlinedTextField(
		value = value,
		onValueChange = { onValueChange(it.filter(Char::isDigit).take(maxDigits)) },
		label = { Text(label) },
		isError = isError,
		supportingText = if (isError) {
			{ Text(stringResource(R.string.mihon_custom_number_error)) }
		} else null,
		singleLine = true,
		modifier = Modifier
			.fillMaxWidth()
			.padding(top = 6.dp),
	)
}

@Composable
private fun profileDetails(
	profile: String,
	connectTimeout: Int?,
	retryCount: Int?,
	retryDelay: Int?,
	backoffEnabled: Boolean,
	maxBackoff: Int?,
): String = when (profile) {
	MihonExtensionNetworkSettings.PROFILE_STANDARD -> stringResource(R.string.mihon_profile_detail_standard)
	MihonExtensionNetworkSettings.PROFILE_AGGRESSIVE -> stringResource(R.string.mihon_profile_detail_aggressive)
	MihonExtensionNetworkSettings.PROFILE_CUSTOM -> stringResource(
		R.string.mihon_profile_detail_custom,
		connectTimeout ?: MihonExtensionNetworkSettings.STANDARD_CONNECT_TIMEOUT_SECONDS,
		retryCount ?: 0,
		retryDelay ?: 0,
		stringResource(if (backoffEnabled) R.string.enabled else R.string.disabled),
		maxBackoff ?: MihonExtensionNetworkSettings.ADAPTIVE_MAX_BACKOFF_SECONDS,
	)
	else -> stringResource(R.string.mihon_profile_detail_adaptive)
}

@Composable
private fun MihonSourceOverridesDialog(
	onDismiss: () -> Unit,
	onChanged: (Int) -> Unit,
) {
	val context = LocalContext.current
	var revision by remember { mutableIntStateOf(0) }
	var hostInput by remember { mutableStateOf("") }
	var selectedProfile by remember { mutableStateOf(MihonExtensionNetworkSettings.PROFILE_STANDARD) }
	val overrides = remember(revision) { MihonExtensionNetworkSettings.getHostOverrides(context) }
	val normalizedHost = remember(hostInput) { parseHost(hostInput) }
	val scrollState = rememberScrollState()

	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(stringResource(R.string.mihon_source_overrides)) },
		text = {
			Column(
				modifier = Modifier
					.heightIn(max = 480.dp)
					.verticalScroll(scrollState),
				verticalArrangement = Arrangement.spacedBy(6.dp),
			) {
				Text(
					text = stringResource(R.string.mihon_source_overrides_summary),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				if (overrides.isEmpty()) {
					Text(stringResource(R.string.mihon_no_source_overrides))
				} else {
					overrides.forEach { (host, profile) ->
						Row(
							modifier = Modifier.fillMaxWidth(),
							verticalAlignment = Alignment.CenterVertically,
						) {
							Column(modifier = Modifier.fillMaxWidth(0.72f)) {
								Text(host, style = MaterialTheme.typography.bodyMedium)
								Text(
									text = stringResource(profileLabelRes(profile)),
									style = MaterialTheme.typography.bodySmall,
									color = MaterialTheme.colorScheme.onSurfaceVariant,
								)
							}
							TextButton(onClick = {
								MihonExtensionNetworkSettings.setHostProfileOverride(context, host, null)
								revision++
								onChanged(R.string.mihon_override_removed)
							}) {
								Text(stringResource(R.string.remove))
							}
						}
					}
				}

				OutlinedTextField(
					value = hostInput,
					onValueChange = { hostInput = it },
					label = { Text(stringResource(R.string.mihon_source_host)) },
					placeholder = { Text("crotpedia.net") },
					isError = hostInput.isNotBlank() && normalizedHost == null,
					singleLine = true,
					modifier = Modifier.fillMaxWidth(),
				)
				Text(
					text = stringResource(R.string.mihon_source_profile),
					style = MaterialTheme.typography.labelLarge,
				)
				SourceOverrideRow(
					label = stringResource(R.string.mihon_profile_adaptive_short),
					selected = selectedProfile == MihonExtensionNetworkSettings.PROFILE_ADAPTIVE,
					onClick = { selectedProfile = MihonExtensionNetworkSettings.PROFILE_ADAPTIVE },
				)
				SourceOverrideRow(
					label = stringResource(R.string.mihon_profile_standard_short),
					selected = selectedProfile == MihonExtensionNetworkSettings.PROFILE_STANDARD,
					onClick = { selectedProfile = MihonExtensionNetworkSettings.PROFILE_STANDARD },
				)
				SourceOverrideRow(
					label = stringResource(R.string.mihon_profile_aggressive),
					selected = selectedProfile == MihonExtensionNetworkSettings.PROFILE_AGGRESSIVE,
					onClick = { selectedProfile = MihonExtensionNetworkSettings.PROFILE_AGGRESSIVE },
				)
				SourceOverrideRow(
					label = stringResource(R.string.mihon_profile_custom),
					selected = selectedProfile == MihonExtensionNetworkSettings.PROFILE_CUSTOM,
					onClick = { selectedProfile = MihonExtensionNetworkSettings.PROFILE_CUSTOM },
				)
				if (selectedProfile == MihonExtensionNetworkSettings.PROFILE_CUSTOM) {
					Text(
						text = stringResource(R.string.mihon_source_custom_uses_global),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
				TextButton(
					enabled = normalizedHost != null,
					onClick = {
						val host = normalizedHost ?: return@TextButton
						MihonExtensionNetworkSettings.setHostProfileOverride(context, host, selectedProfile)
						hostInput = ""
						revision++
						onChanged(R.string.mihon_override_saved)
					},
				) {
					Text(stringResource(R.string.save))
				}
			}
		},
		confirmButton = {
			TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) }
		},
	)
}

@Composable
private fun SourceOverrideRow(
	label: String,
	selected: Boolean,
	onClick: () -> Unit,
) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.clickable(onClick = onClick)
			.padding(vertical = 2.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		RadioButton(selected = selected, onClick = onClick)
		Spacer(Modifier.width(8.dp))
		Text(label)
	}
}

private fun parseHost(input: String): String? {
	val value = input.trim()
	if (value.isEmpty()) return null
	val url = if (value.contains("://")) value.toHttpUrlOrNull() else "https://$value".toHttpUrlOrNull()
	return url?.host?.trim()?.trimEnd('.')?.lowercase()?.takeIf { it.isNotEmpty() }
}

@StringRes
private fun profileLabelRes(profile: String): Int = when (MihonExtensionNetworkSettings.normalizeProfile(profile)) {
	MihonExtensionNetworkSettings.PROFILE_STANDARD -> R.string.mihon_profile_standard
	MihonExtensionNetworkSettings.PROFILE_AGGRESSIVE -> R.string.mihon_profile_aggressive
	MihonExtensionNetworkSettings.PROFILE_CUSTOM -> R.string.mihon_profile_custom
	else -> R.string.mihon_profile_adaptive
}
