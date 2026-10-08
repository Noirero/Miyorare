package org.koitharu.kotatsu.local.library

import org.junit.Assert.assertEquals
import org.junit.Test
import org.koitharu.kotatsu.history.data.HistoryEntity
import org.koitharu.kotatsu.local.library.LocalTreeScanner.Node

class LocalCollectionProjectionTest {
    @Test fun typeAndSearchShareOneLibraryAndEmptySearchRestoresIt() {
        val manga = book("Comic 2", "cbz")
        val novel = book("Novel", "epub")
        val books = listOf(manga, novel)
        assertEquals(listOf(novel), project(books, query = "  NOV  ", type = LocalContentType.NOVEL))
        assertEquals(emptyList<LocalBook>(), project(books, query = "Comic", type = LocalContentType.NOVEL))
        assertEquals(books, project(books, query = ""))
        assertEquals(listOf(manga), project(books, type = LocalContentType.MANGA))
    }

    @Test fun readingFiltersUseExistingHistoryIncludingCompletionBoundary() {
        val unread = book("Unread", "cbz")
        val reading = book("Reading", "cbz")
        val done = book("Done", "epub")
        val books = listOf(unread, reading, done)
        val histories = mapOf("Reading" to history(.4f), "Done" to history(1f))
        assertEquals(listOf(unread), project(books, histories, filter = LocalReadingFilter.UNREAD))
        assertEquals(listOf(reading), project(books, histories, filter = LocalReadingFilter.READING))
        assertEquals(listOf(done), project(books, histories, filter = LocalReadingFilter.COMPLETED))
    }

    @Test fun supportedSortsUseIndexedDataAndHistory() {
        val earlier = book("Comic 2", "cbz", added = 1, modified = 30)
        val newer = book("Comic 10", "cbz", added = 2, modified = 10)
        val books = listOf(newer, earlier)
        assertEquals(listOf(earlier, newer), project(books))
        assertEquals(listOf(newer, earlier), project(books, sort = LocalLibrarySort.TITLE_DESC))
        assertEquals(listOf(newer, earlier), project(books, sort = LocalLibrarySort.ADDED))
        assertEquals(listOf(earlier, newer), project(books, sort = LocalLibrarySort.CHAPTER_UPDATED))
        assertEquals(listOf(earlier, newer), project(books, mapOf("Comic 2" to history(.2f, 99)), sort = LocalLibrarySort.LAST_READ))
    }

    @Test fun discoveriesAccumulateUntilAcknowledgedAndNoChangeDoesNotInventNewContent() {
        assertEquals(3, pendingLocalDiscoveries(2, 1, 10))
        assertEquals(2, pendingLocalDiscoveries(2, 0, 10))
        assertEquals(0, pendingLocalDiscoveries(0, 0, 10))
        assertEquals(1, pendingLocalDiscoveries(2, 0, 1))
        assertEquals(0, pendingLocalDiscoveries(-1, -1, 0))
    }

    private fun project(books: List<LocalBook>, history: Map<String, HistoryEntity> = emptyMap(), query: String? = null,
        type: LocalContentType? = null, filter: LocalReadingFilter = LocalReadingFilter.ALL,
        sort: LocalLibrarySort = LocalLibrarySort.TITLE_ASC) = projectLocalCollection(books, history, query, type, filter, sort, false)

    private fun history(percent: Float, updated: Long = 1) = HistoryEntity(1, 1, updated, 1, 0, 0f, percent, 0, 1)
    private fun book(title: String, extension: String, added: Long = 0, modified: Long = 0): LocalBook {
        val chapter = Node("$title/ch", "content://test/$title/ch", "1.$extension", false, 10, modified)
        return LocalBook("content://test", Node(title, "content://test/$title", title, true, 0, 0),
            listOf(LocalChapter(chapter, emptyList())), emptyList(), title, emptySet(), null, null, 0, added, 0, 0)
    }
}
