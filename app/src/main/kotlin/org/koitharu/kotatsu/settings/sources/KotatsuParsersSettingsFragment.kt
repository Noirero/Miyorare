package org.koitharu.kotatsu.settings.sources

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.kotatsu.runtime.KotatsuRuntimeManager
import org.koitharu.kotatsu.kotatsu.runtime.KotatsuRuntimeUpdater
import org.koitharu.kotatsu.settings.SettingsActivity
import org.koitharu.kotatsu.settings.compose.ActionSettingsItem
import org.koitharu.kotatsu.settings.compose.BaseComposeSettingsFragment
import org.koitharu.kotatsu.settings.compose.MiyorareTheme
import org.koitharu.kotatsu.settings.compose.SettingsGroup
import org.koitharu.kotatsu.settings.compose.SettingsItem
import org.koitharu.kotatsu.settings.compose.SettingsScaffold
import org.koitharu.kotatsu.settings.compose.SwitchSettingsItem
import org.koitharu.kotatsu.settings.work.WorkScheduleManager
import javax.inject.Inject

@AndroidEntryPoint
class KotatsuParsersSettingsFragment : BaseComposeSettingsFragment(R.string.kotatsu_parsers) {

	@Inject lateinit var runtimeManager: KotatsuRuntimeManager
	@Inject lateinit var updater: KotatsuRuntimeUpdater
	@Inject lateinit var workScheduleManager: WorkScheduleManager

	private var uiState by mutableStateOf(KotatsuParsersUiState())

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View = ComposeView(requireContext()).apply {
		setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		setContent {
			MiyorareTheme {
				KotatsuParsersScreen(
					state = uiState,
					onAutoUpdateChanged = ::setAutoUpdateEnabled,
					onCheckForUpdates = ::checkForUpdates,
					onManageSources = ::openSourceManager,
				)
			}
		}
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		refreshStatus()
	}

	private fun refreshStatus(message: String? = uiState.updateMessage) {
		viewLifecycleOwner.lifecycleScope.launch {
			val status = withContext(Dispatchers.Default) { runtimeManager.status() }
			uiState = uiState.copy(
				loading = false,
				status = status,
				autoUpdateEnabled = workScheduleManager.isKotatsuRuntimeAutoUpdateEnabled,
				updateMessage = message,
			)
		}
	}

	private fun setAutoUpdateEnabled(enabled: Boolean) {
		uiState = uiState.copy(autoUpdateEnabled = enabled)
		workScheduleManager.setKotatsuRuntimeAutoUpdatesEnabled(enabled)
	}

	private fun checkForUpdates() {
		if (uiState.checking) return
		uiState = uiState.copy(checking = true, updateMessage = getString(R.string.kotatsu_parsers_checking))
		viewLifecycleOwner.lifecycleScope.launch {
			val result = runCatching { updater.updateLatest() }
			val message = result.fold(
				onSuccess = { manifest ->
					if (manifest == null) {
						getString(R.string.kotatsu_parsers_up_to_date)
					} else {
						getString(R.string.kotatsu_parsers_updated, manifest.packVersion)
					}
				},
				onFailure = { error ->
					getString(
						R.string.kotatsu_parsers_update_failed,
						error.message ?: error.javaClass.simpleName,
					)
				},
			)
			uiState = uiState.copy(checking = false, updateMessage = message)
			refreshStatus(message)
		}
	}

	private fun openSourceManager() {
		(requireActivity() as SettingsActivity).openFragment(
			KotatsuSourcesSettingsFragment::class.java,
			args = null,
			isFromRoot = false,
		)
	}
}

private data class KotatsuParsersUiState(
	val loading: Boolean = true,
	val checking: Boolean = false,
	val autoUpdateEnabled: Boolean = true,
	val status: KotatsuRuntimeManager.RuntimeStatus? = null,
	val updateMessage: String? = null,
)

@Composable
private fun KotatsuParsersScreen(
	state: KotatsuParsersUiState,
	onAutoUpdateChanged: (Boolean) -> Unit,
	onCheckForUpdates: () -> Unit,
	onManageSources: () -> Unit,
) {
	val status = state.status
	val revision = status?.upstreamRevision?.take(12) ?: "—"
	val origin = when (status?.origin) {
		KotatsuRuntimeManager.RuntimeOrigin.BUNDLED -> stringResource(R.string.kotatsu_parsers_origin_bundled)
		KotatsuRuntimeManager.RuntimeOrigin.CURRENT -> stringResource(R.string.kotatsu_parsers_origin_current)
		KotatsuRuntimeManager.RuntimeOrigin.ROLLED_BACK -> stringResource(R.string.kotatsu_parsers_origin_rolled_back)
		null -> stringResource(R.string.kotatsu_parsers_unavailable)
	}
	val sourceCount = status?.sourceCount ?: 0
	val statusSummary = buildString {
		append(stringResource(R.string.kotatsu_parsers_source_count, sourceCount))
		append('\n')
		append(stringResource(R.string.kotatsu_parsers_revision, revision))
		append('\n')
		append(origin)
		state.updateMessage?.takeIf { it.isNotBlank() }?.let {
			append('\n')
			append(it)
		}
	}

	SettingsScaffold {
		item {
			SettingsGroup(title = stringResource(R.string.kotatsu_parsers_runtime_status)) {
				item { pos ->
					SettingsItem(
						title = stringResource(R.string.kotatsu_parsers),
						subtitle = statusSummary,
						icon = R.drawable.ic_manga_source,
						shape = pos.shape,
					)
				}
			}
		}
		item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
		item {
			SettingsGroup {
				item { pos ->
					SwitchSettingsItem(
						title = stringResource(R.string.kotatsu_parsers_auto_update),
						subtitle = stringResource(R.string.kotatsu_parsers_auto_update_summary),
						checked = state.autoUpdateEnabled,
						onCheckedChange = onAutoUpdateChanged,
						icon = R.drawable.ic_app_update,
						shape = pos.shape,
						enabled = !state.loading,
					)
				}
				item { pos ->
					ActionSettingsItem(
						title = if (state.checking) {
							stringResource(R.string.kotatsu_parsers_checking)
						} else {
							stringResource(R.string.kotatsu_parsers_check_updates)
						},
						icon = R.drawable.ic_refresh,
						shape = pos.shape,
						enabled = !state.loading && !state.checking,
						onClick = onCheckForUpdates,
					)
				}
			}
		}
		item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
		item {
			SettingsGroup {
				item { pos ->
					ActionSettingsItem(
						title = stringResource(R.string.kotatsu_parsers_manage_sources),
						subtitle = stringResource(R.string.kotatsu_parsers_manage_sources_summary),
						icon = R.drawable.ic_manga_source,
						shape = pos.shape,
						enabled = status?.isAvailable == true,
						onClick = onManageSources,
					)
				}
			}
		}
		item { Spacer(Modifier.height(24.dp).fillMaxWidth()) }
	}
}
