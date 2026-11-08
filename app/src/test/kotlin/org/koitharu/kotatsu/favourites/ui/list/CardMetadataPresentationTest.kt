package org.koitharu.kotatsu.favourites.ui.list

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.core.prefs.ProgressIndicatorMode
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.favourites.domain.FavouriteContentType

class CardMetadataPresentationTest {
    private fun scope(
        space: FavouriteSpace = FavouriteSpace.NORMAL,
        categoryId: Long = 10L,
        selectedCategoryIds: List<Long>? = null,
        type: FavouriteContentType = FavouriteContentType.MANGA,
        mode: ProgressIndicatorMode = ProgressIndicatorMode.PERCENT_READ,
        unread: Boolean = true,
        downloaded: Boolean = true,
        scan: Boolean = false,
    ) = CardMetadataContext(space, categoryId, selectedCategoryIds, type, mode, unread, downloaded, scan)

    @Test fun `warm resume retains known items while a newer revision is pending`() {
        assertEquals(
            setOf(1L, 2L),
            reusableCardMetadataIds(scope(), scope(), listOf(1L, 2L), listOf(1L, 2L), emptySet(), emptySet()),
        )
        assertFalse(canPublishCardEnrichment(1, 2, true, true))
        assertTrue(canPublishCardEnrichment(2, 2, true, true))
    }

    @Test fun `cold start has no known metadata`() {
        assertEquals(emptySet<Long>(), reusableCardMetadataIds(null, scope(), emptyList(), listOf(1L), emptySet(), emptySet()))
    }

    @Test fun `pagination and reordered visible IDs reuse only previously queried IDs`() {
        assertEquals(
            setOf(3L, 1L),
            reusableCardMetadataIds(scope(), scope(), listOf(1L, 2L, 3L), listOf(3L, 4L, 1L), emptySet(), emptySet()),
        )
    }

    @Test fun `category type space selection progress mode and flags remain isolated`() {
        val old = scope()
        listOf(
            scope(space = FavouriteSpace.PRIVATE),
            scope(categoryId = 11L),
            scope(selectedCategoryIds = listOf(15L)),
            scope(type = FavouriteContentType.NOVEL),
            scope(mode = ProgressIndicatorMode.CHAPTERS_READ),
            scope(unread = false),
            scope(downloaded = false),
            scope(scan = true),
        ).forEach { next ->
            assertEquals(emptySet<Long>(), reusableCardMetadataIds(old, next, listOf(5L), listOf(5L), emptySet(), emptySet()))
        }
    }

    @Test fun `novel classification must remain stable per manga ID`() {
        assertEquals(
            setOf(2L),
            reusableCardMetadataIds(scope(), scope(), listOf(1L, 2L), listOf(1L, 2L), setOf(1L), emptySet()),
        )
    }

    @Test fun `cancelled same-key request cannot clear a newer request after A B A`() {
        val first = CardEnrichmentRequest("A")
        var pending: CardEnrichmentRequest<String>? = first
        pending = CardEnrichmentRequest("B")
        pending = CardEnrichmentRequest("A")
        val newest = pending
        assertEquals(first.key, newest?.key)
        // This is the production cleanup predicate. Comparing keys instead would clear newest.
        if (pending === first) pending = null
        assertTrue(pending === newest)
        assertTrue(canPublishCardEnrichment(10, 10, pending === newest, true))
        if (pending === newest) pending = null
        assertEquals(null, pending)
    }

    @Test fun `cancelled and superseded enrichment cannot publish after rapid resume`() {
        assertFalse(canPublishCardEnrichment(8, 8, false, true))
        assertFalse(canPublishCardEnrichment(8, 8, true, false))
        assertFalse(canPublishCardEnrichment(8, 9, true, true))
        assertTrue(canPublishCardEnrichment(9, 9, true, true))
    }
}
