package org.koitharu.kotatsu.sync.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.MangaEntity
import org.koitharu.kotatsu.favourites.data.FavouriteCategoryEntity
import org.koitharu.kotatsu.sync.library.*

data class LibraryServiceUi(
	val id: LibrarySyncServiceId,
	val status: LibrarySyncConnectionStatus,
	val lastSync: Instant?,
	val reason: String? = null,
	val error: String? = null,
	val directions: Set<LibrarySyncDirection> = emptySet(),
	val pending: List<SyncEntry> = emptyList(),
	val linked: List<SyncEntry> = emptyList(),
	val running: Boolean = false,
)

@HiltViewModel
class LibrarySyncViewModel
@Inject
constructor(
	private val registry: LibrarySyncRegistry,
	private val state: LibrarySyncStateStore,
	private val engine: LibrarySyncEngine,
	private val scheduler: LibrarySyncScheduler,
	private val db: MangaDatabase,
	workManager: WorkManager,
) : ViewModel() {
	val services = MutableStateFlow<List<LibraryServiceUi>>(emptyList())
	val busy = MutableStateFlow(false)
	val message = MutableStateFlow<String?>(null)
	val candidates = MutableStateFlow<List<MangaEntity>>(emptyList())
	val favourites = MutableStateFlow<List<MangaEntity>>(emptyList())
	val categories = MutableStateFlow<List<FavouriteCategoryEntity>>(emptyList())
	val suggestedExternalId = MutableStateFlow<String?>(null)
	val lookupResult = MutableStateFlow<SyncEntry?>(null)
	private val running = LibrarySyncServiceId.entries.associateWith { false }.toMutableMap()
	private var searchJob: Job? = null
	private var mappingJob: Job? = null

	init {
		LibrarySyncServiceId.entries.forEach { id ->
			viewModelScope.launch {
				workManager
					.getWorkInfosForUniqueWorkFlow(LibrarySyncScheduler.workName(id))
					.collect { work ->
						running[id] = work.any {
							it.state == WorkInfo.State.RUNNING ||
								it.state == WorkInfo.State.ENQUEUED ||
								it.state == WorkInfo.State.BLOCKED
						}
						refreshNow()
					}
			}
		}
		refresh()
	}

	fun refresh() {
		viewModelScope.launch { refreshNow() }
	}

	private suspend fun refreshNow() {
		val runningSnapshot = running.toMap()
		val values =
			withContext(Dispatchers.IO) {
				registry.all().map { service ->
					val mappings =
						db.getLibrarySyncDao().mappings(service.id.name).associateBy {
							it.externalId
						}
					val linked =
						if (service is LibrarySyncBlockedService) emptyList()
						else engine.localEntries(service.id)
					LibraryServiceUi(
						service.id,
						service.connectionStatus(),
						service.lastSyncAt(),
						(service as? LibrarySyncBlockedService)?.reason,
						state.error(service.id),
						state.directions(service.id),
						db.getLibrarySyncDao()
							.entries(service.id.name)
							.filter {
								it.externalId !in mappings ||
									linked.none { local -> local.externalId == it.externalId }
							}
							.map { it.toEntry() },
						linked,
						runningSnapshot[service.id] == true,
					)
				}
			}
		services.value = values
		withContext(Dispatchers.IO) {
			categories.value = db.getFavouriteCategoriesDao().findAll()
			favourites.value = db.getFavouritesDao().findAll().map { it.manga }.distinctBy { it.id }
		}
	}

	private fun action(block: suspend () -> Unit) {
		if (busy.value) return
		busy.value = true
		message.value = null
		viewModelScope.launch {
			try {
				withContext(Dispatchers.IO) { block() }
				refreshNow()
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				message.value =
					when (e) {
						is LibrarySyncHttpException -> "Library API HTTP ${e.code}"
						is LibrarySyncAuthException -> "Sign in again to continue library sync"
						else -> "Operation failed (${e.javaClass.simpleName})"
					}
			} finally {
				busy.value = false
			}
		}
	}

	fun login(id: LibrarySyncServiceId, credentials: LibrarySyncCredentials) = action {
		engine.login(id, credentials)
		scheduler.schedule()
	}

	fun logout(id: LibrarySyncServiceId) = action {
		scheduler.cancel(id)
		engine.logout(id)
	}

	fun sync(id: LibrarySyncServiceId) {
		scheduler.manual(id)
		scheduler.schedule()
	}

	fun direction(id: LibrarySyncServiceId, direction: LibrarySyncDirection, enabled: Boolean) {
		state.setDirections(
			id,
			if (enabled) state.directions(id) + direction else state.directions(id) - direction,
		)
		scheduler.schedule()
		refresh()
	}

	fun search(query: String) {
		searchJob?.cancel()
		searchJob = viewModelScope.launch {
			val results =
				withContext(Dispatchers.IO) {
					db.getLibrarySyncDao().searchLocal("%${query.trim()}%")
				}
			candidates.value = results
		}
	}

	fun suggestTrackingMapping(id: LibrarySyncServiceId, localId: Long) {
		mappingJob?.cancel()
		suggestedExternalId.value = null
		mappingJob = viewModelScope.launch {
			val scrobbler =
				when (id) {
					LibrarySyncServiceId.ANILIST ->
						org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService.ANILIST
					LibrarySyncServiceId.KITSU ->
						org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService.KITSU
					else -> return@launch
				}
			suggestedExternalId.value =
				withContext(Dispatchers.IO) {
					db.getScrobblingDao().find(scrobbler.id, localId)?.targetId?.toString()
				}
		}
	}

	fun resetLookup() {
		lookupResult.value = null
	}

	fun lookup(id: LibrarySyncServiceId, externalId: String) = action {
		lookupResult.value =
			engine.exclusive(id) { (registry[id] as LibrarySyncCatalog).lookup(externalId.trim()) }
	}

	fun import(entry: SyncEntry, localId: Long, categoryId: Int) = action {
		engine.confirmImport(entry, localId, categoryId)
	}

	fun link(id: LibrarySyncServiceId, localId: Long, externalId: String) = action {
		engine.linkForPush(id, localId, externalId)
	}

	fun unlink(id: LibrarySyncServiceId, localId: Long) = action {
		engine.exclusive(id) { db.getLibrarySyncDao().unlink(id.name, localId) }
	}

	fun edit(entry: SyncEntry, progress: Int, status: String) = action {
		require(progress >= 0)
		engine.exclusive(entry.service) {
			db.getLibrarySyncDao()
				.put(
					listOf(
						entry
							.copy(progress = progress, status = status, updatedAt = Instant.now())
							.toEntity()
					)
				)
		}
	}
}
