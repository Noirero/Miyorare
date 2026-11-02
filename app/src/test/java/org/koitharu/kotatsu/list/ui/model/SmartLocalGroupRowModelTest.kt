package org.koitharu.kotatsu.list.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartLocalGroupRowModelTest {

    private val groups = (1..5).map { SmartLocalGroupRowModel.Group("Group $it", it) }

    @Test fun collapsedRowsShowAtMostFourGroupsInTwoColumns() {
        val rows = SmartLocalGroupRowModel.rows(groups, expanded = false)
        assertEquals(2, rows.size)
        assertEquals(listOf("Group 1", "Group 3"), rows.map { it.first.name })
        assertEquals(listOf("Group 2", "Group 4"), rows.map { it.second?.name })
    }

    @Test fun expandedRowsIncludeAllGroupsAndEmptyLastSlot() {
        val rows = SmartLocalGroupRowModel.rows(groups, expanded = true)
        assertEquals(3, rows.size)
        assertEquals("Group 5", rows.last().first.name)
        assertNull(rows.last().second)
    }

    @Test fun emptyAndSingleGroupRowsAreSafe() {
        assertTrue(SmartLocalGroupRowModel.rows(emptyList(), expanded = true).isEmpty())
        val rows = SmartLocalGroupRowModel.rows(groups.take(1), expanded = false)
        assertEquals(1, rows.size)
        assertNull(rows.single().second)
    }

    @Test fun countUpdatesRetainIdentityWhileRenamesChangeIt() {
        val first = SmartLocalGroupRowModel(groups[0], groups[1])
        val changedCount = SmartLocalGroupRowModel(groups[0].copy(count = 100), groups[1])
        val renamed = SmartLocalGroupRowModel(groups[0].copy(name = "Renamed"), groups[1])
        assertTrue(first.areItemsTheSame(changedCount))
        assertFalse(first == changedCount)
        assertFalse(first.areItemsTheSame(renamed))
    }
}
