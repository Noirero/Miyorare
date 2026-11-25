package org.koitharu.kotatsu.settings

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.provider.Settings as SystemSettings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.viewModels
import org.koitharu.kotatsu.download.domain.maintenance.DownloadMaintenanceState
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.os.OpenDocumentTreeHelper
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.DownloadFormat
import org.koitharu.kotatsu.core.prefs.TriStateOption
import org.koitharu.kotatsu.core.util.ext.getQuantityStringSafe
import org.koitharu.kotatsu.core.util.ext.powerManager
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.core.util.ext.resolveFile
import org.koitharu.kotatsu.core.util.ext.tryLaunch
import org.koitharu.kotatsu.download.domain.DownloadDestinationStore
import org.koitharu.kotatsu.download.ui.worker.DownloadWorker
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.favourites.domain.ExhentaiPresenceDiscovery
import org.koitharu.kotatsu.favourites.domain.ExhentaiPresenceScanState
import org.koitharu.kotatsu.local.data.LocalStorageManager
import org.koitharu.kotatsu.parsers.util.names
import org.koitharu.kotatsu.settings.compose.ActionSettingsItem
import org.koitharu.kotatsu.settings.compose.BaseComposeSettingsFragment
import org.koitharu.kotatsu.settings.compose.MiyorareSettingsTheme
import org.koitharu.kotatsu.settings.compose.ListSettingsItem
import org.koitharu.kotatsu.settings.compose.PlainInfoSettingsItem
import org.koitharu.kotatsu.settings.compose.SettingsGroup
import org.koitharu.kotatsu.settings.compose.SettingsScaffold
import org.koitharu.kotatsu.settings.compose.SwitchSettingsItem
import org.koitharu.kotatsu.settings.compose.rememberBooleanPref
import org.koitharu.kotatsu.settings.compose.rememberStringPref
import javax.inject.Inject

@AndroidEntryPoint
class DownloadsSettingsFragment :
	BaseComposeSettingsFragment(R.string.settings_downloads_local_files),
	SharedPreferences.OnSharedPreferenceChangeListener {

	@Inject
	lateinit var storageManager: LocalStorageManager

	@Inject
	lateinit var destinationStore: DownloadDestinationStore

	@Inject
	lateinit var downloadsScheduler: DownloadWorker.Scheduler

	@Inject
	lateinit var settings: AppSettings

	private val maintenanceViewModel: DownloadMaintenanceViewModel by viewModels()

	private var refreshJob: Job? = null
	private val storageSummary = MutableStateFlow<String?>(null)
	private val directoryCount = MutableStateFlow(0)
	private val pagesDirSummary = MutableStateFlow<String?>(null)
	private val dozeAvailable = MutableStateFlow(false)

	private val pickFileTreeLauncher = OpenDocumentTreeHelper(this) {
		if (it != null) onDirectoryPicked(it)
	}

	private val startForDozeResult = registerForActivityResult(
		ActivityResultContracts.StartActivityForResult(),
	) {
		dozeAvailable.value = isDozeIgnoreAvailable()
	}

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View = ComposeView(requireContext()).apply {
		setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		setContent {
			MiyorareSettingsTheme("downloads") {
				DownloadsScreen(
					storageSummary = storageSummary.asStateFlow(),
					directoryCount = directoryCount.asStateFlow(),
					pagesDirSummary = pagesDirSummary.asStateFlow(),
					dozeAvailable = dozeAvailable.asStateFlow(),
					onPickLocalManga = { router.openDirectoriesSettings() },
					onPickLocalStorage = { router.showDirectorySelectDialog() },
					onMeteredChanged = { updateDownloadsConstraints() },
					onPickPagesDir = ::launchPagesDirPicker,
					onIgnoreDoze = ::startIgnoreDoseActivity,
					maintenanceState = maintenanceViewModel.state,
					onRebuildDownloadsIndex = maintenanceViewModel::start,
					presenceState = maintenanceViewModel.presenceState,
					onFullExhentaiScan = maintenanceViewModel::scanExhentaiPresenceFull,
				)
			}
		}
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		settings.subscribe(this)
		dozeAvailable.value = isDozeIgnoreAvailable()
	}

	override fun onResume() {
		super.onResume()
		refreshAsync()
	}

	override fun onDestroyView() {
		settings.unsubscribe(this)
		super.onDestroyView()
	}

	override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
		when (key) {
			AppSettings.KEY_LOCAL_STORAGE,
			AppSettings.KEY_LOCAL_MANGA_DIRS,
			AppSettings.KEY_PAGES_SAVE_DIR -> refreshAsync()
		}
	}

	private fun refreshAsync() {
		val owner = viewLifecycleOwnerLiveData.value ?: return
		refreshJob?.cancel()
		refreshJob = owner.lifecycleScope.launch {
			val (storage, availability) = withContext(Dispatchers.IO) {
				val root = destinationStore.effectiveRoot(FavouriteSpace.NORMAL)
				root to downloadDestinationAvailability(root)
			}
			storageSummary.value = when (availability) {
				DownloadDestinationAvailability.AVAILABLE ->
					storageManager.getDirectoryDisplayName(checkNotNull(storage), isFullPath = true)
				DownloadDestinationAvailability.UNAVAILABLE -> if (storage == null) {
					getString(R.string.not_available)
				} else {
					getString(R.string.download_destination_summary_unavailable, storage.path)
				}
				DownloadDestinationAvailability.READ_ONLY ->
					getString(R.string.download_destination_read_only, checkNotNull(storage).path)
			}
			directoryCount.value = withContext(Dispatchers.IO) { storageManager.getReadableDirs().size }
			val df = withContext(Dispatchers.IO) { settings.getPagesSaveDir(requireContext()) }
			pagesDirSummary.value = df?.uri?.resolveFile(requireContext())?.path
				?: df?.uri?.toString()
		}
	}

	private fun onDirectoryPicked(uri: Uri) {
		storageManager.takePermissions(uri)
		val doc = DocumentFile.fromTreeUri(requireContext(), uri)?.takeIf { it.canWrite() }
		settings.setPagesSaveDir(doc?.uri)
	}

	private fun launchPagesDirPicker() {
		val current = settings.getPagesSaveDir(requireContext())?.uri
		if (!pickFileTreeLauncher.tryLaunch(current)) {
			Snackbar.make(
				requireView(),
				R.string.operation_not_supported,
				Snackbar.LENGTH_SHORT,
			).show()
		}
	}

	private fun updateDownloadsConstraints() {
		lifecycleScope.launch {
			try {
				withContext(Dispatchers.Default) {
					val option = when (settings.allowDownloadOnMeteredNetwork) {
						TriStateOption.ENABLED -> true
						TriStateOption.ASK -> return@withContext
						TriStateOption.DISABLED -> false
					}
					downloadsScheduler.updateConstraints(option)
				}
			} catch (e: Exception) {
				e.printStackTraceDebug()
			}
		}
	}

	private fun isDozeIgnoreAvailable(): Boolean {
		val ctx = context ?: return false
		val pm = ctx.powerManager ?: return false
		return !pm.isIgnoringBatteryOptimizations(ctx.packageName)
	}

	@SuppressLint("BatteryLife")
	private fun startIgnoreDoseActivity() {
		val ctx = context ?: return
		val pm = ctx.powerManager ?: return
		if (pm.isIgnoringBatteryOptimizations(ctx.packageName)) return
		try {
			val intent = Intent(
				SystemSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
				"package:${ctx.packageName}".toUri(),
			)
			startForDozeResult.launch(intent)
		} catch (e: ActivityNotFoundException) {
			Snackbar.make(
				requireView(),
				R.string.operation_not_supported,
				Snackbar.LENGTH_SHORT,
			).show()
		}
	}


}

@Composable
private fun DownloadsScreen(
	storageSummary: StateFlow<String?>,
	directoryCount: StateFlow<Int>,
	pagesDirSummary: StateFlow<String?>,
	dozeAvailable: StateFlow<Boolean>,
	onPickLocalManga: () -> Unit,
	onPickLocalStorage: () -> Unit,
	onMeteredChanged: () -> Unit,
	onPickPagesDir: () -> Unit,
	onIgnoreDoze: () -> Unit,
	maintenanceState: StateFlow<DownloadMaintenanceState>,
	onRebuildDownloadsIndex: () -> Unit,
	presenceState: StateFlow<ExhentaiPresenceScanState>,
	onFullExhentaiScan: () -> Unit,
) {
	val ctx = LocalContext.current
	val storage by storageSummary.collectAsState()
	val dirCount by directoryCount.collectAsState()
	val pagesDir by pagesDirSummary.collectAsState()
	val showDoze by dozeAvailable.collectAsState()
	val downloadFormats = remember {
		ctx.resources.getStringArray(R.array.download_formats).toList()
	}
	val downloadFormatValues = remember { DownloadFormat.entries.names().toList() }
	val meteredOptions = remember {
		ctx.resources.getStringArray(R.array.metered_network_options).toList()
	}
	val meteredOptionValues = remember { TriStateOption.entries.names().toList() }

	var downloadFormat by rememberStringPref(
		AppSettings.KEY_DOWNLOADS_FORMAT,
		DownloadFormat.AUTOMATIC.name,
	)
	var metered by rememberStringPref(
		AppSettings.KEY_DOWNLOADS_METERED_NETWORK,
		TriStateOption.ASK.name,
	)
	var pagesDirAsk by rememberBooleanPref(AppSettings.KEY_PAGES_SAVE_ASK, true)
	var privateNotificationDetails by rememberBooleanPref(AppSettings.KEY_PRIVATE_DOWNLOAD_NOTIFICATION_DETAILS, false)
	var scanExhentaiOnResume by rememberBooleanPref(ExhentaiPresenceDiscovery.KEY_SCAN_ON_RESUME, true)
	val presence by presenceState.collectAsState()

	LaunchedEffect(metered) { onMeteredChanged() }

	SettingsScaffold {
		item {
			SettingsGroup(title = stringResource(R.string.settings_group_downloads)) {
				item { pos ->
					ActionSettingsItem(
						title = stringResource(R.string.manga_save_location),
						subtitle = storage,
						icon = R.drawable.ic_storage,
						shape = pos.shape,
						onClick = onPickLocalStorage,
					)
				}
				item { pos ->
					ListSettingsItem(
						title = stringResource(R.string.preferred_download_format),
						entries = downloadFormats,
						entryValues = downloadFormatValues,
						selectedValue = downloadFormat,
						onValueChange = { downloadFormat = it },
						icon = R.drawable.ic_file_zip,
						shape = pos.shape,
					)
				}
				item { pos ->
					ListSettingsItem(
						title = stringResource(R.string.download_over_cellular),
						entries = meteredOptions,
						entryValues = meteredOptionValues,
						selectedValue = metered,
						onValueChange = { metered = it },
						icon = R.drawable.ic_network_cellular,
						shape = pos.shape,
					)
				}
			}
		}
		item { DownloadStructureInfoItem(DownloadStructureInfo.DOWNLOADS) }
		item {
			PlainInfoSettingsItem(
				text = stringResource(R.string.downloads_settings_info),
				icon = R.drawable.ic_info_outline,
			)
		}
		item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
		item {
			SettingsGroup(title = stringResource(R.string.settings_group_on_device_files)) {
				item { pos ->
					ActionSettingsItem(
						title = stringResource(R.string.local_manga_directories),
						subtitle = ctx.resources.getQuantityStringSafe(R.plurals.items, dirCount, dirCount),
						icon = R.drawable.ic_folder_file,
						shape = pos.shape,
						onClick = onPickLocalManga,
					)
				}
			}
		}
		item { DownloadStructureInfoItem(DownloadStructureInfo.LOCAL) }
		item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
		item {
			SettingsGroup(title = stringResource(R.string.notifications)) {
				item { pos ->
					SwitchSettingsItem(
						title = stringResource(R.string.private_download_notification_details),
						subtitle = stringResource(R.string.private_download_notification_details_summary),
						checked = privateNotificationDetails,
						onCheckedChange = { privateNotificationDetails = it },
						icon = R.drawable.ic_notification,
						shape = pos.shape,
					)
				}
			}
		}
		item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
		item { DownloadPerformanceSettingsSection() }
		item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
		item {
			SettingsGroup(title = stringResource(R.string.pages_saving)) {
				item { pos ->
					ActionSettingsItem(
						title = stringResource(R.string.default_page_save_dir),
						subtitle = pagesDir ?: stringResource(androidx.preference.R.string.not_set),
						icon = R.drawable.ic_save,
						shape = pos.shape,
						onClick = onPickPagesDir,
					)
				}
				item { pos ->
					SwitchSettingsItem(
						title = stringResource(R.string.ask_for_dest_dir_every_time),
						checked = pagesDirAsk,
						onCheckedChange = { pagesDirAsk = it },
						icon = R.drawable.ic_alert_outline,
						shape = pos.shape,
					)
				}
			}
		}
		item { DownloadStructureInfoItem(DownloadStructureInfo.SAVED_PAGES) }
		item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
		item {
			SettingsGroup(title = stringResource(R.string.settings_download_maintenance)) {
				item { pos ->
					SwitchSettingsItem(
						title = stringResource(R.string.exhentai_presence_resume_scan),
						subtitle = stringResource(R.string.exhentai_presence_resume_scan_summary),
						checked = scanExhentaiOnResume,
						onCheckedChange = { scanExhentaiOnResume = it },
						icon = R.drawable.ic_sync,
						shape = pos.shape,
					)
				}
				item { pos ->
					ActionSettingsItem(
						title = stringResource(R.string.exhentai_presence_full_scan),
						subtitle = if (presence.running) {
							stringResource(R.string.exhentai_presence_progress, presence.scanned, presence.total)
						} else if (presence.unavailableSpaces.isNotEmpty()) {
							stringResource(R.string.exhentai_presence_unavailable)
						} else {
							stringResource(R.string.exhentai_presence_full_scan_summary)
						},
						icon = R.drawable.ic_sync,
						shape = pos.shape,
						enabled = !presence.running,
						onClick = onFullExhentaiScan,
					)
				}
				item { pos ->
					DownloadMaintenanceSettingsItem(maintenanceState, pos.shape, onRebuildDownloadsIndex)
				}
				if (showDoze) {
					item { pos ->
						ActionSettingsItem(
							title = stringResource(R.string.disable_battery_optimization),
							subtitle = stringResource(R.string.disable_battery_optimization_summary_downloads),
							icon = R.drawable.ic_battery_outline,
							shape = pos.shape,
							onClick = onIgnoreDoze,
						)
					}
				}
			}
		}
		item { Spacer(Modifier.height(24.dp).fillMaxWidth()) }
	}
}
