package org.koitharu.kotatsu.local.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.local.library.LocalTreeScanner.Node

class LocalContentTypeTest {

    @Test
    fun epubOnlyBookIsNovelAndUsesEpubRoute() {
        val book = book("Novel", listOf("Volume 1.EPUB", "Volume 2.epub"))

        assertEquals(LocalContentType.NOVEL, book.contentType)
        assertTrue(book.isNovel)
        assertTrue(book.toManga(showExtensions = false).url.endsWith("/book.epub"))
    }

    @Test
    fun comicBookIsMangaAndUsesComicRoute() {
        val book = book("Comic", listOf("Chapter 1.cbz", "Chapter 2.zip"))

        assertEquals(LocalContentType.MANGA, book.contentType)
        assertFalse(book.isNovel)
        assertTrue(book.toManga(showExtensions = false).url.endsWith("/book"))
    }

    @Test
    fun emptyOrMixedDefensiveStateNeverClaimsNovelCapability() {
        assertEquals(LocalContentType.MANGA, book("Empty", emptyList()).contentType)
        assertEquals(LocalContentType.MANGA, book("Mixed", listOf("1.epub", "2.cbz")).contentType)
    }

    private fun book(name: String, chapterNames: List<String>): LocalBook {
        val root = node("root", "file:///root", "root", directory = true)
        val book = node("book-$name", "file:///root/$name", name, directory = true)
        val chapters = chapterNames.mapIndexed { index, chapterName ->
            LocalChapter(node("chapter-$index", "file:///root/$name/$chapterName", chapterName, directory = false), emptyList())
        }
        return LocalBook(
            rootUri = root.uri,
            node = book,
            chapters = chapters,
            sidecars = emptyList(),
            title = name,
            authors = emptySet(),
            description = null,
            cover = null,
            ignored = 0,
            addedAt = 0L,
            scannedAt = 0L,
            newChapters = 0,
        )
    }

    private fun node(key: String, uri: String, name: String, directory: Boolean) =
        Node(key, uri, name, directory, 0L, 0L)
}
