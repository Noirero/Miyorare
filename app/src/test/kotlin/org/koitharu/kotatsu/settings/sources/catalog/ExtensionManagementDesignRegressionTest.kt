package org.koitharu.kotatsu.settings.sources.catalog

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Source-level guards for the approved extension/repository management redesign. */
class ExtensionManagementDesignRegressionTest {

    @Test
    fun `extension cards expose live status and preserve actions in a compact hierarchy`() {
        val item = source("kotlin/org/koitharu/kotatsu/settings/sources/catalog/SourceCatalogItemAD.kt")
        val layout = source("res/layout/item_source_catalog.xml")
        assertTrue(layout.contains("@drawable/bg_settings_tile"))
        assertTrue(layout.contains("@+id/textView_status"))
        assertTrue(layout.contains("@+id/imageView_more"))
        assertTrue(item.contains("binding.imageViewAdd.text = actionDescription"))
        assertTrue(item.contains("binding.textViewStatus.setText(statusRes)"))
        assertTrue(item.contains("binding.imageViewMore.isVisible = isInstalled"))
        assertTrue(item.contains("listener.onExtensionActionClick(item)"))
        assertTrue(item.contains("listener.onExtensionSettingsClick(item)"))
        assertTrue(item.contains("listener.onExtensionHideClick(item)"))
        assertTrue(item.contains("binding.imageViewMore.isEnabled = !isInProgress"))
        assertTrue(item.contains("extensionPackageFaviconUri(item.packageName)"))
        assertFalse(layout.contains("#6750A4"))
    }

    @Test
    fun `repository cards retain edit retry copy links removal and bounded reordering`() {
        val adapter = source("kotlin/org/koitharu/kotatsu/settings/sources/catalog/stores/ExtensionStoresAdapter.kt")
        val activity = source("kotlin/org/koitharu/kotatsu/settings/sources/catalog/stores/ExtensionStoresActivity.kt")
        val layout = source("res/layout/item_extension_store.xml")
        assertTrue(layout.contains("@drawable/bg_settings_tile"))
        listOf("text_status", "text_url", "text_count", "button_more", "drag_handle").forEach {
            assertTrue(layout.contains("@+id/$it"))
        }
        listOf("listener.onEdit(item)", "listener.onCopy(item)", "listener.onRetry()",
            "listener::onOpenLink", "listener.onRemove(item)", "listener.onDrag(this)").forEach {
            assertTrue("Missing action: $it", adapter.contains(it))
        }
        assertTrue(adapter.contains("item.health == StoreHealth.AVAILABLE && item.catalog.isNotEmpty()"))
        assertTrue(adapter.contains("items[fromIndex].contentType != items[toIndex].contentType"))
        assertTrue(activity.contains("confirmRemove(item)"))
        assertTrue(activity.contains("viewModel.containsStoreUrl(url)"))
        assertTrue(activity.contains("onDrag(holder: RecyclerView.ViewHolder)"))
    }

    @Test
    fun `media tabs keep real pages and theme-derived selection`() {
        val screen = source("res/layout/activity_sources_catalog.xml")
        val activity = source("kotlin/org/koitharu/kotatsu/settings/sources/catalog/SourcesCatalogActivity.kt")
        val selector = source("res/drawable/bg_extension_category_tab.xml")
        assertTrue(screen.contains("@drawable/bg_extension_category_tab"))
        assertTrue(selector.contains("?attr/colorPrimaryContainer"))
        assertTrue(screen.contains("?attr/colorOnPrimaryContainer"))
        assertTrue(activity.contains("TabLayoutMediator(viewBinding.tabs, viewBinding.pager)"))
        assertTrue(activity.contains("viewModel.selectStoreContentType"))
        assertTrue(activity.contains("viewModel.performSearch"))
        assertTrue(activity.contains("viewModel.onInstallEntryClick(item)"))
        assertFalse(selector.contains("#"))
    }

    private fun source(relativePath: String): String = sequenceOf(
        File("src/main", relativePath),
        File("app/src/main", relativePath),
    ).firstOrNull(File::isFile)?.readText()
        ?: error("Cannot find production source: $relativePath")
}
