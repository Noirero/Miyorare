package org.koitharu.kotatsu.discovery.data

import kotlinx.coroutines.flow.first
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.discovery.domain.CatalogAccountState
import org.koitharu.kotatsu.discovery.domain.CatalogTrackingDraft
import org.koitharu.kotatsu.discovery.domain.CatalogTrackingEntry
import org.koitharu.kotatsu.discovery.domain.TrackerCatalogKey
import org.koitharu.kotatsu.discovery.domain.catalogSupportedTrackingStatuses
import org.koitharu.kotatsu.parsers.util.await
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.scrobbling.common.data.ScrobblingEntity
import org.koitharu.kotatsu.scrobbling.common.domain.ScrobblerRepositoryMap
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerType
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerUser
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblingStatus
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.util.EnumMap
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

private const val ANILIST_GRAPHQL = "https://graphql.anilist.co"
private const val MAL_API = "https://api.myanimelist.net/v2"
private const val KITSU_API = "https://kitsu.app/api/edge"
private const val MANGABAKA_API = "https://api.mangabaka.org/v1"
private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
private val KITSU_MEDIA_TYPE = "application/vnd.api+json".toMediaType()

class CatalogAccountUnauthorizedException(val service: ScrobblerService) : IOException("$service session expired")

/**
 * Authenticated companion to the public Tracker Home catalogue.
 *
 * Catalogue reads stay in [TrackerCatalogRepository]. This class only reads/writes the signed-in
 * user's tracker account and never creates a parser Manga or a Room association for a catalogue
 * work. Exact remote reads are cached for the current process so cards can reuse truth already
 * established by a detail visit without performing N+1 account requests.
 */
@Singleton
class TrackerCatalogAccountRepository @Inject constructor(
	private val repositories: ScrobblerRepositoryMap,
	private val database: MangaDatabase,
	@ScrobblerType(ScrobblerService.ANILIST) private val aniListHttp: OkHttpClient,
	@ScrobblerType(ScrobblerService.MAL) private val malHttp: OkHttpClient,
	@ScrobblerType(ScrobblerService.KITSU) private val kitsuHttp: OkHttpClient,
	@ScrobblerType(ScrobblerService.MANGABAKA) private val mangaBakaHttp: OkHttpClient,
) {
	private val lock = Any()
	private val remoteState = EnumMap<ScrobblerService, MutableMap<Long, CatalogTrackingEntry?>>(ScrobblerService::class.java)

	fun snapshot(service: ScrobblerService): CatalogAccountState {
		val repository = repositories[service]
		val authorized = repository.isAuthorized
		val user = repository.cachedUser.takeIf { authorized }
		return CatalogAccountState(
			service = service,
			authorized = authorized,
			user = user,
			profileUrl = user?.let(::catalogAccountProfileUrl),
		)
	}

	suspend fun refreshAccount(service: ScrobblerService): CatalogAccountState {
		val repository = repositories[service]
		if (!repository.isAuthorized) {
			clearSession(service)
			return CatalogAccountState(service)
		}
		val user = repository.cachedUser ?: repository.loadUser()
		return CatalogAccountState(
			service = service,
			authorized = true,
			user = user,
			profileUrl = catalogAccountProfileUrl(user),
		)
	}

	fun logout(service: ScrobblerService) {
		repositories[service].logout()
		clearSession(service)
	}

	suspend fun knownTracking(service: ScrobblerService): Map<Long, CatalogTrackingEntry> {
		if (!repositories[service].isAuthorized) return emptyMap()
		val result = LinkedHashMap<Long, CatalogTrackingEntry>()
		database.getScrobblingDao().observe(service.id).first().forEach { row ->
			result[row.targetId] = row.toCatalogTrackingEntry(service)
		}
		synchronized(lock) {
			remoteState[service]?.forEach { (targetId, entry) ->
				if (entry == null) result.remove(targetId) else result[targetId] = entry
			}
		}
		return result
	}

	suspend fun tracking(key: TrackerCatalogKey): CatalogTrackingEntry? {
		requireSupported(key.service)
		check(repositories[key.service].isAuthorized) { "${key.service} is not authorized" }
		val entry = when (key.service) {
			ScrobblerService.ANILIST -> readAniList(key)
			ScrobblerService.MAL -> readMAL(key)
			ScrobblerService.KITSU -> readKitsu(key)
			ScrobblerService.MANGABAKA -> readMangaBaka(key)
			else -> error("Unsupported Tracker Home account service ${key.service}")
		}
		remember(key.service, key.remoteId.toLong(), entry)
		return entry
	}

	/** Backward-compatible quick add; the Tracker Home UI now uses [saveTracking] with an editor. */
	suspend fun addTracking(key: TrackerCatalogKey): CatalogTrackingEntry =
		saveTracking(key, CatalogTrackingDraft(), existing = null)

	suspend fun saveTracking(
		key: TrackerCatalogKey,
		draft: CatalogTrackingDraft,
		existing: CatalogTrackingEntry?,
	): CatalogTrackingEntry {
		requireSupported(key.service)
		check(repositories[key.service].isAuthorized) { "${key.service} is not authorized" }
		require(draft.status in catalogSupportedTrackingStatuses(key.service)) { "Unsupported ${key.service} status ${draft.status}" }
		val remoteExisting = existing ?: tracking(key)
		val entry = when (key.service) {
			ScrobblerService.ANILIST -> saveAniList(key, draft, remoteExisting)
			ScrobblerService.MAL -> saveMAL(key, draft)
			ScrobblerService.KITSU -> saveKitsu(key, draft, remoteExisting)
			ScrobblerService.MANGABAKA -> saveMangaBaka(key, draft, remoteExisting)
			else -> error("Unsupported Tracker Home account service ${key.service}")
		}
		remember(key.service, key.remoteId.toLong(), entry)
		return entry
	}

	suspend fun removeTracking(key: TrackerCatalogKey, existing: CatalogTrackingEntry?) {
		requireSupported(key.service)
		check(repositories[key.service].isAuthorized) { "${key.service} is not authorized" }
		val entry = existing ?: tracking(key) ?: return
		when (key.service) {
			ScrobblerService.ANILIST -> removeAniList(entry)
			ScrobblerService.MAL -> removeMAL(key)
			ScrobblerService.KITSU -> removeKitsu(entry)
			ScrobblerService.MANGABAKA -> removeMangaBaka(key)
			else -> error("Unsupported Tracker Home account service ${key.service}")
		}
		clearPersistedAssociations(key.service, key.remoteId.toLong())
		remember(key.service, key.remoteId.toLong(), null)
	}

	private suspend fun clearPersistedAssociations(service: ScrobblerService, targetId: Long) {
		val dao = database.getScrobblingDao()
		dao.findByTarget(service.id, targetId).forEach { association ->
			dao.delete(service.id, association.mangaId)
		}
	}

	private suspend fun readAniList(key: TrackerCatalogKey): CatalogTrackingEntry? {
		val query = """
			query {
				Media(id: ${key.remoteId}, type: MANGA) {
					mediaListEntry { id mediaId status progress score(format: POINT_100) }
				}
			}
		""".trimIndent()
		val root = aniListRequest(query)
		val media = root.getJSONObject("data").getJSONObject("Media")
		return media.optJSONObject("mediaListEntry")?.let(::aniListEntry)
	}

	private suspend fun saveAniList(
		key: TrackerCatalogKey,
		draft: CatalogTrackingDraft,
		existing: CatalogTrackingEntry?,
	): CatalogTrackingEntry {
		val status = checkNotNull(catalogTrackingStatusRaw(ScrobblerService.ANILIST, draft.status))
		val identity = existing?.entryId?.let { "id: $it" } ?: "mediaId: ${key.remoteId}"
		val scoreRaw = (draft.rating * 100f).roundToInt().coerceIn(0, 100)
		val query = """
			mutation {
				SaveMediaListEntry($identity, status: $status, progress: ${draft.chapter}, scoreRaw: $scoreRaw) {
					id mediaId status progress score(format: POINT_100)
				}
			}
		""".trimIndent()
		return aniListEntry(aniListRequest(query).getJSONObject("data").getJSONObject("SaveMediaListEntry"))
	}

	private suspend fun removeAniList(entry: CatalogTrackingEntry) {
		val id = checkNotNull(entry.entryId) { "AniList media-list entry id is missing" }
		aniListRequest("mutation { DeleteMediaListEntry(id: $id) { deleted } }")
	}

	private suspend fun aniListRequest(query: String): JSONObject {
		val body = JSONObject().put("query", query).toString().toRequestBody(JSON_MEDIA_TYPE)
		val response = aniListHttp.newCall(Request.Builder().url(ANILIST_GRAPHQL).post(body).build()).await()
		val root = response.requireAccountSuccess(ScrobblerService.ANILIST).parseJson()
		root.throwProviderErrors(ScrobblerService.ANILIST)
		return root
	}

	private fun aniListEntry(json: JSONObject): CatalogTrackingEntry = CatalogTrackingEntry(
		service = ScrobblerService.ANILIST,
		targetId = json.getLong("mediaId"),
		entryId = json.optLong("id", 0L).takeIf { it > 0L },
		status = catalogTrackingStatus(ScrobblerService.ANILIST, json.optionalString("status")),
		chapter = json.optInt("progress", 0).coerceAtLeast(0),
		rating = (json.optDouble("score", 0.0).toFloat() / 100f).coerceIn(0f, 1f),
	)

	private suspend fun readMAL(key: TrackerCatalogKey): CatalogTrackingEntry? {
		val request = Request.Builder()
			.url("$MAL_API/manga/${key.remoteId}?fields=my_list_status")
			.get()
			.build()
		val root = malHttp.newCall(request).await().requireAccountSuccess(ScrobblerService.MAL).parseJson()
		return root.optJSONObject("my_list_status")?.let { malEntry(key.remoteId.toLong(), it) }
	}

	private suspend fun saveMAL(key: TrackerCatalogKey, draft: CatalogTrackingDraft): CatalogTrackingEntry {
		val status = checkNotNull(catalogTrackingStatusRaw(ScrobblerService.MAL, draft.status))
		val body = FormBody.Builder()
			.add("status", status)
			.add("num_chapters_read", draft.chapter.toString())
			.add("score", (draft.rating * 10f).roundToInt().coerceIn(0, 10).toString())
			.build()
		val request = Request.Builder()
			.url("$MAL_API/manga/${key.remoteId}/my_list_status")
			.put(body)
			.build()
		val json = malHttp.newCall(request).await().requireAccountSuccess(ScrobblerService.MAL).parseJson()
		return malEntry(key.remoteId.toLong(), json)
	}

	private suspend fun removeMAL(key: TrackerCatalogKey) {
		val request = Request.Builder().url("$MAL_API/manga/${key.remoteId}/my_list_status").delete().build()
		malHttp.newCall(request).await().requireAccountSuccess(ScrobblerService.MAL).close()
	}

	private fun malEntry(targetId: Long, json: JSONObject): CatalogTrackingEntry = CatalogTrackingEntry(
		service = ScrobblerService.MAL,
		targetId = targetId,
		entryId = targetId,
		status = catalogTrackingStatus(ScrobblerService.MAL, json.optionalString("status")),
		chapter = json.optInt("num_chapters_read", 0).coerceAtLeast(0),
		rating = (json.optDouble("score", 0.0).toFloat() / 10f).coerceIn(0f, 1f),
	)

	private suspend fun readKitsu(key: TrackerCatalogKey): CatalogTrackingEntry? {
		val user = repositories[ScrobblerService.KITSU].cachedUser ?: repositories[ScrobblerService.KITSU].loadUser()
		val url = "$KITSU_API/library-entries".toHttpUrl().newBuilder()
			.addQueryParameter("filter[manga_id]", key.remoteId)
			.addQueryParameter("filter[userId]", user.id.toString())
			.addQueryParameter("include", "manga")
			.build()
		val root = kitsuHttp.newCall(Request.Builder().url(url).get().build()).await()
			.requireAccountSuccess(ScrobblerService.KITSU).parseJson().throwProviderErrors(ScrobblerService.KITSU)
		return root.optJSONArray("data")?.optJSONObject(0)?.let(::kitsuEntry)
	}

	private suspend fun saveKitsu(
		key: TrackerCatalogKey,
		draft: CatalogTrackingDraft,
		existing: CatalogTrackingEntry?,
	): CatalogTrackingEntry {
		val status = checkNotNull(catalogTrackingStatusRaw(ScrobblerService.KITSU, draft.status))
		val attrs = JSONObject().put("status", status).put("progress", draft.chapter)
		attrs.put(
			"ratingTwenty",
			if (draft.rating <= 0f) JSONObject.NULL else (draft.rating * 20f).roundToInt().coerceIn(2, 20),
		)
		val data = JSONObject().put("type", "libraryEntries").put("attributes", attrs)
		val builder = Request.Builder()
		if (existing?.entryId != null) {
			data.put("id", existing.entryId.toString())
			builder.url("$KITSU_API/library-entries/${existing.entryId}?include=manga")
				.patch(JSONObject().put("data", data).toString().toRequestBody(KITSU_MEDIA_TYPE))
		} else {
			val user = repositories[ScrobblerService.KITSU].cachedUser ?: repositories[ScrobblerService.KITSU].loadUser()
			data.put(
				"relationships",
				JSONObject()
					.put("manga", JSONObject().put("data", JSONObject().put("type", "manga").put("id", key.remoteId)))
					.put("user", JSONObject().put("data", JSONObject().put("type", "users").put("id", user.id.toString()))),
			)
			builder.url("$KITSU_API/library-entries?include=manga")
				.post(JSONObject().put("data", data).toString().toRequestBody(KITSU_MEDIA_TYPE))
		}
		val root = kitsuHttp.newCall(builder.build()).await().requireAccountSuccess(ScrobblerService.KITSU)
			.parseJson().throwProviderErrors(ScrobblerService.KITSU)
		return kitsuEntry(root.getJSONObject("data"))
	}

	private suspend fun removeKitsu(entry: CatalogTrackingEntry) {
		val id = checkNotNull(entry.entryId) { "Kitsu library-entry id is missing" }
		val request = Request.Builder().url("$KITSU_API/library-entries/$id").delete().build()
		kitsuHttp.newCall(request).await().requireAccountSuccess(ScrobblerService.KITSU).close()
	}

	private fun kitsuEntry(json: JSONObject): CatalogTrackingEntry {
		val attrs = json.getJSONObject("attributes")
		val target = json.getJSONObject("relationships").getJSONObject("manga").getJSONObject("data").valueAsLong("id")
		return CatalogTrackingEntry(
			service = ScrobblerService.KITSU,
			targetId = target,
			entryId = json.valueAsLong("id"),
			status = catalogTrackingStatus(ScrobblerService.KITSU, attrs.optionalString("status")),
			chapter = attrs.optInt("progress", 0).coerceAtLeast(0),
			rating = (attrs.optDouble("ratingTwenty", 0.0).toFloat() / 20f).coerceIn(0f, 1f),
		)
	}

	private suspend fun readMangaBaka(key: TrackerCatalogKey): CatalogTrackingEntry? {
		val response = mangaBakaHttp.newCall(
			Request.Builder().url("$MANGABAKA_API/my/library/${key.remoteId}").get().build(),
		).await()
		if (response.code == HttpURLConnection.HTTP_NOT_FOUND) {
			response.close()
			return null
		}
		val data = response.requireAccountSuccess(ScrobblerService.MANGABAKA).parseJson().getJSONObject("data")
		return mangaBakaEntry(key.remoteId.toLong(), data)
	}

	private suspend fun saveMangaBaka(
		key: TrackerCatalogKey,
		draft: CatalogTrackingDraft,
		existing: CatalogTrackingEntry?,
	): CatalogTrackingEntry {
		val status = checkNotNull(catalogTrackingStatusRaw(ScrobblerService.MANGABAKA, draft.status))
		val json = JSONObject()
			.put("state", status)
			.put("progress_chapter", draft.chapter.takeIf { it > 0 } ?: JSONObject.NULL)
			.put("rating", (draft.rating * 100f).roundToInt().coerceIn(0, 100).takeIf { it > 0 } ?: JSONObject.NULL)
		val body = json.toString().toRequestBody(JSON_MEDIA_TYPE)
		val builder = Request.Builder().url("$MANGABAKA_API/my/library/${key.remoteId}")
		if (existing == null) builder.post(body) else builder.patch(body)
		mangaBakaHttp.newCall(builder.build()).await().requireAccountSuccess(ScrobblerService.MANGABAKA).close()
		return checkNotNull(readMangaBaka(key)) { "MangaBaka did not return the saved library entry" }
	}

	private suspend fun removeMangaBaka(key: TrackerCatalogKey) {
		val request = Request.Builder().url("$MANGABAKA_API/my/library/${key.remoteId}").delete().build()
		mangaBakaHttp.newCall(request).await().requireAccountSuccess(ScrobblerService.MANGABAKA).close()
	}

	private fun mangaBakaEntry(targetId: Long, json: JSONObject): CatalogTrackingEntry = CatalogTrackingEntry(
		service = ScrobblerService.MANGABAKA,
		targetId = targetId,
		entryId = targetId,
		status = catalogTrackingStatus(ScrobblerService.MANGABAKA, json.optionalString("state")),
		chapter = json.optDouble("progress_chapter", 0.0).takeUnless { it.isNaN() }?.toInt()?.coerceAtLeast(0) ?: 0,
		rating = (json.optInt("rating", 0).toFloat() / 100f).coerceIn(0f, 1f),
	)

	private fun ScrobblingEntity.toCatalogTrackingEntry(service: ScrobblerService) = CatalogTrackingEntry(
		service = service,
		targetId = targetId,
		entryId = when (service) {
			ScrobblerService.MAL, ScrobblerService.MANGABAKA -> targetId
			else -> null
		},
		status = catalogTrackingStatus(service, status),
		chapter = chapter.coerceAtLeast(0),
		rating = rating.coerceIn(0f, 1f),
	)

	private fun remember(service: ScrobblerService, targetId: Long, entry: CatalogTrackingEntry?) {
		synchronized(lock) {
			remoteState.getOrPut(service) { LinkedHashMap() }[targetId] = entry
		}
	}

	private fun clearSession(service: ScrobblerService) {
		synchronized(lock) { remoteState.remove(service) }
	}

	private fun requireSupported(service: ScrobblerService) {
		require(catalogSupportedTrackingStatuses(service).isNotEmpty())
	}

	private fun JSONObject.throwProviderErrors(service: ScrobblerService): JSONObject {
		val errors = optJSONArray("errors")
		if (errors != null && errors.length() > 0) {
			val first = errors.optJSONObject(0)
			val status = first?.optString("status")?.toIntOrNull() ?: first?.optInt("status", 0) ?: 0
			if (status == HttpURLConnection.HTTP_UNAUTHORIZED || status == HttpURLConnection.HTTP_FORBIDDEN) {
				throw CatalogAccountUnauthorizedException(service)
			}
			throw IOException(first?.optionalString("message")
				?: first?.optionalString("detail")
				?: first?.optionalString("title")
				?: "Tracker provider request failed")
		}
		return this
	}

	private fun JSONObject.valueAsLong(name: String): Long = when (val value = opt(name)) {
		is Number -> value.toLong()
		is String -> value.toLong()
		else -> error("Missing numeric $name")
	}

	private fun JSONObject.optionalString(name: String): String? = optString(name).takeIf(String::isNotBlank)
}

private fun Response.requireAccountSuccess(service: ScrobblerService): Response {
	if (code == HttpURLConnection.HTTP_UNAUTHORIZED || code == HttpURLConnection.HTTP_FORBIDDEN) {
		close()
		throw CatalogAccountUnauthorizedException(service)
	}
	if (!isSuccessful) {
		val status = code
		close()
		throw IOException("$service request failed with HTTP $status")
	}
	return this
}

internal fun catalogTrackingStatus(service: ScrobblerService, raw: String?): ScrobblingStatus? {
	val normalized = raw?.trim()?.lowercase(Locale.ROOT) ?: return null
	return when (service) {
		ScrobblerService.ANILIST -> when (normalized) {
			"planning" -> ScrobblingStatus.PLANNED
			"current" -> ScrobblingStatus.READING
			"repeating" -> ScrobblingStatus.RE_READING
			"completed" -> ScrobblingStatus.COMPLETED
			"paused" -> ScrobblingStatus.ON_HOLD
			"dropped" -> ScrobblingStatus.DROPPED
			else -> null
		}
		ScrobblerService.MAL -> when (normalized) {
			"plan_to_read" -> ScrobblingStatus.PLANNED
			"reading" -> ScrobblingStatus.READING
			"completed" -> ScrobblingStatus.COMPLETED
			"on_hold" -> ScrobblingStatus.ON_HOLD
			"dropped" -> ScrobblingStatus.DROPPED
			else -> null
		}
		ScrobblerService.KITSU -> when (normalized) {
			"planned" -> ScrobblingStatus.PLANNED
			"current" -> ScrobblingStatus.READING
			"completed" -> ScrobblingStatus.COMPLETED
			"on_hold" -> ScrobblingStatus.ON_HOLD
			"dropped" -> ScrobblingStatus.DROPPED
			else -> null
		}
		ScrobblerService.MANGABAKA -> when (normalized) {
			"plan_to_read" -> ScrobblingStatus.PLANNED
			"reading" -> ScrobblingStatus.READING
			"rereading" -> ScrobblingStatus.RE_READING
			"completed" -> ScrobblingStatus.COMPLETED
			"paused" -> ScrobblingStatus.ON_HOLD
			"dropped" -> ScrobblingStatus.DROPPED
			else -> null
		}
		else -> null
	}
}

internal fun catalogTrackingStatusRaw(service: ScrobblerService, status: ScrobblingStatus): String? = when (service) {
	ScrobblerService.ANILIST -> when (status) {
		ScrobblingStatus.PLANNED -> "PLANNING"
		ScrobblingStatus.READING -> "CURRENT"
		ScrobblingStatus.RE_READING -> "REPEATING"
		ScrobblingStatus.COMPLETED -> "COMPLETED"
		ScrobblingStatus.ON_HOLD -> "PAUSED"
		ScrobblingStatus.DROPPED -> "DROPPED"
	}
	ScrobblerService.MAL -> when (status) {
		ScrobblingStatus.PLANNED -> "plan_to_read"
		ScrobblingStatus.READING -> "reading"
		ScrobblingStatus.COMPLETED -> "completed"
		ScrobblingStatus.ON_HOLD -> "on_hold"
		ScrobblingStatus.DROPPED -> "dropped"
		ScrobblingStatus.RE_READING -> null
	}
	ScrobblerService.KITSU -> when (status) {
		ScrobblingStatus.PLANNED -> "planned"
		ScrobblingStatus.READING -> "current"
		ScrobblingStatus.COMPLETED -> "completed"
		ScrobblingStatus.ON_HOLD -> "on_hold"
		ScrobblingStatus.DROPPED -> "dropped"
		ScrobblingStatus.RE_READING -> null
	}
	ScrobblerService.MANGABAKA -> when (status) {
		ScrobblingStatus.PLANNED -> "plan_to_read"
		ScrobblingStatus.READING -> "reading"
		ScrobblingStatus.RE_READING -> "rereading"
		ScrobblingStatus.COMPLETED -> "completed"
		ScrobblingStatus.ON_HOLD -> "paused"
		ScrobblingStatus.DROPPED -> "dropped"
	}
	else -> null
}

internal fun catalogAccountProfileUrl(user: ScrobblerUser): String? = when (user.service) {
	ScrobblerService.ANILIST -> "https://anilist.co/user/${user.nickname.pathSegment()}"
	ScrobblerService.MAL -> "https://myanimelist.net/profile/${user.nickname.pathSegment()}"
	ScrobblerService.KITSU -> "https://kitsu.app/users/${user.id}"
	ScrobblerService.MANGABAKA -> "https://mangabaka.org/my/settings/profile"
	else -> null
}

private fun String.pathSegment(): String = URLEncoder.encode(this, Charsets.UTF_8.name()).replace("+", "%20")
