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
import org.koitharu.kotatsu.scrobbling.discord.ui.DiscordSignInAttempts
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

	@Test
	fun sameCandidateCanRetryAfterTransientFailureWithoutDuplicateInFlightChecks() {
		var time = 0L
		val attempts = DiscordSignInAttempts { time }
		val first = checkNotNull(attempts.begin("fixture-token"))
		assertEquals(null, attempts.begin("fixture-token"))
		assertTrue(attempts.complete(first, TokenState.UNAVAILABLE))
		time = 4_999L
		assertEquals(null, attempts.begin("fixture-token"))
		time = 5_000L
		val retry = checkNotNull(attempts.begin("fixture-token"))
		assertTrue(!attempts.complete(first, TokenState.INVALID))
		assertTrue(attempts.isCurrent(retry))
		assertTrue(attempts.complete(retry, TokenState.INVALID))
		time = 100_000L
		assertEquals(null, attempts.begin("fixture-token"))
		assertTrue(attempts.begin("different-fixture") != null)
	}

	@Test
	fun rateLimitBlocksCallbacksUntilLocalCooldownAndAllowsSameCandidateAgain() {
		var time = 0L
		val attempts = DiscordSignInAttempts { time }
		val first = checkNotNull(attempts.begin("fixture-token"))
		assertTrue(attempts.complete(first, TokenState.RATE_LIMITED))
		time = 59_999L
		assertEquals(null, attempts.begin("fixture-token"))
		assertEquals(null, attempts.begin("different-fixture"))
		time = 60_000L
		assertTrue(attempts.begin("fixture-token") != null)
	}

	private fun classify(status: Int) = TokenState.fromFailure(
		HttpStatusException("synthetic response", status, "https://example.invalid"),
	)
}
