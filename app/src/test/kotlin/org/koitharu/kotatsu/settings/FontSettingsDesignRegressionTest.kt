package org.koitharu.kotatsu.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Source-level guards for the approved App Font preview design: a pinned
 * preview that follows the tapped card, fed by real preview families. */
class FontSettingsDesignRegressionTest {

    @Test
    fun `font showcase uses actual preview families and semantic theme colors`() {
        val screen = source("kotlin/org/koitharu/kotatsu/settings/fonts/FontSettingsFragment.kt")
        val theme = source("kotlin/org/koitharu/kotatsu/settings/compose/SettingsTheme.kt")

        assertTrue(screen.contains("FontPreviewHero("))
        assertTrue(screen.contains("family = targetFamily"))
        assertTrue(screen.contains("viewModel.preview(it)"))
        assertTrue(screen.contains("family = googleSansRounded"))
        assertTrue(screen.contains("viewModel.fontFamily(pack)"))
        assertTrue(screen.contains("MaterialTheme.colorScheme"))
        assertTrue(screen.contains("lerp(colors.surfaceContainerHigh, colors.primaryContainer"))
        assertFalse("Accent must follow the chosen theme, not fixed mockup cyan", screen.contains("Color(0x"))
        assertTrue(theme.contains("internal val googleSansRounded = FontFamily("))
    }

    @Test
    fun `font preview uninstall confirmation and catalog loading remain wired`() {
        val screen = source("kotlin/org/koitharu/kotatsu/settings/fonts/FontSettingsFragment.kt")
        val model = source("kotlin/org/koitharu/kotatsu/settings/fonts/FontSettingsViewModel.kt")

        assertTrue(screen.contains("onClick = { showPreview(null) }"))
        assertTrue(screen.contains("onClick = { showPreview(pack.pack.id) }"))
        assertTrue(screen.contains("onClick = { showPreview(pack.id) }"))
        assertTrue(screen.contains("viewModel.activate(null)"))
        assertTrue(screen.contains("viewModel.activate(targetInstalled.pack.id)"))
        assertTrue(model.contains("repository.previewFontFamily(pack)"))
        assertTrue(screen.contains("onRemove = { pendingRemoval = pack }"))
        assertTrue(screen.contains("AlertDialog("))
        assertTrue(screen.contains("viewModel.uninstall(pack.pack.id)"))
        assertTrue(screen.contains("onInstall = { viewModel.install(pack) }"))
        assertTrue(screen.contains("catalogLoading by viewModel.catalogLoading.collectAsState()"))
        assertTrue(model.contains("if (_catalogLoading.value) return"))
        assertTrue(model.contains("_catalogLoading.value = false"))
    }

    @Test
    fun `catalog loading state is initialized before constructor starts refresh`() {
        val model = source("kotlin/org/koitharu/kotatsu/settings/fonts/FontSettingsViewModel.kt")
        val init = model.indexOf("init {")
        val refresh = model.indexOf("refreshCatalog()", init)
        val loading = model.indexOf("private val _catalogLoading = MutableStateFlow(false)")
        val loaded = model.indexOf("private val _catalogLoaded = MutableStateFlow(false)")
        val errors = model.indexOf("private val _errorMessage = MutableStateFlow<String?>(null)")

        // Kotlin executes field initializers and init blocks in source order.
        // Refreshing before _catalogLoading exists crashes immediately on screen entry.
        assertTrue("Initial refresh must exist in the constructor", init >= 0 && refresh > init)
        assertTrue("Loading flow must exist before initial refresh", loading >= 0 && loading < init)
        assertTrue("Loaded flow must exist before initial refresh", loaded >= 0 && loaded < init)
        assertTrue("Error flow must exist before initial refresh", errors >= 0 && errors < init)
    }

    private fun source(relativePath: String): String = sequenceOf(
        File("src/main", relativePath),
        File("app/src/main", relativePath),
    ).firstOrNull(File::isFile)?.readText()
        ?: error("Cannot find production source: $relativePath")
}
