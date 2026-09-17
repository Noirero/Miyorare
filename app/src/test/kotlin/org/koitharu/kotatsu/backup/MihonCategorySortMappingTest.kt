package org.koitharu.kotatsu.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.list.domain.ListSortOrder

class MihonCategorySortMappingTest {

    @Test
    fun supportedMihonSortsMapInBothDirections() {
        assertEquals(ListSortOrder.ALPHABETIC_REVERSE, decodeMihonCategorySortOrder(0b00000000L))
        assertEquals(ListSortOrder.ALPHABETIC, decodeMihonCategorySortOrder(0b01000000L))
        assertEquals(ListSortOrder.LAST_READ, decodeMihonCategorySortOrder(0b00000100L))
        assertEquals(ListSortOrder.LONG_AGO_READ, decodeMihonCategorySortOrder(0b01000100L))
        assertEquals(ListSortOrder.UNREAD_COUNT, decodeMihonCategorySortOrder(0b00001100L))
        assertEquals(ListSortOrder.UNREAD_COUNT_ASC, decodeMihonCategorySortOrder(0b01001100L))
        assertEquals(ListSortOrder.TOTAL_CHAPTERS, decodeMihonCategorySortOrder(0b00010000L))
        assertEquals(ListSortOrder.TOTAL_CHAPTERS_ASC, decodeMihonCategorySortOrder(0b01010000L))
        assertEquals(ListSortOrder.LATEST_CHAPTER, decodeMihonCategorySortOrder(0b00010100L))
        assertEquals(ListSortOrder.LATEST_CHAPTER_ASC, decodeMihonCategorySortOrder(0b01010100L))
        assertEquals(ListSortOrder.NEWEST, decodeMihonCategorySortOrder(0b00011100L))
        assertEquals(ListSortOrder.OLDEST, decodeMihonCategorySortOrder(0b01011100L))
    }

    @Test
    fun supportedMiyorareSortsRoundTripThroughMihonFlags() {
        val supported = listOf(
            ListSortOrder.ALPHABETIC_REVERSE,
            ListSortOrder.ALPHABETIC,
            ListSortOrder.LAST_READ,
            ListSortOrder.LONG_AGO_READ,
            ListSortOrder.UNREAD_COUNT,
            ListSortOrder.UNREAD_COUNT_ASC,
            ListSortOrder.TOTAL_CHAPTERS,
            ListSortOrder.TOTAL_CHAPTERS_ASC,
            ListSortOrder.LATEST_CHAPTER,
            ListSortOrder.LATEST_CHAPTER_ASC,
            ListSortOrder.NEWEST,
            ListSortOrder.OLDEST,
        )
        supported.forEach { order ->
            assertEquals(
                "Sort must survive Miyorare -> Mihon backup -> Miyorare restore: $order",
                order,
                decodeMihonCategorySortOrder(encodeMihonCategorySortOrder(order.name)),
            )
        }
    }

    @Test
    fun restoreTargetsMapToSeparateFavouriteSpaces() {
        assertEquals(FavouriteSpace.NORMAL, favouriteSpaceForMihonRestoreTarget(MihonRestoreTarget.NORMAL))
        assertEquals(FavouriteSpace.PRIVATE, favouriteSpaceForMihonRestoreTarget(MihonRestoreTarget.PRIVATE))
    }

    @Test
    fun unsupportedCurrentMihonSortsDoNotPretendToBeNewest() {
        assertNull(decodeMihonCategorySortOrder(0b00001000L)) // Last Update
        assertNull(decodeMihonCategorySortOrder(0b00011000L)) // Chapter Fetch Date
        assertNull(decodeMihonCategorySortOrder(0b00100000L)) // Tracker Mean
        assertNull(decodeMihonCategorySortOrder(0b00111100L)) // Random
    }
}
