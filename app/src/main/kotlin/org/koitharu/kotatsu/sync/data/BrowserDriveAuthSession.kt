package org.koitharu.kotatsu.sync.data

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

internal enum class DriveAuthMode {
	GMS,
	BROWSER_TEMPORARY,
}

internal enum class BrowserAuthOrigin {
	SYNC_SETTINGS,
	ONBOARDING,
}

internal data class DriveAccountInfo(
	val email: String?,
	val displayName: String?,
	val photoUrl: String?,
)

internal data class BrowserSessionSnapshot(
	val isUsable: Boolean,
	val expiresAt: Long?,
	val account: DriveAccountInfo?,
	val expired: Boolean,
)

internal data class BrowserAuthLaunch(
	val transactionId: String,
	val codeChallenge: String,
)

internal data class BrowserAuthCallbackProof(
	val transactionId: String,
	val code: String,
	val codeVerifier: String,
	val origin: BrowserAuthOrigin,
)

/**
 * Process-local credential store for browser Drive authorization.
 *
 * The class is public only because Hilt injects it into public Android components. Its credential
 * API remains module-internal so access tokens cannot accidentally become an application-wide API.
 */
@Singleton
class BrowserDriveAuthSession @Inject constructor() {

	private class PendingTransaction(
		val transactionId: String,
		val codeVerifier: String,
		val origin: BrowserAuthOrigin,
		val expiresAt: Long,
	)

	private class Credential(
		val accessToken: String,
		val expiresAt: Long,
		val account: DriveAccountInfo?,
	)

	private val lock = Any()
	private val random = SecureRandom()
	private var pending: PendingTransaction? = null
	private var credential: Credential? = null
	private var expiredSinceLastLogin = false

	internal fun begin(origin: BrowserAuthOrigin, now: Long = System.currentTimeMillis()): BrowserAuthLaunch = synchronized(lock) {
		val transactionId = randomUrlSafe(32)
		val verifier = randomUrlSafe(48)
		pending = PendingTransaction(
			transactionId = transactionId,
			codeVerifier = verifier,
			origin = origin,
			expiresAt = now + TRANSACTION_TTL_MS,
		)
		BrowserAuthLaunch(
			transactionId = transactionId,
			codeChallenge = sha256UrlSafe(verifier),
		)
	}

	/** Consumes the pending app transaction exactly once before any exchange request occurs. */
	internal fun consumeCallback(
		transactionId: String,
		code: String,
		now: Long = System.currentTimeMillis(),
	): BrowserAuthCallbackProof = synchronized(lock) {
		val current = pending ?: throw BrowserAuthException("No browser sign-in is pending")
		if (current.expiresAt <= now) {
			pending = null
			throw BrowserAuthException("Browser sign-in session expired")
		}
		if (transactionId != current.transactionId) {
			throw BrowserAuthException("Browser sign-in transaction mismatch")
		}
		if (code.isBlank()) {
			throw BrowserAuthException("Browser sign-in response is missing its one-time code")
		}
		pending = null
		BrowserAuthCallbackProof(
			transactionId = transactionId,
			code = code,
			codeVerifier = current.codeVerifier,
			origin = current.origin,
		)
	}

	internal fun cancelPending() = synchronized(lock) {
		pending = null
	}

	internal fun install(
		accessToken: String,
		expiresAt: Long,
		account: DriveAccountInfo?,
		now: Long = System.currentTimeMillis(),
	) = synchronized(lock) {
		if (accessToken.isBlank() || expiresAt <= now) {
			credential = null
			expiredSinceLastLogin = true
			throw BrowserAuthException("Browser sign-in returned an unusable access token")
		}
		credential = Credential(accessToken, expiresAt, account)
		expiredSinceLastLogin = false
	}

	internal fun snapshot(now: Long = System.currentTimeMillis()): BrowserSessionSnapshot = synchronized(lock) {
		pruneExpiredLocked(now)
		val current = credential
		BrowserSessionSnapshot(
			isUsable = current != null,
			expiresAt = current?.expiresAt,
			account = current?.account,
			expired = expiredSinceLastLogin,
		)
	}

	internal fun requireAccessToken(
		minimumRemainingMs: Long = 0L,
		now: Long = System.currentTimeMillis(),
	): String = synchronized(lock) {
		pruneExpiredLocked(now)
		val current = credential ?: throw BrowserAuthException("Temporary browser session expired")
		if (current.expiresAt - now <= minimumRemainingMs) {
			credential = null
			expiredSinceLastLogin = true
			throw BrowserAuthException("Temporary browser session is too close to expiry")
		}
		current.accessToken
	}

	internal fun invalidate(accessToken: String) = synchronized(lock) {
		if (credential?.accessToken == accessToken) {
			credential = null
			expiredSinceLastLogin = true
		}
	}

	internal fun clear() = synchronized(lock) {
		pending = null
		credential = null
		expiredSinceLastLogin = false
	}

	private fun pruneExpiredLocked(now: Long) {
		if (credential?.expiresAt?.let { it <= now } == true) {
			credential = null
			expiredSinceLastLogin = true
		}
		if (pending?.expiresAt?.let { it <= now } == true) {
			pending = null
		}
	}

	private fun randomUrlSafe(bytes: Int): String {
		val value = ByteArray(bytes)
		random.nextBytes(value)
		return Base64.getUrlEncoder().withoutPadding().encodeToString(value)
	}

	private fun sha256UrlSafe(value: String): String {
		val digest = MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray())
		return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
	}

	private companion object {
		const val TRANSACTION_TTL_MS = 10L * 60L * 1000L
	}
}

internal class BrowserAuthException(message: String, cause: Throwable? = null) : Exception(message, cause)
