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

    private fun source(relativePath: String): String = sequenceOf(
        File("src/main", relativePath),
        File("app/src/main", relativePath),
    ).firstOrNull(File::isFile)?.readText()
        ?: error("Cannot find production source: $relativePath")
}
