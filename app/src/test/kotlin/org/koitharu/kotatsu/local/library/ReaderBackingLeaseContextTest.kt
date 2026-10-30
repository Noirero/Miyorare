package org.koitharu.kotatsu.local.library

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ReaderBackingLeaseContextTest {

    @Test
    fun `owner follows IO dispatcher and does not escape its load`() = runBlocking {
        val owner = Any()
        assertNull(currentCoroutineContext()[ReaderBackingLeaseContext])
        withContext(ReaderBackingLeaseContext(owner)) {
            withContext(Dispatchers.IO) {
                assertSame(owner, currentCoroutineContext()[ReaderBackingLeaseContext]?.token)
            }
        }
        assertNull(currentCoroutineContext()[ReaderBackingLeaseContext])
    }

    @Test
    fun `separate attempts never share owner identity`() {
        val first = ReaderBackingLeaseContext(Any())
        val second = ReaderBackingLeaseContext(Any())
        org.junit.Assert.assertNotSame(first.token, second.token)
    }
}
