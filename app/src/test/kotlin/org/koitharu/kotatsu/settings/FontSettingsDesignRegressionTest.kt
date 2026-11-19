package org.koitharu.kotatsu.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Source-level guards for the approved App Font showcase redesign. */
class FontSettingsDesignRegressionTest {

    @Test
    fun `font showcase uses actual preview families and semantic theme colors`() {
        val screen = source("kotlin/org/koitharu/kotatsu/settings/fonts/FontSettingsFragment.kt")
        val theme = source("kotlin/org/koitharu/kotatsu/settings/compose/SettingsTheme.kt")

        assertTrue(screen.contains("ActiveFontPreview(name = activeName, family = activeFamily)"))
        assertTrue(screen.contains("family = googleSansRounded"))
        assertTrue(screen.contains("viewModel.fontFamily(pack)"))
        assertTrue(screen.contains("MaterialTheme.colorScheme"))
        assertTrue(screen.contains("lerp(colors.surfaceContainerHigh, colors.primaryContainer"))
        assertFalse("Accent must follow the chosen theme, not fixed mockup cyan", screen.contains("Color(0x"))
        assertTrue(theme.contains("internal val googleSansRounded = FontFamily("))
    }

    @Test
    fun `font selection uninstall confirmation and catalog loading remain wired`() {
        val screen = source("kotlin/org/koitharu/kotatsu/settings/fonts/FontSettingsFragment.kt")
        val model = source("kotlin/org/koitharu/kotatsu/settings/fonts/FontSettingsViewModel.kt")

        assertTrue(screen.contains("role = Role.RadioButton"))
        assertTrue(screen.contains("onSelect = { viewModel.activate(null) }"))
        assertTrue(screen.contains("onSelect = { viewModel.activate(pack.pack.id) }"))
        assertTrue(screen.contains("onRemove = { pendingRemoval = pack }"))
        assertTrue(screen.contains("AlertDialog("))
        assertTrue(screen.contains("viewModel.uninstall(pack.pack.id)"))
        assertTrue(screen.contains("onInstall = { viewModel.install(pack) }"))
        assertTrue(screen.contains("catalogLoading by viewModel.catalogLoading.collectAsState()"))
        assertTrue(model.contains("if (_catalogLoading.value) return"))
        assertTrue(model.contains("_catalogLoading.value = false"))
    }

    private fun source(relativePath: String): String = sequenceOf(
        File("src/main", relativePath),
        File("app/src/main", relativePath),
    ).firstOrNull(File::isFile)?.readText()
        ?: error("Cannot find production source: $relativePath")
}
