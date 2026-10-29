package org.koitharu.kotatsu.details.ui

import java.io.File
import org.junit.Test
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class LocalChapterGalleryCacheRegressionTest {
    private fun source(path: String) = sequenceOf(
        File("src/main/kotlin/org/koitharu/kotatsu", path),
        File("app/src/main/kotlin/org/koitharu/kotatsu", path),
    ).first { it.isFile }.readText()

    @Test fun galleryDoesNotUseFullReaderPageCache() {
        val activity = source("details/ui/DetailsExpressiveActivity.kt")
        val library = source("local/library/SmartLocalLibrary.kt")
        val gallery = source("details/ui/LocalChapterGallery.kt")
        assertTrue(activity.contains("smartLocalLibrary.chapterGalleryThumbnail(chapter)"))
        assertFalse(activity.contains("repo.getPages(chapter).firstOrNull()"))
        assertTrue(library.contains("smart-local-chapter-gallery"))
        assertTrue(library.contains("maxBytes = 48L * 1024 * 1024, maxEntries = 512"))
        assertTrue(library.contains("contentReader.cover(plan, publish)"))
        assertTrue(gallery.contains("withContext(Dispatchers.IO)"))
        assertFalse(gallery.contains("ImageRequest.Builder"))
    }

    @Test fun galleryCardGeometryAndReaderClickStayIdentical() {
        val gallery = source("details/ui/LocalChapterGallery.kt")
        assertTrue(gallery.contains("GridCells.Fixed(2)"))
        assertTrue(gallery.contains("aspectRatio(2f / 3f)"))
        assertTrue(gallery.contains("RoundedCornerShape(16.dp)"))
        assertTrue(gallery.contains("Arrangement.spacedBy(12.dp)"))
        assertTrue(gallery.contains("Arrangement.spacedBy(18.dp)"))
        assertTrue(gallery.contains("onChapterClick(item)"))
        assertTrue(gallery.contains("ContentScale.Crop"))
    }
}
