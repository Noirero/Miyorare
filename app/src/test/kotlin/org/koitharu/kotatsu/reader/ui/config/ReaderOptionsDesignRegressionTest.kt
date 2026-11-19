package org.koitharu.kotatsu.reader.ui.config

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the approved Manga Reader options redesign without changing the reader engine.
 * Runtime sheet sizing, rotation and page gestures still require Android/device validation.
 */
class ReaderOptionsDesignRegressionTest {

    @Test
    fun `reader overflow opens the same adaptive sheet and retains EPUB routing`() {
        val reader = source("kotlin/org/koitharu/kotatsu/reader/ui/ReaderActivity.kt")
        val dock = source("kotlin/org/koitharu/kotatsu/reader/ui/ReaderActionsView.kt")
        val provider = source("kotlin/org/koitharu/kotatsu/reader/ui/ReaderMenuProvider.kt")
        val sheet = source("kotlin/org/koitharu/kotatsu/reader/ui/config/ReaderConfigSheet.kt")
        assertTrue(reader.contains("router.showReaderConfigSheet(currentMode)"))
        assertTrue(reader.contains("viewModel.saveCurrentState(readerManager.currentReader?.getCurrentState())"))
        assertTrue(dock.contains("R.id.button_options -> listener?.openMenu()"))
        assertTrue(provider.contains("onOpenMenu()"))
        assertTrue(sheet.contains("if (isEpubBook) {"))
        assertTrue(sheet.contains("EpubConfigContent()"))
        assertTrue(sheet.contains("ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed"))
        assertTrue(sheet.contains("expandToContent()"))
        // The same capsule is reused by EPUB with three explicit, localized tabs.
        // Preserve its optional tabs parameter instead of hiding EPUB navigation.
        assertTrue(sheet.contains("tabs: List<Triple<String, Int, Int>>? = null"))
        assertTrue(sheet.contains("val visibleTabs = tabs ?: listOf("))
        assertTrue(sheet.contains("visibleTabs.forEach"))
        assertTrue(sheet.contains("Triple(stringResource(R.string.epub_text_tab)"))
        assertTrue(sheet.contains("Triple(stringResource(R.string.epub_reading_tab)"))
        assertTrue(sheet.contains("Triple(stringResource(R.string.epub_tools_tab)"))
    }

    @Test
    fun `reader mode keeps four modes and prevents invalid double-page edits`() {
        val modes = source("kotlin/org/koitharu/kotatsu/reader/ui/config/ReaderModeComponents.kt")
        val sheet = source("kotlin/org/koitharu/kotatsu/reader/ui/config/ReaderConfigSheet.kt")
        listOf("STANDARD", "REVERSED", "VERTICAL", "WEBTOON").forEach { mode ->
            assertTrue(modes.contains("ReaderMode.$mode"))
        }
        assertTrue(modes.contains("selectedIndex = selectedIndex"))
        assertTrue(modes.contains("enabled = isModeStandardOrReversed"))
        assertTrue(modes.contains("enabled = isModeStandardOrReversed && isDoubleOnLandscape"))
        assertTrue(modes.contains("onSensitivityChange"))
        assertTrue(sheet.contains("callback?.onReaderModeChanged(newMode)"))
        assertTrue(sheet.contains("settings.isReaderDoubleOnLandscape = enabled"))
        assertTrue(sheet.contains("settings.isReaderDoubleOnFoldable = enabled"))
        assertTrue(sheet.contains("settings.readerDoublePagesSensitivity = value / 100f"))
    }

    @Test
    fun `tools keep all actions and offer localized accessible compact cards`() {
        val sheet = source("kotlin/org/koitharu/kotatsu/reader/ui/config/ReaderConfigSheet.kt")
        val en = source("res/values/strings.xml")
        val id = source("res/values-in/strings.xml")
        listOf(
            "callback?.onSavePageClick()",
            "orientationHelper.toggleScreenOrientation()",
            "callback?.onScrollTimerClick(false)",
            "router.openColorFilterConfig(manga, page)",
            "viewModel.toggleBookmark()",
            "router.openReaderSettings()",
        ).forEach { action -> assertTrue("Action missing: $action", sheet.contains(action)) }
        assertTrue(sheet.contains("role = Role.Tab"))
        assertTrue(sheet.contains("selected = isSelected"))
        assertTrue(sheet.contains("R.string.read_mode"))
        assertTrue(sheet.contains("R.string.reader_tools"))
        assertTrue(sheet.contains("R.string.reader_advanced_options"))
        assertTrue(sheet.contains("R.string.bookmark_remove else R.string.bookmark_add"))
        assertTrue(sheet.contains("contentDescription = contentDescription"))
        assertTrue(sheet.contains("heightIn(min = 88.dp)"))
        assertFalse(sheet.contains("subtitle = \"Advanced reader options\""))
        assertTrue(sheet.contains("MaterialTheme.colorScheme.surfaceContainerHigh"))
        listOf("reader_tools", "reader_advanced_options").forEach { key ->
            assertTrue(en.contains("name=\"$key\""))
            assertTrue(id.contains("name=\"$key\""))
        }
    }

    @Test
    fun `quick deck reduces Manga sheet height without touching EPUB or reader state`() {
        val sheet = source("kotlin/org/koitharu/kotatsu/reader/ui/config/ReaderConfigSheet.kt")
        val modes = source("kotlin/org/koitharu/kotatsu/reader/ui/config/ReaderModeComponents.kt")
        val selector = source("kotlin/org/koitharu/kotatsu/core/ui/sheet/ExpressiveSheet.kt")

        val mangaStart = sheet.indexOf("private fun ReaderConfigContent()")
        val mangaEnd = sheet.indexOf("private fun BottomPillTabBar(", mangaStart)
        assertTrue(mangaStart >= 0 && mangaEnd > mangaStart)
        val manga = sheet.substring(mangaStart, mangaEnd)
        assertFalse(manga.contains("Spacer(modifier = Modifier.height(84.dp))"))
        assertTrue(manga.contains("pagerHeightPx"))
        assertTrue(manga.contains("Modifier.height(with(density) { pagerHeightPx.toDp() })"))
        assertTrue(manga.contains("BottomPillTabBar("))
        assertTrue(manga.contains("compact = true"))
        assertTrue(manga.contains("modifier = Modifier,"))
        assertTrue(manga.contains("verticalScroll(rememberScrollState())"))
        assertTrue(manga.indexOf("BottomPillTabBar(") > manga.indexOf("HorizontalPager("))

        assertTrue(modes.contains("compact = true"))
        assertTrue(selector.contains("compact: Boolean = false"))
        assertTrue(selector.contains("height(if (compact) 76.dp else 112.dp)"))
        assertTrue(sheet.contains("height(if (compact) 48.dp else 52.dp)"))
        assertTrue(sheet.contains("val visibleTabs = tabs ?: listOf("))
        assertTrue(sheet.contains("Triple(stringResource(R.string.epub_tools_tab)"))
    }

    @Test
    fun `compact tools retain six action callbacks and accessible bookmark`() {
        val sheet = source("kotlin/org/koitharu/kotatsu/reader/ui/config/ReaderConfigSheet.kt")
        val start = sheet.indexOf("private fun ToolsGridSection(")
        val end = sheet.indexOf("private fun ToolWideCard(", start)
        assertTrue(start >= 0 && end > start)
        val tools = sheet.substring(start, end)
        listOf("onSaveClick", "onOrientationClick", "onScrollTimerClick",
            "onColorFilterClick", "onBookmarkClick", "onSettingsClick").forEach {
            assertTrue("Missing reader action $it", tools.contains(it))
        }
        assertTrue(tools.contains("ReaderQuickToolCard("))
        assertTrue(tools.contains("heightIn(min = 76.dp)"))
        assertTrue(tools.contains("modifier = Modifier.size(76.dp)"))
        assertTrue(tools.contains("R.string.bookmark_remove else R.string.bookmark_add"))
        assertFalse(tools.contains("modifier = Modifier.weight(1f),\\n                                    )"))
        assertTrue(tools.contains("MaterialTheme.colorScheme.primaryContainer"))
        assertTrue(tools.contains("MaterialTheme.colorScheme.surfaceContainerHigh"))
    }

    @Test
    fun `reader dock and Settings preview share active Modern colors without changing controls`() {
        val dock = source("kotlin/org/koitharu/kotatsu/reader/ui/ReaderActionsView.kt")
        val preview = source("res/layout/view_reader_bar_preview.xml")
        val settings = source("kotlin/org/koitharu/kotatsu/settings/reader/ReaderBarSettingsFragment.kt")
        assertTrue(preview.contains("org.koitharu.kotatsu.reader.ui.ReaderActionsView"))
        assertTrue(settings.contains("ReaderActionsView"))
        assertTrue(dock.contains("applyModernReaderBarPalette()"))
        assertTrue(dock.contains("context.miyorareViewPaletteFromPreferences() ?: return"))
        assertTrue(dock.contains("FloatingToolbarLayout"))
        assertTrue(dock.contains("ColorStateList.valueOf(palette.surfaceContainer)"))
        assertTrue(dock.contains("binding.slider.trackActiveTintList = primary"))
        assertTrue(dock.contains("binding.slider.thumbTintList = primary"))
        assertTrue(dock.contains("binding.slider.trackInactiveTintList"))
        assertTrue(dock.contains("if (isActive) it.primary else it.onSurfaceVariant"))
        assertTrue(dock.contains("listener?.onSavePageClick()"))
        assertTrue(dock.contains("listener?.onBookmarkClick()"))
        assertTrue(dock.contains("AppSettings.KEY_READER_CONTROLS -> updateControlsVisibility()"))
    }

    @Test
    fun `color filter chrome follows Modern scheme but preserves the actual filter`() {
        val screen = source("kotlin/org/koitharu/kotatsu/reader/ui/colorfilter/ColorFilterConfigActivity.kt")
        assertTrue(screen.contains("miyorareViewPaletteFromPreferences() ?: return"))
        assertTrue(screen.contains("applyModernFilterColors(viewBinding.root, palette)"))
        assertTrue(screen.contains("root.setCardBackgroundColor(palette.surfaceContainerHigh)"))
        assertTrue(screen.contains("root.trackActiveTintList = primary"))
        assertTrue(screen.contains("root.thumbTintList = primary"))
        assertTrue(screen.contains("root.chipBackgroundColor = ColorStateList("))
        assertTrue(screen.contains("root.trackTintList = ColorStateList("))
        assertTrue(screen.contains("checkedState"))
        assertTrue(screen.contains("root.id == R.id.button_reset"))
        assertTrue(screen.contains("viewModel.setBrightness(value)"))
        assertTrue(screen.contains("viewModel.setContrast(value)"))
        assertTrue(screen.contains("viewModel.saveGlobally()"))
        assertTrue(screen.contains("ReaderCustomTint.applyToImage(viewBinding.imageViewAfter"))
        assertTrue(screen.contains("onLoadingChanged(isLoading: Boolean)"))
    }

    @Test
    fun `Color Studio keeps native preview and saves while docking actions`() {
        val screen = source("res/layout/activity_color_filter.xml")
        val activity = source("kotlin/org/koitharu/kotatsu/reader/ui/colorfilter/ColorFilterConfigActivity.kt")
        val vm = source("kotlin/org/koitharu/kotatsu/reader/ui/colorfilter/ColorFilterConfigViewModel.kt")
        assertTrue(screen.contains("@+id/imageView_before"))
        assertTrue(screen.contains("@+id/imageView_after"))
        assertTrue(screen.contains("android:layout_weight=\"1\""))
        assertTrue(screen.indexOf("@+id/button_reset") > screen.indexOf("</ScrollView>"))
        assertTrue(screen.indexOf("@+id/button_done") > screen.indexOf("</ScrollView>"))
        assertTrue(activity.contains("loadPreview(viewModel.preview)"))
        assertTrue(activity.contains("ReaderCustomTint.applyToImage(viewBinding.imageViewAfter"))
        assertTrue(activity.contains("R.id.button_done -> showSaveConfirmation()"))
        assertTrue(activity.contains("R.id.button_reset -> viewModel.reset()"))
        assertTrue(activity.contains("viewModel.saveGlobally()"))
        assertTrue(activity.contains("viewModel.save()"))
        assertTrue(vm.contains("mangaDataRepository.resetColorFilters()"))
    }

    @Test
    fun `Custom Tint expansion is independent of filter enablement and restores state`() {
        val controls = source("res/layout/reader_custom_filter_controls.xml")
        val activity = source("kotlin/org/koitharu/kotatsu/reader/ui/colorfilter/ColorFilterConfigActivity.kt")
        assertTrue(controls.contains("@+id/button_expand_tint"))
        assertTrue(controls.contains("@+id/custom_advanced_content"))
        assertTrue(controls.contains("android:visibility=\"gone\""))
        assertTrue(activity.contains("savedInstanceState?.getBoolean(KEY_ADVANCED_EXPANDED)"))
        assertTrue(activity.contains("outState.putBoolean(KEY_ADVANCED_EXPANDED, advancedExpanded)"))
        assertTrue(activity.contains("custom.customAdvancedContent.isVisible = advancedExpanded"))
        assertTrue(activity.contains("custom.buttonExpandTint.setOnClickListener"))
        assertTrue(activity.contains("custom.switchCustomTint.setOnCheckedChangeListener(this)"))
        assertTrue(activity.contains("if (!updatingUi) viewModel.setCustomEnabled(isChecked)"))
        listOf("sliderTintRed", "sliderTintGreen", "sliderTintBlue", "sliderTintAlpha",
            "chip_blend_default", "chip_blend_multiply", "chip_blend_screen",
            "chip_blend_overlay", "chip_blend_dodge", "chip_blend_burn").forEach {
            assertTrue("Lost Color Studio setting: $it", controls.contains(it))
        }
    }

    private fun source(relativePath: String): String = sequenceOf(
        File("src/main", relativePath),
        File("app/src/main", relativePath),
    ).firstOrNull(File::isFile)?.readText()
        ?: error("Cannot find production source: $relativePath")
}
