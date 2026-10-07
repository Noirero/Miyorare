package org.koitharu.kotatsu.local.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalDocumentsTraversalTest {
    @Test
    fun `traversal proof admits only discovered nodes`() {
        val root = "provider:root"
        val traversed = hashSetOf(root)
        val child = "provider:root/child"
        val unrelated = "provider:other"

        traversed += child

        assertTrue(root in traversed)
        assertTrue(child in traversed)
        assertFalse(unrelated in traversed)
    }
}