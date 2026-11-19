package org.koitharu.kotatsu.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Guards the approved Sources & Extensions grouping without changing existing behavior. */
class SourcesSettingsDesignRegressionTest {

    @Test
    fun `sources management has five connected theme-aware sections`() {
        val screen = source("kotlin/org/koitharu/kotatsu/settings/sources/ExtensionsSettingsFragment.kt")
        val group = source("kotlin/org/koitharu/kotatsu/settings/compose/SettingsGroup.kt")

        assertEquals(5, screen.split("connectedModernRows = true").size - 1)
        listOf(
            "sources_section_library", "sources_section_maintenance",
            "sources_section_display", "sources_section_privacy", "sources_section_updates",
        ).forEach { key -> assertTrue(screen.contains("R.string.$key")) }
        assertTrue(screen.contains("MaterialTheme.colorScheme.onSurfaceVariant"))
        assertTrue(screen.contains("NavigationSettingsItem("))
        assertTrue(group.contains("connectedModernRows: Boolean = false"))
        assertTrue(group.contains("modern = modern && !connectedModernRows"))
        assertFalse("Do not hardcode the mockup accent color", screen.contains("Color(0x"))
    }

    @Test
    fun `all original actions and installer-dependent update behavior remain wired`() {
        val screen = source("kotlin/org/koitharu/kotatsu/settings/sources/ExtensionsSettingsFragment.kt")
        listOf(
            "onOpenMiyorareSourcePacks", "onOpenCatalog", "onOpenStores",
            "onOpenKotatsuParsers", "onOpenTsukiPlugins", "onOpenBrokenSourcesMigration",
            "onChooseInstallerMethod",
        ).forEach { action -> assertTrue(screen.contains("onClick = $action")) }
        assertTrue(screen.contains("var sortOrder by rememberStringPref(AppSettings.KEY_SOURCES_ORDER"))
        assertTrue(screen.contains("var noNsfw by rememberBooleanPref(AppSettings.KEY_DISABLE_NSFW"))
        assertTrue(screen.contains("enabled = autoUpdateEnabled"))
        assertTrue(screen.contains("installerUiState.method != ExtensionInstallerMethod.SYSTEM"))
        assertTrue(screen.contains("sources_auto_update_unavailable"))
    }

    @Test
    fun `settings search group labels stay aligned with Sources screen`() {
        val search = source("kotlin/org/koitharu/kotatsu/settings/search/SettingsSearchHelper.kt")
        val section = search.substringAfter("section(R.string.settings_sources_extensions)")
            .substringBefore("section(R.string.reader_settings)")
        listOf(
            "sources_section_library", "sources_section_maintenance",
            "sources_section_display", "sources_section_privacy", "sources_section_updates",
        ).forEach { key -> assertTrue(section.contains("group(sectionCrumbs, R.string.$key)")) }
        assertTrue(section.contains("migrate_broken_sources"))
        assertTrue(section.contains("extension_installer_method"))
        val en = source("res/values/settings_consistency.xml")
        val id = source("res/values-in/settings_consistency.xml")
        assertTrue(en.contains("name=\"sources_screen_intro\""))
        assertTrue(id.contains("name=\"sources_screen_intro\""))
    }

    private fun source(relativePath: String): String = sequenceOf(
        File("src/main", relativePath),
        File("app/src/main", relativePath),
    ).firstOrNull(File::isFile)?.readText()
        ?: error("Cannot find production source: $relativePath")
}
