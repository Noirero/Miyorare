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
		session.begin(BrowserAuthOrigin.SYNC_SETTINGS, now = 1_000L)
		session.install("token", expiresAt = 10_000L, account = null, now = 1_000L)
		session.clear()
		assertFalse(session.snapshot(now = 2_000L).isUsable)
		assertThrows(BrowserAuthException::class.java) { session.pendingProof(now = 2_000L) }
	}

	@Test
	fun startingSecondLoginReplacesStalePendingTransaction() {
		val session = BrowserDriveAuthSession()
		val first = session.begin(BrowserAuthOrigin.SYNC_SETTINGS, now = 1_000L)
		val second = session.begin(BrowserAuthOrigin.SYNC_SETTINGS, now = 2_000L)
		assertFalse(first.transactionId == second.transactionId)
		assertEquals(second.transactionId, session.pendingProof(now = 2_000L).transactionId)
		assertThrows(BrowserAuthException::class.java) {
			session.consumeOneTimeCode(first.transactionId, "code", now = 2_000L)
		}
	}

	@Test
	fun oneTimeCodeConsumesLocalTransaction() {
		val session = BrowserDriveAuthSession()
		val launch = session.begin(BrowserAuthOrigin.SYNC_SETTINGS, now = 1_000L)
		val proof = session.consumeOneTimeCode(launch.transactionId, "one-time", now = 2_000L)
		assertEquals("one-time", proof.code)
		assertThrows(BrowserAuthException::class.java) {
			session.consumeOneTimeCode(launch.transactionId, "one-time", now = 2_000L)
		}
	}
}
