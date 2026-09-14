package org.koitharu.kotatsu.settings.tracker

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.provider.Settings as SystemSettings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.viewModels
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.FavouriteCategory
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.settings.SettingsActivity
import org.koitharu.kotatsu.settings.compose.ActionSettingsItem
import org.koitharu.kotatsu.settings.compose.BaseComposeSettingsFragment
import org.koitharu.kotatsu.settings.compose.DropSauceTheme
import org.koitharu.kotatsu.settings.compose.ListSettingsItem
import org.koitharu.kotatsu.settings.compose.MultiSelectSettingsItem
import org.koitharu.kotatsu.settings.compose.NavigationSettingsItem
import org.koitharu.kotatsu.settings.compose.SettingsGroup
import org.koitharu.kotatsu.settings.compose.SettingsScaffold
import org.koitharu.kotatsu.settings.compose.SwitchSettingsItem
import org.koitharu.kotatsu.settings.compose.rememberBooleanPref
import org.koitharu.kotatsu.settings.compose.rememberStringPref
import org.koitharu.kotatsu.settings.compose.rememberStringSetPref
import org.koitharu.kotatsu.tracker.work.TrackerNotificationHelper
import javax.inject.Inject
import kotlin.math.roundToInt

@AndroidEntryPoint
class TrackerSettingsFragment : BaseComposeSettingsFragment(R.string.settings_chapter_updates) {

    @Inject
    lateinit var settings: AppSettings

    @Inject
    lateinit var notificationHelper: TrackerNotificationHelper

    private val viewModel by viewModels<TrackerSettingsViewModel>()
    private val notificationsEnabledState = MutableStateFlow(false)
    private val categoriesCountState = MutableStateFlow<IntArray?>(null)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            DropSauceTheme {
                val notificationsEnabled by notificationsEnabledState.asStateFlow().collectAsState()
                val categoriesCount by categoriesCountState.asStateFlow().collectAsState()
                val categories by viewModel.categories.collectAsState()
                TrackerScreen(
                    notificationsEnabled = notificationsEnabled,
                    categoriesCount = categoriesCount,
                    categories = categories,
                    onTrackCategories = router::showTrackerCategoriesConfigSheet,
                    onDownloadCategoriesChange = viewModel::setNewChaptersDownloadCategories,
                    onNotificationsSettings = ::openNotificationsSettings,
                    onOpenDiagnostics = {
                        (activity as? SettingsActivity)?.openFragment(
                            TrackerDiagnosticsSettingsFragment::class.java,
                            null,
                            isFromRoot = false,
                        )
                    },
                )
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel.categoriesCount.observe(viewLifecycleOwner) { categoriesCountState.value = it }
    }

    override fun onResume() {
        super.onResume()
        notificationsEnabledState.value = notificationHelper.getAreNotificationsEnabled()
    }

    private fun openNotificationsSettings() {
        val intent = Intent(SystemSettings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(SystemSettings.EXTRA_APP_PACKAGE, requireContext().packageName)
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Snackbar.make(requireView(), R.string.operation_not_supported, Snackbar.LENGTH_SHORT).show()
        }
    }
}

@Composable
private fun TrackerScreen(
    notificationsEnabled: Boolean,
    categoriesCount: IntArray?,
    categories: List<FavouriteCategory>,
    onTrackCategories: () -> Unit,
    onDownloadCategoriesChange: (Set<Long>) -> Unit,
    onNotificationsSettings: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    val ctx = LocalContext.current

    var enabled by rememberBooleanPref(AppSettings.KEY_TRACKER_ENABLED, true)
    var wifiOnly by rememberBooleanPref(AppSettings.KEY_TRACKER_WIFI_ONLY, false)
    var frequency by rememberStringPref(AppSettings.KEY_TRACKER_FREQUENCY, "1")
    var trackSources by rememberStringSetPref(
        AppSettings.KEY_TRACK_SOURCES,
        setOf(AppSettings.TRACK_FAVOURITES),
    )
    var trackerNoNsfw by rememberBooleanPref(AppSettings.KEY_TRACKER_NO_NSFW, false)
    var smartUpdate by rememberStringSetPref(AppSettings.KEY_TRACKER_SMART_UPDATE, emptySet())

    val freqValues = remember { ctx.resources.getStringArray(R.array.values_tracker_frequency).toList() }
    val freqEntries = remember(freqValues) {
        ctx.resources.getStringArray(R.array.tracker_frequency).mapIndexed { index, label ->
            val factor = freqValues.getOrNull(index)?.toFloatOrNull() ?: return@mapIndexed label
            if (factor > 0f) "$label (${(18f / factor).roundToInt()}h)" else label
        }
    }
    val sourceEntries = remember { ctx.resources.getStringArray(R.array.track_sources).toList() }
    val sourceValues = remember { ctx.resources.getStringArray(R.array.values_track_sources).toList() }
    val smartUpdateEntries = remember { ctx.resources.getStringArray(R.array.smart_update_rules).toList() }
    val smartUpdateValues = remember { ctx.resources.getStringArray(R.array.values_smart_update_rules).toList() }
    val downloadEntries = remember(categories) { categories.map { it.title } }
    val downloadValues = remember(categories) { categories.map { it.id.toString() } }
    val selectedDownloadValues = remember(categories) {
        categories
            .filter { it.isNewChaptersDownloadEnabled }
            .mapTo(LinkedHashSet()) { it.id.toString() }
    }

    val notificationsSummary = if (notificationsEnabled) {
        stringResource(R.string.show_notification_new_chapters_on)
    } else {
        stringResource(R.string.show_notification_new_chapters_off)
    }
    val categoriesSummary = categoriesCount?.let {
        ctx.getString(R.string.enabled_d_of_d, it[0], it[1])
    }
    val categoriesEnabled = enabled && AppSettings.TRACK_FAVOURITES in trackSources

    SettingsScaffold {
        item {
            SettingsGroup {
                item { pos ->
                    SwitchSettingsItem(
                        title = stringResource(R.string.check_new_chapters_title),
                        checked = enabled,
                        onCheckedChange = { enabled = it },
                        icon = R.drawable.ic_feed,
                        shape = pos.shape,
                    )
                }
            }
        }
        item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
        item {
            SettingsGroup(title = stringResource(R.string.settings_group_tracker)) {
                item { pos ->
                    SwitchSettingsItem(
                        title = stringResource(R.string.only_using_wifi),
                        subtitle = stringResource(R.string.tracker_wifi_only_summary),
                        checked = wifiOnly,
                        onCheckedChange = { wifiOnly = it },
                        icon = R.drawable.ic_network_cellular,
                        shape = pos.shape,
                        enabled = enabled,
                    )
                }
                item { pos ->
                    ListSettingsItem(
                        title = stringResource(R.string.frequency_of_check),
                        entries = freqEntries,
                        entryValues = freqValues,
                        selectedValue = frequency,
                        onValueChange = { frequency = it },
                        icon = R.drawable.ic_timelapse,
                        shape = pos.shape,
                        enabled = enabled,
                    )
                }
                item { pos ->
                    MultiSelectSettingsItem(
                        title = stringResource(R.string.track_sources),
                        entries = sourceEntries,
                        entryValues = sourceValues,
                        selectedValues = trackSources,
                        onValuesChange = { trackSources = it },
                        icon = R.drawable.ic_manga_source,
                        shape = pos.shape,
                        enabled = enabled,
                    )
                }
                item { pos ->
                    ActionSettingsItem(
                        title = stringResource(R.string.favourites_categories),
                        subtitle = categoriesSummary,
                        icon = R.drawable.ic_list_group,
                        shape = pos.shape,
                        enabled = categoriesEnabled,
                        onClick = onTrackCategories,
                    )
                }
                item { pos ->
                    ActionSettingsItem(
                        title = stringResource(R.string.notifications_settings),
                        subtitle = notificationsSummary,
                        icon = R.drawable.ic_notification,
                        shape = pos.shape,
                        enabled = enabled,
                        onClick = onNotificationsSettings,
                    )
                }
                item { pos ->
                    SwitchSettingsItem(
                        title = stringResource(R.string.disable_nsfw_notifications),
                        subtitle = stringResource(R.string.disable_nsfw_notifications_summary),
                        checked = trackerNoNsfw,
                        onCheckedChange = { trackerNoNsfw = it },
                        icon = R.drawable.ic_nsfw,
                        shape = pos.shape,
                        enabled = enabled,
                    )
                }
                item { pos ->
                    MultiSelectSettingsItem(
                        title = stringResource(R.string.download_new_chapters),
                        entries = downloadEntries,
                        entryValues = downloadValues,
                        selectedValues = selectedDownloadValues,
                        onValuesChange = { values ->
                            onDownloadCategoriesChange(values.mapNotNull { it.toLongOrNull() }.toSet())
                        },
                        icon = R.drawable.ic_download,
                        shape = pos.shape,
                        enabled = categoriesEnabled && categories.isNotEmpty(),
                    )
                }
                item { pos ->
                    MultiSelectSettingsItem(
                        title = stringResource(R.string.smart_update),
                        entries = smartUpdateEntries,
                        entryValues = smartUpdateValues,
                        selectedValues = smartUpdate,
                        onValuesChange = { smartUpdate = it },
                        emptySummary = stringResource(R.string.smart_update_summary),
                        icon = R.drawable.ic_data_saver,
                        shape = pos.shape,
                        enabled = enabled,
                    )
                }
            }
        }
        item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
        item {
            SettingsGroup(title = stringResource(R.string.settings_advanced)) {
                item { pos ->
                    NavigationSettingsItem(
                        title = stringResource(R.string.settings_diagnostics),
                        subtitle = stringResource(R.string.tracker_debug_info_summary),
                        icon = R.drawable.ic_script,
                        shape = pos.shape,
                        onClick = onOpenDiagnostics,
                    )
                }
            }
        }
        item { Spacer(Modifier.height(24.dp).fillMaxWidth()) }
    }
}
