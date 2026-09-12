package org.koitharu.kotatsu.settings.sources

import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
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
import org.koitharu.kotatsu.extensions.runtime.getExternalExtensionLanguageDisplayName
import org.koitharu.kotatsu.settings.compose.ActionSettingsItem
import org.koitharu.kotatsu.settings.compose.BaseComposeSettingsFragment
import org.koitharu.kotatsu.settings.compose.DropSauceTheme
import org.koitharu.kotatsu.settings.compose.InfoSettingsItem
import org.koitharu.kotatsu.settings.compose.SwitchSettingsItem
import org.koitharu.kotatsu.tsuki.TsukiPluginInstaller
import org.koitharu.kotatsu.tsuki.TsukiPluginManager
import org.koitharu.kotatsu.tsuki.TsukiPluginValidator
import org.koitharu.kotatsu.tsuki.model.TsukiPluginDescriptor
import org.koitharu.kotatsu.tsuki.model.TsukiPluginProvider
import org.koitharu.kotatsu.tsuki.model.TsukiPluginState
import org.koitharu.kotatsu.tsuki.model.TsukiSourceDescriptor
import org.koitharu.kotatsu.tsuki.model.TsukiSourceIdentity
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class TsukiPluginsSettingsFragment : BaseComposeSettingsFragment(R.string.tsuki_plugins_title) {

	@Inject
	lateinit var pluginManager: TsukiPluginManager

	@Inject
	lateinit var pluginInstaller: TsukiPluginInstaller

	private var busy by mutableStateOf(false)

	private val importJarLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
		if (uri != null && !busy) {
			runLongOperation {
				val plugin = pluginInstaller.installLocal(uri)
				getString(R.string.tsuki_plugin_import_success, plugin.displayName)
			}
		}
	}

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
				TsukiPluginsScreen(
					plugins = plugins.filterNot { it.provider == TsukiPluginProvider.MIYORARE },
					busy = busy,
					onInstallOfficial = ::installOrUpdateOfficial,
					onImportGitHub = ::promptGitHubImport,
					onImportLocal = ::confirmLocalImport,
					onPluginEnabled = ::setPluginEnabled,
					onLanguageEnabled = ::setLanguageEnabled,
					onSourceEnabled = ::setSourceEnabled,
					canCheckUpdate = pluginInstaller::supportsRemoteUpdate,
					onCheckUpdate = ::checkUpdate,
					onRemove = ::confirmRemove,
				)
			}
		}
	}

	private fun installOrUpdateOfficial(provider: TsukiPluginProvider) {
		if (busy || !pluginInstaller.isStageAvailable(provider)) return
		runLongOperation {
			val installed = pluginManager.getPlugins().firstOrNull { it.provider == provider }
			if (installed != null && pluginInstaller.checkForUpdate(installed) == null) {
				return@runLongOperation getString(R.string.tsuki_plugin_up_to_date, installed.displayName)
			}
			val plugin = pluginInstaller.installLatest(provider)
			getString(R.string.tsuki_plugin_install_success, plugin.displayName)
		}
	}

	private fun checkUpdate(plugin: TsukiPluginDescriptor) {
		if (busy || !pluginInstaller.supportsRemoteUpdate(plugin)) return
		runLongOperation {
			if (pluginInstaller.checkForUpdate(plugin) == null) {
				getString(R.string.tsuki_plugin_up_to_date, plugin.displayName)
			} else {
				val updated = pluginInstaller.installLatest(plugin)
				getString(R.string.tsuki_plugin_install_success, updated.displayName)
			}
		}
	}

	private fun promptGitHubImport() {
		if (busy) return
		val input = EditText(requireContext()).apply {
			hint = getString(R.string.tsuki_github_import_hint)
			isSingleLine = true
		}
		MaterialAlertDialogBuilder(requireContext())
			.setTitle(R.string.tsuki_github_import_title)
			.setMessage(R.string.tsuki_github_import_message)
			.setView(input)
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(R.string.tsuki_github_import_install) { _, _ ->
				val repository = input.text?.toString().orEmpty().trim()
				if (repository.isBlank()) {
					showError(IllegalArgumentException(getString(R.string.tsuki_github_import_hint)))
					return@setPositiveButton
				}
				runLongOperation {
					val plugin = pluginInstaller.installFromGitHubRepository(repository)
					getString(R.string.tsuki_plugin_install_success, plugin.displayName)
				}
			}
			.show()
	}

	private fun confirmLocalImport() {
		if (busy) return
		MaterialAlertDialogBuilder(requireContext())
			.setTitle(R.string.tsuki_local_import_warning_title)
			.setMessage(R.string.tsuki_local_import_warning_message)
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(R.string.tsuki_local_import_continue) { _, _ ->
				if (!busy) importJarLauncher.launch(arrayOf("*/*"))
			}
			.show()
	}

	private fun setPluginEnabled(plugin: TsukiPluginDescriptor, enabled: Boolean) {
		if (busy) return
		lifecycleScope.launch(Dispatchers.IO) {
			try {
				pluginManager.setEnabled(plugin.provider, plugin.pluginId, enabled)
			} catch (error: CancellationException) {
				throw error
			} catch (error: Throwable) {
				withContext(Dispatchers.Main) { showError(error) }
			}
		}
	}

	private fun setLanguageEnabled(plugin: TsukiPluginDescriptor, localeKey: String, enabled: Boolean) {
		if (busy) return
		val states = plugin.sources.asSequence()
			.filterNot { it.isBroken }
			.filter { normalizedTsukiLanguage(it.locale) == localeKey }
			.associate { source ->
				TsukiSourceIdentity(plugin.provider, plugin.pluginId, source.name) to enabled
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

	private fun setSourceEnabled(plugin: TsukiPluginDescriptor, source: TsukiSourceDescriptor, enabled: Boolean) {
		if (busy || source.isBroken) return
		lifecycleScope.launch(Dispatchers.IO) {
			try {
				pluginManager.setSourceEnabled(
					TsukiSourceIdentity(plugin.provider, plugin.pluginId, source.name),
					enabled,
				)
			} catch (error: CancellationException) {
				throw error
			} catch (error: Throwable) {
				withContext(Dispatchers.Main) { showError(error) }
			}
		}
	}

	private fun confirmRemove(plugin: TsukiPluginDescriptor) {
		MaterialAlertDialogBuilder(requireContext())
			.setTitle(R.string.tsuki_plugin_remove_confirm_title)
			.setMessage(getString(R.string.tsuki_plugin_remove_confirm_message, plugin.displayName))
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(R.string.remove) { _, _ ->
				if (busy) return@setPositiveButton
				lifecycleScope.launch(Dispatchers.IO) {
					try {
						pluginManager.remove(plugin.provider, plugin.pluginId)
						withContext(Dispatchers.Main) {
							Toast.makeText(
								requireContext(),
								getString(R.string.tsuki_plugin_removed, plugin.displayName),
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

	private fun runLongOperation(block: suspend () -> String) {
		if (busy) return
		busy = true
		lifecycleScope.launch {
			try {
				val message = block()
				Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
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

private data class TsukiLanguageScreenModel(
	val localeKey: String,
	val totalCount: Int,
	val enabledCount: Int,
)

private data class TsukiPluginScreenModel(
	val plugin: TsukiPluginDescriptor,
	val availableCount: Int,
	val enabledCount: Int,
	val languages: List<TsukiLanguageScreenModel>,
	val filteredSources: List<TsukiSourceDescriptor>,
)

@Composable
private fun TsukiPluginsScreen(
	plugins: List<TsukiPluginDescriptor>,
	busy: Boolean,
	onInstallOfficial: (TsukiPluginProvider) -> Unit,
	onImportGitHub: () -> Unit,
	onImportLocal: () -> Unit,
	onPluginEnabled: (TsukiPluginDescriptor, Boolean) -> Unit,
	onLanguageEnabled: (TsukiPluginDescriptor, String, Boolean) -> Unit,
	onSourceEnabled: (TsukiPluginDescriptor, TsukiSourceDescriptor, Boolean) -> Unit,
	canCheckUpdate: (TsukiPluginDescriptor) -> Boolean,
	onCheckUpdate: (TsukiPluginDescriptor) -> Unit,
	onRemove: (TsukiPluginDescriptor) -> Unit,
) {
	val context = LocalContext.current
	var sourceQuery by rememberSaveable { mutableStateOf("") }
	var showUnavailableSources by rememberSaveable { mutableStateOf(false) }
	val normalizedQuery = sourceQuery.trim().lowercase(Locale.ROOT)
	val unavailableSourceCount = remember(plugins) {
		plugins.sumOf { plugin -> plugin.sources.count { it.isBroken } }
	}
	val baseModels = remember(plugins) {
		plugins.map { plugin ->
			val available = plugin.sources.filterNot { it.isBroken }
			val enabled = plugin.enabledSourceNames
			val languages = available.groupBy { normalizedTsukiLanguage(it.locale) }
				.map { (localeKey, sources) ->
					TsukiLanguageScreenModel(
						localeKey = localeKey,
						totalCount = sources.size,
						enabledCount = sources.count { it.name in enabled },
					)
				}
				.sortedBy { it.localeKey }
			TsukiPluginScreenModel(
				plugin = plugin,
				availableCount = available.size,
				enabledCount = available.count { it.name in enabled },
				languages = languages,
				filteredSources = available,
			)
		}
	}
	val pluginModels = remember(baseModels, normalizedQuery, showUnavailableSources) {
		baseModels.map { model ->
			model.copy(
				filteredSources = model.plugin.sources.asSequence()
					.filter { source -> showUnavailableSources || !source.isBroken }
					.filter { source ->
						normalizedQuery.isEmpty() ||
							source.title.lowercase(Locale.ROOT).contains(normalizedQuery) ||
							source.name.lowercase(Locale.ROOT).contains(normalizedQuery) ||
							source.locale.lowercase(Locale.ROOT).contains(normalizedQuery) ||
							source.contentType.lowercase(Locale.ROOT).contains(normalizedQuery)
					}
					.toList(),
			)
		}
	}

	LazyColumn(
		modifier = Modifier.fillMaxSize(),
		contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp),
		verticalArrangement = Arrangement.spacedBy(8.dp),
	) {
		item(key = "intro") {
			InfoSettingsItem(
				title = stringResource(R.string.tsuki_plugins_title),
				subtitle = stringResource(R.string.tsuki_plugins_summary),
				icon = R.drawable.ic_info_outline,
			)
		}
		item(key = "compatible-header") { SectionTitle(stringResource(R.string.tsuki_plugins_compatible)) }
		item(key = "install-uma") {
			ActionSettingsItem(
				title = stringResource(R.string.tsuki_plugins_install_uma),
				subtitle = stringResource(R.string.tsuki_plugins_install_uma_summary),
				icon = R.drawable.ic_download,
				enabled = !busy,
				onClick = { onInstallOfficial(TsukiPluginProvider.UMA) },
			)
		}
		item(key = "install-gekkoushi") {
			ActionSettingsItem(
				title = stringResource(R.string.tsuki_plugins_install_gekkoushi),
				subtitle = stringResource(R.string.tsuki_plugins_install_gekkoushi_summary),
				icon = R.drawable.ic_download,
				enabled = !busy,
				onClick = { onInstallOfficial(TsukiPluginProvider.GEKKOUSHI) },
			)
		}
		item(key = "import-github") {
			ActionSettingsItem(
				title = stringResource(R.string.tsuki_github_import_action),
				subtitle = stringResource(R.string.tsuki_github_import_summary),
				icon = R.drawable.ic_add,
				enabled = !busy,
				onClick = onImportGitHub,
			)
		}
		item(key = "import-local") {
			ActionSettingsItem(
				title = stringResource(R.string.tsuki_plugins_import_local),
				subtitle = stringResource(R.string.tsuki_plugins_import_local_summary),
				icon = R.drawable.ic_add,
				enabled = !busy,
				onClick = onImportLocal,
			)
		}
		if (busy) {
			item(key = "working") {
				InfoSettingsItem(
					title = stringResource(R.string.tsuki_plugin_working),
					icon = R.drawable.ic_updated,
				)
			}
		}
		item(key = "optional-note") {
			InfoSettingsItem(
				title = stringResource(R.string.tsuki_plugin_optional_note),
				icon = R.drawable.ic_info_outline,
			)
		}

		item(key = "installed-header") { SectionTitle(stringResource(R.string.tsuki_plugins_installed)) }
		if (plugins.isEmpty()) {
			item(key = "none") {
				InfoSettingsItem(title = stringResource(R.string.tsuki_plugins_none))
			}
		} else {
			item(key = "source-search") {
				OutlinedTextField(
					value = sourceQuery,
					onValueChange = { sourceQuery = it },
					modifier = Modifier.fillMaxWidth(),
					singleLine = true,
					label = { Text(stringResource(R.string.tsuki_source_search_hint)) },
				)
			}
			if (unavailableSourceCount > 0) {
				item(key = "show-unavailable-sources") {
					SwitchSettingsItem(
						title = stringResource(R.string.tsuki_source_show_unavailable),
						subtitle = stringResource(
							R.string.tsuki_source_show_unavailable_summary,
							unavailableSourceCount,
						),
						checked = showUnavailableSources,
						onCheckedChange = { showUnavailableSources = it },
						enabled = !busy,
					)
				}
			}
		}

		pluginModels.forEach { model ->
			val plugin = model.plugin
			val pluginKey = "${plugin.provider.wireName}:${plugin.pluginId}"
			item(key = "plugin-info:$pluginKey") {
				val api = plugin.requiredApi ?: stringResource(
					R.string.tsuki_plugin_api_not_declared,
					TsukiPluginValidator.HOST_API_VERSION,
				)
				InfoSettingsItem(
					title = plugin.displayName,
					subtitle = stringResource(
						R.string.tsuki_plugin_security_metadata,
						plugin.pluginId,
						plugin.provider.wireName,
						plugin.version,
						api,
						plugin.state.name,
						plugin.compatibility.name,
						plugin.sources.size,
						Formatter.formatFileSize(context, plugin.fileSize),
						plugin.sha256,
						plugin.origin.ifBlank { "—" },
					),
					icon = R.drawable.ic_info_outline,
				)
			}
			plugin.failureReason?.takeIf { it.isNotBlank() }?.let { reason ->
				item(key = "plugin-failure:$pluginKey") {
					InfoSettingsItem(
						title = stringResource(R.string.tsuki_plugin_failure_title),
						subtitle = stringResource(R.string.tsuki_plugin_failure_summary, reason),
						icon = R.drawable.ic_info_outline,
					)
				}
			}
			item(key = "plugin-enabled:$pluginKey") {
				SwitchSettingsItem(
					title = stringResource(R.string.tsuki_plugin_enabled),
					subtitle = stringResource(R.string.tsuki_plugin_enabled_summary),
					checked = plugin.state == TsukiPluginState.ENABLED,
					onCheckedChange = { onPluginEnabled(plugin, it) },
					icon = R.drawable.ic_download,
					enabled = !busy && plugin.state != TsukiPluginState.BROKEN,
				)
			}
			if (canCheckUpdate(plugin)) {
				item(key = "plugin-update:$pluginKey") {
					ActionSettingsItem(
						title = stringResource(R.string.tsuki_plugin_check_update),
						subtitle = stringResource(R.string.tsuki_plugin_check_update_summary),
						icon = R.drawable.ic_updated,
						enabled = !busy,
						onClick = { onCheckUpdate(plugin) },
					)
				}
			}
			item(key = "plugin-remove:$pluginKey") {
				ActionSettingsItem(
					title = stringResource(R.string.tsuki_plugin_remove, plugin.displayName),
					icon = R.drawable.ic_delete,
					enabled = !busy,
					accentColor = MaterialTheme.colorScheme.error,
					onClick = { onRemove(plugin) },
				)
			}
			item(key = "languages-header:$pluginKey") {
				SectionTitle("${stringResource(R.string.tsuki_plugin_languages)} · ${plugin.displayName}")
			}
			items(
				items = model.languages,
				key = { language -> "language:$pluginKey:${language.localeKey}" },
			) { language ->
				val checked = language.totalCount > 0 && language.enabledCount == language.totalCount
				val title = if (language.localeKey == OTHER_LANGUAGE_KEY) {
					stringResource(R.string.tsuki_plugin_language_other)
				} else {
					"${getExternalExtensionLanguageDisplayName(language.localeKey)} (${language.localeKey.uppercase(Locale.ROOT)})"
				}
				SwitchSettingsItem(
					title = title,
					subtitle = stringResource(
					R.string.tsuki_plugin_language_summary,
					language.enabledCount,
					language.totalCount,
				),
					checked = checked,
					onCheckedChange = { onLanguageEnabled(plugin, language.localeKey, it) },
					enabled = !busy,
				)
			}
			item(key = "sources-header:$pluginKey") {
				SectionTitle(
					"${stringResource(R.string.tsuki_plugin_sources)} · ${plugin.displayName}\n" +
						stringResource(
							R.string.tsuki_plugin_sources_summary,
							model.enabledCount,
							model.availableCount,
						),
				)
			}
			items(
				items = model.filteredSources,
				key = { source -> "source:$pluginKey:${source.name}" },
			) { source ->
				val checked = source.name in plugin.enabledSourceNames
				val subtitle = buildString {
					if (source.locale.isNotBlank()) append(source.locale.uppercase(Locale.ROOT)).append(" · ")
					append(source.contentType)
					if (source.isBroken) append(" · ").append(context.getString(R.string.tsuki_source_broken))
				}
				SwitchSettingsItem(
					title = source.title.ifBlank { source.name },
					subtitle = subtitle,
					checked = checked,
					onCheckedChange = { onSourceEnabled(plugin, source, it) },
					enabled = !busy && !source.isBroken,
				)
			}
		}
	}
}

private fun normalizedTsukiLanguage(value: String): String =
	value.trim().lowercase(Locale.ROOT).ifBlank { OTHER_LANGUAGE_KEY }

private const val OTHER_LANGUAGE_KEY = "other"

@Composable
private fun SectionTitle(text: String) {
	Text(
		text = text,
		style = MaterialTheme.typography.labelLarge,
		color = MaterialTheme.colorScheme.primary,
		modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 2.dp),
	)
}
