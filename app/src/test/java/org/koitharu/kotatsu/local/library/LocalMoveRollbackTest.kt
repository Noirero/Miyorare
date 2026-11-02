package org.koitharu.kotatsu.local.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class LocalMoveRollbackTest {
    @Test fun cleanupReversesCreationOrder() {
        val removed = mutableListOf<String>()
        val failures = LocalMoveRollback.cleanup(listOf("folder", "page1", "page2")) {
            removed += it
        }
        assertEquals(listOf("page2", "page1", "folder"), removed)
        assertTrue(failures.isEmpty())
    }

    @Test fun safDeleteFailureDoesNotPreventOtherCleanup() {
        val removed = mutableListOf<String>()
        val failures = LocalMoveRollback.cleanup(listOf("folder", "page1", "page2")) {
            removed += it
            if (it == "page2") throw IOException("SAF provider refused delete")
        }
        assertEquals(listOf("page2", "page1", "folder"), removed)
        assertEquals(1, failures.size)
        assertTrue(failures.single() is IOException)
    }
    @Test fun cancellationFailureDoesNotPreventRemainingRollback() {
        val removed = mutableListOf<String>()
        val failures = LocalMoveRollback.cleanup(listOf("folder", "page1", "page2")) {
            removed += it
            if (it == "page2") throw java.util.concurrent.CancellationException("cancelled during cleanup")
        }
        assertEquals(listOf("page2", "page1", "folder"), removed)
        assertEquals(1, failures.size)
        assertTrue(failures.single() is java.util.concurrent.CancellationException)
    }

}
