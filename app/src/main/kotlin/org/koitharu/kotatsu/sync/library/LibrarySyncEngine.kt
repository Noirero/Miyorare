package org.koitharu.kotatsu.sync.library

import androidx.room.withTransaction
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.toMangaChapters
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.favourites.data.FavouriteEntity
import org.koitharu.kotatsu.history.data.HistoryEntity
import org.koitharu.kotatsu.scrobbling.common.domain.findRemoteProgressIndex

@Singleton
class LibrarySyncEngine
@Inject
constructor(
	private val db: MangaDatabase,
	private val registry: LibrarySyncRegistry,
	private val state: LibrarySyncStateStore,
	private val settings: AppSettings,
) {
	private val locks = LibrarySyncServiceId.entries.associateWith { Mutex() }

	suspend fun <T> exclusive(id: LibrarySyncServiceId, block: suspend () -> T): T =
		locks.getValue(id).withLock { block() }

	suspend fun login(id: LibrarySyncServiceId, credentials: LibrarySyncCredentials) =
		withContext(Dispatchers.IO) {
			exclusive(id) {
				// Clear the old account before saving a new session. A process death between
				// credential storage and database cleanup must never expose old mappings to
				// another account. A failed sign-in consequently leaves this service signed out.
				registry[id].logout()
				db.withTransaction {
					db.getLibrarySyncDao().clearMappings(id.name)
					db.getLibrarySyncDao().clearEntries(id.name)
				}
				state.clear(id)
				registry[id].login(credentials)
			}
		}

	suspend fun logout(id: LibrarySyncServiceId) =
		withContext(Dispatchers.IO) {
			exclusive(id) {
				registry[id].logout()
				db.withTransaction {
					db.getLibrarySyncDao().clearMappings(id.name)
					db.getLibrarySyncDao().clearEntries(id.name)
				}
				state.clear(id)
			}
		}

	suspend fun sync(id: LibrarySyncServiceId) =
		withContext(Dispatchers.IO) {
			exclusive(id) {
				val service = registry[id]
				if (
					service.connectionStatus() == LibrarySyncConnectionStatus.DISCONNECTED ||
						service is LibrarySyncBlockedService
				)
					return@exclusive
				val directions = state.directions(id)
				if (directions.isEmpty() || settings.isIncognitoModeEnabled) return@exclusive
				state.setSyncing(id, true)
				try {
					val dao = db.getLibrarySyncDao()
					// Push also reads the remote timestamps to implement LWW without overwriting
					// newer data.
					val remote = service.pullLibrary()
					val local = localEntries(id)
					val winners = LibrarySyncMerge.lastWriteWins(local, remote)
					val localById = local.associateBy { it.externalId }
					val remoteById = remote.associateBy { it.externalId }
					if (LibrarySyncDirection.PULL in directions) {
						db.withTransaction {
							val mappings = dao.mappings(id.name).associateBy { it.externalId }
							for (entry in winners) {
								val mapping = mappings[entry.externalId]
								if (mapping != null && remoteById[entry.externalId] == entry)
									applyProgress(entry, mapping.localMangaId)
							}
							// Unmapped records stay in the inbox until the user selects a source
							// manga and confirms.
							dao.put(winners.map { it.toEntity() })
						}
					}
					if (LibrarySyncDirection.PUSH in directions) {
						val writes = winners.filter { entry ->
							entry.externalId in localById &&
								entry == localById[entry.externalId] &&
								remoteById[entry.externalId]?.let {
									it.progress != entry.progress || it.status != entry.status
								} != false
						}
						for (batch in writes.chunked(20)) {
							service.pushLibrary(batch)
							// Checkpoint server timestamps in bounded batches for Worker retries.
							val ids = batch.mapTo(HashSet()) { it.externalId }
							val updated = service.pullLibrary().filter { it.externalId in ids }
							dao.put(updated.map { it.toEntity() })
						}
					}
					state.markSynced(id)
					state.setError(id, null)
				} catch (e: CancellationException) {
					throw e
				} catch (e: Exception) {
					state.setError(
						id,
						when (e) {
							is LibrarySyncAuthException -> "Sign in again to continue library sync"
							is LibrarySyncHttpException -> "Library API HTTP ${e.code}"
							is javax.net.ssl.SSLException ->
								"Secure connection failed; check the network and try again"
							is java.io.IOException -> "Network error; library sync will retry"
							else -> "Library sync failed (${e.javaClass.simpleName})"
						},
					)
					throw e
				} finally {
					state.setSyncing(id, false)
				}
			}
		}

	suspend fun localEntries(id: LibrarySyncServiceId): List<SyncEntry> {
		val snapshots = db.getLibrarySyncDao().entries(id.name).associateBy { it.externalId }
		val favourites = db.getFavouritesDao().findAll().groupBy { it.manga.id }
		val mappings = db.getLibrarySyncDao().mappings(id.name)
		val localIds = mappings.map { it.localMangaId }.filter { it in favourites }
		val histories =
			localIds
				.chunked(500)
				.flatMap { db.getHistoryDao().findByIds(it) }
				.associateBy { it.mangaId }
		val allChapters =
			localIds.chunked(500).flatMap { db.getChaptersDao().findAll(it) }.groupBy { it.mangaId }
		return mappings.mapNotNull { mapping ->
			val favourite =
				favourites[mapping.localMangaId]?.firstOrNull() ?: return@mapNotNull null
			if (settings.isIncognitoModeEnabled(favourite.manga.isNsfw)) return@mapNotNull null
			val history = histories[mapping.localMangaId]
			val snapshot = snapshots[mapping.externalId]
			val chapters = allChapters[mapping.localMangaId].orEmpty()
			val current = chapters.firstOrNull { it.chapterId == history?.chapterId }
			val branch = chapters.filter { it.branch == current?.branch }.sortedBy { it.index }
			val currentProgress =
				current?.number?.takeIf { it > 0 }?.toInt()
					?: current?.let { branch.indexOf(it).takeIf { index -> index >= 0 }?.plus(1) }
			val historyAt = history?.updatedAt ?: 0
			val newerHistory = historyAt > (snapshot?.updatedAt ?: 0)
			val progress =
				if (newerHistory) currentProgress ?: snapshot?.progress ?: 0
				else snapshot?.progress ?: currentProgress ?: 0
			val updatedAt = maxOf(historyAt, snapshot?.updatedAt ?: favourite.favourite.createdAt)
			val status =
				snapshot?.status ?: if (progress > 0) currentStatus(id) else plannedStatus(id)
			SyncEntry(
				id,
				mapping.externalId,
				mapping.localMangaId,
				favourite.manga.title,
				progress,
				status,
				Instant.ofEpochMilli(updatedAt),
				snapshot?.remoteEntryId,
			)
		}
	}

	private suspend fun applyProgress(entry: SyncEntry, localId: Long) {
		// Do not touch Private-only content or resurrect removed favourites in background.
		val favourite = db.getFavouritesDao().findAllRaw(localId).any { it.deletedAt == 0L }
		if (!favourite) return
		val manga = db.getMangaDao().find(localId)?.manga ?: return
		if (settings.isIncognitoModeEnabled(manga.isNsfw)) return
		val chapters = db.getChaptersDao().findAll(localId).sortedBy { it.index }
		if (chapters.isEmpty() || chapters.map { it.branch }.distinct().size != 1) return
		val targetIndex =
			if (entry.progress == 0) 0
			else findRemoteProgressIndex(chapters.toMangaChapters(), entry.progress)
		if (targetIndex !in chapters.indices) return
		val target = chapters[targetIndex]
		val old = db.getHistoryDao().findIncludingDeleted(localId)
		if (
			old?.deletedAt?.let { it != 0L } == true ||
				(old?.updatedAt ?: 0) > entry.updatedAt.toEpochMilli()
		)
			return
		val index = chapters.indexOf(target)
		db.getHistoryDao()
			.upsertForSync(
				HistoryEntity(
					localId,
					old?.createdAt ?: entry.updatedAt.toEpochMilli(),
					entry.updatedAt.toEpochMilli(),
					target.chapterId,
					old?.page?.takeIf { old.chapterId == target.chapterId } ?: 0,
					old?.scroll?.takeIf { old.chapterId == target.chapterId } ?: 0f,
					if (entry.progress == 0) 0f else (index + 1f) / chapters.size,
					0L,
					chapters.size,
				)
			)
	}

	/**
	 * Call only after UI confirmation of the exact remote title, local source manga and category.
	 */
	suspend fun confirmImport(entry: SyncEntry, localId: Long, categoryId: Int) =
		withContext(Dispatchers.IO) {
			exclusive(entry.service) {
				check(LibrarySyncDirection.PULL in state.directions(entry.service))
				db.withTransaction {
					val category = db.getFavouriteCategoriesDao().find(categoryId)
					require(category.space == 0 && category.deletedAt == 0L)
					val manga = requireNotNull(db.getMangaDao().find(localId)).manga
					require(!settings.isIncognitoModeEnabled(manga.isNsfw))
					val privateOnly =
						db.getPrivateFavouritesDao().findAllRaw(localId).any { it.deletedAt == 0L }
					require(!privateOnly) { "Private favourites cannot be imported" }
					db.getLibrarySyncDao().unlink(entry.service.name, localId)
					db.getLibrarySyncDao()
						.put(
							LibrarySyncMappingEntity(
								entry.service.name,
								entry.externalId,
								localId,
								System.currentTimeMillis(),
							)
						)
					if (db.getFavouritesDao().findAllRaw(localId).none { it.deletedAt == 0L }) {
						val now = System.currentTimeMillis()
						db.getFavouritesDao()
							.upsert(
								FavouriteEntity(localId, categoryId.toLong(), 0, false, now, 0L)
							)
					}
					db.getLibrarySyncDao().put(listOf(entry.toEntity()))
					applyProgress(entry, localId)
				}
			}
		}

	suspend fun linkForPush(id: LibrarySyncServiceId, localId: Long, externalId: String) =
		withContext(Dispatchers.IO) {
			exclusive(id) {
				val favourite = db.getFavouritesDao().findAll().first { it.manga.id == localId }
				require(!settings.isIncognitoModeEnabled(favourite.manga.isNsfw))
				require(externalId.toLongOrNull()?.let { it > 0 } == true)
				db.withTransaction {
					db.getLibrarySyncDao().unlink(id.name, localId)
					db.getLibrarySyncDao()
						.put(
							LibrarySyncMappingEntity(
								id.name,
								externalId,
								localId,
								System.currentTimeMillis(),
							)
						)
				}
			}
		}

	companion object {
		fun currentStatus(id: LibrarySyncServiceId): String =
			if (id == LibrarySyncServiceId.ANILIST) "CURRENT" else "current"

		fun plannedStatus(id: LibrarySyncServiceId): String =
			if (id == LibrarySyncServiceId.ANILIST) "PLANNING" else "planned"
	}
}
