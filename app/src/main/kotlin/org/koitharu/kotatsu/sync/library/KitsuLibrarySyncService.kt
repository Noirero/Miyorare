package org.koitharu.kotatsu.sync.library

import java.time.Instant
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.koitharu.kotatsu.scrobbling.kitsu.data.KitsuInterceptor

/** Uses the same official JSON:API contract and media type as KitsuRepository. */
class KitsuLibrarySyncService(secrets: LibrarySyncSecretStore, state: LibrarySyncStateStore) :
	TokenLibrarySyncService(LibrarySyncServiceId.KITSU, secrets, state, 1100) {
	override suspend fun login(credentials: LibrarySyncCredentials) {
		val tokens =
			http.json(
				Request.Builder()
					.url(TOKEN_URL)
					.post(
						FormBody.Builder()
							.add("grant_type", "password")
							.add("username", requireNotNull(credentials.username))
							.add("password", requireNotNull(credentials.password))
							.build()
					)
					.build()
			)
		val token = tokens.getString("access_token")
		val user =
			http
				.json(
					Request.Builder()
						.url(
							"$BASE/users"
								.toHttpUrl()
								.newBuilder()
								.addQueryParameter("filter[self]", "true")
								.build()
						)
						.header("Authorization", "Bearer $token")
						.header("Accept", KitsuInterceptor.VND_JSON)
						.build()
				)
				.getJSONArray("data")
				.getJSONObject(0)
		saveSession(token, user.getString("id"), tokens.getString("refresh_token"))
	}

	override suspend fun pullLibrary(): List<SyncEntry> {
		val result = ArrayList<SyncEntry>()
		val userId = session().getString("user_id")
		var offset = 0
		do {
			val url =
				"$BASE/library-entries"
					.toHttpUrl()
					.newBuilder()
					.addQueryParameter("filter[userId]", userId)
					.addQueryParameter("filter[kind]", "manga")
					.addQueryParameter("include", "manga")
					.addQueryParameter("page[limit]", "20")
					.addQueryParameter("page[offset]", offset.toString())
					.build()
			val response = request(Request.Builder().url(url))
			val items = response.getJSONArray("data")
			val included = response.optJSONArray("included")
			val titles = HashMap<String, String>()
			if (included != null)
				for (i in 0 until included.length()) {
					val item = included.getJSONObject(i)
					if (item.getString("type") == "manga")
						titles[item.getString("id")] =
							item.getJSONObject("attributes").getString("canonicalTitle")
				}
			for (i in 0 until items.length()) {
				val item = items.getJSONObject(i)
				val manga =
					item.getJSONObject("relationships").getJSONObject("manga").optJSONObject("data")
						?: continue
				val mangaId = manga.getString("id")
				val attrs = item.getJSONObject("attributes")
				result +=
					SyncEntry(
						id,
						mangaId,
						null,
						titles[mangaId] ?: "Kitsu #$mangaId",
						attrs.getInt("progress").coerceAtLeast(0),
						attrs.getString("status"),
						Instant.parse(attrs.getString("updatedAt")),
						item.getString("id"),
					)
			}
			val more =
				response.optJSONObject("links")?.opt("next")?.let {
					it != JSONObject.NULL && it.toString().isNotBlank()
				} == true
			check(!more || items.length() > 0) { "Invalid Kitsu pagination" }
			offset += items.length()
		} while (more)
		return result
	}

	override suspend fun pushLibrary(entries: List<SyncEntry>) {
		val userId = session().getString("user_id")
		for (entry in entries) {
			require(entry.service == id && entry.externalId.toLong() > 0 && entry.progress >= 0)
			val status = entry.status ?: "planned"
			require(status in STATUSES)
			// Resolve existing entry on each write: safe retries never blindly duplicate a POST.
			val url =
				"$BASE/library-entries"
					.toHttpUrl()
					.newBuilder()
					.addQueryParameter("filter[userId]", userId)
					.addQueryParameter("filter[mangaId]", entry.externalId)
					.build()
			val existing = request(Request.Builder().url(url)).getJSONArray("data").optJSONObject(0)
			val data =
				JSONObject()
					.put("type", "libraryEntries")
					.put(
						"attributes",
						JSONObject().put("progress", entry.progress).put("status", status),
					)
			val builder = Request.Builder()
			if (existing != null) {
				data.put("id", existing.getString("id"))
				builder
					.url("$BASE/library-entries/${existing.getString("id")}")
					.patch(JSONObject().put("data", data).toString().toRequestBody(JSON_API))
			} else {
				data.put(
					"relationships",
					JSONObject()
						.put(
							"manga",
							JSONObject()
								.put(
									"data",
									JSONObject().put("type", "manga").put("id", entry.externalId),
								),
						)
						.put(
							"user",
							JSONObject()
								.put("data", JSONObject().put("type", "users").put("id", userId)),
						),
				)
				builder
					.url("$BASE/library-entries")
					.post(JSONObject().put("data", data).toString().toRequestBody(JSON_API))
			}
			request(builder)
		}
	}

	override suspend fun lookup(externalId: String): SyncEntry {
		require(externalId.toLong() > 0)
		val data = request(Request.Builder().url("$BASE/manga/$externalId")).getJSONObject("data")
		return SyncEntry(
			id,
			data.getString("id"),
			null,
			data.getJSONObject("attributes").getString("canonicalTitle"),
			0,
			"planned",
			Instant.EPOCH,
		)
	}

	private suspend fun request(builder: Request.Builder): JSONObject {
		suspend fun execute(): JSONObject {
			val raw =
				http.request(
					builder
						.header("Authorization", "Bearer ${session().getString("access_token")}")
						.header("Accept", KitsuInterceptor.VND_JSON)
						.build()
				)
			return if (raw.isBlank()) JSONObject() else JSONObject(raw)
		}
		return try {
			execute()
		} catch (e: LibrarySyncHttpException) {
			if (e.code != 401) throw e
			val old = session()
			val refresh =
				old.optString("refresh_token").takeIf { it.isNotBlank() }
					?: throw LibrarySyncAuthException()
			val tokens =
				try {
					http.json(
						Request.Builder()
							.url(TOKEN_URL)
							.post(
								FormBody.Builder()
									.add("grant_type", "refresh_token")
									.add("refresh_token", refresh)
									.build()
							)
							.build()
					)
				} catch (refreshError: LibrarySyncHttpException) {
					if (refreshError.code in setOf(400, 401, 403)) throw LibrarySyncAuthException()
					else throw refreshError
				}
			saveSession(
				tokens.getString("access_token"),
				old.getString("user_id"),
				tokens.getString("refresh_token"),
			)
			try {
				execute()
			} catch (second: LibrarySyncHttpException) {
				if (second.code == 401) throw LibrarySyncAuthException() else throw second
			}
		}
	}

	companion object {
		private const val BASE = "https://kitsu.app/api/edge"
		private const val TOKEN_URL = "https://kitsu.app/api/oauth/token"
		private val JSON_API = KitsuInterceptor.VND_JSON.toMediaType()
		val STATUSES = setOf("current", "planned", "completed", "on_hold", "dropped")
	}
}
