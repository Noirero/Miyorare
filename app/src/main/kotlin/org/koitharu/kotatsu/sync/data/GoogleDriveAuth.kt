@file:Suppress("DEPRECATION")

package org.koitharu.kotatsu.sync.data

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.sync.domain.SyncSignInRequiredException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Drive authentication façade. Existing GMS sign-in keeps its persistent/background semantics,
 * while browser sign-in resolves a process-local temporary bearer token through the same contract.
 */
@Singleton
class GoogleDriveAuth @Inject constructor(
	@ApplicationContext private val context: Context,
	private val syncSettings: SyncSettings,
	private val browserSession: BrowserDriveAuthSession,
) {

	private val signInClient by lazy {
		val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
			.requestEmail()
			// Sync data lives exclusively in Drive's hidden appDataFolder; no ordinary Drive file access.
			.requestScopes(Scope(SCOPE_APPDATA))
			.build()
		GoogleSignIn.getClient(context, options)
	}

	/** Intent that launches the Google account chooser and Drive scope consent. */
	val signInIntent: Intent
		get() = signInClient.signInIntent

	/** Parses a GMS sign-in result and makes GMS the active auth provider. */
	fun accountFromIntent(data: Intent?): GoogleSignInAccount {
		val account = GoogleSignIn.getSignedInAccountFromIntent(data).getResult(ApiException::class.java)
		browserSession.clear()
		syncSettings.authMode = DriveAuthMode.GMS
		return account
	}

	fun lastAccount(): GoogleSignInAccount? = runCatching { GoogleSignIn.getLastSignedInAccount(context) }.getOrNull()

	/** True only when Google Play Services exposes an account usable by [GoogleAuthUtil]. */
	fun hasSignedInAccount(): Boolean = lastAccount()?.account != null

	internal fun activeMode(): DriveAuthMode? = syncSettings.authMode ?: when {
		hasSignedInAccount() -> DriveAuthMode.GMS
		else -> null
	}

	/** A foreground sync may use either a persistent GMS session or a live browser session. */
	fun hasUsableSession(): Boolean = when (activeMode()) {
		DriveAuthMode.GMS -> syncSettings.hasAccountMetadata && hasSignedInAccount()
		DriveAuthMode.BROWSER_TEMPORARY -> syncSettings.hasAccountMetadata && browserSession.snapshot().isUsable
		null -> false
	}

	/** Periodic/background sync intentionally remains a GMS-only capability. */
	fun isBackgroundCapable(): Boolean =
		activeMode() == DriveAuthMode.GMS &&
			syncSettings.hasAccountMetadata &&
			hasSignedInAccount() &&
			!syncSettings.isAuthorizationRequired

	internal fun browserSnapshot(): BrowserSessionSnapshot = browserSession.snapshot()

	internal fun accountInfo(): DriveAccountInfo? = when (activeMode()) {
		DriveAuthMode.BROWSER_TEMPORARY -> browserSession.snapshot().account
		DriveAuthMode.GMS -> DriveAccountInfo(
			email = syncSettings.accountEmail,
			displayName = syncSettings.accountName,
			photoUrl = syncSettings.accountPhotoUrl,
		)
		null -> null
	}

	suspend fun signOut() {
		when (activeMode()) {
			DriveAuthMode.BROWSER_TEMPORARY -> browserSession.clear()
			DriveAuthMode.GMS, null -> {
				runCatching { signInClient.signOut().await() }
				// revokeAccess so the next GMS sign-in prompts for consent / a different account.
				runCatching { signInClient.revokeAccess().await() }
			}
		}
	}

	/**
	 * Returns a Drive access token from the active provider. Browser tokens must have enough lifetime
	 * left to begin a sync; the repository then keeps using that same token until Google rejects a
	 * later request, which avoids aborting an already-successful in-flight request on a local timer.
	 */
	suspend fun requireAccessToken(): String = when (activeMode()) {
		DriveAuthMode.BROWSER_TEMPORARY -> try {
			browserSession.requireAccessToken(BrowserDriveAuthClient.MIN_SYNC_START_REMAINING_MS)
		} catch (e: BrowserAuthException) {
			throw SyncSignInRequiredException(e.message ?: "Temporary browser session expired")
		}
		DriveAuthMode.GMS, null -> requireGmsAccessToken()
	}

	private suspend fun requireGmsAccessToken(): String = withContext(Dispatchers.IO) {
		val account = lastAccount()?.account ?: throw SyncSignInRequiredException()
		try {
			GoogleAuthUtil.getToken(context, account, OAUTH2_SCOPE)
		} catch (e: UserRecoverableAuthException) {
			throw e.toSyncSignInRequiredException()
		}
	}

	/**
	 * Invalidates a rejected token. Browser mode deliberately clears the temporary credential so the
	 * repository's existing single retry falls through to SignInRequired instead of silently renewing.
	 */
	fun invalidateToken(token: String) {
		when (activeMode()) {
			DriveAuthMode.BROWSER_TEMPORARY -> browserSession.invalidate(token)
			DriveAuthMode.GMS, null -> runCatching { GoogleAuthUtil.clearToken(context, token) }
		}
	}

	private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
		addOnSuccessListener { cont.resume(it) }
		addOnFailureListener { cont.resumeWithException(it) }
		addOnCanceledListener { cont.cancel() }
	}

	companion object {
		const val SCOPE_APPDATA = "https://www.googleapis.com/auth/drive.appdata"
		private const val OAUTH2_SCOPE = "oauth2:$SCOPE_APPDATA"
	}
}

internal fun UserRecoverableAuthException.toSyncSignInRequiredException(): SyncSignInRequiredException =
	SyncSignInRequiredException(
		message = message ?: "Google Drive authorization is required",
		recoveryIntent = intent,
	)
