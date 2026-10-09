package org.koitharu.kotatsu.scrobbling.mangaupdates.data

import androidx.room.withTransaction
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.scrobbling.common.data.*
import org.koitharu.kotatsu.scrobbling.common.domain.TrackerDetailsProvider
import org.koitharu.kotatsu.scrobbling.common.domain.model.*
import java.io.IOException
import kotlin.math.abs
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MangaUpdatesRepository internal constructor(
	private val api: MangaUpdatesApi,
	private val store: MangaUpdatesSessionStore,
	private val db: MangaDatabase,
	private val settings: AppSettings,
	private val scope: CoroutineScope,
	private val writes: MangaUpdatesWriteGate,
) : ScrobblerRepository, TrackerDetailsProvider {
	@Inject constructor(api: MangaUpdatesApi, store: MangaUpdatesSessionStore, db: MangaDatabase, settings: AppSettings) :
		this(api, store, db, settings, CoroutineScope(SupervisorJob() + Dispatchers.IO), MangaUpdatesWriteGate())
	private val operations = Mutex()
	private val login = Mutex()
	private val searches = Mutex()
	private var searchCursor: SearchCursor? = null
	private data class SearchCursor(val query: String, val type: ScrobblerMangaType, val generation: Long, val nextPage: Int, val ids: Set<Long>, val exhausted: Boolean)
	private val progress = MangaUpdatesProgressQueue(scope) { entry ->
		operations.withLock {
			api.ensureCurrent(entry.ticket)
			if (settings.isIncognitoModeEnabled(entry.nsfw)) return@withLock
			val entity = association(entry.mangaId, entry.ticket)
			if (entity.targetId != entry.targetId) throw CancellationException("MangaUpdates association changed")
			updateChapter(entity, entry.chapter, entry.ticket)
		}
	}
	val failedProgress: StateFlow<Set<Long>> = progress.failedManga
	init {
		scope.launch {
			store.generation.collect { generation ->
				progress.invalidateGeneration(generation)
				if (searchCursor?.generation != generation) searchCursor = null
			}
		}
	}
	override val oauthUrl = "https://www.mangaupdates.com/"
	override val cachedUser get() = store.snapshot()?.user
	override val isAuthorized get() = store.snapshot() != null
	override val detailsService = ScrobblerService.MANGAUPDATES
	override val detailsSessionGeneration get() = store.generation
	override val detailsCapabilities = setOf(TrackerContent.STAFF, TrackerContent.RECOMMENDATIONS)

	suspend fun signIn(username: String, password: String): ScrobblerUser = login.withLock {
		require(username.isNotBlank() && password.isNotEmpty()) { "Username and password are required" }
		store.snapshot()
		val generation = store.generation.value
		val response = api.request("PUT", "account/login", buildJsonObject { put("username", username.trim()); put("password", password) }).muObject()
		val token = mangaUpdatesLoginToken(response)
		val ticket = MangaUpdatesAuthTicket(token, generation, invalidateStored = false)
		val user = mangaUpdatesUser(api.request("GET", "account/profile", ticket = ticket).muObject())
		currentCoroutineContext().ensureActive()
		if (!store.save(token, user, generation)) throw CancellationException("MangaUpdates account changed")
		progress.reset()
		user
	}

	override suspend fun authorize(code: String?) { throw IOException("Use the MangaUpdates sign-in form") }
	override suspend fun loadUser(): ScrobblerUser {
		val ticket = api.sessionTicket()
		val user = mangaUpdatesUser(api.request("GET", "account/profile", ticket = ticket).muObject())
		if (user.id != store.snapshot()?.user?.id) {
			store.clear(ticket.generation)
			throw IOException("MangaUpdates account changed; sign in again")
		}
		// A profile refresh does not replace the session or reset its generation.
		return user
	}

	override fun logout() {
		val old = store.snapshot()
		store.clear()
		progress.reset()
		if (old != null) {
			val revocation = MangaUpdatesAuthTicket(old.token, store.generation.value, invalidateStored = false)
			scope.launch {
				try { api.request("POST", "account/logout", ticket = revocation) } catch (_: Exception) { /* Local logout is authoritative even offline. */ }
			}
		}
	}

	override suspend fun unregister(mangaId: Long) {
		progress.remove(mangaId)
		operations.withLock { db.getScrobblingDao().delete(detailsService.id, mangaId) }
	}

	override suspend fun findManga(query: String, offset: Int, type: ScrobblerMangaType): List<ScrobblerManga> = searches.withLock {
		require(offset in 0..10000)
		val ticket = api.sessionTicket()
		val previous = if (offset == 0) null else {
			searchCursor?.takeIf { it.query == query && it.type == type && it.generation == ticket.generation && it.ids.size == offset }
				?: throw IOException("Restart MangaUpdates search to resume pagination")
		}
		if (previous?.exhausted == true || previous?.nextPage?.let { it > 100 } == true) return@withLock emptyList()
		val page = previous?.nextPage ?: 1
		val body = buildJsonObject {
			put("search", query); put("stype", "title"); put("page", page); put("perpage", 100)
			if (type.isNovel) put("type", buildJsonArray { add("Novel") }) else put("filter_types", buildJsonArray { add("Novel") })
		}
		val root = api.request("POST", "series/search", body).muObject()
		api.ensureCurrent(ticket)
		val rows = root.array("results") ?: throw IOException("Invalid MangaUpdates search results")
		val results = rows.take(100).mapNotNull { edge ->
			val record = (edge as? JsonObject)?.obj("record") ?: return@mapNotNull null
			val id = record.id("series_id")?.toLong() ?: return@mapNotNull null
			val title = record.text("title") ?: return@mapNotNull null
			ScrobblerManga(id, title, null, record.muImage(), record.text("url") ?: "https://www.mangaupdates.com/series/$id", false)
		}.distinctBy { it.id }
		if (results.isEmpty() && rows.isNotEmpty()) throw IOException("Malformed MangaUpdates search records")
		val perPage = (root["per_page"] as? JsonPrimitive)?.intOrNull?.takeIf { it in 1..100 } ?: 100
		val total = (root["total_hits"] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 }
		searchCursor = SearchCursor(query, type, ticket.generation, page + 1, previous?.ids.orEmpty() + results.map { it.id }, rows.size < perPage || total?.let { page.toLong() * perPage >= it } == true)
		results
	}

	override suspend fun getMangaInfo(id: Long): ScrobblerMangaInfo {
		val root = series(id)
		val authors = root.array("authors").orEmpty().filterIsInstance<JsonObject>()
		return ScrobblerMangaInfo(id, root.text("title") ?: throw IOException("Missing MangaUpdates title"), root.muImage().orEmpty(),
			root.text("url") ?: "https://www.mangaupdates.com/series/$id", root.text("description").orEmpty(),
			0, // Latest release is not a verified total chapter count.
			authors.filter { it.text("type") == "Author" }.mapNotNull { it.text("name") }.distinct().joinToString().ifBlank { null },
			authors.filter { it.text("type") == "Artist" }.mapNotNull { it.text("name") }.distinct().joinToString().ifBlank { null })
	}

	override suspend fun createRate(mangaId: Long, scrobblerMangaId: Long): Boolean = operations.withLock {
		require(scrobblerMangaId > 0)
		val ticket = api.sessionTicket()
		eligible(mangaId, ticket)
		var existing = remoteState(scrobblerMangaId, ticket)
		var adopted = existing != null
		if (existing == null) {
			val list = defaultList("wish", ticket)
			val initial = MangaUpdatesRemoteState(scrobblerMangaId, list.id, list.type, 0, 0, 0)
			for (attempt in 0..1) {
				try {
					existing = writes.execute {
						eligible(mangaId, ticket)
						// Another client may have added the title while this request was paced.
						remoteState(scrobblerMangaId, ticket)?.also { adopted = true } ?: run {
							api.request("POST", "lists/series", initial.writeBody(), ticket)
							remoteState(scrobblerMangaId, ticket) ?: throw IOException("MangaUpdates entry was not confirmed")
						}
					}
					break
				} catch (e: IOException) {
					api.ensureCurrent(ticket)
					existing = remoteState(scrobblerMangaId, ticket)
					if (existing != null) { adopted = true; break }
					if (attempt == 1 || !retryable(e)) throw e
				}
			}
		}
		val state = existing ?: throw IOException("MangaUpdates entry was not confirmed")
		val rating = rating(state.seriesId, ticket)
		save(mangaId, state, rating, ticket, replace = true)
		progress.remove(mangaId)
		// Always adopt confirmed remote values, including a concurrent addition, without a reset write.
		adopted || state.chapter > 0 || state.volume > 0 || rating > 0f || state.type != "wish"
	}

	override suspend fun refreshRate(entity: ScrobblingEntity): ScrobblingEntity = operations.withLock {
		val ticket = api.sessionTicket()
		val current = association(entity.mangaId, ticket)
		if (current.targetId != entity.targetId || current.id != entity.id) throw CancellationException("MangaUpdates association changed")
		val state = remoteState(current.targetId, ticket) ?: throw MangaUpdatesHttpException(404)
		save(current.mangaId, state, rating(current.targetId, ticket), ticket)
	}

	override suspend fun updateRate(rateId: Int, mangaId: Long, chapter: Int) = operations.withLock {
		require(chapter >= 0)
		val ticket = api.sessionTicket()
		val entity = association(mangaId, ticket)
		require(entity.id == rateId)
		updateChapter(entity, chapter, ticket)
		Unit
	}

	private suspend fun updateChapter(entity: ScrobblingEntity, chapter: Int, ticket: MangaUpdatesAuthTicket) {
		val state = updateRemote(entity.mangaId, entity.targetId, ticket) { old ->
			val reading = if (old.type == "wish") {
				val lists = lists(ticket)
				if (lists.singleOrNull { it.id == old.listId }?.custom == false) lists.singleOrNull { it.type == "read" && !it.custom } else null
			} else null
			old.copy(chapter = maxOf(old.chapter, chapter), listId = reading?.id ?: old.listId, type = reading?.type ?: old.type)
		}
		save(entity.mangaId, state, rating(entity.targetId, ticket), ticket)
	}

	override suspend fun updateRate(rateId: Int, mangaId: Long, rating: Float, status: String?, comment: String?, setStartDate: Boolean) = operations.withLock {
		val ticket = api.sessionTicket()
		val entity = association(mangaId, ticket)
		require(entity.id == rateId)
		val changedStatus = status?.takeIf { it != entity.status }
		val newRating = mangaUpdatesRatingValue(rating)
		val changeRating = abs(rating - entity.rating) > 0.00001f
		val state = updateRemote(mangaId, entity.targetId, ticket) { old ->
			if (changedStatus == null || changedStatus == old.type) old else defaultList(changedStatus, ticket).let { old.copy(listId = it.id, type = it.type) }
		}
		if (changeRating) writeRating(mangaId, entity.targetId, newRating, ticket)
		// Comments and start dates are absent from this provider contract; never invent them.
		save(mangaId, state, rating(entity.targetId, ticket), ticket)
		Unit
	}

	suspend fun getVolume(mangaId: Long): Int {
		val ticket = api.sessionTicket()
		val entity = association(mangaId, ticket)
		return (remoteState(entity.targetId, ticket) ?: throw MangaUpdatesHttpException(404)).volume.also {
			if (association(mangaId, ticket).targetId != entity.targetId) throw CancellationException("MangaUpdates association changed")
		}
	}
	suspend fun updateVolume(mangaId: Long, volume: Int) = operations.withLock {
		require(volume >= 0)
		val ticket = api.sessionTicket()
		val entity = association(mangaId, ticket)
		val state = updateRemote(mangaId, entity.targetId, ticket) { it.copy(volume = volume) }
		save(mangaId, state, rating(entity.targetId, ticket), ticket)
		Unit
	}

	suspend fun enqueueProgress(mangaId: Long, chapter: Int, nsfw: Boolean) {
		require(chapter >= 0)
		if (!settings.isIncognitoModeEnabled(nsfw)) {
			val ticket = api.sessionTicket()
			val entity = association(mangaId, ticket)
			progress.enqueue(MangaUpdatesPendingProgress(mangaId, entity.targetId, chapter, nsfw, ticket))
		}
	}
	fun retryProgress(mangaId: Long) = progress.retry(mangaId)

	override suspend fun loadCharacters(target: TrackerTarget, page: TrackerPage): TrackerResult<TrackerPerson> = TrackerResult.Unsupported
	override suspend fun loadStaff(target: TrackerTarget, page: TrackerPage): TrackerResult<TrackerPerson> {
		validateDetails(target, page)
		val ticket = api.sessionTicket()
		val result = mangaUpdatesStaff(series(target.id.toLong()), target)
		val people = when (result) { is TrackerResult.Success -> result.items; is TrackerResult.Partial -> result.items; else -> return result }
		var failed = false
		val portraits = mutableMapOf<String, String?>()
		for (id in people.mapNotNull { it.id }.distinct().take(10)) {
			api.ensureCurrent(ticket)
			try {
				val author = api.request("GET", "authors/$id").muObject()
				if (author.id() != id) throw IOException("MangaUpdates returned a different author")
				portraits[id] = author.muImage()
			} catch (e: CancellationException) { throw e } catch (_: IOException) { failed = true }
		}
		api.ensureCurrent(ticket)
		val items = people.map { it.copy(image = portraits[it.id]) }
		return when {
			result is TrackerResult.Partial -> result.copy(items = items, reasons = result.reasons + if (failed) setOf(TrackerPartialReason.PROVIDER_ERROR) else emptySet())
			failed -> TrackerResult.Partial(items, setOf(TrackerPartialReason.PROVIDER_ERROR))
			else -> TrackerResult.Success(items)
		}
	}
	override suspend fun loadRecommendations(target: TrackerTarget, page: TrackerPage): TrackerResult<TrackerRecommendation> {
		validateDetails(target, page)
		val ticket = api.sessionTicket()
		return mangaUpdatesRecommendations(series(target.id.toLong()), target).also { api.ensureCurrent(ticket) }
	}

	private fun validateDetails(target: TrackerTarget, page: TrackerPage) {
		require(target.service == detailsService && page.number == 1 && page.url == null)
	}
	private suspend fun series(id: Long): JsonObject {
		require(id > 0)
		return api.request("GET", "series/$id").muObject().also { if (it.id("series_id") != id.toString()) throw IOException("MangaUpdates returned a different series") }
	}
	private suspend fun lists(ticket: MangaUpdatesAuthTicket) = mangaUpdatesLists(api.request("GET", "lists", ticket = ticket)).also { require(it.size <= 1000) }
	private suspend fun defaultList(type: String, ticket: MangaUpdatesAuthTicket): MangaUpdatesList =
		lists(ticket).singleOrNull { it.type == type && !it.custom } ?: throw IOException("MangaUpdates default list is unavailable")
	private suspend fun remoteState(id: Long, ticket: MangaUpdatesAuthTicket): MangaUpdatesRemoteState? = try {
		mangaUpdatesRemoteState(api.request("GET", "lists/series/$id", ticket = ticket).muObject(), id)
	} catch (e: MangaUpdatesHttpException) { if (e.code == 404) null else throw e }
	private suspend fun rating(id: Long, ticket: MangaUpdatesAuthTicket): Float = try {
		mangaUpdatesRating(api.request("GET", "series/$id/rating", ticket = ticket).muObject())
	} catch (e: MangaUpdatesHttpException) { if (e.code == 404) 0f else throw e }

	private suspend fun updateRemote(mangaId: Long, targetId: Long, ticket: MangaUpdatesAuthTicket, change: suspend (MangaUpdatesRemoteState) -> MangaUpdatesRemoteState): MangaUpdatesRemoteState {
		for (attempt in 0..1) try {
			return writes.execute {
				eligible(mangaId, ticket)
				val entity = association(mangaId, ticket)
				if (entity.targetId != targetId) throw CancellationException("MangaUpdates association changed")
				val old = remoteState(targetId, ticket) ?: throw MangaUpdatesHttpException(404)
				val next = change(old)
				if (next == old) old else {
					eligible(mangaId, ticket)
					api.request("POST", "lists/series/update", next.writeBody(), ticket)
					val confirmed = remoteState(targetId, ticket) ?: throw IOException("MangaUpdates update was not confirmed")
					if (change(confirmed) != confirmed) throw IOException("MangaUpdates update was not confirmed")
					confirmed
				}
			}
		} catch (e: IOException) { api.ensureCurrent(ticket); if (attempt == 1 || !retryable(e)) throw e }
		throw IOException("MangaUpdates update failed")
	}
	private suspend fun writeRating(mangaId: Long, id: Long, value: Int, ticket: MangaUpdatesAuthTicket) {
		for (attempt in 0..1) try {
			eligible(mangaId, ticket)
			if (abs(rating(id, ticket) - value / 10f) < 0.00001f) return
			if (value == 0) api.request("DELETE", "series/$id/rating", ticket = ticket)
			else api.request("PUT", "series/$id/rating", buildJsonObject { put("rating", value) }, ticket)
			if (abs(rating(id, ticket) - value / 10f) >= 0.00001f) throw IOException("MangaUpdates rating was not confirmed")
			return
		} catch (e: IOException) { api.ensureCurrent(ticket); if (attempt == 1 || !retryable(e)) throw e }
	}
	private fun retryable(e: IOException) = e !is MangaUpdatesHttpException || e.code == 412 || e.code == 429 || e.code in 500..599
	private suspend fun eligible(mangaId: Long, ticket: MangaUpdatesAuthTicket) {
		api.ensureCurrent(ticket)
		val manga = db.getMangaDao().find(mangaId)?.manga ?: throw CancellationException("MangaUpdates local manga is unavailable")
		if (settings.isIncognitoModeEnabled(manga.isNsfw) || db.getPrivateFavouritesDao().isPrivateOnly(mangaId)) throw CancellationException("MangaUpdates tracking is suppressed")
		api.ensureCurrent(ticket)
	}
	private suspend fun association(mangaId: Long, ticket: MangaUpdatesAuthTicket): ScrobblingEntity {
		eligible(mangaId, ticket)
		return db.getScrobblingDao().find(detailsService.id, mangaId)?.takeIf { it.targetId > 0 } ?: throw CancellationException("MangaUpdates association is unavailable")
	}
	private suspend fun save(mangaId: Long, state: MangaUpdatesRemoteState, rating: Float, ticket: MangaUpdatesAuthTicket, replace: Boolean = false): ScrobblingEntity = db.withTransaction {
		eligible(mangaId, ticket)
		if (!replace && association(mangaId, ticket).targetId != state.seriesId) throw CancellationException("MangaUpdates association changed")
		// No provider rate id exists. The existing compound key includes service and local manga id.
		val entity = ScrobblingEntity(detailsService.id, 0, mangaId, state.seriesId, state.type, state.chapter, null, rating)
		if (replace) db.getScrobblingDao().delete(detailsService.id, mangaId)
		db.getScrobblingDao().upsert(entity)
		eligible(mangaId, ticket)
		entity
	}
}
