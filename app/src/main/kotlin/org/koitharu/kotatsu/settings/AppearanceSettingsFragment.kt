package org.koitharu.kotatsu.settings

import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.ColorScheme
import org.koitharu.kotatsu.core.prefs.ListMode
import org.koitharu.kotatsu.core.prefs.MiyorareAppearance
import org.koitharu.kotatsu.core.prefs.MiyorareCustomBackgroundIntensity
import org.koitharu.kotatsu.core.prefs.MiyorareDesignStyle
import org.koitharu.kotatsu.core.prefs.MiyorareThemePreset
import org.koitharu.kotatsu.core.prefs.SearchSuggestionType
import org.koitharu.kotatsu.core.prefs.VisualEffectLevel
import org.koitharu.kotatsu.core.prefs.VisualEffectPreferences
import org.koitharu.kotatsu.core.ui.MiyorareCustomBackgroundStore
import org.koitharu.kotatsu.core.ui.util.ActivityRecreationHandle
import org.koitharu.kotatsu.core.util.LocaleComparator
import org.koitharu.kotatsu.core.util.ext.getLocalesConfig
import org.koitharu.kotatsu.core.util.ext.sortedWithSafe
import org.koitharu.kotatsu.core.util.ext.toList
import org.koitharu.kotatsu.core.util.ext.tryLaunch
import org.koitharu.kotatsu.favourites.domain.FavouriteHeaderScrollMode
import org.koitharu.kotatsu.favourites.domain.FavouriteListLoadingMode
import org.koitharu.kotatsu.parsers.util.names
import org.koitharu.kotatsu.parsers.util.toTitleCase
import org.koitharu.kotatsu.settings.appearance.PreviewSettingsFragment
import org.koitharu.kotatsu.settings.compose.ActionSettingsItem
import org.koitharu.kotatsu.settings.compose.BaseComposeSettingsFragment
import org.koitharu.kotatsu.settings.compose.ColorSchemePickerRow
import org.koitharu.kotatsu.settings.compose.ConfirmDialog
import org.koitharu.kotatsu.settings.compose.MiyorareTheme
import org.koitharu.kotatsu.settings.compose.EditTextSettingsItem
import org.koitharu.kotatsu.settings.compose.ListSettingsItem
import org.koitharu.kotatsu.settings.compose.MiyorareChoiceSettingsItem
import org.koitharu.kotatsu.settings.compose.MultiSelectSettingsItem
import org.koitharu.kotatsu.settings.compose.NavigationSettingsItem
import org.koitharu.kotatsu.settings.compose.SettingsGroup
import org.koitharu.kotatsu.settings.compose.SettingsScaffold
import org.koitharu.kotatsu.settings.compose.SliderSettingsItem
import org.koitharu.kotatsu.settings.compose.SwitchSettingsItem
import org.koitharu.kotatsu.settings.compose.rememberBooleanPref
import org.koitharu.kotatsu.settings.compose.rememberIntPref
import org.koitharu.kotatsu.settings.compose.rememberReadingIndicatorPref
import org.koitharu.kotatsu.settings.compose.rememberStringPref
import org.koitharu.kotatsu.settings.compose.rememberStringSetPref
import org.koitharu.kotatsu.settings.nav.NavConfigFragment
import javax.inject.Inject

@AndroidEntryPoint
class AppearanceSettingsFragment : BaseComposeSettingsFragment(R.string.appearance) {

    @Inject
    lateinit var settings: AppSettings

    @Inject
    lateinit var activityRecreationHandle: ActivityRecreationHandle

    private var isResettingAppearance = false

    private var isImportingCustomBackground = false

    private val customBackgroundPicker = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) importCustomBackground(uri)
    }

    private val customBackgroundFilePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) importCustomBackground(uri)
    }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener listener@ { _, key ->
        if (isResettingAppearance && key in APPEARANCE_RESET_KEYS) return@listener
        when (key) {
            AppSettings.KEY_THEME -> AppCompatDelegate.setDefaultNightMode(settings.theme)
            AppSettings.KEY_COLOR_THEME,
            AppSettings.KEY_THEME_AMOLED,
            AppSettings.KEY_UI_SCALE,
            MiyorareAppearance.KEY_DESIGN_STYLE,
            MiyorareAppearance.KEY_THEME_PRESET,
            MiyorareAppearance.KEY_CUSTOM_ACCENT,
            MiyorareAppearance.KEY_CUSTOM_BACKGROUND_COLOR_SYNC,
            MiyorareAppearance.KEY_CUSTOM_BACKGROUND_INTENSITY,
            VisualEffectPreferences.KEY_LEVEL -> activityRecreationHandle.recreateAll()
            AppSettings.KEY_APP_LOCALE -> AppCompatDelegate.setApplicationLocales(settings.appLocales)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            MiyorareTheme {
                AppearanceScreen(
                    onOpenDetailsAppearance = {
                        (activity as? SettingsActivity)?.openFragment(
                            PreviewSettingsFragment::class.java,
                            null,
                            isFromRoot = false,
                        )
                    },
                    onOpenNavConfig = {
                        (activity as? SettingsActivity)?.openFragment(
                            NavConfigFragment::class.java,
                            null,
                            isFromRoot = false,
                        )
                    },
                    onOpenFeed = {
                        (activity as? SettingsActivity)?.openFragment(
                            FeedAppearanceSettingsFragment::class.java,
                            null,
                            isFromRoot = false,
                        )
                    },
                    onOpenAdvanced = {
                        (activity as? SettingsActivity)?.openFragment(
                            AppearanceAdvancedSettingsFragment::class.java,
                            null,
                            isFromRoot = false,
                        )
                    },
                    onPickCustomBackground = ::pickCustomBackground,
                    onRemoveCustomBackground = ::removeCustomBackground,
                    onResetAppearance = ::resetAppearance,
                )
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        settings.subscribe(prefListener)
    }

    override fun onDestroyView() {
        settings.unsubscribe(prefListener)
        super.onDestroyView()
    }

    private fun pickCustomBackground() {
        val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        if (!customBackgroundPicker.tryLaunch(request)) {
            customBackgroundFilePicker.tryLaunch(arrayOf("image/*"))
        }
    }

    private fun importCustomBackground(uri: Uri) {
        if (isImportingCustomBackground) return
        isImportingCustomBackground = true
        Toast.makeText(requireContext(), R.string.miyorare_custom_background_processing, Toast.LENGTH_SHORT).show()
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                MiyorareCustomBackgroundStore.import(requireContext(), uri)
            }
            isImportingCustomBackground = false
            if (result.isSuccess) {
                Toast.makeText(requireContext(), R.string.miyorare_custom_background_applied, Toast.LENGTH_SHORT).show()
                activityRecreationHandle.recreateAll()
            } else {
                Toast.makeText(requireContext(), R.string.miyorare_custom_background_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun removeCustomBackground() {
        MiyorareCustomBackgroundStore.clear(requireContext())
        activityRecreationHandle.recreateAll()
    }

    private fun resetAppearance() {
        val previousTheme = settings.theme
        isResettingAppearance = true
        settings.resetMiyorareAppearance()
        val resetTheme = settings.theme
        if (previousTheme != resetTheme) {
            AppCompatDelegate.setDefaultNightMode(resetTheme)
        } else {
            activityRecreationHandle.recreateAll()
        }
        view?.post { isResettingAppearance = false }
    }

    private companion object {
        val APPEARANCE_RESET_KEYS = setOf(
            AppSettings.KEY_THEME,
            AppSettings.KEY_COLOR_THEME,
            AppSettings.KEY_THEME_AMOLED,
            MiyorareAppearance.KEY_DESIGN_STYLE,
            MiyorareAppearance.KEY_THEME_PRESET,
            MiyorareAppearance.KEY_CUSTOM_ACCENT,
            MiyorareAppearance.KEY_CUSTOM_BACKGROUND_COLOR_SYNC,
            MiyorareAppearance.KEY_CUSTOM_BACKGROUND_INTENSITY,
            VisualEffectPreferences.KEY_LEVEL,
        )
    }
}

@Composable
private fun AppearanceScreen(
    onOpenDetailsAppearance: () -> Unit,
    onOpenNavConfig: () -> Unit,
    onOpenFeed: () -> Unit,
    onOpenAdvanced: () -> Unit,
    onPickCustomBackground: () -> Unit,
    onRemoveCustomBackground: () -> Unit,
    onResetAppearance: () -> Unit,
) {
    val ctx = LocalContext.current

    val themeEntries = remember { ctx.resources.getStringArray(R.array.themes).toList() }
    val themeValues = remember { ctx.resources.getStringArray(R.array.values_theme).toList() }
    val visualEffectEntries = remember { VisualEffectLevel.entries.map { ctx.getString(it.titleResId) } }
    val visualEffectValues = remember { VisualEffectLevel.entries.map { it.name } }
    val designStyleEntries = remember { MiyorareDesignStyle.entries.map { ctx.getString(it.titleResId) } }
    val designStyleValues = remember { MiyorareDesignStyle.entries.map { it.name } }
    val modernThemeEntries = remember { MiyorareThemePreset.entries.map { ctx.getString(it.titleResId) } }
    val modernThemeValues = remember { MiyorareThemePreset.entries.map { it.name } }
    val customBackgroundIntensityEntries = remember {
        MiyorareCustomBackgroundIntensity.entries.map { ctx.getString(it.titleResId) }
    }
    val customBackgroundIntensityValues = remember {
        MiyorareCustomBackgroundIntensity.entries.map { it.name }
    }
    val listModeEntries = remember { ctx.resources.getStringArray(R.array.list_modes).toList() }
    val listModeValues = remember { ListMode.entries.names().toList() }
    val badgeEntries = remember { ctx.resources.getStringArray(R.array.list_badges).toList() }
    val badgeValues = remember { ctx.resources.getStringArray(R.array.values_list_badges).toList() }
    val detailsTabEntries = remember { ctx.resources.getStringArray(R.array.details_tabs).toList() }
    val detailsTabValues = remember { ctx.resources.getStringArray(R.array.details_tabs_values).toList() }
    val readingIndicatorEntries = remember { ctx.resources.getStringArray(R.array.reading_indicator_modes).toList() }
    val readingIndicatorValues = remember { ctx.resources.getStringArray(R.array.values_reading_indicator_modes).toList() }
    val searchSuggestionEntries = remember { SearchSuggestionType.entries.map { ctx.getString(it.titleResId) } }
    val searchSuggestionValues = remember { SearchSuggestionType.entries.names().toList() }
    val favouriteScrollEntries = remember { FavouriteHeaderScrollMode.entries.map { ctx.getString(it.titleResId) } }
    val favouriteScrollValues = remember { FavouriteHeaderScrollMode.entries.map { it.name } }
    val favouriteLoadingEntries = remember { FavouriteListLoadingMode.entries.map { ctx.getString(it.titleResId) } }
    val favouriteLoadingValues = remember { FavouriteListLoadingMode.entries.map { it.name } }
    val locales = remember { ctx.getLocalesConfig().toList().sortedWithSafe(LocaleComparator()) }
    val localeEntries = remember(locales) {
        listOf(ctx.getString(R.string.follow_system)) + locales.map { it.getDisplayName(it).toTitleCase(it) }
    }
    val localeValues = remember(locales) { listOf("") + locales.map { it.toLanguageTag() } }

    var colorScheme by rememberStringPref(AppSettings.KEY_COLOR_THEME, ColorScheme.default.name)
    var theme by rememberStringPref(AppSettings.KEY_THEME, "-1")
    var amoled by rememberBooleanPref(AppSettings.KEY_THEME_AMOLED, false)
    var visualEffects by rememberStringPref(VisualEffectPreferences.KEY_LEVEL, VisualEffectLevel.BALANCED.name)
    var designStyle by rememberStringPref(MiyorareAppearance.KEY_DESIGN_STYLE, MiyorareDesignStyle.CLASSIC.name)
    var modernTheme by rememberStringPref(MiyorareAppearance.KEY_THEME_PRESET, MiyorareThemePreset.MIYORARE.name)
    var customAccent by rememberStringPref(MiyorareAppearance.KEY_CUSTOM_ACCENT, MiyorareAppearance.DEFAULT_CUSTOM_ACCENT)
    var customBackgroundColorSync by rememberBooleanPref(
        MiyorareAppearance.KEY_CUSTOM_BACKGROUND_COLOR_SYNC,
        true,
    )
    var customBackgroundIntensity by rememberStringPref(
        MiyorareAppearance.KEY_CUSTOM_BACKGROUND_INTENSITY,
        MiyorareCustomBackgroundIntensity.BALANCED.name,
    )
    val customBackgroundRevision by rememberIntPref(MiyorareAppearance.KEY_CUSTOM_BACKGROUND_REVISION, 0)
    val hasCustomBackground = remember(customBackgroundRevision) {
        MiyorareCustomBackgroundStore.hasBackground(ctx)
    }
    val customBackgroundPreviewPath = remember(customBackgroundRevision) {
        MiyorareCustomBackgroundStore.previewPathOrNull(ctx)
    }
    var showResetDialog by remember { mutableStateOf(false) }
    var uiScale by rememberIntPref(AppSettings.KEY_UI_SCALE, 100)
    var hapticFeedback by rememberBooleanPref(AppSettings.KEY_HAPTIC_FEEDBACK, true)
    var locale by rememberStringPref(AppSettings.KEY_APP_LOCALE, "")
    var listMode by rememberStringPref(AppSettings.KEY_LIST_MODE, ListMode.GRID.name)
    var gridSize by rememberIntPref(AppSettings.KEY_GRID_SIZE, 100)
    var quickFilter by rememberBooleanPref(AppSettings.KEY_QUICK_FILTER, true)
    var readingIndicator by rememberReadingIndicatorPref(AppSettings.KEY_PROGRESS_INDICATORS)
    var mangaListBadges by rememberStringSetPref(AppSettings.KEY_MANGA_LIST_BADGES, emptySet())
    var favouriteScrollMode by rememberStringPref(
        FavouriteHeaderScrollMode.KEY_PREFERENCE,
        FavouriteHeaderScrollMode.SCROLL_AWAY.name,
    )
    var favouriteLoadingMode by rememberStringPref(
        AppSettings.KEY_FAVOURITES_LIST_LOADING_MODE,
        FavouriteListLoadingMode.PAGED.name,
    )
    var titleTapToRead by rememberBooleanPref(AppSettings.KEY_TITLE_TAP_TO_READ, false)
    var checkDuplicates by rememberBooleanPref(AppSettings.KEY_CHECK_DUPLICATES, true)
    var descriptionCollapse by rememberBooleanPref(AppSettings.KEY_COLLAPSE_DESCRIPTION, true)
    var pagesTab by rememberBooleanPref(AppSettings.KEY_PAGES_TAB, true)
    var detailsTab by rememberStringPref(AppSettings.KEY_DETAILS_TAB, "-1")
    var searchSuggestions by rememberStringSetPref(AppSettings.KEY_SEARCH_SUGGESTION_TYPES, emptySet())
    var mainFab by rememberBooleanPref(AppSettings.KEY_MAIN_FAB, true)
    var navLabels by rememberBooleanPref(AppSettings.KEY_NAV_LABELS, true)

    SettingsScaffold {
        item {
            SettingsGroup(title = stringResource(R.string.miyorare_appearance_group)) {
                item { pos ->
                    MiyorareChoiceSettingsItem(
                        title = stringResource(R.string.miyorare_design_style),
                        entries = designStyleEntries,
                        entryValues = designStyleValues,
                        selectedValue = designStyle,
                        onValueChange = { designStyle = it },
                        icon = R.drawable.ic_appearance,
                        shape = pos.shape,
                    )
                }
                item { pos ->
                    MiyorareChoiceSettingsItem(
                        title = stringResource(R.string.miyorare_display_mode),
                        entries = themeEntries,
                        entryValues = themeValues,
                        selectedValue = theme,
                        onValueChange = {
                            theme = it
                            @Suppress("WrongConstant")
                            val mode = it.toIntOrNull() ?: AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                            AppCompatDelegate.setDefaultNightMode(mode)
                        },
                        icon = R.drawable.ic_appearance,
                        shape = pos.shape,
                    )
                }
                if (designStyle == MiyorareDesignStyle.MODERN.name) {
                    item { pos ->
                        ListSettingsItem(
                            title = stringResource(R.string.miyorare_modern_theme),
                            entries = modernThemeEntries,
                            entryValues = modernThemeValues,
                            selectedValue = modernTheme,
                            onValueChange = { modernTheme = it },
                            icon = R.drawable.ic_appearance,
                            shape = pos.shape,
                        )
                    }
                    if (modernTheme == MiyorareThemePreset.CUSTOM.name) {
                        item { pos ->
                            ActionSettingsItem(
                                title = stringResource(R.string.miyorare_custom_background_choose),
                                subtitle = if (hasCustomBackground) {
                                    stringResource(R.string.miyorare_custom_background_selected)
                                } else {
                                    stringResource(R.string.miyorare_custom_background_choose_summary)
                                },
                                icon = R.drawable.ic_images,
                                shape = pos.shape,
                                onClick = onPickCustomBackground,
                            )
                        }
                        if (hasCustomBackground && customBackgroundPreviewPath != null) {
                            item { pos ->
                                CustomBackgroundPreview(
                                    path = customBackgroundPreviewPath,
                                    revision = customBackgroundRevision,
                                    shape = pos.shape,
                                )
                            }
                            item { pos ->
                                SwitchSettingsItem(
                                    title = stringResource(R.string.miyorare_custom_background_use_colors),
                                    subtitle = stringResource(R.string.miyorare_custom_background_use_colors_summary),
                                    checked = customBackgroundColorSync,
                                    onCheckedChange = { customBackgroundColorSync = it },
                                    icon = R.drawable.ic_palette,
                                    shape = pos.shape,
                                )
                            }
                            if (customBackgroundColorSync) {
                                item { pos ->
                                    ListSettingsItem(
                                        title = stringResource(R.string.miyorare_custom_background_intensity),
                                        entries = customBackgroundIntensityEntries,
                                        entryValues = customBackgroundIntensityValues,
                                        selectedValue = customBackgroundIntensity,
                                        onValueChange = { customBackgroundIntensity = it },
                                        icon = R.drawable.ic_appearance,
                                        shape = pos.shape,
                                    )
                                }
                            }
                        }
                        if (!hasCustomBackground || !customBackgroundColorSync) {
                            item { pos ->
                                EditTextSettingsItem(
                                    title = stringResource(R.string.miyorare_custom_accent),
                                    value = customAccent,
                                    hint = MiyorareAppearance.DEFAULT_CUSTOM_ACCENT,
                                    onValueChange = { value ->
                                        MiyorareAppearance.normalizeAccent(value)?.let { customAccent = it }
                                    },
                                    isValueValid = { MiyorareAppearance.normalizeAccent(it) != null },
                                    invalidMessage = stringResource(R.string.miyorare_custom_accent_invalid),
                                    icon = R.drawable.ic_appearance,
                                    shape = pos.shape,
                                )
                            }
                        }
                        if (hasCustomBackground) {
                            item { pos ->
                                ActionSettingsItem(
                                    title = stringResource(R.string.miyorare_custom_background_remove),
                                    subtitle = stringResource(R.string.miyorare_custom_background_remove_summary),
                                    icon = R.drawable.ic_delete_all,
                                    shape = pos.shape,
                                    onClick = onRemoveCustomBackground,
                                )
                            }
                        }
                    }
                }
                item { pos ->
                    val isSystemDark = isSystemInDarkTheme()
                    val isDarkActive = when (theme.toIntOrNull()) {
                        AppCompatDelegate.MODE_NIGHT_YES -> true
                        AppCompatDelegate.MODE_NIGHT_NO -> false
                        else -> isSystemDark
                    }
                    SwitchSettingsItem(
                        title = stringResource(R.string.black_dark_theme),
                        subtitle = stringResource(R.string.black_dark_theme_summary),
                        checked = amoled,
                        onCheckedChange = { amoled = it },
                        icon = R.drawable.ic_eye_off,
                        enabled = isDarkActive,
                        shape = pos.shape,
                    )
                }
                if (designStyle == MiyorareDesignStyle.MODERN.name) {
                    item { pos ->
                        MiyorareChoiceSettingsItem(
                            title = stringResource(R.string.visual_effects),
                            entries = visualEffectEntries,
                            entryValues = visualEffectValues,
                            selectedValue = visualEffects,
                            onValueChange = { visualEffects = it },
                            icon = R.drawable.ic_appearance,
                            shape = pos.shape,
                        )
                    }
                }
                item { pos ->
                    ListSettingsItem(
                        title = stringResource(R.string.language),
                        entries = localeEntries,
                        entryValues = localeValues,
                        selectedValue = locale,
                        onValueChange = { locale = it },
                        icon = R.drawable.ic_language,
                        shape = pos.shape,
                    )
                }
                item { pos ->
                    SliderSettingsItem(
                        title = stringResource(R.string.ui_scale),
                        value = uiScale,
                        valueFrom = 80,
                        valueTo = 120,
                        stepSize = 10,
                        valueLabel = { value ->
                            when {
                                value <= 80 -> ctx.getString(R.string.ui_scale_smallest)
                                value < 100 -> ctx.getString(R.string.ui_scale_smaller)
                                value == 100 -> ctx.getString(R.string.ui_scale_default)
                                value < 120 -> ctx.getString(R.string.ui_scale_larger)
                                else -> ctx.getString(R.string.ui_scale_largest)
                            }
                        },
                        onValueChange = { uiScale = it },
                        icon = R.drawable.ic_zoom_in,
                        shape = pos.shape,
                    )
                }
                item { pos ->
                    SwitchSettingsItem(
                        title = stringResource(R.string.haptic_feedback),
                        subtitle = stringResource(R.string.haptic_feedback_summary),
                        checked = hapticFeedback,
                        onCheckedChange = { hapticFeedback = it },
                        icon = R.drawable.ic_haptic,
                        shape = pos.shape,
                    )
                }
            }
        }
        if (designStyle == MiyorareDesignStyle.CLASSIC.name) {
            item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
            item {
                ColorSchemePickerRow(
                    title = stringResource(R.string.color_theme),
                    selectedValue = colorScheme,
                    onValueChange = { colorScheme = it },
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                )
            }
        }
        item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
        item {
            SettingsGroup(title = stringResource(R.string.manga_list)) {
                item { pos ->
                    ListSettingsItem(
                        title = stringResource(R.string.list_mode),
                        entries = listModeEntries,
                        entryValues = listModeValues,
                        selectedValue = listMode,
                        onValueChange = { listMode = it },
                        icon = R.drawable.ic_list,
                        shape = pos.shape,
                    )
                }
                item { pos ->
                    SliderSettingsItem(
                        title = stringResource(R.string.grid_size),
                        value = gridSize,
                        valueFrom = 50,
                        valueTo = 150,
                        stepSize = 5,
                        unitSuffix = "%",
                        onValueChange = { gridSize = it },
                        icon = R.drawable.ic_grid,
                        shape = pos.shape,
                    )
                }
                item { pos ->
                    SwitchSettingsItem(
                        title = stringResource(R.string.show_quick_filters),
                        subtitle = stringResource(R.string.show_quick_filters_summary),
                        checked = quickFilter,
                        onCheckedChange = { quickFilter = it },
                        icon = R.drawable.ic_filter_menu,
                        shape = pos.shape,
                    )
                }
                item { pos ->
                    ListSettingsItem(
                        title = stringResource(R.string.show_reading_indicators),
                        entries = readingIndicatorEntries,
                        entryValues = readingIndicatorValues,
                        selectedValue = readingIndicator,
                        onValueChange = { readingIndicator = it },
                        icon = R.drawable.ic_history,
                        shape = pos.shape,
                    )
                }
                item { pos ->
                    MultiSelectSettingsItem(
                        title = stringResource(R.string.badges_in_lists),
                        entries = badgeEntries,
                        entryValues = badgeValues,
                        selectedValues = mangaListBadges,
                        onValuesChange = { mangaListBadges = it },
                        icon = R.drawable.ic_tag,
                        shape = pos.shape,
                    )
                }
                item { pos ->
                    ListSettingsItem(
                        title = stringResource(R.string.favourites_scroll_mode),
                        entries = favouriteScrollEntries,
                        entryValues = favouriteScrollValues,
                        selectedValue = favouriteScrollMode,
                        onValueChange = { favouriteScrollMode = it },
                        icon = R.drawable.ic_list,
                        shape = pos.shape,
                    )
                }
                item { pos ->
                    ListSettingsItem(
                        title = stringResource(R.string.favourites_loading_mode),
                        entries = favouriteLoadingEntries,
                        entryValues = favouriteLoadingValues,
                        selectedValue = favouriteLoadingMode,
                        onValueChange = { favouriteLoadingMode = it },
                        icon = R.drawable.ic_list,
                        shape = pos.shape,
                    )
                }
                item { pos ->
                    SwitchSettingsItem(
                        title = stringResource(R.string.title_tap_to_read),
                        subtitle = stringResource(R.string.title_tap_to_read_summary),
                        checked = titleTapToRead,
                        onCheckedChange = { titleTapToRead = it },
                        icon = R.drawable.ic_read,
                        shape = pos.shape,
                    )
                }
                item { pos ->
                    SwitchSettingsItem(
                        title = stringResource(R.string.duplicates_check),
                        subtitle = stringResource(R.string.duplicates_check_summary),
                        checked = checkDuplicates,
                        onCheckedChange = { checkDuplicates = it },
                        icon = R.drawable.ic_duplicate,
                        shape = pos.shape,
                    )
                }
            }
        }
        item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
        item {
            SettingsGroup(title = stringResource(R.string.details)) {
                item { pos ->
                    SwitchSettingsItem(
                        title = stringResource(R.string.collapse_long_description),
                        checked = descriptionCollapse,
                        onCheckedChange = { descriptionCollapse = it },
                        icon = R.drawable.ic_expand,
                        shape = pos.shape,
                    )
                }
                item { pos ->
                    SwitchSettingsItem(
                        title = stringResource(R.string.show_pages_thumbs),
                        subtitle = stringResource(R.string.show_pages_thumbs_summary),
                        checked = pagesTab,
                        onCheckedChange = { pagesTab = it },
                        icon = R.drawable.ic_images,
                        shape = pos.shape,
                    )
                }
                item { pos ->
                    ListSettingsItem(
                        title = stringResource(R.string.default_tab),
                        entries = detailsTabEntries,
                        entryValues = detailsTabValues,
                        selectedValue = detailsTab,
                        onValueChange = { detailsTab = it },
                        icon = R.drawable.ic_list_group,
                        shape = pos.shape,
                        enabled = pagesTab,
                    )
                }
                item { pos ->
                    NavigationSettingsItem(
                        title = stringResource(R.string.details_appearance),
                        subtitle = stringResource(R.string.details_appearance_summary),
                        icon = R.drawable.ic_list_detailed,
                        shape = pos.shape,
                        onClick = onOpenDetailsAppearance,
                    )
                }
            }
        }
        item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
        item {
            SettingsGroup(title = stringResource(R.string.main_screen)) {
                item { pos ->
                    MultiSelectSettingsItem(
                        title = stringResource(R.string.search_suggestions),
                        entries = searchSuggestionEntries,
                        entryValues = searchSuggestionValues,
                        selectedValues = searchSuggestions,
                        onValuesChange = { searchSuggestions = it },
                        icon = R.drawable.ic_suggestion,
                        shape = pos.shape,
                    )
                }
                item { pos ->
                    NavigationSettingsItem(
                        title = stringResource(R.string.main_screen_sections),
                        icon = R.drawable.ic_drawer_menu,
                        shape = pos.shape,
                        onClick = onOpenNavConfig,
                    )
                }
                item { pos ->
                    SwitchSettingsItem(
                        title = stringResource(R.string.main_screen_fab),
                        subtitle = stringResource(R.string.main_screen_fab_summary),
                        checked = mainFab,
                        onCheckedChange = { mainFab = it },
                        icon = R.drawable.ic_read,
                        shape = pos.shape,
                    )
                }
                item { pos ->
                    SwitchSettingsItem(
                        title = stringResource(R.string.show_labels_in_navbar),
                        checked = navLabels,
                        onCheckedChange = { navLabels = it },
                        icon = R.drawable.ic_title,
                        shape = pos.shape,
                    )
                }
                item { pos ->
                    NavigationSettingsItem(
                        title = stringResource(R.string.settings_feed),
                        icon = R.drawable.ic_feed,
                        shape = pos.shape,
                        onClick = onOpenFeed,
                    )
                }
                item { pos ->
                    NavigationSettingsItem(
                        title = stringResource(R.string.settings_advanced),
                        subtitle = stringResource(R.string.settings_advanced_appearance_summary),
                        icon = R.drawable.ic_script,
                        shape = pos.shape,
                        onClick = onOpenAdvanced,
                    )
                }
            }
        }
        item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
        item {
            SettingsGroup(title = stringResource(R.string.appearance)) {
                item { pos ->
                    ActionSettingsItem(
                        title = stringResource(R.string.miyorare_reset_appearance),
                        subtitle = stringResource(R.string.miyorare_reset_appearance_summary),
                        onClick = { showResetDialog = true },
                        icon = R.drawable.ic_refresh,
                        shape = pos.shape,
                    )
                }
            }
        }
        item { Spacer(Modifier.height(24.dp).fillMaxWidth()) }
    }

    if (showResetDialog) {
        ConfirmDialog(
            title = stringResource(R.string.miyorare_reset_appearance),
            message = stringResource(R.string.miyorare_reset_appearance_confirm),
            confirmLabel = stringResource(R.string.reset),
            dismissLabel = stringResource(android.R.string.cancel),
            onConfirm = onResetAppearance,
            onDismiss = { showResetDialog = false },
        )
    }
}


@Composable
private fun CustomBackgroundPreview(
    path: String,
    revision: Int,
    shape: Shape,
) {
    val bitmap = remember(path, revision) {
        BitmapFactory.decodeFile(path)?.asImageBitmap()
    }
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = stringResource(R.string.miyorare_custom_background_preview),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(164.dp),
            )
        }
    }
}
