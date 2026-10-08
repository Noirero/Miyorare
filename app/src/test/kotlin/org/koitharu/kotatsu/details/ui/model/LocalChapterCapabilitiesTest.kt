package org.koitharu.kotatsu.details.ui.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.core.model.LocalMangaSource
import org.koitharu.kotatsu.core.model.UnknownMangaSource
import org.koitharu.kotatsu.parsers.model.MangaChapter

class LocalChapterCapabilitiesTest {
    @Test fun indexedLocalChapterCannotBeDownloaded() {
        assertFalse(ChapterListItem(chapter("smart-local://chapter/1/test.cbz"), 0).canDownload)
        assertFalse(ChapterListItem(chapter("smart-local://chapter/1/book.epub"), 0).canDownload)
    }
    @Test fun legacyLocalRetainsLocalCapabilityAndRemoteCanDownload() {
        assertFalse(ChapterListItem(chapter("file:///legacy/chapter.cbz"), 0).canDownload)
        assertTrue(ChapterListItem(chapter("https://example.org/chapter").copy(source = UnknownMangaSource), 0).canDownload)
    }
    private fun chapter(url: String) = MangaChapter(
        id = 1L, title = "Chapter", number = 1f, volume = 0, url = url,
        scanlator = null, uploadDate = 0L, branch = null, source = LocalMangaSource,
    )
}
