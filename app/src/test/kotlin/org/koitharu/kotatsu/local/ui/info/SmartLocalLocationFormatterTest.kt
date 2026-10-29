package org.koitharu.kotatsu.local.ui.info

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SmartLocalLocationFormatterTest {
    @Test fun decodesSdCardTreeWithoutShowingContentUri() {
        val uri = "content://com.android.externalstorage.documents/tree/78FF-F627%3A01.%20SDCARD%2F-%20Zona%20Tachi%20fork%20-%20%2FCdisplayex%20Folder"
        assertEquals(
            "SD card (78FF-F627) / 01. SDCARD / - Zona Tachi fork - / Cdisplayex Folder",
            SmartLocalLocationFormatter.format(uri),
        )
    }

    @Test fun handlesInternalStorageAndDocumentIds() {
        assertEquals(
            "Internal storage / Comics / Manga One",
            SmartLocalLocationFormatter.format("content://com.android.externalstorage.documents/tree/primary%3AComics/document/primary%3AComics%2FManga%20One"),
        )
        assertEquals("/storage/emulated/0/Manga", SmartLocalLocationFormatter.format("/storage/emulated/0/Manga"))
        assertEquals("Document provider", SmartLocalLocationFormatter.format("content://provider/tree/"))
    }

    private fun source(path: String) = sequenceOf(
        File("src/main/kotlin/org/koitharu/kotatsu", path),
        File("app/src/main/kotlin/org/koitharu/kotatsu", path),
    ).first { it.isFile }.readText()

    @Test fun localBadgeAndGalleryContrastStayScoped() {
        val grid = source("list/ui/adapter/MangaGridItemAD.kt")
        val overlay = source("list/ui/FavouriteCoverOverlayView.kt")
        val gallery = source("details/ui/LocalChapterGallery.kt")
        val info = source("local/ui/info/LocalInfoViewModel.kt")
        assertTrue(grid.contains("isLocalSource = true, languageLabel = null"))
        assertTrue(overlay.contains("value.isLocalSource && !showTitle"))
        assertTrue(gallery.contains("color = androidx.compose.ui.graphics.Color.White,"))
        assertTrue(gallery.contains("aspectRatio(2f / 3f)"))
        assertTrue(gallery.contains("GridCells.Fixed(2)"))
        assertTrue(info.contains("SmartLocalLocationFormatter.format(book.node.uri)"))
        assertFalse(info.contains("path.value = book.node.uri"))
    }
}
