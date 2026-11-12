package org.koitharu.kotatsu.favourites.groups.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryGroupCollageSlotsTest {
    @Test fun repeatsThreeMembersInTheFourthSlotAsGridDoes() {
        assertEquals(listOf("A", "B", "C", "A"), collageCoverSlots(listOf("A", "B", "C")))
    }

    @Test fun twoMembersAlternateAcrossAllFourSlots() {
        assertEquals(listOf(1, 2, 1, 2), collageCoverSlots(listOf(1, 2)))
    }

    @Test fun fourAndMoreMembersUseOnlyFirstFour() {
        assertEquals(listOf(1, 2, 3, 4), collageCoverSlots(listOf(1, 2, 3, 4, 5)))
    }

    @Test fun emptyMembersHaveNoSlots() {
        assertEquals(emptyList<Int>(), collageCoverSlots(emptyList()))
    }
}
