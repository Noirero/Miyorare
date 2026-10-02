package org.koitharu.kotatsu.sync.library

import java.time.Instant
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** Official implicit grant / auth-pin. Never uses Tracking's client secret or plaintext storage. */
class AniListLibrarySyncService(secrets: LibrarySyncSecretStore, state: LibrarySyncStateStore) :
	TokenLibrarySyncService(LibrarySyncServiceId.ANILIST, secrets, state, 2100) {
	override suspend fun login(credentials: LibrarySyncCredentials) {
		val token = requireNotNull(credentials.accessToken).trim()
		val user = graphql("query { Viewer { id } }", token = token).getJSONObject("Viewer")
		saveSession(token, user.getLong("id").toString())
	}

	override suspend fun pullLibrary(): List<SyncEntry> {
		val userId = session().getString("user_id").toInt()
		val entries = ArrayList<SyncEntry>()
		var page = 1
		do {
			val variables = JSONObject().put("user", userId).put("page", page)
			val data =
				graphql(
						"""
						query(${'$'}user: Int!, ${'$'}page: Int!) {
							Page(page: ${'$'}page, perPage: 50) {
								pageInfo { hasNextPage }
								mediaList(userId: ${'$'}user, type: MANGA) {
									mediaId progress status updatedAt id
									media { title { userPreferred } }
								}
							}
						}
						"""
							.trimIndent(),
						variables,
					)
					.getJSONObject("Page")
			val items = data.getJSONArray("mediaList")
			for (index in 0 until items.length()) {
				val item = items.getJSONObject(index)
				entries +=
					SyncEntry(
						id,
						item.getLong("mediaId").toString(),
						null,
						item
							.getJSONObject("media")
							.getJSONObject("title")
							.getString("userPreferred"),
						item.getInt("progress").coerceAtLeast(0),
						item.getString("status"),
						Instant.ofEpochSecond(item.getLong("updatedAt")),
						item.getLong("id").toString(),
					)
			}
			val more = data.getJSONObject("pageInfo").getBoolean("hasNextPage")
			check(!more || items.length() > 0) { "Invalid AniList pagination" }
			page++
		} while (more)
		return entries
	}

	override suspend fun pushLibrary(entries: List<SyncEntry>) {
		for (entry in entries) {
			require(entry.service == id && entry.progress >= 0)
			val status = entry.status ?: LibrarySyncEngine.plannedStatus(id)
			require(status in STATUSES)
			graphql(
				"""
				mutation(${'$'}media: Int!, ${'$'}progress: Int!, ${'$'}status: MediaListStatus!) {
					SaveMediaListEntry(mediaId: ${'$'}media, progress: ${'$'}progress, status: ${'$'}status) { id updatedAt }
				}
				"""
					.trimIndent(),
				JSONObject()
					.put("media", entry.externalId.toInt())
					.put("progress", entry.progress)
					.put("status", status),
			)
		}
	}

	override suspend fun lookup(externalId: String): SyncEntry {
		require(externalId.toInt() > 0)
		val data =
			graphql(
					"query(\$id: Int!) { Media(id: \$id, type: MANGA) { id title { userPreferred } } }",
					JSONObject().put("id", externalId.toInt()),
				)
				.getJSONObject("Media")
		return SyncEntry(
			id,
			data.getInt("id").toString(),
			null,
			data.getJSONObject("title").getString("userPreferred"),
			0,
			"PLANNING",
			Instant.EPOCH,
		)
	}

	private suspend fun graphql(
		query: String,
		variables: JSONObject = JSONObject(),
		token: String = session().getString("access_token"),
	): JSONObject {
		val body =
			JSONObject()
				.put("query", query)
				.put("variables", variables)
				.toString()
				.toRequestBody(JSON)
		val response =
			try {
				http.json(
					Request.Builder()
						.url(ENDPOINT)
						.header("Authorization", "Bearer $token")
						.post(body)
						.build()
				)
			} catch (e: LibrarySyncHttpException) {
				if (e.code == 401) throw LibrarySyncAuthException() else throw e
			}
		val errors = response.optJSONArray("errors")
		if (errors != null && errors.length() > 0) {
			val codes =
				(0 until errors.length()).map { errors.getJSONObject(it).optInt("status", 400) }
			if (401 in codes || 403 in codes) throw LibrarySyncAuthException()
			if (429 in codes) state.setRetryAt(id, System.currentTimeMillis() + 60_000)
			throw LibrarySyncHttpException(codes.firstOrNull { it == 429 || it >= 500 } ?: 400)
		}
		return response.getJSONObject("data")
	}

	companion object {
		private val JSON = "application/json; charset=utf-8".toMediaType()
		private const val ENDPOINT = "https://graphql.anilist.co"
		val STATUSES = setOf("CURRENT", "PLANNING", "COMPLETED", "DROPPED", "PAUSED", "REPEATING")
	}
}
