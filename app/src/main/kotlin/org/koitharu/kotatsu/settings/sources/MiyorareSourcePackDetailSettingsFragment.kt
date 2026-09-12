package org.koitharu.kotatsu.settings.sources

import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import coil3.ImageLoader
import coil3.compose.AsyncImage
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
import org.koitharu.kotatsu.settings.compose.SettingsItem
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
class MiyorareSourcePackDetailSettingsFragment : BaseComposeSettingsFragment(R.string.miyorare_source_pack_detail_title) {

	@Inject
	lateinit var pluginManager: TsukiPluginManager

	@Inject
	lateinit var pluginInstaller: TsukiPluginInstaller

	@Inject
	lateinit var imageLoader: ImageLoader

	private var busy by mutableStateOf(false)

	private val pack: MiyorareOfficialSourcePack by lazy {
		val pluginId = arguments?.getString(ARG_PACK_ID).orEmpty()
		MiyorareOfficialSourcePacks.find(pluginId) ?: MiyorareOfficialSourcePacks.packs.first()
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		pluginManager.initialize()
	}

	override fun onResume() {
		super.onResume()
		activity?.title = pack.displayName
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
				MiyorareSourcePackDetailScreen(
					pack = pack,
					plugins = plugins,
					imageLoader = imageLoader,
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

	private fun installOrUpdate() {
		if (busy || !pluginInstaller.isStageAvailable(TsukiPluginProvider.MIYORARE)) return
		runLongOperation {
			val installed = installedPluginsForPack()
			if (installed.isNotEmpty() && !pluginInstaller.hasMiyorarePackUpdate(pack.pluginId)) {
				return@runLongOperation getString(R.string.tsuki_plugin_up_to_date, pack.displayName)
			}
			pluginInstaller.installLatestMiyorare(pack.pluginId)
			getString(R.string.tsuki_plugin_install_success, pack.displayName)
		}
	}

	private fun setPackEnabled(model: MiyorarePackDetailModel, enabled: Boolean) {
		if (busy || model.plugins.isEmpty()) return
		val manageable = model.plugins.filterNot { it.state == TsukiPluginState.BROKEN }
		if (manageable.isEmpty()) return
		lifecycleScope.launch(Dispatchers.IO) {
			val changed = ArrayList<TsukiPluginDescriptor>(manageable.size)
			try {
				manageable.forEach { plugin ->
					val wasEnabled = plugin.state == TsukiPluginState.ENABLED
					if (wasEnabled == enabled) return@forEach
					pluginManager.setEnabled(plugin.provider, plugin.pluginId, enabled)
					changed += plugin
				}
			} catch (error: CancellationException) {
				throw error
			} catch (error: Throwable) {
				// Keep the logical pack coherent if a later shard manifest write fails.
				changed.asReversed().forEach { plugin ->
					runCatching {
						pluginManager.setEnabled(
							plugin.provider,
							plugin.pluginId,
							plugin.state == TsukiPluginState.ENABLED,
						)
					}
				}
				withContext(Dispatchers.Main) { showError(error) }
			}
		}
	}

	private fun setAllSourcesEnabled(model: MiyorarePackDetailModel, enabled: Boolean) {
		if (busy) return
		val states = model.sources.asSequence()
			.filterNot(MiyorareSourcePackRow::isUnavailable)
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

	private fun setSourceEnabled(row: MiyorareSourcePackRow, enabled: Boolean) {
		if (busy || row.isUnavailable()) return
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

	private fun confirmRemove(model: MiyorarePackDetailModel) {
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

	private fun installedPluginsForPack(): List<TsukiPluginDescriptor> =
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

	companion object {
		const val ARG_PACK_ID = "miyorare_source_pack_id"
	}
}

private data class MiyorareSourcePackRow(
	val plugin: TsukiPluginDescriptor,
	val source: TsukiSourceDescriptor,
)

private fun MiyorareSourcePackRow.isUnavailable(): Boolean =
	plugin.state == TsukiPluginState.BROKEN || source.isBroken

private data class MiyorarePackDetailModel(
	val pack: MiyorareOfficialSourcePack,
	val plugins: List<TsukiPluginDescriptor>,
	val sources: List<MiyorareSourcePackRow>,
	val availableCount: Int,
	val enabledCount: Int,
	val versionLabel: String,
)

private fun buildMiyorarePackDetailModel(
	pack: MiyorareOfficialSourcePack,
	plugins: List<TsukiPluginDescriptor>,
): MiyorarePackDetailModel {
	val packPlugins = plugins.filter { plugin ->
		plugin.provider == TsukiPluginProvider.MIYORARE &&
			MiyorareOfficialSourcePacks.findByInstalledPluginId(plugin.pluginId)?.pluginId == pack.pluginId
	}
	val rows = packPlugins.flatMap { plugin ->
		plugin.sources.map { source -> MiyorareSourcePackRow(plugin, source) }
	}.sortedBy { row -> row.source.title.ifBlank { row.source.name }.lowercase(Locale.ROOT) }
	val available = rows.filterNot(MiyorareSourcePackRow::isUnavailable)
	val versions = packPlugins.map { it.version }.distinct()
	return MiyorarePackDetailModel(
		pack = pack,
		plugins = packPlugins,
		sources = rows,
		availableCount = available.size,
		enabledCount = available.count { row -> row.source.name in row.plugin.enabledSourceNames },
		versionLabel = versions.singleOrNull() ?: versions.takeIf { it.isNotEmpty() }?.joinToString(" / ").orEmpty(),
	)
}

@Composable
private fun MiyorareSourcePackDetailScreen(
	pack: MiyorareOfficialSourcePack,
	plugins: List<TsukiPluginDescriptor>,
	imageLoader: ImageLoader,
	busy: Boolean,
	onInstallOrUpdate: () -> Unit,
	onPackEnabled: (MiyorarePackDetailModel, Boolean) -> Unit,
	onAllSourcesEnabled: (MiyorarePackDetailModel, Boolean) -> Unit,
	onSourceEnabled: (MiyorareSourcePackRow, Boolean) -> Unit,
	onRemove: (MiyorarePackDetailModel) -> Unit,
) {
	val context = LocalContext.current
	var sourceQuery by rememberSaveable { mutableStateOf("") }
	var showUnavailableSources by rememberSaveable { mutableStateOf(false) }
	var showTechnicalDetails by rememberSaveable { mutableStateOf(false) }
	val normalizedQuery = sourceQuery.trim().lowercase(Locale.ROOT)
	val model = remember(pack, plugins) { buildMiyorarePackDetailModel(pack, plugins) }
	val unavailableCount = remember(model.sources) { model.sources.count(MiyorareSourcePackRow::isUnavailable) }
	val visibleRows = remember(model.sources, normalizedQuery, showUnavailableSources) {
		model.sources.filter { row ->
			(showUnavailableSources || !row.isUnavailable()) &&
				(normalizedQuery.isEmpty() ||
					row.source.title.lowercase(Locale.ROOT).contains(normalizedQuery) ||
					row.source.name.lowercase(Locale.ROOT).contains(normalizedQuery) ||
					row.source.locale.lowercase(Locale.ROOT).contains(normalizedQuery) ||
					row.source.contentType.lowercase(Locale.ROOT).contains(normalizedQuery))
		}
	}

	LazyColumn(
		modifier = Modifier.fillMaxSize(),
		contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp),
		verticalArrangement = Arrangement.spacedBy(8.dp),
	) {
		item(key = "status") {
			val flag = if (pack.language == "id") "🇮🇩" else "🇬🇧"
			val status = if (model.plugins.isEmpty()) {
				stringResource(R.string.miyorare_source_pack_detail_not_installed)
			} else {
				stringResource(
					R.string.miyorare_source_pack_detail_status,
					model.versionLabel.ifBlank { "—" },
					model.availableCount,
					model.enabledCount,
				)
			}
			SettingsItem(
				title = "$flag ${pack.displayName}",
				subtitle = status,
				icon = R.drawable.ic_launcher_main_art,
				tintIcon = false,
			)
		}

		item(key = "install-update") {
			ActionSettingsItem(
				title = stringResource(R.string.miyorare_source_pack_install_update),
				subtitle = stringResource(R.string.miyorare_source_pack_install_update_summary),
				icon = R.drawable.ic_updated,
				enabled = !busy,
				onClick = onInstallOrUpdate,
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

		if (model.plugins.isNotEmpty()) {
			item(key = "pack-enabled") {
				val manageable = model.plugins.filterNot { it.state == TsukiPluginState.BROKEN }
				SwitchSettingsItem(
					title = stringResource(R.string.miyorare_source_pack_enabled),
					subtitle = stringResource(R.string.miyorare_source_pack_enabled_summary),
					checked = manageable.isNotEmpty() && manageable.all { it.state == TsukiPluginState.ENABLED },
					onCheckedChange = { onPackEnabled(model, it) },
					enabled = !busy && manageable.isNotEmpty(),
				)
			}

			item(key = "all-sources") {
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

			item(key = "technical-toggle") {
				SwitchSettingsItem(
					title = stringResource(R.string.miyorare_source_pack_technical),
					subtitle = stringResource(R.string.miyorare_source_pack_technical_summary),
					checked = showTechnicalDetails,
					onCheckedChange = { showTechnicalDetails = it },
					enabled = !busy,
				)
			}

			if (showTechnicalDetails) {
				model.plugins.forEach { plugin ->
					item(key = "technical:${plugin.pluginId}") {
						InfoSettingsItem(
							title = plugin.displayName,
							subtitle = stringResource(
								R.string.miyorare_source_pack_shard_summary,
								plugin.provider.wireName,
								plugin.version,
								plugin.sources.size,
								Formatter.formatFileSize(context, plugin.fileSize),
							) + "\n" + stringResource(
								R.string.miyorare_source_pack_shard_details,
								plugin.state.name,
								plugin.pluginId,
								plugin.sha256,
								plugin.origin.ifBlank { "—" },
							),
							icon = R.drawable.ic_info_outline,
						)
					}
				}
				item(key = "remove-pack") {
					ActionSettingsItem(
						title = stringResource(R.string.miyorare_source_pack_remove, pack.displayName),
						icon = R.drawable.ic_delete,
						enabled = !busy,
						accentColor = MaterialTheme.colorScheme.error,
						onClick = { onRemove(model) },
					)
				}
			}

			item(key = "sources-header") {
				MiyorareSourcePackSectionTitle(stringResource(R.string.miyorare_source_pack_sources))
			}

			items(
				items = visibleRows,
				key = { row -> "source:${row.plugin.pluginId}:${row.source.name}" },
			) { row ->
				val source = row.source
				val checked = source.name in row.plugin.enabledSourceNames
				val enabled = !busy && !row.isUnavailable()
				val subtitle = buildString {
					if (source.locale.isNotBlank()) append(source.locale.uppercase(Locale.ROOT)).append(" · ")
					append(source.contentType)
					if (row.isUnavailable()) append(" · ").append(context.getString(R.string.tsuki_source_broken))
				}
				SwitchSettingsItem(
					title = source.title.ifBlank { source.name },
					subtitle = subtitle,
					checked = checked,
					onCheckedChange = { onSourceEnabled(row, it) },
					enabled = enabled,
					leading = {
						MiyorareSourceLogo(
							source = source,
							imageLoader = imageLoader,
							enabled = enabled,
						)
					},
				)
			}
		}
	}
}

@Composable
private fun MiyorareSourceLogo(
	source: TsukiSourceDescriptor,
	imageLoader: ImageLoader,
	enabled: Boolean,
) {
	val logoModel = remember(source.iconUrl) {
		source.iconUrl
			?.trim()
			?.takeIf { url ->
				url.length <= MAX_SOURCE_ICON_URL_LENGTH &&
					(url.startsWith("https://") || url.startsWith("http://"))
			}
			?: R.drawable.ic_manga_source
	}
	Surface(
		modifier = Modifier.size(40.dp),
		shape = RoundedCornerShape(12.dp),
		color = MaterialTheme.colorScheme.surfaceContainerHighest,
	) {
		AsyncImage(
			model = logoModel,
			imageLoader = imageLoader,
			contentDescription = null,
			contentScale = ContentScale.Fit,
			placeholder = painterResource(R.drawable.ic_manga_source),
			error = painterResource(R.drawable.ic_manga_source),
			fallback = painterResource(R.drawable.ic_manga_source),
			modifier = Modifier
				.fillMaxSize()
				.padding(6.dp)
				.alpha(if (enabled) 1f else 0.42f),
		)
	}
}

@Composable
private fun MiyorareSourcePackSectionTitle(text: String) {
	Text(
		text = text,
		style = MaterialTheme.typography.labelLarge,
		color = MaterialTheme.colorScheme.primary,
	)
}

private const val MAX_SOURCE_ICON_URL_LENGTH = 2_048
