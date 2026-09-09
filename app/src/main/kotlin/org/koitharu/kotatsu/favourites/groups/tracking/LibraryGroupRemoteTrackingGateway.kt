package org.koitharu.kotatsu.favourites.groups.tracking

import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.util.await
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.parsers.util.parseJsonArray
import org.koitharu.kotatsu.scrobbling.common.data.ScrobblerStorage
import org.koitharu.kotatsu.scrobbling.common.domain.ScrobblerRepositoryMap
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerType
import org.koitharu.kotatsu.scrobbling.kitsu.data.KitsuAuthenticator
import org.koitharu.kotatsu.scrobbling.kitsu.data.KitsuInterceptor
import java.net.HttpURLConnection
import javax.inject.Inject
import javax.inject.Singleton

private const val ANILIST_ENDPOINT = "https://graphql.anilist.co"
private const val MAL_API = "https://api.myanimelist.net/v2"
private const val KITSU_API = "https://kitsu.app/api/edge"
private const val SHIKIMORI_API = "https://shikimori.one/api"
private const val MANGABAKA_API = "https://api.mangabaka.org/v1"

/** Remote state that is safe to persist against a Library Group rather than a manga row. */
data class RemoteLibraryGroupRate(
	val rateId: Long,
	val targetId: Long,
	val status: String?,
	val progress: Int,
	val rating: Float,
	val comment: String?,
)

/**
 * Talks to the same authenticated tracking services as normal manga tracking without using a fake
 * manga id or writing through ScrobblingEntity. This keeps Group state independent from its source
 * manga while preserving the user's existing service sessions.
 */
@Singleton
class LibraryGroupRemoteTrackingGateway @Inject constructor(
	@ScrobblerType(ScrobblerService.SHIKIMORI) private val shikimoriHttp: OkHttpClient,
	@ScrobblerType(ScrobblerService.ANILIST) private val aniListHttp: OkHttpClient,
	@ScrobblerType(ScrobblerService.MAL) private val malHttp: OkHttpClient,
	@ScrobblerType(ScrobblerService.MANGABAKA) private val mangaBakaHttp: OkHttpClient,
	@ScrobblerType(ScrobblerService.KITSU) kitsuStorage: ScrobblerStorage,
	kitsuAuthenticator: KitsuAuthenticator,
	private val repositories: ScrobblerRepositoryMap,
) {

	private val kitsuHttp: OkHttpClient = OkHttpClient.Builder()
		.authenticator(kitsuAuthenticator)
		.addInterceptor(KitsuInterceptor(kitsuStorage))
		.build()

	suspend fun ensureLinked(service: ScrobblerService, targetId: Long): RemoteLibraryGroupRate = when (service) {
		ScrobblerService.ANILIST -> aniListEnsure(targetId)
		ScrobblerService.MAL -> malEnsure(targetId)
		ScrobblerService.KITSU -> kitsuEnsure(targetId)
		ScrobblerService.SHIKIMORI -> shikimoriEnsure(targetId)
		ScrobblerService.MANGABAKA -> mangaBakaEnsure(targetId)
	}

	suspend fun refresh(service: ScrobblerService, rate: RemoteLibraryGroupRate): RemoteLibraryGroupRate = when (service) {
		ScrobblerService.ANILIST -> aniListRefresh(rate.rateId)
		ScrobblerService.MAL -> malRefresh(rate.targetId)
		ScrobblerService.KITSU -> kitsuRefresh(rate.rateId)
		ScrobblerService.SHIKIMORI -> shikimoriRefresh(rate.targetId)
		ScrobblerService.MANGABAKA -> mangaBakaRefresh(rate.targetId)
	}

	suspend fun syncProgress(
		service: ScrobblerService,
		rate: RemoteLibraryGroupRate,
		progress: Int,
	): RemoteLibraryGroupRate = when (service) {
		ScrobblerService.ANILIST -> aniListSync(rate.rateId, progress)
		ScrobblerService.MAL -> malSync(rate.targetId, progress)
		ScrobblerService.KITSU -> kitsuSync(rate.rateId, progress)
		ScrobblerService.SHIKIMORI -> shikimoriSync(rate.rateId, progress)
		ScrobblerService.MANGABAKA -> mangaBakaSync(rate, progress)
	}

	private suspend fun aniListEnsure(targetId: Long): RemoteLibraryGroupRate {
		val existing = aniListRequest(
			"""
			query {
				Media(id: $targetId) {
					mediaListEntry { id mediaId status notes score(format: POINT_100) progress }
				}
			}
			""",
		).getJSONObject("data").getJSONObject("Media").optJSONObject("mediaListEntry")
		if (existing != null) return existing.toAniListRate()
		val created = aniListRequest(
			"""
			mutation {
				SaveMediaListEntry(mediaId: $targetId) {
					id mediaId status notes score(format: POINT_100) progress
				}
			}
			""",
		).getJSONObject("data").getJSONObject("SaveMediaListEntry")
		return created.toAniListRate()
	}

	private suspend fun aniListRefresh(rateId: Long): RemoteLibraryGroupRate {
		val json = aniListRequest(
			"""
			query {
				MediaList(id: $rateId) { id mediaId status notes score(format: POINT_100) progress }
			}
			""",
		).getJSONObject("data").getJSONObject("MediaList")
		return json.toAniListRate()
	}

	private suspend fun aniListSync(rateId: Long, progress: Int): RemoteLibraryGroupRate {
		val json = aniListRequest(
			"""
			mutation {
				SaveMediaListEntry(id: $rateId, progress: ${progress.coerceAtLeast(0)}) {
					id mediaId status notes score(format: POINT_100) progress
				}
			}
			""",
		).getJSONObject("data").getJSONObject("SaveMediaListEntry")
		return json.toAniListRate()
	}

	private suspend fun aniListRequest(query: String): JSONObject {
		val body = JSONObject()
			.put("query", query.replace(Regex("\\s+"), " ").trim())
			.toString()
			.toRequestBody("application/json; charset=utf-8".toMediaType())
		val json = aniListHttp.newCall(Request.Builder().url(ANILIST_ENDPOINT).post(body).build()).await().parseJson()
		val errors = json.optJSONArray("errors")
		check(errors == null || errors.length() == 0) { errors?.toString().orEmpty() }
		return json
	}

	private fun JSONObject.toAniListRate() = RemoteLibraryGroupRate(
		rateId = getLong("id"),
		targetId = getLong("mediaId"),
		status = getStringOrNull("status"),
		progress = optInt("progress", 0),
		rating = (optDouble("score", 0.0).toFloat() / 100f).coerceIn(0f, 1f),
		comment = getStringOrNull("notes"),
	)

	private suspend fun malEnsure(targetId: Long): RemoteLibraryGroupRate {
		malLoad(targetId)?.let { return it }
		val body = FormBody.Builder().add("status", "reading").add("score", "0").build()
		val json = malHttp.newCall(
			Request.Builder().url("$MAL_API/manga/$targetId/my_list_status").put(body).build(),
		).await().parseJson()
		return json.toMalRate(targetId)
	}

	private suspend fun malRefresh(targetId: Long): RemoteLibraryGroupRate =
		checkNotNull(malLoad(targetId)) { "MyAnimeList no longer has this entry in the user's list" }

	private suspend fun malLoad(targetId: Long): RemoteLibraryGroupRate? {
		val json = malHttp.newCall(
			Request.Builder().url("$MAL_API/manga/$targetId?fields=my_list_status").get().build(),
		).await().parseJson()
		return json.optJSONObject("my_list_status")?.toMalRate(targetId)
	}

	private suspend fun malSync(targetId: Long, progress: Int): RemoteLibraryGroupRate {
		val body = FormBody.Builder().add("num_chapters_read", progress.coerceAtLeast(0).toString()).build()
		val json = malHttp.newCall(
			Request.Builder().url("$MAL_API/manga/$targetId/my_list_status").put(body).build(),
		).await().parseJson()
		return json.toMalRate(targetId)
	}

	private fun JSONObject.toMalRate(targetId: Long) = RemoteLibraryGroupRate(
		rateId = targetId,
		targetId = targetId,
		status = getStringOrNull("status"),
		progress = optInt("num_chapters_read", 0),
		rating = (optDouble("score", 0.0).toFloat() / 10f).coerceIn(0f, 1f),
		comment = getStringOrNull("comments"),
	)

	private suspend fun kitsuEnsure(targetId: Long): RemoteLibraryGroupRate {
		kitsuFind(targetId)?.let { return it.toKitsuRate() }
		val user = repositories[ScrobblerService.KITSU].cachedUser
			?: repositories[ScrobblerService.KITSU].loadUser()
		val payload = JSONObject().apply {
			put("data", JSONObject().apply {
				put("type", "libraryEntries")
				put("attributes", JSONObject().put("status", "planned").put("progress", 0))
				put("relationships", JSONObject().apply {
					put("manga", JSONObject().put("data", JSONObject().put("type", "manga").put("id", targetId)))
					put("user", JSONObject().put("data", JSONObject().put("type", "users").put("id", user.id)))
				})
			})
		}
		val json = kitsuHttp.newCall(
			Request.Builder()
				.url("$KITSU_API/library-entries?include=manga")
				.post(payload.jsonApiBody())
				.build(),
		).await().parseJson().getJSONObject("data")
		return json.toKitsuRate()
	}

	private suspend fun kitsuFind(targetId: Long): JSONObject? {
		val user = repositories[ScrobblerService.KITSU].cachedUser
			?: repositories[ScrobblerService.KITSU].loadUser()
		val json = kitsuHttp.newCall(
			Request.Builder()
				.url("$KITSU_API/library-entries?filter[manga_id]=$targetId&filter[userId]=${user.id}&include=manga")
				.get()
				.build(),
		).await().parseJson()
		return json.optJSONArray("data")?.optJSONObject(0)
	}

	private suspend fun kitsuRefresh(rateId: Long): RemoteLibraryGroupRate {
		val json = kitsuHttp.newCall(
			Request.Builder().url("$KITSU_API/library-entries/$rateId?include=manga").get().build(),
		).await().parseJson().getJSONObject("data")
		return json.toKitsuRate()
	}

	private suspend fun kitsuSync(rateId: Long, progress: Int): RemoteLibraryGroupRate {
		val payload = JSONObject().put(
			"data",
			JSONObject()
				.put("type", "libraryEntries")
				.put("id", rateId)
				.put("attributes", JSONObject().put("progress", progress.coerceAtLeast(0))),
		)
		val json = kitsuHttp.newCall(
			Request.Builder()
				.url("$KITSU_API/library-entries/$rateId?include=manga")
				.patch(payload.jsonApiBody())
				.build(),
		).await().parseJson().getJSONObject("data")
		return json.toKitsuRate()
	}

	private fun JSONObject.toKitsuRate(): RemoteLibraryGroupRate {
		val attrs = getJSONObject("attributes")
		val target = getJSONObject("relationships").getJSONObject("manga").getJSONObject("data")
		return RemoteLibraryGroupRate(
			rateId = getString("id").toLong(),
			targetId = target.getString("id").toLong(),
			status = attrs.getStringOrNull("status"),
			progress = attrs.optInt("progress", 0),
			rating = (attrs.optDouble("ratingTwenty", 0.0).toFloat() / 20f).coerceIn(0f, 1f),
			comment = attrs.getStringOrNull("notes"),
		)
	}

	private suspend fun shikimoriEnsure(targetId: Long): RemoteLibraryGroupRate {
		shikimoriFind(targetId)?.let { return it.toShikimoriRate() }
		val user = repositories[ScrobblerService.SHIKIMORI].cachedUser
			?: repositories[ScrobblerService.SHIKIMORI].loadUser()
		val payload = JSONObject().put(
			"user_rate",
			JSONObject()
				.put("target_id", targetId)
				.put("target_type", "Manga")
				.put("user_id", user.id),
		)
		val json = shikimoriHttp.newCall(
			Request.Builder().url("$SHIKIMORI_API/v2/user_rates").post(payload.jsonBody()).build(),
		).await().parseJson()
		return json.toShikimoriRate()
	}

	private suspend fun shikimoriFind(targetId: Long): JSONObject? {
		val user = repositories[ScrobblerService.SHIKIMORI].cachedUser
			?: repositories[ScrobblerService.SHIKIMORI].loadUser()
		val response = shikimoriHttp.newCall(
			Request.Builder()
				.url("$SHIKIMORI_API/v2/user_rates?user_id=${user.id}&target_id=$targetId&target_type=Manga")
				.get()
				.build(),
		).await().parseJsonArray()
		return response.optJSONObject(0)
	}

	private suspend fun shikimoriRefresh(targetId: Long): RemoteLibraryGroupRate =
		checkNotNull(shikimoriFind(targetId)) { "Shikimori no longer has this entry in the user's list" }
			.toShikimoriRate()

	private suspend fun shikimoriSync(rateId: Long, progress: Int): RemoteLibraryGroupRate {
		val payload = JSONObject().put(
			"user_rate",
			JSONObject().put("chapters", progress.coerceAtLeast(0)),
		)
		val json = shikimoriHttp.newCall(
			Request.Builder().url("$SHIKIMORI_API/v2/user_rates/$rateId").patch(payload.jsonBody()).build(),
		).await().parseJson()
		return json.toShikimoriRate()
	}

	private fun JSONObject.toShikimoriRate() = RemoteLibraryGroupRate(
		rateId = getLong("id"),
		targetId = getLong("target_id"),
		status = getStringOrNull("status"),
		progress = optInt("chapters", 0),
		rating = (optDouble("score", 0.0).toFloat() / 10f).coerceIn(0f, 1f),
		comment = getStringOrNull("text"),
	)

	private suspend fun mangaBakaEnsure(targetId: Long): RemoteLibraryGroupRate {
		mangaBakaLoad(targetId)?.let { return it }
		mangaBakaHttp.newCall(
			Request.Builder()
				.url("$MANGABAKA_API/my/library/$targetId")
				.post(JSONObject().put("state", "reading").jsonBody())
				.build(),
		).await().parseJson()
		return checkNotNull(mangaBakaLoad(targetId)) { "Unable to create MangaBaka tracking entry" }
	}

	private suspend fun mangaBakaRefresh(targetId: Long): RemoteLibraryGroupRate =
		checkNotNull(mangaBakaLoad(targetId)) { "MangaBaka no longer has this entry in the user's library" }

	private suspend fun mangaBakaLoad(targetId: Long): RemoteLibraryGroupRate? {
		val response = mangaBakaHttp.newCall(
			Request.Builder().url("$MANGABAKA_API/my/library/$targetId").get().build(),
		).await()
		if (response.code == HttpURLConnection.HTTP_NOT_FOUND) {
			response.close()
			return null
		}
		return response.parseJson().getJSONObject("data").toMangaBakaRate(targetId)
	}

	private suspend fun mangaBakaSync(
		current: RemoteLibraryGroupRate,
		progress: Int,
	): RemoteLibraryGroupRate {
		val rating = (current.rating * 100f).toInt().coerceIn(0, 100)
		val payload = JSONObject()
			.put("state", current.status ?: "reading")
			.put("progress_chapter", progress.coerceAtLeast(0).takeIf { it > 0 } ?: JSONObject.NULL)
			.put("rating", rating.takeIf { it > 0 } ?: JSONObject.NULL)
		mangaBakaHttp.newCall(
			Request.Builder()
				.url("$MANGABAKA_API/my/library/${current.targetId}")
				.put(payload.jsonBody())
				.build(),
		).await().parseJson()
		return checkNotNull(mangaBakaLoad(current.targetId)) { "Unable to refresh MangaBaka tracking entry" }
	}

	private fun JSONObject.toMangaBakaRate(targetId: Long) = RemoteLibraryGroupRate(
		rateId = targetId,
		targetId = targetId,
		status = getStringOrNull("state"),
		progress = optDouble("progress_chapter", 0.0).takeUnless { it.isNaN() }?.toInt() ?: 0,
		rating = (optInt("rating", 0).toFloat() / 100f).coerceIn(0f, 1f),
		comment = null,
	)

	private fun JSONObject.jsonBody() = toString().toRequestBody("application/json".toMediaType())
	private fun JSONObject.jsonApiBody() = toString().toRequestBody("application/vnd.api+json".toMediaType())
}
