package org.koitharu.kotatsu.settings.discord

import org.jsoup.HttpStatusException
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.SocketTimeoutException

class TokenStateTest {

	@Test
	fun tokenRejectionIsDistinctFromTemporaryFailures() {
		assertEquals(TokenState.INVALID, classify(401))
		assertEquals(TokenState.RATE_LIMITED, classify(429))
		for (status in listOf(403, 500, 504)) {
			assertEquals(TokenState.UNAVAILABLE, classify(status))
		}
		assertEquals(TokenState.UNAVAILABLE, TokenState.fromFailure(SocketTimeoutException()))
	}

	private fun classify(status: Int) = TokenState.fromFailure(
		HttpStatusException("synthetic response", status, "https://example.invalid"),
	)
}
