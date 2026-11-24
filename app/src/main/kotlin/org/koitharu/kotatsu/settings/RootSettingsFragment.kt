package org.koitharu.kotatsu.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import coil3.ImageLoader
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.github.AppUpdateRepository
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.ui.LocalMiyorareVisualPalette
import org.koitharu.kotatsu.core.ui.MiyorareVisualTokens
import org.koitharu.kotatsu.core.ui.miyorareAccentSurface
import org.koitharu.kotatsu.core.ui.util.ReversibleActionObserver
import org.koitharu.kotatsu.core.util.ShareHelper
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.settings.about.AboutSettingsFragment
import org.koitharu.kotatsu.settings.compose.BaseComposeSettingsFragment
import org.koitharu.kotatsu.settings.compose.CategoryPalette
import org.koitharu.kotatsu.settings.compose.MiyorareTheme
import org.koitharu.kotatsu.settings.compose.SettingsGroup
import org.koitharu.kotatsu.settings.compose.SettingsItem
import org.koitharu.kotatsu.settings.compose.SettingsNavigationIndicator
import org.koitharu.kotatsu.settings.compose.SettingsScaffold
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.koitharu.kotatsu.settings.journey.JourneyDisplayMode
import org.koitharu.kotatsu.settings.journey.JourneyPanelModel
import org.koitharu.kotatsu.settings.journey.SettingsJourneyPanelScreen
import org.koitharu.kotatsu.settings.journey.SettingsJourneyPanelViewModel
import org.koitharu.kotatsu.settings.search.SettingsSearchMenuProvider
import org.koitharu.kotatsu.settings.search.SettingsSearchViewModel
import org.koitharu.kotatsu.settings.sources.ExtensionsSettingsFragment
import org.koitharu.kotatsu.settings.tracker.TrackerSettingsFragment
import org.koitharu.kotatsu.stats.domain.YearInReview
import org.koitharu.kotatsu.stats.share.YearInReviewShareCard
import org.koitharu.kotatsu.stats.ui.StatsScreen
import org.koitharu.kotatsu.stats.ui.StatsViewModel
import org.koitharu.kotatsu.sync.ui.SyncSettingsFragment
import javax.inject.Inject

@AndroidEntryPoint
class RootSettingsFragment : BaseComposeSettingsFragment(R.string.settings) {

    @Inject
    lateinit var appUpdateRepository: AppUpdateRepository

    @Inject
    lateinit var imageLoader: ImageLoader

    private val searchViewModel: SettingsSearchViewModel by activityViewModels()

    private val journeyViewModel: SettingsJourneyPanelViewModel by viewModels()

    private val statsViewModel: StatsViewModel by viewModels()

    // Bridges between the pager composition below and the activity back press:
    // while the Journey page is showing, Back returns to the Settings page
    // instead of leaving the Settings screen.
    private val journeyPageVisible = MutableStateFlow(false)
    private var journeyBackAction: (() -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            MiyorareTheme {
                val update by appUpdateRepository.observeAvailableUpdate().collectAsState()
                val journeyStats by journeyViewModel.stats.collectAsState()
                val journeyProfile by journeyViewModel.profile.collectAsState()
                val journeyModel by journeyViewModel.model.collectAsState()
                val journeyGoal by journeyViewModel.weeklyGoal.collectAsState()
                val panelDisplayMode by journeyViewModel.displayMode.collectAsState()
                val stats by statsViewModel.stats.collectAsState()
                val statsLoading by statsViewModel.isLoading.collectAsState()
                val statsPeriod by statsViewModel.period.collectAsState()
                val statsScope by statsViewModel.scope.collectAsState()
                val statsMatureMode by statsViewModel.matureMode.collectAsState()
                val statsSelectedCategories by statsViewModel.selectedCategories.collectAsState()
                val statsCategories by statsViewModel.favoriteCategories.collectAsState(emptyList())
                val statsProfile by statsViewModel.readerProfile.collectAsState()
                val statsYearInReview by statsViewModel.yearInReview.collectAsState()
                // Root Settings and the Reader Journey panel are two pages of one
                // pager — the same stable swipe pattern as the Favourites tabs:
                // the pages follow the finger and settle into place, instead of a
                // bare trigger firing a navigation. Page 0 is Settings itself,
                // page 1 is the Journey panel.
                val pagerState = rememberPagerState(pageCount = { 2 })
                val coroutineScope = rememberCoroutineScope()
                val settingsActivity = activity as? SettingsActivity
                val bottomInset = with(LocalDensity.current) {
                    (settingsActivity?.systemBarsBottomPx ?: 0).toDp()
                }
                LaunchedEffect(pagerState.currentPage, panelDisplayMode) {
                    journeyPageVisible.value = pagerState.currentPage == 1
                    settingsActivity?.setSectionTitle(
                        if (pagerState.currentPage == 1) {
                            getString(R.string.reader_journey)
                        } else {
                            null
                        },
                    )
                    settingsActivity?.setJourneyModelButtonVisible(
                        pagerState.currentPage == 1 && panelDisplayMode == JourneyDisplayMode.KARTU,
                    )
                }
                SideEffect {
                    journeyBackAction = {
                        coroutineScope.launch { pagerState.animateScrollToPage(0) }
                    }
                }
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                ) { page ->
                    if (page == 0) {
                        RootSettingsContent(
                            appVersion = BuildConfig.VERSION_NAME,
                            updateAvailable = update != null,
                            onSectionClick = { section -> openSection(section) },
                            onUpdateClick = { router.openAppUpdate() },
                        )
                    } else {
                        // The Journey page starts on Kartu and keeps its own
                        // remembered Utama / Kartu choice, independent from the
                        // main Reader Journey screen's display mode. The switch
                        // itself sits at the very end of the page's content —
                        // the bottom of the Kartu Overview tab, and the bottom
                        // of the Utama statistics list — with nothing below it.
                        // The Kartu model picker leaves the page entirely: a
                        // header button in Settings (shown only while this page
                        // and Kartu mode are up) opens the model chooser.
                        Box(modifier = Modifier.fillMaxSize()) {
                            if (panelDisplayMode == JourneyDisplayMode.KARTU) {
                                SettingsJourneyPanelScreen(
                                    stats = journeyStats.forMatureMode(statsMatureMode),
                                    profile = journeyProfile,
                                    model = journeyModel,
                                    onModelChange = journeyViewModel::setModel,
                                    weeklyGoal = journeyGoal,
                                    onWeeklyGoalChange = journeyViewModel::setWeeklyGoal,
                                    showModelPicker = false,
                                    bottomInset = bottomInset,
                                    overviewFooter = {
                                        JourneyPanelDisplayToggle(
                                            mode = panelDisplayMode,
                                            onModeChange = journeyViewModel::setDisplayMode,
                                        )
                                    },
                                )
                            } else {
                                StatsScreen(
                                    stats = stats.forMatureMode(statsMatureMode),
                                    isLoading = statsLoading || stats.matureMode != statsMatureMode,
                                    period = statsPeriod,
                                    scope = statsScope,
                                    matureMode = statsMatureMode,
                                    categories = statsCategories,
                                    selectedCategories = statsSelectedCategories,
                                    imageLoader = imageLoader,
                                    profile = statsProfile,
                                    yearInReview = statsYearInReview,
                                    bottomInset = bottomInset,
                                    onPeriodChange = { statsViewModel.period.value = it },
                                    onScopeChange = { statsViewModel.scope.value = it },
                                    onMatureModeChange = statsViewModel::setMatureMode,
                                    onCategoryToggle = statsViewModel::toggleCategory,
                                    onCategoriesClear = statsViewModel::clearCategories,
                                    onProfileUpdate = statsViewModel::updateReaderProfile,
                                    onCosmeticsUpdate = statsViewModel::updateReaderCosmetics,
                                    onShareYearInReview = ::shareYearInReview,
                                    onMangaClick = { router.openDetails(it) },
                                    footerContent = {
                                        JourneyPanelDisplayToggle(
                                            mode = panelDisplayMode,
                                            onModeChange = journeyViewModel::setDisplayMode,
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().addMenuProvider(
            SettingsSearchMenuProvider(searchViewModel),
            viewLifecycleOwner,
        )
        (activity as? SettingsActivity)?.onJourneyModelButtonClick = { showJourneyModelPicker() }
        statsViewModel.onActionDone.observeEvent(
            viewLifecycleOwner,
            ReversibleActionObserver(view),
        )
        val journeyBackCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                journeyBackAction?.invoke()
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, journeyBackCallback)
        viewLifecycleOwner.lifecycleScope.launch {
            journeyPageVisible.collect { journeyBackCallback.isEnabled = it }
        }
    }

    override fun onDestroyView() {
        (activity as? SettingsActivity)?.apply {
            onJourneyModelButtonClick = null
            setJourneyModelButtonVisible(false)
        }
        super.onDestroyView()
    }

    /** Model chooser for the Kartu panel, opened from the Settings header button. */
    private fun showJourneyModelPicker() {
        val models = JourneyPanelModel.entries
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.journey_panel_model_label)
            .setSingleChoiceItems(
                models.map { getString(it.labelRes) }.toTypedArray(),
                models.indexOf(journeyViewModel.model.value),
            ) { dialog, which ->
                journeyViewModel.setModel(models[which])
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openSection(section: SettingsSection) {
        val activity = activity as? SettingsActivity ?: return
        activity.openFragment(section.fragmentClass, null, isFromRoot = true)
    }

    private fun shareYearInReview(review: YearInReview) {
        viewLifecycleOwner.lifecycleScope.launch {
            val context = requireContext()
            val uri = withContext(Dispatchers.Default) {
                YearInReviewShareCard.renderToShareUri(context, review)
            }
            ShareHelper(context).shareImage(uri)
        }
    }

}

/**
 * Utama / Kartu switch shown at the very end of the Settings Journey page's
 * content, below everything else on the page. A pill
 * with two segments; the active segment is filled with the theme's primary
 * color. Neutral chrome on purpose: it belongs to the page, not to any of the
 * four Kartu visual models below it.
 */
@Composable
private fun JourneyPanelDisplayToggle(
    mode: JourneyDisplayMode,
    onModeChange: (JourneyDisplayMode) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(percent = 50),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Row(modifier = Modifier.padding(4.dp)) {
                JourneyDisplayMode.entries.forEach { item ->
                    val selected = item == mode
                    Surface(
                        onClick = { onModeChange(item) },
                        shape = RoundedCornerShape(percent = 50),
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            Color.Transparent
                        },
                    ) {
                        Text(
                            text = stringResource(
                                if (item == JourneyDisplayMode.UTAMA) {
                                    R.string.journey_display_utama
                                } else {
                                    R.string.journey_display_kartu
                                },
                            ),
                            color = if (selected) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

private enum class SettingsSection(
    val titleRes: Int,
    val iconRes: Int,
    val paletteKey: String,
    val summaryRes: IntArray,
    val fragmentClass: Class<out Fragment>,
    val tintIcon: Boolean = true,
) {
    APPEARANCE(
        R.string.settings_appearance_navigation, R.drawable.ic_appearance, "appearance",
        intArrayOf(R.string.settings_appearance_navigation_summary),
        AppearanceSettingsFragment::class.java,
    ),
    READER(
        R.string.reader_settings, R.drawable.ic_book_page, "reader",
        intArrayOf(R.string.read_mode, R.string.scale_mode, R.string.switch_pages),
        ReaderSettingsFragment::class.java,
    ),
    EXTENSIONS(
        R.string.settings_sources_extensions, R.drawable.ic_manga_source, "extensions",
        intArrayOf(R.string.manage_extensions, R.string.nsfw_filter, R.string.sort_order),
        ExtensionsSettingsFragment::class.java,
    ),
    TRACKER(
        R.string.settings_chapter_updates, R.drawable.ic_feed, "tracker",
        intArrayOf(R.string.track_sources, R.string.notifications_settings),
        TrackerSettingsFragment::class.java,
    ),
    DOWNLOADS(
        R.string.settings_downloads_local_files, R.drawable.ic_download, "downloads",
        intArrayOf(R.string.settings_downloads_local_files_summary),
        DownloadsSettingsFragment::class.java,
    ),
    SYNC(
        R.string.google_drive_sync, R.drawable.ic_cloud_sync, "sync",
        intArrayOf(R.string.sync_sign_in_summary),
        SyncSettingsFragment::class.java,
    ),
    STORAGE(
        R.string.settings_storage_data, R.drawable.ic_usage, "storage",
        intArrayOf(R.string.settings_storage_data_summary),
        StorageSettingsFragment::class.java,
    ),
    NETWORK(
        R.string.settings_network, R.drawable.ic_web, "storage",
        intArrayOf(R.string.settings_network_summary),
        NetworkSettingsFragment::class.java,
    ),
    BACKUP(
        R.string.backup_restore, R.drawable.ic_backup_restore, "backup",
        intArrayOf(R.string.restore_backup),
        BackupSettingsFragment::class.java,
    ),
    PRIVACY_SECURITY(
        R.string.settings_privacy_security, R.drawable.ic_lock, "private",
        intArrayOf(R.string.settings_privacy_security_summary),
        PrivacySecuritySettingsFragment::class.java,
    ),
    INSIGHTS(
        R.string.settings_insights_recommendations, R.drawable.ic_suggestion, "services",
        intArrayOf(R.string.settings_insights_recommendations_summary),
        InsightsSettingsFragment::class.java,
    ),
    CONNECTED_SERVICES(
        R.string.settings_connected_services, R.drawable.ic_services, "services",
        intArrayOf(R.string.settings_connected_services_summary),
        ConnectedServicesSettingsFragment::class.java,
    ),
    ABOUT(
        R.string.about, R.drawable.ic_info_outline, "about",
        IntArray(0),
        AboutSettingsFragment::class.java,
    ),
}

private enum class SettingsGroupAccent(
    val lightColor: Color,
    val darkColor: Color,
) {
    READING(
        lightColor = Color(0xFF00838F),
        darkColor = Color(0xFF4DD0E1),
    ),
    CONTENT(
        lightColor = Color(0xFF1565C0),
        darkColor = Color(0xFF64B5F6),
    ),
    DATA(
        lightColor = Color(0xFF00897B),
        darkColor = Color(0xFF5EEAD4),
    ),
    PRIVACY(
        lightColor = Color(0xFF7E57C2),
        darkColor = Color(0xFFB388FF),
    ),
    APP(
        lightColor = Color(0xFF1976D2),
        darkColor = Color(0xFF64B5F6),
    ),
}

@Composable
private fun SettingsGroupAccent.resolveColor(): Color {
    val surface = MaterialTheme.colorScheme.surface
    val isDark = (surface.red * 0.299f + surface.green * 0.587f + surface.blue * 0.114f) < 0.5f
    return if (isDark) darkColor else lightColor
}

private data class SettingsSectionGroup(
    val titleRes: Int,
    val iconRes: Int,
    val accent: SettingsGroupAccent,
    val sections: List<SettingsSection>,
)

private val settingsSectionGroups = listOf(
    SettingsSectionGroup(
        titleRes = R.string.settings_group_reading_interface,
        iconRes = R.drawable.ic_book_page,
        accent = SettingsGroupAccent.READING,
        sections = listOf(SettingsSection.APPEARANCE, SettingsSection.READER),
    ),
    SettingsSectionGroup(
        titleRes = R.string.settings_group_content_sources,
        iconRes = R.drawable.ic_grid,
        accent = SettingsGroupAccent.CONTENT,
        sections = listOf(
            SettingsSection.EXTENSIONS,
            SettingsSection.TRACKER,
            SettingsSection.DOWNLOADS,
        ),
    ),
    SettingsSectionGroup(
        titleRes = R.string.settings_group_data_sync,
        iconRes = R.drawable.ic_cloud_sync,
        accent = SettingsGroupAccent.DATA,
        sections = listOf(
            SettingsSection.SYNC,
            SettingsSection.STORAGE,
            SettingsSection.NETWORK,
            SettingsSection.BACKUP
        ),
    ),
    SettingsSectionGroup(
        titleRes = R.string.settings_group_privacy_services,
        iconRes = R.drawable.ic_lock,
        accent = SettingsGroupAccent.PRIVACY,
        sections = listOf(
            SettingsSection.PRIVACY_SECURITY,
            SettingsSection.INSIGHTS,
            SettingsSection.CONNECTED_SERVICES,
        ),
    ),
    SettingsSectionGroup(
        titleRes = R.string.settings_group_app,
        iconRes = R.drawable.ic_info_outline,
        accent = SettingsGroupAccent.APP,
        sections = listOf(SettingsSection.ABOUT),
    ),
)

@Composable
private fun RootSettingsContent(
    appVersion: String,
    updateAvailable: Boolean,
    onSectionClick: (SettingsSection) -> Unit,
    onUpdateClick: () -> Unit,
) {
    val ctx = LocalContext.current
    val modern = LocalMiyorareVisualPalette.current.isModern
    // This content is page 0 of the root pager; page 1 is the Journey panel.
    SettingsScaffold {
        if (updateAvailable) {
            item {
                UpdateBanner(onClick = onUpdateClick)
            }
            item { Spacer(Modifier.height(12.dp).fillMaxWidth()) }
        }
        settingsSectionGroups.forEachIndexed { groupIndex, group ->
            item {
                SettingsGroup(
                    title = stringResource(group.titleRes),
                    titleIcon = group.iconRes,
                    titleColor = group.accent.resolveColor(),
                ) {
                    group.sections.forEach { section ->
                        item { pos ->
                            val subtitle = if (section == SettingsSection.ABOUT) {
                                appVersion
                            } else {
                                section.summaryRes.firstOrNull()?.let { ctx.getString(it) }
                            }
                            SettingsItem(
                                title = stringResource(section.titleRes),
                                subtitle = subtitle,
                                icon = section.iconRes,
                                iconColors = CategoryPalette.forKey(section.paletteKey),
                                tintIcon = section.tintIcon,
                                shape = pos.shape,
                                onClick = { onSectionClick(section) },
                                trailing = if (modern) {
                                    { SettingsNavigationIndicator() }
                                } else null,
                            )
                        }
                    }
                }
            }
            if (groupIndex < settingsSectionGroups.lastIndex) {
                item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
            }
        }
        item { Spacer(Modifier.height(24.dp).fillMaxWidth()) }
    }
}

@Composable
private fun UpdateBanner(onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val palette = LocalMiyorareVisualPalette.current
    val modern = palette.isModern
    val shape = RoundedCornerShape(
        if (modern) MiyorareVisualTokens.RADIUS_CONTROL_DP.dp else 24.dp,
    )
    val bannerModifier = Modifier
        .fillMaxWidth()
        .let {
            if (modern) it.miyorareAccentSurface(palette = palette, shape = shape) else it
        }
    val contentColor = if (modern) palette.onButton else cs.onPrimaryContainer
    Surface(
        modifier = bannerModifier,
        shape = shape,
        color = if (modern) Color.Transparent else cs.primaryContainer,
        contentColor = contentColor,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = 16.dp,
                    vertical = if (modern) 14.dp else 16.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_app_update),
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(if (modern) 26.dp else 28.dp),
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.app_update_available),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = contentColor,
                )
                Text(
                    text = stringResource(R.string.update),
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.82f),
                )
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                painter = painterResource(R.drawable.ic_arrow_forward),
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}
