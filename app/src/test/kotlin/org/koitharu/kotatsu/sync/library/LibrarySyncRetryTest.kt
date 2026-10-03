package org.koitharu.kotatsu.sync.library

import java.io.IOException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.*
import org.junit.Test

class LibrarySyncRetryTest {
	@Test
	fun `transient HTTP failures retry`() {
		listOf(408, 429, 500, 502, 503, 504).forEach {
			assertTrue(isLibrarySyncRetryable(LibrarySyncHttpException(it)))
		}
	}

	@Test
	fun `permanent failures and bad credentials do not retry`() {
		listOf(400, 401, 403, 404, 422).forEach {
			assertFalse(isLibrarySyncRetryable(LibrarySyncHttpException(it)))
		}
		assertFalse(isLibrarySyncRetryable(LibrarySyncAuthException()))
		assertFalse(isLibrarySyncRetryable(IllegalArgumentException()))
		assertFalse(isLibrarySyncRetryable(SSLHandshakeException("untrusted")))
	}

	@Test
	fun `network failure retries`() {
		assertTrue(isLibrarySyncRetryable(IOException("offline")))
	}

	@Test
	fun `retry after seconds and HTTP date survive restart`() {
		assertEquals(31000, retryAfterAt("30", 1000))
		assertEquals(1445412480000, retryAfterAt("Wed, 21 Oct 2015 07:28:00 GMT", 1000))
	}

	@Test
	fun `invalid retry after is conservative`() {
		assertEquals(61000, retryAfterAt(null, 1000))
		assertEquals(61000, retryAfterAt("bad", 1000))
		assertEquals(1000, retryAfterAt("-2", 1000))
	}
}
