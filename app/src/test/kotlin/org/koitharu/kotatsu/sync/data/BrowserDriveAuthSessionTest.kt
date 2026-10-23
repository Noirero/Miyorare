package org.koitharu.kotatsu.sync.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserDriveAuthSessionTest {

	@Test
	fun validTokenIsReturnedBeforeExpiry() {
		val session = BrowserDriveAuthSession()
		session.install("token", expiresAt = 10_000L, account = null, now = 1_000L)
		assertEquals("token", session.requireAccessToken(now = 2_000L))
		assertTrue(session.snapshot(now = 2_000L).isUsable)
	}

	@Test
	fun expiredTokenIsErasedAndUnavailable() {
		val session = BrowserDriveAuthSession()
		session.install("token", expiresAt = 2_000L, account = null, now = 1_000L)
		assertThrows(BrowserAuthException::class.java) {
			session.requireAccessToken(now = 2_001L)
		}
		val snapshot = session.snapshot(now = 2_001L)
		assertFalse(snapshot.isUsable)
		assertTrue(snapshot.expired)
		assertNull(snapshot.expiresAt)
	}

	@Test
	fun minimumRemainingLifetimeRejectsDoomedSync() {
		val session = BrowserDriveAuthSession()
		session.install("token", expiresAt = 121_000L, account = null, now = 1_000L)
		assertThrows(BrowserAuthException::class.java) {
			session.requireAccessToken(minimumRemainingMs = 120_000L, now = 1_000L)
		}
	}

	@Test
	fun logoutClearsCredentialAndPendingTransaction() {
		val session = BrowserDriveAuthSession()
		val launch = session.begin(now = 1_000L)
		session.install("token", expiresAt = 10_000L, account = null, now = 1_000L)
		session.clear()
		assertFalse(session.snapshot(now = 2_000L).isUsable)
		assertThrows(BrowserAuthException::class.java) {
			session.consumeCallback(launch.transactionId, "one-time", now = 2_000L)
		}
	}

	@Test
	fun startingSecondLoginReplacesStalePendingTransaction() {
		val session = BrowserDriveAuthSession()
		val first = session.begin(now = 1_000L)
		val second = session.begin(now = 2_000L)
		assertFalse(first.transactionId == second.transactionId)
		assertThrows(BrowserAuthException::class.java) {
			session.consumeCallback(first.transactionId, "stale-code", now = 2_000L)
		}
		val proof = session.consumeCallback(second.transactionId, "fresh-code", now = 2_000L)
		assertEquals(second.transactionId, proof.transactionId)
		assertEquals("fresh-code", proof.code)
	}

	@Test
	fun callbackConsumesLocalTransactionExactlyOnce() {
		val session = BrowserDriveAuthSession()
		val launch = session.begin(now = 1_000L)
		val proof = session.consumeCallback(launch.transactionId, "one-time", now = 2_000L)
		assertEquals("one-time", proof.code)
		assertTrue(proof.codeVerifier.isNotBlank())
		assertThrows(BrowserAuthException::class.java) {
			session.consumeCallback(launch.transactionId, "one-time", now = 2_000L)
		}
	}

	@Test
	fun mismatchedProviderErrorDoesNotCancelRealPendingLogin() {
		val session = BrowserDriveAuthSession()
		val launch = session.begin(now = 1_000L)

		assertThrows(BrowserAuthException::class.java) {
			session.consumeErrorCallback("other-transaction", now = 2_000L)
		}

		val proof = session.consumeCallback(launch.transactionId, "valid-code", now = 2_000L)
		assertEquals(launch.transactionId, proof.transactionId)
	}

	@Test
	fun matchingProviderErrorConsumesPendingLogin() {
		val session = BrowserDriveAuthSession()
		val launch = session.begin(now = 1_000L)
		session.consumeErrorCallback(launch.transactionId, now = 2_000L)

		assertThrows(BrowserAuthException::class.java) {
			session.consumeCallback(launch.transactionId, "late-code", now = 2_000L)
		}
	}
}
