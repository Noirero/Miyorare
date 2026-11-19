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
        assertTrue(item.contains("binding.textViewStatus.isVisible = !isInProgress"))
        assertTrue(item.contains("item.action == SourceCatalogItem.Extension.Action.UNINSTALL"))
        assertTrue(item.contains("binding.textViewStatus.setText(R.string.installed)"))
        assertFalse(item.contains("binding.textViewStatus.setText(statusRes)"))
        assertTrue(layout.contains("android:visibility=\"gone\""))
        assertTrue(item.contains("binding.imageViewAdd.backgroundTintList = ColorStateList.valueOf(palette.primary)"))
        assertTrue(item.contains("binding.textViewTitle.setTextColor(palette.onSurface)"))
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
        val screen = source("res/layout/activity_extension_stores.xml")
        assertTrue(layout.contains("@drawable/bg_settings_tile"))
        listOf("text_status", "text_url", "text_count", "button_more", "drag_handle").forEach {
            assertTrue(layout.contains("@+id/$it"))
        }
        listOf("listener.onEdit(item)", "listener.onCopy(item)", "listener.onRetry()",
            "listener::onOpenLink", "listener.onRemove(item)", "listener.onDrag(this)").forEach {
            assertTrue("Missing action: $it", adapter.contains(it))
        }
        assertTrue(adapter.contains("item.health == StoreHealth.AVAILABLE && item.catalog.isNotEmpty()"))
        assertTrue(adapter.contains("binding.tileRoot.backgroundTintList = ColorStateList.valueOf(palette.surfaceContainer)"))
        assertTrue(adapter.contains("binding.buttonEdit.setTextColor(palette.primary)"))
        assertTrue(adapter.contains("item.store.website?.let(listener::onOpenLink)"))
        assertFalse(layout.contains("@+id/text_kind"))
        assertFalse(layout.contains("@+id/button_website"))
        assertTrue(layout.contains("@+id/store_icon"))
        assertTrue(screen.indexOf("@+id/recyclerView") < screen.indexOf("@+id/fab_add"))
        assertFalse(screen.contains("app:layout_anchor="))
        assertFalse(screen.contains("android:paddingBottom=\"88dp\""))
        assertFalse(activity.contains("viewBinding.fabAdd.updateLayoutParams"))
        assertTrue(activity.contains("viewBinding.content.updatePadding(bottom = bars.bottom)"))
        assertTrue(activity.contains("viewBinding.fabAdd.backgroundTintList = ColorStateList.valueOf(palette.primary)"))
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
        val catalogAdapter = source("kotlin/org/koitharu/kotatsu/settings/sources/catalog/SourcesCatalogAdapter.kt")
        assertTrue(screen.contains("@drawable/bg_extension_category_tab"))
        assertTrue(selector.contains("?attr/colorPrimaryContainer"))
        assertTrue(screen.contains("?attr/colorOnPrimaryContainer"))
        assertTrue(activity.contains("TabLayoutMediator(viewBinding.tabs, viewBinding.pager)"))
        assertTrue(activity.contains("viewModel.selectStoreContentType"))
        assertTrue(activity.contains("viewModel.performSearch"))
        assertTrue(activity.contains("viewModel.onInstallEntryClick(item)"))
        assertTrue(activity.contains("miyorareViewPaletteFromPreferences()"))
        assertTrue(activity.contains("viewBinding.tabs.setSelectedTabIndicatorColor(palette.primary)"))
        assertTrue(activity.contains("viewBinding.tabsMedia.setTabTextColors(palette.onSurfaceVariant, palette.onPrimaryContainer)"))
        assertTrue(activity.contains("chip.chipBackgroundColor = backgrounds"))
        assertTrue(activity.contains("chip.setTextColor(foregrounds)"))
        assertTrue(catalogAdapter.contains("binding.button.backgroundTintList = ColorStateList.valueOf(palette.primary)"))
        assertTrue(catalogAdapter.contains("binding.buttonMethod.setTextColor(palette.onSurface)"))
        assertFalse(selector.contains("#"))
    }

    @Test
    fun `extension and repository action labels have space for font descenders`() {
        // Both Update and Uninstall use imageView_add; Edit uses button_edit.
        // Fixed 40dp controls clipped the active app font on the owner's device.
        val extension = source("res/layout/item_source_catalog.xml")
        val repository = source("res/layout/item_extension_store.xml")
        listOf(extension to "imageView_add", repository to "button_edit").forEach { (layout, id) ->
            val marker = "android:id=\"@+id/$id\""
            val start = layout.indexOf(marker)
            assertTrue("Missing primary action button $id", start >= 0)
            val end = layout.indexOf("/>", start)
            assertTrue("Unterminated button $id", end > start)
            val button = layout.substring(start, end)
            assertTrue("$id must measure text height", button.contains("android:layout_height=\"wrap_content\""))
            assertTrue("$id must have a 48dp minimum touch area", button.contains("android:minHeight=\"48dp\""))
            assertTrue("$id must center the icon and label", button.contains("android:gravity=\"center\""))
            assertTrue("$id must retain font ascenders/descenders", button.contains("android:includeFontPadding=\"true\""))
            assertTrue("$id must have vertical breathing room", button.contains("android:paddingVertical=\"8dp\""))
        }
    }

    private fun source(relativePath: String): String = sequenceOf(
        File("src/main", relativePath),
        File("app/src/main", relativePath),
    ).firstOrNull(File::isFile)?.readText()
        ?: error("Cannot find production source: $relativePath")
}
