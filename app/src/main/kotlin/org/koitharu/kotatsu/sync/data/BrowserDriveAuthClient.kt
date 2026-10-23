package org.koitharu.kotatsu.sync.data

import android.net.Uri
import eu.kanade.tachiyomi.network.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.koitharu.kotatsu.core.network.BaseHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

internal data class BrowserAuthCompletion(
	val origin: BrowserAuthOrigin,
	val account: DriveAccountInfo?,
	val expiresAt: Long,
)

/** Android side of the temporary browser-auth broker contract. */
@Singleton
class BrowserDriveAuthClient @Inject constructor(
	@BaseHttpClient baseHttpClient: OkHttpClient,
	private val session: BrowserDriveAuthSession,
	private val syncSettings: SyncSettings,
) {

	private val httpClient = baseHttpClient.newBuilder().apply {
		interceptors().clear()
		networkInterceptors().clear()
		cache(null)
		connectTimeout(15, TimeUnit.SECONDS)
		readTimeout(30, TimeUnit.SECONDS)
		writeTimeout(30, TimeUnit.SECONDS)
		callTimeout(45, TimeUnit.SECONDS)
	}.build()

	private val json = Json { ignoreUnknownKeys = true }

	internal fun isConfigured(): Boolean = configuredBaseUrl() != null

	internal fun createLoginUri(origin: BrowserAuthOrigin): Uri {
		val base = configuredBaseUrl()
			?: throw BrowserAuthException("Browser sign-in is not configured for this build")
		val launch = session.begin(origin)
		return Uri.parse(
			base.newBuilder()
				.addPathSegments("v1/google/start")
				.addQueryParameter("transaction_id", launch.transactionId)
				.addQueryParameter("code_challenge", launch.codeChallenge)
				.build()
				.toString(),
		)
	}

	internal fun cancelPending() = session.cancelPending()

	/**
	 * Waits for the HTTPS broker callback to finish. The app proves possession of its process-local
	 * verifier before the broker reveals the short-lived one-time app code. This fallback avoids
	 * putting either the Google token or app code in a browser/deep-link URL when no verified App Link
	 * domain is configured yet.
	 */
	internal suspend fun awaitCompletion(): BrowserAuthCompletion = withContext(Dispatchers.IO) {
		val base = configuredBaseUrl()
			?: throw BrowserAuthException("Browser sign-in is not configured for this build")
		while (true) {
			val proof = session.pendingProof()
			val payload = json.encodeToString(
				PollRequest.serializer(),
				PollRequest(proof.transactionId, proof.codeVerifier),
			)
			val request = Request.Builder()
				.url(base.newBuilder().addPathSegments("v1/google/poll").build())
				.post(payload.toRequestBody(JSON_MEDIA_TYPE))
				.header("Accept", "application/json")
				.build()
			val oneTimeCode = httpClient.newCall(request).await().use { response ->
				if (response.code == 202) return@use null
				if (!response.isSuccessful) {
					throw BrowserAuthException(
						if (response.code in setOf(400, 401, 403, 404, 409, 410)) {
							"Browser sign-in session is no longer valid"
						} else {
							"Browser sign-in service is temporarily unavailable"
						},
					)
				}
				val body = response.body.string()
				val poll = runCatching { json.decodeFromString(PollResponse.serializer(), body) }
					.getOrElse { throw BrowserAuthException("Browser sign-in returned an invalid response", it) }
				poll.error?.takeIf { it.isNotBlank() }?.let {
					session.cancelPending()
					throw BrowserAuthException(it.toUserMessage())
				}
				poll.code?.takeIf { it.isNotBlank() }
					?: throw BrowserAuthException("Browser sign-in response is missing its one-time code")
			}
			if (oneTimeCode == null) {
				delay(POLL_INTERVAL_MS)
				continue
			}
			val exchangeProof = session.consumeOneTimeCode(proof.transactionId, oneTimeCode)
			return@withContext exchange(base, exchangeProof)
		}
	}

	private suspend fun exchange(
		base: okhttp3.HttpUrl,
		proof: BrowserAuthCallbackProof,
	): BrowserAuthCompletion {
		val payload = json.encodeToString(
			ExchangeRequest.serializer(),
			ExchangeRequest(proof.code, proof.transactionId, proof.codeVerifier),
		)
		val request = Request.Builder()
			.url(base.newBuilder().addPathSegments("v1/google/exchange").build())
			.post(payload.toRequestBody(JSON_MEDIA_TYPE))
			.header("Accept", "application/json")
			.build()
		val response = httpClient.newCall(request).await().use { httpResponse ->
			if (!httpResponse.isSuccessful) {
				throw BrowserAuthException(
					if (httpResponse.code in setOf(400, 401, 403, 404, 409, 410)) {
						"Browser sign-in session is no longer valid"
					} else {
						"Browser sign-in service is temporarily unavailable"
					},
				)
			}
			val body = httpResponse.body.string()
			if (body.isBlank()) throw BrowserAuthException("Browser sign-in returned an empty response")
			runCatching { json.decodeFromString(ExchangeResponse.serializer(), body) }
				.getOrElse { throw BrowserAuthException("Browser sign-in returned an invalid response", it) }
		}
		val token = response.accessToken.takeIf { it.isNotBlank() }
			?: throw BrowserAuthException("Browser sign-in response did not contain an access token")
		if (response.expiresIn <= 0L) throw BrowserAuthException("Browser sign-in returned an invalid expiry")
		val now = System.currentTimeMillis()
		val expiresAt = runCatching { Math.addExact(now, Math.multiplyExact(response.expiresIn, 1000L)) }
			.getOrElse { throw BrowserAuthException("Browser sign-in returned an invalid expiry", it) }
		val account = response.account?.let {
			DriveAccountInfo(
				email = it.email?.ifBlank { null },
				displayName = it.displayName?.ifBlank { null },
				photoUrl = it.photoUrl?.ifBlank { null },
			)
		}
		session.install(token, expiresAt, account, now)
		// Non-secret display metadata may remain for UX/bookkeeping, but is never proof of auth.
		syncSettings.accountEmail = account?.email ?: "Google Drive"
		syncSettings.accountName = account?.displayName
		syncSettings.accountPhotoUrl = account?.photoUrl
		syncSettings.authMode = DriveAuthMode.BROWSER_TEMPORARY
		syncSettings.isAuthorizationRequired = false
		return BrowserAuthCompletion(proof.origin, account, expiresAt)
	}

	private fun configuredBaseUrl() = BrowserDriveAuthConfig.BASE_URL
		.trim()
		.trimEnd('/')
		.takeIf { it.isNotEmpty() }
		?.toHttpUrlOrNull()
		?.takeIf { it.isHttps }

	private fun String.toUserMessage(): String = when (this) {
		"access_denied" -> "Google sign-in was cancelled or access was denied"
		"temporarily_unavailable" -> "Google sign-in is temporarily unavailable"
		"expired" -> "Browser sign-in session expired"
		else -> "Google sign-in could not be completed"
	}

	@Serializable
	private data class PollRequest(
		@SerialName("transaction_id") val transactionId: String,
		@SerialName("code_verifier") val codeVerifier: String,
	)

	@Serializable
	private data class PollResponse(
		@SerialName("code") val code: String? = null,
		@SerialName("error") val error: String? = null,
	)

	@Serializable
	private data class ExchangeRequest(
		@SerialName("code") val code: String,
		@SerialName("transaction_id") val transactionId: String,
		@SerialName("code_verifier") val codeVerifier: String,
	)

	@Serializable
	private data class ExchangeResponse(
		@SerialName("access_token") val accessToken: String = "",
		@SerialName("expires_in") val expiresIn: Long = 0L,
		@SerialName("account") val account: ExchangeAccount? = null,
	)

	@Serializable
	private data class ExchangeAccount(
		@SerialName("email") val email: String? = null,
		@SerialName("display_name") val displayName: String? = null,
		@SerialName("photo_url") val photoUrl: String? = null,
	)

	companion object {
		const val MIN_SYNC_START_REMAINING_MS = 2L * 60L * 1000L
		private const val POLL_INTERVAL_MS = 1_500L
		private val JSON_MEDIA_TYPE = "application/json; charset=UTF-8".toMediaType()
	}
}
