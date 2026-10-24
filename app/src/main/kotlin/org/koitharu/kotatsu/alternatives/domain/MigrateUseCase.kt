package org.koitharu.kotatsu.alternatives.domain

import androidx.room.withTransaction
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.model.getPreferredBranch
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.prefs.ChapterReadOverridesMigrationStore
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.details.data.MangaNotesRepository
import org.koitharu.kotatsu.details.domain.ProgressUpdateUseCase
import org.koitharu.kotatsu.history.data.HistoryEntity
import org.koitharu.kotatsu.history.data.toMangaHistory
import org.koitharu.kotatsu.local.data.index.LocalMangaIndex
import org.koitharu.kotatsu.local.data.index.PreparedDownloadIdentityMove
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.reader.ui.config.MangaReaderProfileStore
import org.koitharu.kotatsu.scrobbling.common.data.ScrobblingDao
import org.koitharu.kotatsu.scrobbling.common.data.ScrobblingEntity
import org.koitharu.kotatsu.scrobbling.common.domain.Scrobbler
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblingStatus
import org.koitharu.kotatsu.tracker.data.TrackEntity
import javax.inject.Inject

class MigrateUseCase @Inject constructor(
	private val mangaRepositoryFactory: MangaRepository.Factory,
	private val mangaDataRepository: MangaDataRepository,
	private val database: MangaDatabase,
	private val progressUpdateUseCase: ProgressUpdateUseCase,
	private val mangaReaderProfileStore: MangaReaderProfileStore,
	private val mangaNotesRepository: MangaNotesRepository,
	private val chapterReadOverridesMigrationStore: ChapterReadOverridesMigrationStore,
	private val localMangaIndex: LocalMangaIndex,
	private val losslessMigrationTransfer: LosslessMigrationTransfer,
	private val scrobblers: Set<@JvmSuppressWildcards Scrobbler>,
) {

	suspend operator fun invoke(
		oldManga: Manga,
		newManga: Manga,
		migrateProgress: Boolean = true,
		lossless: Boolean = false,
		requireLibraryEntry: Boolean = false,
	) = transferMutex.withLock {
		// Serialize all consumers across instances: SharedPreferences preparation cannot join Room.
		// Only the new public Migration paths opt into the live library/privacy admission check.
		if (requireLibraryEntry) checkLibraryAdmission(oldManga.id, newManga.id)
		performMigration(oldManga, newManga, migrateProgress, lossless, requireLibraryEntry)
	}

	private suspend fun checkLibraryAdmission(oldId: Long, newId: Long) {
		if (!database.getMangaDao().isMigrationEntryVisible(oldId) || database.getPrivateFavouritesDao().isPrivateOnly(newId)) {
			throw SourceChangedException()
		}
	}

	private suspend fun performMigration(oldManga: Manga, newManga: Manga, migrateProgress: Boolean, lossless: Boolean, requireLibraryEntry: Boolean) {
		require(!lossless || migrateProgress) {
			"Lossless source replacement requires progress/state migration"
		}
		val oldDetails = if (migrateProgress && oldManga.chapters.isNullOrEmpty()) {
			runCatchingCancellable {
				mangaRepositoryFactory.create(oldManga.source).getDetails(oldManga)
			}.getOrDefault(oldManga)
		} else {
			oldManga
		}
		val newDetails = if (newManga.chapters.isNullOrEmpty()) {
			mangaRepositoryFactory.create(newManga.source).getDetails(newManga)
		} else {
			newManga
		}

		// The lossless plan is intentionally prepared before any user-state mutation. Ambiguous chapter
		// identity or incompatible destination metadata aborts the replacement rather than dropping one
		// side silently. Legacy callers keep the historical compatibility path below.
		val losslessPlan = if (lossless) {
			losslessMigrationTransfer.prepare(oldDetails, newDetails)
		} else {
			null
		}

		mangaDataRepository.storeManga(newDetails, replaceExisting = true)

		var preparedReaderProfile = false
		var preparedNote = false
		var preparedReadOverrides = false
		var preparedDownloadIdentity: PreparedDownloadIdentityMove? = null

		val state = try {
			// SharedPreferences cannot join the Room transaction. Prepare durable destination copies first
			// while keeping source copies intact. Every preparation is covered by the same rollback boundary,
			// so a later preparation failure cannot leave a half-owned destination copy behind.
			preparedReaderProfile = mangaReaderProfileStore.prepareMove(oldDetails.id, newDetails.id)
			preparedNote = mangaNotesRepository.prepareMove(oldDetails.id, newDetails.id)
			preparedReadOverrides = losslessPlan?.let(losslessMigrationTransfer::prepareReadOverrides) ?: false
			if (losslessPlan != null && losslessPlan.oldReadOverrides.isNotEmpty()) {
				// AppSettings uses apply() for normal UI writes. Migration needs a synchronous disk barrier
				// before Room may commit so process death cannot lose the destination override copy.
				chapterReadOverridesMigrationStore.persistCurrent(newDetails.id)
			}
			preparedDownloadIdentity = if (losslessPlan != null) {
				localMangaIndex.prepareDownloadIdentityMove(
					oldDetails.id, newDetails.id, chapterTargets = losslessPlan.chapterTargets.mapValues { it.value.id },
				)
			} else {
				null
			}

			database.withTransaction {
				// Recheck in the transaction that commits membership; privacy cannot change between this
				// snapshot and applyRoom. A failure uses the existing prepared-state rollback boundary.
				if (requireLibraryEntry) checkLibraryAdmission(oldDetails.id, newDetails.id)
				if (losslessPlan != null) {
					val result = losslessMigrationTransfer.applyRoom(losslessPlan)
					return@withTransaction MigrationState(
						wasPrivateOnly = result.wasPrivateOnly,
						newHistory = result.newHistory,
						migratedScrobblers = result.migratedScrobblers,
					)
				}

				val favoritesDao = database.getFavouritesDao()
				val privateFavoritesDao = database.getPrivateFavouritesDao()
				val oldFavourites = favoritesDao.findAllRaw(oldDetails.id)
				val oldPrivateFavourites = privateFavoritesDao.findAllRaw(oldDetails.id)
				val wasPrivateOnly = oldPrivateFavourites.isNotEmpty() && oldFavourites.isEmpty()

				if (oldFavourites.isNotEmpty()) {
					favoritesDao.delete(oldDetails.id)
					for (f in oldFavourites) favoritesDao.upsert(f.copy(mangaId = newDetails.id))
				}
				if (oldPrivateFavourites.isNotEmpty()) {
					privateFavoritesDao.delete(oldDetails.id)
					for (f in oldPrivateFavourites) privateFavoritesDao.upsert(f.copy(mangaId = newDetails.id))
				}

				// Move tracker rows locally before any network request. This keeps every row attached to the
				// new membership even if the tracker is offline or privacy changes while migration continues.
				// Plain Normal replacement intentionally drops tracker state when progress migration is off;
				// Private-only keeps it locally so an old link remains safely unlinkable inside the vault.
				val migratedScrobblers = moveScrobblingRows(
					oldMangaId = oldDetails.id,
					newMangaId = newDetails.id,
					keep = migrateProgress || wasPrivateOnly,
				)

				val preferencesDao = database.getPreferencesDao()
				preferencesDao.find(oldDetails.id)?.let { prefs ->
					preferencesDao.delete(oldDetails.id)
					preferencesDao.upsert(prefs.copy(mangaId = newDetails.id))
				}

				if (!migrateProgress) {
					database.getBookmarksDao().deleteAll(oldDetails.id)
					database.getHistoryDao().delete(oldDetails.id)
					database.getTracksDao().delete(oldDetails.id)
					return@withTransaction MigrationState(
						wasPrivateOnly = wasPrivateOnly,
						newHistory = null,
						migratedScrobblers = migratedScrobblers,
					)
				}

				val bookmarksDao = database.getBookmarksDao()
				val oldBookmarks = bookmarksDao.findAll(oldDetails.id)
				if (oldBookmarks.isNotEmpty()) {
					val chapterIds = mapChapterIds(oldDetails, newDetails)
					val migrated = oldBookmarks.mapNotNull { bookmark ->
						val newChapterId = chapterIds[bookmark.chapterId] ?: return@mapNotNull null
						bookmark.copy(mangaId = newDetails.id, chapterId = newChapterId)
					}
					bookmarksDao.deleteAll(oldDetails.id)
					if (migrated.isNotEmpty()) bookmarksDao.upsert(migrated)
				}
				migrateChapterPersonalCompatibility(
					database = database,
					oldManga = oldDetails,
					newManga = newDetails,
					chapterIds = mapChapterIds(oldDetails, newDetails),
				)

				val historyDao = database.getHistoryDao()
				val oldHistory = historyDao.find(oldDetails.id)
				val newHistory = if (oldHistory != null) {
					val migrated = makeNewHistory(oldDetails, newDetails, oldHistory)
					historyDao.delete(oldDetails.id)
					historyDao.upsert(migrated)
					migrated
				} else {
					null
				}

				val tracksDao = database.getTracksDao()
				val oldTrack = tracksDao.find(oldDetails.id)
				if (oldTrack != null) {
					val lastChapter = newDetails.chapters?.lastOrNull()
					tracksDao.delete(oldDetails.id)
					tracksDao.upsert(
						TrackEntity(
							mangaId = newDetails.id,
							lastChapterId = lastChapter?.id ?: 0L,
							newChapters = 0,
							lastCheckTime = System.currentTimeMillis(),
							lastChapterDate = lastChapter?.uploadDate ?: 0L,
							lastResult = TrackEntity.RESULT_EXTERNAL_MODIFICATION,
							lastError = null,
						),
					)
				}

				MigrationState(
					wasPrivateOnly = wasPrivateOnly,
					newHistory = newHistory,
					migratedScrobblers = migratedScrobblers,
				)
			}
		} catch (error: Throwable) {
			if (preparedReaderProfile) {
				runCatching { mangaReaderProfileStore.rollbackPreparedMove(newDetails.id) }
					.onFailure { rollbackError -> error.addSuppressed(rollbackError) }
			}
			if (preparedNote) {
				runCatching { mangaNotesRepository.rollbackPreparedMove(newDetails.id) }
					.onFailure { rollbackError -> error.addSuppressed(rollbackError) }
			}
			if (losslessPlan != null) {
				runCatching { losslessMigrationTransfer.rollbackReadOverrides(losslessPlan, preparedReadOverrides) }
					.onFailure { rollbackError -> error.addSuppressed(rollbackError) }
				if (preparedReadOverrides) {
					runCatching { chapterReadOverridesMigrationStore.persistCurrent(newDetails.id) }
						.onFailure { rollbackError -> error.addSuppressed(rollbackError) }
				}
				runCatching {
					localMangaIndex.rollbackPreparedDownloadIdentityMove(newDetails.id, preparedDownloadIdentity)
				}.onFailure { rollbackError -> error.addSuppressed(rollbackError) }
			}
			throw error
		}

		// Room now points at the new id and the destination copies are already durable. Source cleanup
		// can happen afterwards without creating a process-death window where the new manga has no
		// Notes/Profile/read/download-identity state. A crash during cleanup can leave a duplicate source
		// copy, but not a missing destination copy; a later retry may safely finish that cleanup.
		mangaReaderProfileStore.finishPreparedMove(oldDetails.id, newDetails.id)
		mangaNotesRepository.finishPreparedMove(oldDetails.id, newDetails.id)
		losslessPlan?.let {
			losslessMigrationTransfer.finishReadOverrides(it)
			chapterReadOverridesMigrationStore.persistCurrent(oldDetails.id)
			localMangaIndex.finishPreparedDownloadIdentityMove(oldDetails.id, preparedDownloadIdentity)
		}

		// All Room state is committed before tracker/source I/O starts. Private-only skips this entire
		// block; Normal+Private remains public by design. Each Scrobbler also re-checks privacy at its
		// own outbound boundary, covering membership changes while this loop is running.
		if (migrateProgress && !state.wasPrivateOnly) {
			for (scrobbler in scrobblers) {
				if (!scrobbler.isEnabled || scrobbler.scrobblerService.id !in state.migratedScrobblers) continue
				if (database.getPrivateFavouritesDao().isPrivateOnly(newDetails.id)) break
				val prevInfo = scrobbler.getLocalScrobblingInfoOrNull(newDetails.id) ?: continue
				val status = prevInfo.status ?: when {
					state.newHistory == null -> ScrobblingStatus.PLANNED
					state.newHistory.percent == 1f -> ScrobblingStatus.COMPLETED
					else -> ScrobblingStatus.READING
				}
				runCatchingCancellable {
					scrobbler.linkManga(newDetails.id, prevInfo.targetId, status)
					scrobbler.updateScrobblingInfo(
						mangaId = newDetails.id,
						rating = prevInfo.rating,
						status = status,
						comment = prevInfo.comment,
					)
					state.newHistory?.let { history ->
						scrobbler.scrobble(newDetails, history.chapterId)
					}
				}.onFailure { it.printStackTraceDebug() }
			}
		}

		// Lossless transfer already preserved the exact reading/resume state. Recalculating it from
		// replacement chapter coverage can turn partial progress into completion after Room commits.
		// Ordinary Alternatives/Duplicates/Downloads consumers retain their compatibility behavior.
		if (!lossless) {
			runCatchingCancellable { progressUpdateUseCase(newManga) }
				.onFailure { it.printStackTraceDebug() }
		}
	}

	/**
	 * Move old local tracker rows to the new manga id without any service call.
	 * Existing links on the destination win; they are never overwritten by a migration.
	 * Returns tracker ids whose old row was actually adopted by the destination.
	 */
	private suspend fun moveScrobblingRows(
		oldMangaId: Long,
		newMangaId: Long,
		keep: Boolean,
	): Set<Int> {
		if (oldMangaId == newMangaId) return emptySet()
		val dao = database.getScrobblingDao()
		val migrated = LinkedHashSet<Int>()
		for (entity in dao.findAll(oldMangaId)) {
			if (keep && dao.find(entity.scrobbler, newMangaId) == null) {
				scrobbblingUpsert(dao, entity, newMangaId)
				migrated += entity.scrobbler
			}
			scrobbblingDelete(dao, entity, oldMangaId)
		}
		return migrated
	}

	private suspend fun scrobbblingUpsert(dao: ScrobblingDao, entity: ScrobblingEntity, mangaId: Long) {
		dao.upsert(
			ScrobblingEntity(
				scrobbler = entity.scrobbler,
				id = entity.id,
				mangaId = mangaId,
				targetId = entity.targetId,
				status = entity.status,
				chapter = entity.chapter,
				comment = entity.comment,
				rating = entity.rating,
			),
		)
	}

	private suspend fun scrobbblingDelete(dao: ScrobblingDao, entity: ScrobblingEntity, mangaId: Long) {
		dao.delete(entity.scrobbler, mangaId)
	}

	private fun makeNewHistory(oldManga: Manga, newManga: Manga, history: HistoryEntity): HistoryEntity {
		if (oldManga.chapters.isNullOrEmpty()) {
			val branch = newManga.getPreferredBranch(null)
			val chapters = checkNotNull(newManga.getChapters(branch))
			val currentChapter = if (history.percent in 0f..1f) {
				chapters[(chapters.lastIndex * history.percent).toInt()]
			} else {
				chapters.first()
			}
			return HistoryEntity(
				mangaId = newManga.id,
				createdAt = history.createdAt,
				updatedAt = history.updatedAt,
				chapterId = currentChapter.id,
				page = history.page,
				scroll = history.scroll,
				percent = history.percent,
				deletedAt = 0,
				chaptersCount = chapters.count { it.branch == currentChapter.branch },
			)
		}
		val branch = oldManga.getPreferredBranch(history.toMangaHistory())
		val oldChapters = checkNotNull(oldManga.getChapters(branch))
		var index = oldChapters.indexOfFirst { it.id == history.chapterId }
		if (index < 0) {
			index = if (history.percent in 0f..1f) {
				(oldChapters.lastIndex * history.percent).toInt()
			} else {
				0
			}
		}
		val newChapters = checkNotNull(newManga.chapters).groupBy { it.branch }
		val newBranch = if (newChapters.containsKey(branch)) branch else newManga.getPreferredBranch(null)
		val newChapterId = checkNotNull(newChapters[newBranch]).let {
			val oldChapter = oldChapters[index]
			it.findByNumber(oldChapter.volume, oldChapter.number) ?: it.getOrNull(index) ?: it.last()
		}.id

		return HistoryEntity(
			mangaId = newManga.id,
			createdAt = history.createdAt,
			updatedAt = history.updatedAt,
			chapterId = newChapterId,
			page = history.page,
			scroll = history.scroll,
			percent = history.percent,
			deletedAt = 0,
			chaptersCount = checkNotNull(newChapters[newBranch]).size,
		)
	}

	private fun mapChapterIds(oldManga: Manga, newManga: Manga): Map<Long, Long> {
		val newChapters = newManga.chapters
		if (newChapters.isNullOrEmpty()) return emptyMap()
		val byNumber = HashMap<Pair<Int, Float>, Long>(newChapters.size)
		for (chapter in newChapters) {
			if (chapter.number > 0f) byNumber.putIfAbsent(chapter.volume to chapter.number, chapter.id)
		}
		val oldChapters = oldManga.chapters ?: return emptyMap()
		val result = HashMap<Long, Long>(oldChapters.size)
		for (chapter in oldChapters) {
			byNumber[chapter.volume to chapter.number]?.let { result[chapter.id] = it }
		}
		return result
	}

	private fun List<MangaChapter>.findByNumber(volume: Int, number: Float): MangaChapter? =
		if (number <= 0f) null else firstOrNull { it.volume == volume && it.number == number }

	private data class MigrationState(
		val wasPrivateOnly: Boolean,
		val newHistory: HistoryEntity?,
		val migratedScrobblers: Set<Int>,
	)
	class SourceChangedException : IllegalStateException("Migration library or privacy state changed")

	private companion object {
		val transferMutex = Mutex()
	}

}
