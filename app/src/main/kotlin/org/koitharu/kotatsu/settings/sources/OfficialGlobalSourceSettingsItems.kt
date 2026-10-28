package org.koitharu.kotatsu.settings.sources

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.settings.compose.ActionSettingsItem
import org.koitharu.kotatsu.settings.compose.EditTextSettingsItem
import org.koitharu.kotatsu.settings.compose.InfoSettingsItem
import org.koitharu.kotatsu.settings.compose.ListSettingsItem
import org.koitharu.kotatsu.settings.compose.SettingsGroup
import org.koitharu.kotatsu.settings.compose.SettingsListScope
import org.koitharu.kotatsu.settings.compose.SwitchSettingsItem
import org.koitharu.kotatsu.tsuki.runtime.OfficialGlobalDiagnostic
import org.koitharu.kotatsu.tsuki.runtime.OfficialGlobalDiagnosticStage
import org.koitharu.kotatsu.tsuki.runtime.OfficialGlobalDiagnosticStatus
import org.koitharu.kotatsu.tsuki.runtime.OfficialGlobalDomainMode
import org.koitharu.kotatsu.tsuki.runtime.OfficialGlobalRequestProfile
import org.koitharu.kotatsu.tsuki.runtime.OfficialGlobalSourceResilience
import org.koitharu.kotatsu.tsuki.runtime.OfficialGlobalSourceSettingsSnapshot
import java.text.DateFormat
import java.util.Date

internal fun SettingsListScope.officialGlobalSourceSettings(
	state: OfficialGlobalSourceSettingsUiState,
	onTrustedDomainSelected: (String) -> Unit,
	onCustomDomainEntered: (String) -> Unit,
	onAutoMirrorChanged: (Boolean) -> Unit,
	onTestAddress: () -> Unit,
	onResetAddress: () -> Unit,
	onTestSource: () -> Unit,
	onResetSession: () -> Unit,
	onRequestProfileChanged: (OfficialGlobalRequestProfile) -> Unit,
) {
	val snapshot = state.snapshot
	if (snapshot == null) {
		if (state.isLoading) {
			item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
			item {
				SettingsGroup(title = stringResource(R.string.global_source_recovery_group)) {
					item { pos ->
						InfoSettingsItem(
							title = stringResource(R.string.global_source_status),
							subtitle = stringResource(R.string.global_source_status_testing),
							shape = pos.shape,
						)
					}
				}
			}
		}
		return
	}

	item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
	item {
		AddressGroup(
			snapshot = snapshot,
			loading = state.isLoading,
			onTrustedDomainSelected = onTrustedDomainSelected,
			onCustomDomainEntered = onCustomDomainEntered,
			onAutoMirrorChanged = onAutoMirrorChanged,
			onTestAddress = onTestAddress,
			onResetAddress = onResetAddress,
		)
	}

	item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
	item {
		RecoveryGroup(
			state = state,
			snapshot = snapshot,
			onTestSource = onTestSource,
			onResetSession = onResetSession,
		)
	}

	item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
	item {
		SettingsGroup(title = stringResource(R.string.global_source_network_group)) {
			item { pos ->
				ListSettingsItem(
					title = stringResource(R.string.global_source_request_handling),
					entries = listOf(
						stringResource(R.string.global_source_request_auto),
						stringResource(R.string.global_source_request_normal),
						stringResource(R.string.global_source_request_slower),
					),
					entryValues = listOf(
						OfficialGlobalRequestProfile.AUTO.name,
						OfficialGlobalRequestProfile.NORMAL.name,
						OfficialGlobalRequestProfile.SLOWER.name,
					),
					selectedValue = snapshot.requestProfile.name,
					onValueChange = { raw ->
						OfficialGlobalRequestProfile.entries.firstOrNull { it.name == raw }
							?.let(onRequestProfileChanged)
					},
					shape = pos.shape,
					enabled = !state.isLoading,
				)
			}
		}
	}

	item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
	item { SourceInfoGroup(snapshot) }
}

@Composable
private fun AddressGroup(
	snapshot: OfficialGlobalSourceSettingsSnapshot,
	loading: Boolean,
	onTrustedDomainSelected: (String) -> Unit,
	onCustomDomainEntered: (String) -> Unit,
	onAutoMirrorChanged: (Boolean) -> Unit,
	onTestAddress: () -> Unit,
	onResetAddress: () -> Unit,
) {
	val mode = domainModeLabel(snapshot.domainMode)
	SettingsGroup(title = stringResource(R.string.global_source_address_group)) {
		item { pos ->
			InfoSettingsItem(
				title = stringResource(R.string.global_source_active_address),
				subtitle = "${snapshot.activeDomain} · $mode",
				shape = pos.shape,
			)
		}

		if (snapshot.presetDomains.size > 1) {
			item { pos ->
				val customActive = snapshot.domainMode == OfficialGlobalDomainMode.CUSTOM
				val entries = buildList {
					addAll(snapshot.presetDomains)
					if (customActive) {
						add(stringResource(R.string.global_source_custom_active_label, snapshot.activeDomain))
					}
				}
				val values = buildList {
					addAll(snapshot.presetDomains)
					if (customActive) add(snapshot.activeDomain)
				}
				ListSettingsItem(
					title = stringResource(R.string.global_source_trusted_address),
					entries = entries,
					entryValues = values,
					selectedValue = snapshot.activeDomain,
					onValueChange = { domain ->
						if (domain != snapshot.activeDomain && domain in snapshot.presetDomains) {
							onTrustedDomainSelected(domain)
						}
					},
					shape = pos.shape,
					enabled = !loading,
				)
			}
			item { pos ->
				SwitchSettingsItem(
					title = stringResource(R.string.global_source_auto_mirror),
					subtitle = stringResource(R.string.global_source_auto_mirror_summary),
					checked = snapshot.autoMirrorEnabled,
					onCheckedChange = onAutoMirrorChanged,
					shape = pos.shape,
					enabled = !loading,
				)
			}
		}

		item { pos ->
			EditTextSettingsItem(
				title = stringResource(R.string.global_source_custom_url),
				value = snapshot.activeDomain.takeIf { snapshot.domainMode == OfficialGlobalDomainMode.CUSTOM }.orEmpty(),
				hint = stringResource(R.string.global_source_custom_url_hint),
				onValueChange = onCustomDomainEntered,
				shape = pos.shape,
				enabled = !loading,
				isValueValid = { OfficialGlobalSourceResilience.normalizeDomainInput(it) != null },
				invalidMessage = stringResource(R.string.global_source_custom_url_invalid),
			)
		}
		item { pos ->
			ActionSettingsItem(
				title = stringResource(R.string.global_source_test_address),
				subtitle = stringResource(R.string.global_source_test_address_summary),
				shape = pos.shape,
				enabled = !loading,
				onClick = onTestAddress,
			)
		}
		if (snapshot.domainMode != OfficialGlobalDomainMode.DEFAULT) {
			item { pos ->
				ActionSettingsItem(
					title = stringResource(R.string.global_source_reset_address),
					subtitle = stringResource(R.string.global_source_reset_address_summary),
					shape = pos.shape,
					enabled = !loading,
					onClick = onResetAddress,
				)
			}
		}
	}
}

@Composable
private fun RecoveryGroup(
	state: OfficialGlobalSourceSettingsUiState,
	snapshot: OfficialGlobalSourceSettingsSnapshot,
	onTestSource: () -> Unit,
	onResetSession: () -> Unit,
) {
	SettingsGroup(title = stringResource(R.string.global_source_recovery_group)) {
		item { pos ->
			ActionSettingsItem(
				title = stringResource(R.string.global_source_test_source),
				subtitle = stringResource(R.string.global_source_test_source_summary),
				shape = pos.shape,
				enabled = !state.isLoading,
				onClick = onTestSource,
			)
		}
		item { pos ->
			val diagnostic = state.latestActionDiagnostic ?: snapshot.diagnostic
			InfoSettingsItem(
				title = if (state.isLoading) {
					stringResource(R.string.global_source_status_testing)
				} else {
					diagnosticTitle(diagnostic)
				},
				subtitle = diagnosticSummary(diagnostic, snapshot),
				shape = pos.shape,
			)
		}
		item { pos ->
			ActionSettingsItem(
				title = stringResource(R.string.global_source_reset_session),
				subtitle = stringResource(R.string.global_source_reset_session_summary),
				shape = pos.shape,
				enabled = !state.isLoading,
				onClick = onResetSession,
			)
		}
	}
}

@Composable
private fun SourceInfoGroup(snapshot: OfficialGlobalSourceSettingsSnapshot) {
	val details = buildList {
		add(stringResource(R.string.global_source_info_source, snapshot.sourceTitle))
		add(stringResource(R.string.global_source_info_pack, snapshot.packName))
		snapshot.packVersion?.let { add(stringResource(R.string.global_source_info_version, it)) }
		add(stringResource(R.string.global_source_info_provider, snapshot.providerName))
		snapshot.packLastModified?.let { timestamp ->
			val formatted = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(timestamp))
			add(stringResource(R.string.global_source_info_updated, formatted))
		}
	}
	SettingsGroup(title = stringResource(R.string.global_source_info_group)) {
		item { pos ->
			InfoSettingsItem(
				title = stringResource(R.string.global_source_info_title),
				subtitle = details.joinToString("\n"),
				shape = pos.shape,
			)
		}
	}
}

@Composable
private fun domainModeLabel(mode: OfficialGlobalDomainMode): String = when (mode) {
	OfficialGlobalDomainMode.DEFAULT -> stringResource(R.string.global_source_domain_default)
	OfficialGlobalDomainMode.TRUSTED_MIRROR -> stringResource(R.string.global_source_domain_trusted_mirror)
	OfficialGlobalDomainMode.CUSTOM -> stringResource(R.string.global_source_domain_custom)
}

@Composable
private fun diagnosticTitle(diagnostic: OfficialGlobalDiagnostic?): String = when (diagnostic?.status) {
	null,
	OfficialGlobalDiagnosticStatus.UNTESTED -> stringResource(R.string.global_source_status_never_tested)
	OfficialGlobalDiagnosticStatus.HEALTHY -> if (diagnostic.stage == OfficialGlobalDiagnosticStage.NONE) {
		stringResource(R.string.global_source_diag_address_reachable)
	} else {
		stringResource(R.string.global_source_diag_healthy)
	}
	OfficialGlobalDiagnosticStatus.INVALID_URL -> stringResource(R.string.global_source_diag_invalid_url)
	OfficialGlobalDiagnosticStatus.NETWORK_ERROR -> stringResource(R.string.global_source_diag_network_error)
	OfficialGlobalDiagnosticStatus.HTTP_ERROR -> stringResource(R.string.global_source_diag_http_error)
	OfficialGlobalDiagnosticStatus.REDIRECT -> stringResource(R.string.global_source_diag_redirect)
	OfficialGlobalDiagnosticStatus.CLOUDFLARE -> stringResource(R.string.global_source_diag_cloudflare)
	OfficialGlobalDiagnosticStatus.RATE_LIMITED -> stringResource(R.string.global_source_diag_rate_limited)
	OfficialGlobalDiagnosticStatus.PARSER_MISMATCH -> stringResource(R.string.global_source_diag_parser_mismatch)
	OfficialGlobalDiagnosticStatus.AUTH_REQUIRED -> stringResource(R.string.global_source_diag_auth_required)
	OfficialGlobalDiagnosticStatus.SOURCE_UNAVAILABLE -> stringResource(R.string.global_source_diag_unavailable)
}

@Composable
private fun diagnosticSummary(
	diagnostic: OfficialGlobalDiagnostic?,
	snapshot: OfficialGlobalSourceSettingsSnapshot,
): String {
	if (diagnostic == null) {
		return listOf(
			snapshot.activeDomain,
			domainModeLabel(snapshot.domainMode),
			stringResource(R.string.global_source_status_never_tested),
		).joinToString(" · ")
	}
	val parts = mutableListOf<String>()
	parts += diagnostic.domain.ifBlank { snapshot.activeDomain }
	diagnostic.httpStatus?.let { parts += "HTTP $it" }
	stageLabel(diagnostic.stage)?.let(parts::add)
	if (diagnostic.domain.equals(snapshot.activeDomain, ignoreCase = true)) {
		parts += domainModeLabel(snapshot.domainMode)
	} else if (!diagnostic.isHealthy) {
		parts += stringResource(R.string.global_source_active_unchanged, snapshot.activeDomain)
	}
	if (diagnostic.testedAt > 0L) {
		parts += DateUtils.getRelativeTimeSpanString(
			diagnostic.testedAt,
			System.currentTimeMillis(),
			DateUtils.MINUTE_IN_MILLIS,
		).toString()
	}
	return parts.joinToString(" · ")
}

@Composable
private fun stageLabel(stage: OfficialGlobalDiagnosticStage): String? = when (stage) {
	OfficialGlobalDiagnosticStage.NONE -> null
	OfficialGlobalDiagnosticStage.CATALOGUE -> stringResource(R.string.global_source_diag_stage_catalogue)
	OfficialGlobalDiagnosticStage.DETAILS -> stringResource(R.string.global_source_diag_stage_details)
	OfficialGlobalDiagnosticStage.CHAPTERS -> stringResource(R.string.global_source_diag_stage_chapters)
	OfficialGlobalDiagnosticStage.READER -> stringResource(R.string.global_source_diag_stage_reader)
}