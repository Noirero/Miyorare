package org.koitharu.kotatsu.settings.discord

import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.jsoup.HttpStatusException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.scrobbling.discord.ui.completeDiscordSignIn
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

	@Test
	fun signInCommitsOnlyAfterVerification() = runBlocking {
		val events = mutableListOf<String>()
		val result = completeDiscordSignIn(
			"fixture-token",
			checkToken = { events.add("verified") },
			onVerified = { events.add("committed") },
		)

		assertEquals(TokenState.VALID, result)
		assertEquals(listOf("verified", "committed"), events)
	}

	@Test
	fun signInRejectionAndRetryableFailuresNeverCommit() = runBlocking {
		val saved = mutableListOf<String>()
		for ((status, state) in listOf(401 to TokenState.INVALID, 429 to TokenState.RATE_LIMITED, 500 to TokenState.UNAVAILABLE)) {
			val result = completeDiscordSignIn(
				"fixture-token",
				checkToken = { throw HttpStatusException("synthetic response", status, "https://example.invalid") },
				onVerified = { saved.add(it) },
			)
			assertEquals(state, result)
		}
		assertTrue(saved.isEmpty())
	}

	@Test
	fun cancelledSignInNeverCommits() = runBlocking {
		val saved = mutableListOf<String>()
		val job = launch {
			completeDiscordSignIn(
				"fixture-token",
				checkToken = { currentCoroutineContext().cancel() },
				onVerified = { saved.add(it) },
			)
		}
		job.join()

		assertTrue(job.isCancelled)
		assertTrue(saved.isEmpty())
	}

	private fun classify(status: Int) = TokenState.fromFailure(
		HttpStatusException("synthetic response", status, "https://example.invalid"),
	)
}
