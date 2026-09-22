package org.koitharu.kotatsu.tsuki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.sources.compat.EhentaiSourceFamily

class ExHentaiBrowseAccessRegressionTest {

    private val sourceName = EhentaiSourceFamily.OFFICIAL_SOURCE_NAME

    @Test
    fun `idle ExHentai browse does not enter advanced dynamic search path`() {
        assertFalse(shouldTranslateExHentaiDynamicFilter(sourceName, MangaListFilter.EMPTY))
    }

    @Test
    fun `ExHentai query still enters dynamic search path`() {
        assertTrue(
            shouldTranslateExHentaiDynamicFilter(
                sourceName,
                MangaListFilter(query = "gragas743"),
            ),
        )
    }

    @Test
    fun `official ExHentai exposes only newest before parser is ready`() {
        assertEquals(setOf(SortOrder.NEWEST), fallbackTsukiSortOrders(sourceName))
        assertEquals(
            SortOrder.NEWEST,
            fallbackTsukiDefaultSortOrder(sourceName, SortOrder.POPULARITY),
        )
    }

    @Test
    fun `other Tsuki sources retain generic startup sort fallback`() {
        assertEquals(
            setOf(SortOrder.POPULARITY, SortOrder.RELEVANCE),
            fallbackTsukiSortOrders("TSUKI:CUSTOM:test:SOURCE"),
        )
        assertEquals(
            SortOrder.RELEVANCE,
            fallbackTsukiDefaultSortOrder("TSUKI:CUSTOM:test:SOURCE", SortOrder.RELEVANCE),
        )
    }
}
