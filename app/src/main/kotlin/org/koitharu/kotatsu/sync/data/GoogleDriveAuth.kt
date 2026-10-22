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
 * Google authentication for Drive sync, using the classic GoogleSignIn (account chooser + scope
 * consent) plus [GoogleAuthUtil] for minting OAuth2 access tokens. This is more reliable for direct
 * Drive REST calls than the newer Identity AuthorizationClient, whose tokens were being rejected.
 */
@Singleton
class GoogleDriveAuth @Inject constructor(
	@ApplicationContext private val context: Context,
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

	/** Parses the signed-in account from the sign-in result; throws [ApiException] on failure. */
	fun accountFromIntent(data: Intent?): GoogleSignInAccount =
		GoogleSignIn.getSignedInAccountFromIntent(data).getResult(ApiException::class.java)

	fun lastAccount(): GoogleSignInAccount? = GoogleSignIn.getLastSignedInAccount(context)

	/** True only when Google Play Services still exposes an account usable by [GoogleAuthUtil]. */
	fun hasSignedInAccount(): Boolean = lastAccount()?.account != null

	suspend fun signOut() {
		runCatching { signInClient.signOut().await() }
		// revokeAccess so the next sign-in prompts for consent / a different account.
		runCatching { signInClient.revokeAccess().await() }
	}

	/** Returns a valid OAuth2 access token for the appDataFolder scope, or requests foreground recovery. */
	suspend fun requireAccessToken(): String = withContext(Dispatchers.IO) {
		val account = lastAccount()?.account ?: throw SyncSignInRequiredException()
		try {
			GoogleAuthUtil.getToken(context, account, OAUTH2_SCOPE)
		} catch (e: UserRecoverableAuthException) {
			throw e.toSyncSignInRequiredException()
		}
	}

	/** Clears a cached token the server rejected so the next [requireAccessToken] fetches a fresh one. */
	fun invalidateToken(token: String) {
		runCatching { GoogleAuthUtil.clearToken(context, token) }
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
