package org.koitharu.kotatsu.local.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalGroupVisibilityTest {
    private val groups = mapOf("Reading" to setOf("stable-a", "stable-b"),
        "Empty" to emptySet())

    @Test fun selectedGroupOnlyShowsMembers() {
        assertTrue(LocalGroupVisibility.visible("Reading", groups, "stable-a"))
        assertFalse(LocalGroupVisibility.visible("Reading", groups, "other"))
    }

    @Test fun deletedOrRenamedGroupFailsClosed() {
        assertFalse(LocalGroupVisibility.visible("Missing", groups, "stable-a"))
        assertFalse(LocalGroupVisibility.visible("Empty", groups, "stable-a"))
    }

    @Test fun noGroupFilterPreservesAllTitles() {
        assertTrue(LocalGroupVisibility.visible(null, groups, "other"))
    }

    @Test fun cardCountOnlyIncludesIndexedMembers() {
        assertEquals(1, LocalGroupVisibility.count(groups, "Reading", setOf("stable-b")))
        assertEquals(0, LocalGroupVisibility.count(groups, "Missing", setOf("stable-a")))
    }

    @Test fun groupMembershipSurvivesEquivalentKeyReconstruction() {
        val reindexedKey = String(charArrayOf('s','t','a','b','l','e','-','a'))
        assertTrue(LocalGroupVisibility.visible("Reading", groups, reindexedKey))
    }
}
