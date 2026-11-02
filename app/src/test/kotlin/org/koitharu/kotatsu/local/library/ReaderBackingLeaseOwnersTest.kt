package org.koitharu.kotatsu.local.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderBackingLeaseOwnersTest {

    @Test
    fun `repeated enumeration by one owner acquires only one durable pin`() {
        val owners = ReaderBackingLeaseOwners()
        val token = Any()
        assertTrue(owners.retain(token, "archive.cbz"))
        assertFalse(owners.retain(token, "archive.cbz"))
        assertEquals(setOf("archive.cbz"), owners.release(token))
        assertTrue(owners.release(token).isEmpty())
    }

    @Test
    fun `two readers of one PDF have independent ownership`() {
        val owners = ReaderBackingLeaseOwners()
        val first = Any()
        val second = Any()
        assertTrue(owners.retain(first, "book.pdf"))
        assertTrue(owners.retain(second, "book.pdf"))
        assertEquals(setOf("book.pdf"), owners.release(first))
        assertEquals(setOf("book.pdf"), owners.release(second))
    }

    @Test
    fun `one owner can retain multiple archive backings`() {
        val owners = ReaderBackingLeaseOwners()
        val token = Any()
        assertTrue(owners.retain(token, "chapter1.cbz"))
        assertTrue(owners.retain(token, "chapter2.cbz"))
        assertEquals(setOf("chapter1.cbz", "chapter2.cbz"), owners.release(token))
    }
}
