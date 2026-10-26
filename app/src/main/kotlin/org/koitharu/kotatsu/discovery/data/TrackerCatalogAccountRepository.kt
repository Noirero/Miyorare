package org.koitharu.kotatsu.discovery.data

import kotlinx.coroutines.flow.first
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.discovery.domain.CatalogAccountState
import org.koitharu.kotatsu.discovery.domain.CatalogTrackingEntry
import org.koitharu.kotatsu.discovery.domain.TrackerCatalogKey
import org.koitharu.kotatsu.parsers.util.await
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.scrobbling.common.data.ScrobblingEntity
import org.koitharu.kotatsu.scrobbling.common.domain.ScrobblerRepositoryMap
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerType
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerUser
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblingStatus
import java.io.IOException
import java.net.URLEncoder
import java.util.EnumMap
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

private const val ANILIST_GRAPHQL = "https://graphql.anilist.co"
private const val MAL_API = "https://api.myanimelist.net/v2"
private const val KITSU_API = "https://kitsu.app/api/edge"
private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
private val KITSU_MEDIA_TYPE = "application/vnd.api+json".toMediaType()

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
			else -> error("Unsupported Tracker Home account service ${key.service}")
		}
		remember(key.service, key.remoteId.toLong(), entry)
		return entry
	}

	suspend fun addTracking(key: TrackerCatalogKey): CatalogTrackingEntry {
		requireSupported(key.service)
		check(repositories[key.service].isAuthorized) { "${key.service} is not authorized" }
		tracking(key)?.let { return it }
		val entry = when (key.service) {
			ScrobblerService.ANILIST -> addAniList(key)
			ScrobblerService.MAL -> addMAL(key)
			ScrobblerService.KITSU -> addKitsu(key)
			else -> error("Unsupported Tracker Home account service ${key.service}")
		}
		remember(key.service, key.remoteId.toLong(), entry)
		return entry
	}

	private suspend fun readAniList(key: TrackerCatalogKey): CatalogTrackingEntry? {
		val query = """
			query {
				Media(id: ${key.remoteId}, type: MANGA) {
					mediaListEntry { mediaId status progress score(format: POINT_100) }
				}
			}
		""".trimIndent()
		val root = aniListRequest(query)
		val media = root.getJSONObject("data").getJSONObject("Media")
		return media.optJSONObject("mediaListEntry")?.let(::aniListEntry)
	}

	private suspend fun addAniList(key: TrackerCatalogKey): CatalogTrackingEntry {
		val query = """
			mutation {
				SaveMediaListEntry(mediaId: ${key.remoteId}, status: PLANNING) {
					mediaId status progress score(format: POINT_100)
				}
			}
		""".trimIndent()
		return aniListEntry(aniListRequest(query).getJSONObject("data").getJSONObject("SaveMediaListEntry"))
	}

	private suspend fun aniListRequest(query: String): JSONObject {
		val body = JSONObject().put("query", query).toString().toRequestBody(JSON_MEDIA_TYPE)
		val root = aniListHttp.newCall(Request.Builder().url(ANILIST_GRAPHQL).post(body).build()).await().parseJson()
		root.throwProviderErrors()
		return root
	}

	private fun aniListEntry(json: JSONObject): CatalogTrackingEntry = CatalogTrackingEntry(
		service = ScrobblerService.ANILIST,
		targetId = json.getLong("mediaId"),
		status = catalogTrackingStatus(ScrobblerService.ANILIST, json.optionalString("status")),
		chapter = json.optInt("progress", 0).coerceAtLeast(0),
		rating = (json.optDouble("score", 0.0).toFloat() / 100f).coerceIn(0f, 1f),
	)

	private suspend fun readMAL(key: TrackerCatalogKey): CatalogTrackingEntry? {
		val request = Request.Builder()
			.url("$MAL_API/manga/${key.remoteId}?fields=my_list_status")
			.get()
			.build()
		val root = malHttp.newCall(request).await().parseJson()
		return root.optJSONObject("my_list_status")?.let { malEntry(key.remoteId.toLong(), it) }
	}

	private suspend fun addMAL(key: TrackerCatalogKey): CatalogTrackingEntry {
		val body = FormBody.Builder()
			.add("status", "plan_to_read")
			.add("score", "0")
			.build()
		val request = Request.Builder()
			.url("$MAL_API/manga/${key.remoteId}/my_list_status")
			.put(body)
			.build()
		return malEntry(key.remoteId.toLong(), malHttp.newCall(request).await().parseJson())
	}

	private fun malEntry(targetId: Long, json: JSONObject): CatalogTrackingEntry = CatalogTrackingEntry(
		service = ScrobblerService.MAL,
		targetId = targetId,
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
		val root = kitsuHttp.newCall(Request.Builder().url(url).get().build()).await().parseJson().throwProviderErrors()
		return root.optJSONArray("data")?.optJSONObject(0)?.let(::kitsuEntry)
	}

	private suspend fun addKitsu(key: TrackerCatalogKey): CatalogTrackingEntry {
		val user = repositories[ScrobblerService.KITSU].cachedUser ?: repositories[ScrobblerService.KITSU].loadUser()
		val payload = JSONObject()
			.put("data", JSONObject()
				.put("type", "libraryEntries")
				.put("attributes", JSONObject().put("status", "planned").put("progress", 0))
				.put("relationships", JSONObject()
					.put("manga", JSONObject().put("data", JSONObject().put("type", "manga").put("id", key.remoteId)))
					.put("user", JSONObject().put("data", JSONObject().put("type", "users").put("id", user.id.toString())))))
		val url = "$KITSU_API/library-entries".toHttpUrl().newBuilder()
			.addQueryParameter("include", "manga")
			.build()
		val request = Request.Builder()
			.url(url)
			.post(payload.toString().toRequestBody(KITSU_MEDIA_TYPE))
			.build()
		return kitsuEntry(kitsuHttp.newCall(request).await().parseJson().throwProviderErrors().getJSONObject("data"))
	}

	private fun kitsuEntry(json: JSONObject): CatalogTrackingEntry {
		val attrs = json.getJSONObject("attributes")
		val target = json.getJSONObject("relationships").getJSONObject("manga").getJSONObject("data").valueAsLong("id")
		return CatalogTrackingEntry(
			service = ScrobblerService.KITSU,
			targetId = target,
			status = catalogTrackingStatus(ScrobblerService.KITSU, attrs.optionalString("status")),
			chapter = attrs.optInt("progress", 0).coerceAtLeast(0),
			rating = (attrs.optDouble("ratingTwenty", 0.0).toFloat() / 20f).coerceIn(0f, 1f),
		)
	}

	private fun ScrobblingEntity.toCatalogTrackingEntry(service: ScrobblerService) = CatalogTrackingEntry(
		service = service,
		targetId = targetId,
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
		require(service == ScrobblerService.ANILIST || service == ScrobblerService.MAL || service == ScrobblerService.KITSU)
	}

	private fun JSONObject.throwProviderErrors(): JSONObject {
		val errors = optJSONArray("errors")
		if (errors != null && errors.length() > 0) {
			val first = errors.optJSONObject(0)
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
		else -> null
	}
}

internal fun catalogAccountProfileUrl(user: ScrobblerUser): String? = when (user.service) {
	ScrobblerService.ANILIST -> "https://anilist.co/user/${user.nickname.pathSegment()}"
	ScrobblerService.MAL -> "https://myanimelist.net/profile/${user.nickname.pathSegment()}"
	ScrobblerService.KITSU -> "https://kitsu.app/users/${user.id}"
	else -> null
}

private fun String.pathSegment(): String = URLEncoder.encode(this, Charsets.UTF_8.name()).replace("+", "%20")
