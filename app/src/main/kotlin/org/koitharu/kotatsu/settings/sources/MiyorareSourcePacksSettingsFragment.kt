package org.koitharu.kotatsu.settings.sources

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.settings.compose.ActionSettingsItem
import org.koitharu.kotatsu.settings.compose.BaseComposeSettingsFragment
import org.koitharu.kotatsu.settings.compose.DropSauceTheme
import org.koitharu.kotatsu.settings.compose.InfoSettingsItem
import org.koitharu.kotatsu.settings.compose.SwitchSettingsItem
import org.koitharu.kotatsu.tsuki.MiyorareOfficialSourcePack
import org.koitharu.kotatsu.tsuki.MiyorareOfficialSourcePacks
import org.koitharu.kotatsu.tsuki.TsukiPluginInstaller
import org.koitharu.kotatsu.tsuki.TsukiPluginManager
import org.koitharu.kotatsu.tsuki.model.TsukiPluginDescriptor
import org.koitharu.kotatsu.tsuki.model.TsukiPluginProvider
import org.koitharu.kotatsu.tsuki.model.TsukiPluginState
import org.koitharu.kotatsu.tsuki.model.TsukiSourceDescriptor
import org.koitharu.kotatsu.tsuki.model.TsukiSourceIdentity
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class MiyorareSourcePacksSettingsFragment : BaseComposeSettingsFragment(R.string.miyorare_source_packs_title) {

	@Inject
	lateinit var pluginManager: TsukiPluginManager

	@Inject
	lateinit var pluginInstaller: TsukiPluginInstaller

	private var busy by mutableStateOf(false)

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		pluginManager.initialize()
	}

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View = ComposeView(requireContext()).apply {
		setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		setContent {
			DropSauceTheme {
				val plugins by pluginManager.plugins.collectAsState()
				MiyorareSourcePacksScreen(
					plugins = plugins,
					busy = busy,
					onInstallOrUpdate = ::installOrUpdate,
					onPackEnabled = ::setPackEnabled,
					onAllSourcesEnabled = ::setAllSourcesEnabled,
					onSourceEnabled = ::setSourceEnabled,
					onRemove = ::confirmRemove,
				)
			}
		}
	}

	private fun installOrUpdate(pack: MiyorareOfficialSourcePack) {
		if (busy || !pluginInstaller.isStageAvailable(TsukiPluginProvider.MIYORARE)) return
		runLongOperation {
			val installed = installedPluginsFor(pack)
			if (installed.isNotEmpty() && !pluginInstaller.hasMiyorarePackUpdate(pack.pluginId)) {
				return@runLongOperation getString(R.string.tsuki_plugin_up_to_date, pack.displayName)
			}
			pluginInstaller.installLatestMiyorare(pack.pluginId)
			getString(R.string.tsuki_plugin_install_success, pack.displayName)
		}
	}

	private fun setPackEnabled(model: MiyorarePackScreenModel, enabled: Boolean) {
		if (busy || model.plugins.isEmpty()) return
		lifecycleScope.launch(Dispatchers.IO) {
			try {
				model.plugins.forEach { plugin ->
					pluginManager.setEnabled(plugin.provider, plugin.pluginId, enabled)
				}
			} catch (error: CancellationException) {
				throw error
			} catch (error: Throwable) {
				withContext(Dispatchers.Main) { showError(error) }
			}
		}
	}

	private fun setAllSourcesEnabled(model: MiyorarePackScreenModel, enabled: Boolean) {
		if (busy) return
		val states = model.sources.asSequence()
			.filterNot { it.source.isBroken }
			.associate { row ->
				TsukiSourceIdentity(row.plugin.provider, row.plugin.pluginId, row.source.name) to enabled
			}
		if (states.isEmpty()) return
		lifecycleScope.launch(Dispatchers.IO) {
			try {
				pluginManager.setSourceStates(states)
			} catch (error: CancellationException) {
				throw error
			} catch (error: Throwable) {
				withContext(Dispatchers.Main) { showError(error) }
			}
		}
	}

	private fun setSourceEnabled(row: MiyorareSourceRow, enabled: Boolean) {
		if (busy || row.source.isBroken) return
		lifecycleScope.launch(Dispatchers.IO) {
			try {
				pluginManager.setSourceEnabled(
					TsukiSourceIdentity(row.plugin.provider, row.plugin.pluginId, row.source.name),
					enabled,
				)
			} catch (error: CancellationException) {
				throw error
			} catch (error: Throwable) {
				withContext(Dispatchers.Main) { showError(error) }
			}
		}
	}

	private fun confirmRemove(model: MiyorarePackScreenModel) {
		if (model.plugins.isEmpty()) return
		MaterialAlertDialogBuilder(requireContext())
			.setTitle(R.string.miyorare_source_pack_remove_confirm_title)
			.setMessage(getString(R.string.miyorare_source_pack_remove_confirm_message, model.pack.displayName))
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(R.string.remove) { _, _ ->
				if (busy) return@setPositiveButton
				lifecycleScope.launch(Dispatchers.IO) {
					try {
						model.plugins.forEach { plugin ->
							pluginManager.remove(plugin.provider, plugin.pluginId)
						}
						withContext(Dispatchers.Main) {
							Toast.makeText(
								requireContext(),
								getString(R.string.tsuki_plugin_removed, model.pack.displayName),
								Toast.LENGTH_SHORT,
							).show()
						}
					} catch (error: CancellationException) {
						throw error
					} catch (error: Throwable) {
						withContext(Dispatchers.Main) { showError(error) }
					}
				}
			}
			.show()
	}

	private fun installedPluginsFor(pack: MiyorareOfficialSourcePack): List<TsukiPluginDescriptor> =
		pluginManager.getPlugins().filter { plugin ->
			plugin.provider == TsukiPluginProvider.MIYORARE &&
				MiyorareOfficialSourcePacks.findByInstalledPluginId(plugin.pluginId)?.pluginId == pack.pluginId
		}

	private fun runLongOperation(block: suspend () -> String) {
		if (busy) return
		busy = true
		lifecycleScope.launch {
			try {
				Toast.makeText(requireContext(), block(), Toast.LENGTH_LONG).show()
			} catch (error: CancellationException) {
				throw error
			} catch (error: Throwable) {
				showError(error)
			} finally {
				busy = false
			}
		}
	}

	private fun showError(error: Throwable) {
		val ctx = context ?: return
		val reason = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
		Toast.makeText(
			ctx,
			getString(R.string.tsuki_plugin_operation_failed, reason),
			Toast.LENGTH_LONG,
		).show()
	}
}

private data class MiyorareSourceRow(
	val plugin: TsukiPluginDescriptor,
	val source: TsukiSourceDescriptor,
)

private data class MiyorarePackScreenModel(
	val pack: MiyorareOfficialSourcePack,
	val plugins: List<TsukiPluginDescriptor>,
	val sources: List<MiyorareSourceRow>,
	val availableCount: Int,
	val enabledCount: Int,
	val versionLabel: String,
)

@Composable
private fun MiyorareSourcePacksScreen(
	plugins: List<TsukiPluginDescriptor>,
	busy: Boolean,
	onInstallOrUpdate: (MiyorareOfficialSourcePack) -> Unit,
	onPackEnabled: (MiyorarePackScreenModel, Boolean) -> Unit,
	onAllSourcesEnabled: (MiyorarePackScreenModel, Boolean) -> Unit,
	onSourceEnabled: (MiyorareSourceRow, Boolean) -> Unit,
	onRemove: (MiyorarePackScreenModel) -> Unit,
) {
	val context = LocalContext.current
	var sourceQuery by rememberSaveable { mutableStateOf("") }
	var showUnavailableSources by rememberSaveable { mutableStateOf(false) }
	val normalizedQuery = sourceQuery.trim().lowercase(Locale.ROOT)
	val models = remember(plugins) {
		MiyorareOfficialSourcePacks.packs.map { pack ->
			val packPlugins = plugins.filter { plugin ->
				plugin.provider == TsukiPluginProvider.MIYORARE &&
					MiyorareOfficialSourcePacks.findByInstalledPluginId(plugin.pluginId)?.pluginId == pack.pluginId
			}
			val rows = packPlugins.flatMap { plugin ->
				plugin.sources.map { source -> MiyorareSourceRow(plugin, source) }
			}.sortedBy { row -> row.source.title.ifBlank { row.source.name }.lowercase(Locale.ROOT) }
			val available = rows.filterNot { it.source.isBroken }
			val versions = packPlugins.map { it.version }.distinct()
			MiyorarePackScreenModel(
				pack = pack,
				plugins = packPlugins,
				sources = rows,
				availableCount = available.size,
				enabledCount = available.count { row -> row.source.name in row.plugin.enabledSourceNames },
				versionLabel = versions.singleOrNull() ?: versions.takeIf { it.isNotEmpty() }?.joinToString(" / ").orEmpty(),
			)
		}
	}
	val unavailableCount = remember(models) {
		models.sumOf { model -> model.sources.count { it.source.isBroken } }
	}

	LazyColumn(
		modifier = Modifier.fillMaxSize(),
		contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp),
		verticalArrangement = Arrangement.spacedBy(8.dp),
	) {
		item(key = "intro") {
			InfoSettingsItem(
				title = stringResource(R.string.miyorare_source_packs_title),
				subtitle = stringResource(R.string.miyorare_source_packs_summary),
				icon = R.drawable.ic_info_outline,
			)
		}

		item(key = "official-header") {
			MiyorareSectionTitle(stringResource(R.string.miyorare_source_packs_official))
		}
		models.forEach { model ->
			item(key = "pack-action:${model.pack.pluginId}") {
				val baseSummary = if (model.pack.language == "id") {
					stringResource(R.string.tsuki_plugins_install_miyorare_id_summary)
				} else {
					stringResource(R.string.tsuki_plugins_install_miyorare_en_summary)
				}
				val stateSummary = if (model.plugins.isEmpty()) {
					stringResource(R.string.miyorare_source_pack_not_installed)
				} else {
					stringResource(
						R.string.miyorare_source_pack_installed,
						model.versionLabel.ifBlank { "—" },
						model.availableCount,
					)
				}
				ActionSettingsItem(
					title = model.pack.displayName,
					subtitle = "$baseSummary · $stateSummary\n${stringResource(R.string.miyorare_source_pack_tap_update)}",
					icon = R.drawable.ic_download,
					enabled = !busy,
					onClick = { onInstallOrUpdate(model.pack) },
				)
			}
		}

		if (busy) {
			item(key = "working") {
				InfoSettingsItem(
					title = stringResource(R.string.tsuki_plugin_working),
					icon = R.drawable.ic_updated,
				)
			}
		}

		val installedModels = models.filter { it.plugins.isNotEmpty() }
		if (installedModels.isNotEmpty()) {
			item(key = "installed-header") {
				MiyorareSectionTitle(stringResource(R.string.miyorare_source_packs_installed))
			}
			item(key = "source-search") {
				OutlinedTextField(
					value = sourceQuery,
					onValueChange = { sourceQuery = it },
					modifier = Modifier.fillMaxWidth(),
					singleLine = true,
					label = { Text(stringResource(R.string.tsuki_source_search_hint)) },
				)
			}
			if (unavailableCount > 0) {
				item(key = "show-unavailable") {
					SwitchSettingsItem(
						title = stringResource(R.string.tsuki_source_show_unavailable),
						subtitle = stringResource(R.string.tsuki_source_show_unavailable_summary, unavailableCount),
						checked = showUnavailableSources,
						onCheckedChange = { showUnavailableSources = it },
						enabled = !busy,
					)
				}
			}
		}

		installedModels.forEach { model ->
			val packKey = model.pack.pluginId
			item(key = "pack-header:$packKey") {
				MiyorareSectionTitle(model.pack.displayName)
			}
			item(key = "pack-enabled:$packKey") {
				SwitchSettingsItem(
					title = stringResource(R.string.miyorare_source_pack_enabled),
					subtitle = stringResource(R.string.miyorare_source_pack_enabled_summary),
					checked = model.plugins.all { it.state == TsukiPluginState.ENABLED },
					onCheckedChange = { onPackEnabled(model, it) },
					enabled = !busy && model.plugins.none { it.state == TsukiPluginState.BROKEN },
				)
			}
			item(key = "pack-all-sources:$packKey") {
				val allEnabled = model.availableCount > 0 && model.enabledCount == model.availableCount
				SwitchSettingsItem(
					title = stringResource(R.string.miyorare_source_pack_enable_all),
					subtitle = stringResource(
						R.string.tsuki_plugin_sources_summary,
						model.enabledCount,
						model.availableCount,
					),
					checked = allEnabled,
					onCheckedChange = { onAllSourcesEnabled(model, it) },
					enabled = !busy && model.availableCount > 0,
				)
			}
			item(key = "pack-remove:$packKey") {
				ActionSettingsItem(
					title = stringResource(R.string.miyorare_source_pack_remove, model.pack.displayName),
					icon = R.drawable.ic_delete,
					enabled = !busy,
					onClick = { onRemove(model) },
				)
			}
			item(key = "sources-title:$packKey") {
				MiyorareSectionTitle(
					"${stringResource(R.string.tsuki_plugin_sources)} · ${model.pack.displayName}",
				)
			}
			val visibleRows = model.sources.filter { row ->
				(showUnavailableSources || !row.source.isBroken) &&
					(normalizedQuery.isEmpty() ||
						row.source.title.lowercase(Locale.ROOT).contains(normalizedQuery) ||
						row.source.name.lowercase(Locale.ROOT).contains(normalizedQuery) ||
						row.source.contentType.lowercase(Locale.ROOT).contains(normalizedQuery))
			}
			items(
				items = visibleRows,
				key = { row -> "source:$packKey:${row.plugin.pluginId}:${row.source.name}" },
			) { row ->
				val source = row.source
				val checked = source.name in row.plugin.enabledSourceNames
				val subtitle = buildString {
					if (source.locale.isNotBlank()) append(source.locale.uppercase(Locale.ROOT)).append(" · ")
					append(source.contentType)
					if (source.isBroken) append(" · ").append(context.getString(R.string.tsuki_source_broken))
				}
				SwitchSettingsItem(
					title = source.title.ifBlank { source.name },
					subtitle = subtitle,
					checked = checked,
					onCheckedChange = { onSourceEnabled(row, it) },
					enabled = !busy && !source.isBroken,
				)
			}
		}
	}
}

@Composable
private fun MiyorareSectionTitle(text: String) {
	Text(
		text = text,
		style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
		color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
	)
}
