package org.koitharu.kotatsu.explore.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import org.koitharu.kotatsu.parsers.model.SortOrder

class SourceShelfOrdersTest {
    @Test fun usesOnlyAdvertisedShelfOrders() {
        assertEquals(listOf(SortOrder.UPDATED, SortOrder.POPULARITY),
            sourceShelfOrders(setOf(SortOrder.POPULARITY, SortOrder.UPDATED, SortOrder.RELEVANCE)))
        assertEquals(listOf(SortOrder.POPULARITY), sourceShelfOrders(setOf(SortOrder.POPULARITY)))
        assertEquals(listOf(SortOrder.UPDATED), sourceShelfOrders(setOf(SortOrder.UPDATED)))
        assertEquals(emptyList<SortOrder>(), sourceShelfOrders(setOf(SortOrder.RELEVANCE)))
        assertEquals(emptyList<SortOrder>(), sourceShelfOrders(emptySet()))
    }
}
