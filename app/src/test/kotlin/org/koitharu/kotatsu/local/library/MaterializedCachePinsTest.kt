package org.koitharu.kotatsu.local.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MaterializedCachePinsTest {

    @Test
    fun `temporary cover release removes its only pin`() {
        val pins = MaterializedCachePins()

        pins.acquire("book.pdf")
        assertTrue(pins.isPinned("book.pdf"))

        pins.release("book.pdf")
        assertFalse(pins.isPinned("book.pdf"))
    }

    @Test
    fun `cover release preserves reader pin on same materialized file`() {
        val pins = MaterializedCachePins()

        pins.acquire("book.pdf") // Reader
        pins.acquire("book.pdf") // Cover extraction
        pins.release("book.pdf") // Cover extraction completes

        assertTrue(pins.isPinned("book.pdf"))
    }

    @Test
    fun `all references must release before cache file becomes evictable`() {
        val pins = MaterializedCachePins()

        pins.acquire("book.pdf")
        pins.acquire("book.pdf")
        pins.release("book.pdf")
        assertTrue(pins.isPinned("book.pdf"))

        pins.release("book.pdf")
        assertFalse(pins.isPinned("book.pdf"))
    }
}
