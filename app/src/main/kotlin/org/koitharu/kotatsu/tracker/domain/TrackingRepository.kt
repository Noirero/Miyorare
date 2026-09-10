package org.koitharu.kotatsu.tracker.domain

import androidx.annotation.VisibleForTesting
import androidx.room.withTransaction
import dagger.Reusable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.toManga
import org.koitharu.kotatsu.core.db.entity.toMangaTags
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.util.ReversibleHandle
import org.koitharu.kotatsu.core.util.ext.mapItems
import org.koitharu.kotatsu.core.util.ext.toInstantOrNull
import org.koitharu.kotatsu.details.domain.ProgressUpdateUseCase
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.list.domain.ListFilterOption
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.util.ifZero
import org.koitharu.kotatsu.tracker.data.TrackEntity
import org.koitharu.kotatsu.tracker.data.TrackLogEntity
import org.koitharu.kotatsu.tracker.data.toTrackingLogItem
import org.koitharu.kotatsu.tracker.domain.model.MangaTracking
import org.koitharu.kotatsu.tracker.domain.model.MangaUpdates
import org.koitharu.kotatsu.tracker.domain.model.TrackingLogItem
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

private const val NO_ID = 0L
private const val MAX_LOG_SIZE = 120

@Reusable
class TrackingRepository @Inject constructor(
	private val db: MangaDatabase,
	private val settings: AppSettings,
	private val progressUpdateUseCase: ProgressUpdateUseCase,
) {

	private var isGcCalled = AtomicBoolean(false)

	suspend fun getNewChaptersCount(mangaId: Long): Int = db.getTracksDao().findNewChapters(mangaId)

	fun observeNewChaptersCount(mangaId: Long): Flow<Int> = db.getTracksDao().observeNewChapters(mangaId)

	fun observeUnreadUpdatesCount(): Flow<Int> = db.getTrackLogsDao().observeUnreadCount()

	fun observeUpdatedManga(limit: Int, filterOptions: Set<ListFilterOption>): Flow<List<MangaTracking>> {
		return db.getTracksDao().observeUpdatedManga(limit, filterOptions)
			.mapItems {
				MangaTracking(
					manga = it.manga.toManga(it.tags.toMangaTags(), null),
					lastChapterId = it.track.lastChapterId,
					lastCheck = it.track.lastCheckTime.toInstantOrNull(),
					lastChapterDate = it.track.lastChapterDate.toInstantOrNull(),
					newChapters = it.track.newChapters,
				)
			}.distinctUntilChanged()
			.onStart { gcIfNotCalled() }
	}

	suspend fun getTracks(offset: Int, limit: Int): List<MangaTracking> {
		val smartUpdateRules = settings.trackerSmartUpdateRules
		return db.getTracksDao().findAllForChecking(
			trackHistory = AppSettings.TRACK_HISTORY in settings.trackSources,
			trackFavourites = AppSettings.TRACK_FAVOURITES in settings.trackSources,
			skipCompleted = AppSettings.SMART_UPDATE_SKIP_COMPLETED in smartUpdateRules,
			skipUnstarted = AppSettings.SMART_UPDATE_SKIP_UNSTARTED in smartUpdateRules,
			skipUnread = AppSettings.SMART_UPDATE_SKIP_UNREAD in smartUpdateRules,
			offset = offset,
			limit = limit,
		).map {
			MangaTracking(
				manga = it.manga.toManga(emptySet(), null),
				lastChapterId = it.track.lastChapterId,
				lastCheck = it.track.lastCheckTime.toInstantOrNull(),
				lastChapterDate = it.track.lastChapterDate.toInstantOrNull(),
				newChapters = it.track.newChapters,
			)
		}
	}

	suspend fun getTrackOrNull(manga: Manga): MangaTracking? {
		val track = db.getTracksDao().find(manga.id) ?: return null
		return MangaTracking(
			manga = manga,
			lastChapterId = track.lastChapterId,
			lastCheck = track.lastCheckTime.toInstantOrNull(),
			lastChapterDate = track.lastChapterDate.toInstantOrNull(),
			newChapters = track.newChapters,
		)
	}

	fun observeTrackingLog(
		limit: Int,
		filterOptions: Set<ListFilterOption>,
		space: FavouriteSpace = FavouriteSpace.NORMAL,
	): Flow<List<TrackingLogItem>> {
		val source = if (space == FavouriteSpace.PRIVATE) {
			db.getTrackLogsDao().observeAllPrivate(limit, filterOptions)
		} else {
			db.getTrackLogsDao().observeAll(limit, filterOptions)
		}
		return source.mapItems { it.toTrackingLogItem() }
			.onStart { if (space == FavouriteSpace.NORMAL) gcIfNotCalled() }
	}

	fun observeAllTracks(limit: Int, filterOptions: Set<ListFilterOption>): Flow<List<MangaTracking>> {
		return db.getTracksDao().observeAllTracks(limit, filterOptions)
			.mapItems {
				MangaTracking(
					manga = it.manga.toManga(it.tags.toMangaTags(), null),
					lastChapterId = it.track.lastChapterId,
					lastCheck = it.track.lastCheckTime.toInstantOrNull(),
					lastChapterDate = it.track.lastChapterDate.toInstantOrNull(),
					newChapters = it.track.newChapters,
				)
			}.distinctUntilChanged()
			.onStart { gcIfNotCalled() }
	}

	suspend fun getLogsCount() = db.getTrackLogsDao().count()
	suspend fun clearLogs() = db.getTrackLogsDao().clear()

	suspend fun removeLog(id: Long): ReversibleHandle? {
		val dao = db.getTrackLogsDao()
		val entity = dao.findById(id) ?: return null
		dao.delete(id)
		return ReversibleHandle { dao.insert(entity) }
	}

	suspend fun markLogsRead(mangaId: Long): ReversibleHandle {
		val logsDao = db.getTrackLogsDao()
		val tracksDao = db.getTracksDao()
		val priorCounter = tracksDao.findNewChapters(mangaId)
		val priorUnread = logsDao.findUnreadIds(mangaId).toList()
		db.withTransaction {
			tracksDao.clearCounter(mangaId)
			logsDao.markAsRead(mangaId)
		}
		return ReversibleHandle {
			db.withTransaction {
				tracksDao.setCounter(mangaId, priorCounter)
				if (priorUnread.isNotEmpty()) logsDao.markUnread(priorUnread)
			}
		}
	}

	suspend fun clearCounters() = db.getTracksDao().clearCounters()

	suspend fun gc() = db.withTransaction {
		db.getTracksDao().gc()
		db.getTrackLogsDao().run {
			gc()
			trim(MAX_LOG_SIZE)
		}
	}

	suspend fun saveUpdates(updates: MangaUpdates) {
		val hasNewChapters = updates is MangaUpdates.Success &&
			updates.isValid &&
			updates.newChapters.isNotEmpty()
		// Progress refresh may fetch source details. Complete it before opening the short write
		// transaction so a slow network response cannot block unrelated database readers/writers.
		if (hasNewChapters) progressUpdateUseCase(updates.manga)
		db.withTransaction {
			val track = getOrCreateTrack(updates.manga.id).mergeWith(updates)
			db.getTracksDao().upsert(track)
			if (hasNewChapters) {
				val logEntity = TrackLogEntity(
					mangaId = updates.manga.id,
					chapters = updates.newChapters.joinToString("\n") { x -> x.title.orEmpty() },
					chapterIds = updates.newChapters.joinToString("\n") { x -> x.id.toString() },
					createdAt = System.currentTimeMillis(),
					isUnread = true,
				)
				db.getTrackLogsDao().insert(logEntity)
			}
		}
	}

	suspend fun clearUpdates(ids: Collection<Long>) {
		if (ids.isEmpty()) return
		db.withTransaction {
			for (id in ids) {
				db.getTracksDao().clearCounter(id)
				db.getTrackLogsDao().markAsRead(id)
			}
		}
	}

	suspend fun mergeWith(tracking: MangaTracking) {
		val entity = TrackEntity(
			mangaId = tracking.manga.id,
			lastChapterId = tracking.lastChapterId,
			newChapters = tracking.newChapters,
			lastCheckTime = tracking.lastCheck?.toEpochMilli() ?: 0L,
			lastChapterDate = tracking.lastChapterDate?.toEpochMilli() ?: 0L,
			lastResult = TrackEntity.RESULT_EXTERNAL_MODIFICATION,
			lastError = null,
		)
		db.withTransaction {
			db.getTracksDao().upsert(entity)
			if (tracking.newChapters == 0) db.getTrackLogsDao().markAsRead(tracking.manga.id)
		}
	}

	suspend fun getCategoriesCount(): IntArray {
		val normal = db.getFavouriteCategoriesDao().findAll()
		val categories = if (db.getPrivateFavouritesDao().isIsolationDisabled()) {
			normal + db.getFavouriteCategoriesDao().findAllInSpace(FavouriteSpace.PRIVATE.dbValue)
		} else {
			normal
		}
		return intArrayOf(categories.count { it.track }, categories.size)
	}

	suspend fun updateTracks() = db.withTransaction {
		val dao = db.getTracksDao()
		dao.gc()
		val ids = dao.findAllIds().toMutableSet()
		val size = ids.size
		if (AppSettings.TRACK_HISTORY in settings.trackSources) {
			val historyIds = db.getHistoryDao().findAllIds()
			for (mangaId in historyIds) {
				if (!ids.remove(mangaId)) dao.upsert(TrackEntity.create(mangaId))
			}
		}
		if (AppSettings.TRACK_FAVOURITES in settings.trackSources) {
			val favoritesIds = db.getFavouritesDao().findIdsWithTrackOrNewChaptersDownload()
			for (mangaId in favoritesIds) {
				if (!ids.remove(mangaId)) dao.upsert(TrackEntity.create(mangaId))
			}
			val privateIds = db.getPrivateFavouritesDao().findIdsWithTrackOrNewChaptersDownload()
			for (mangaId in privateIds) {
				if (!ids.remove(mangaId)) dao.upsert(TrackEntity.create(mangaId))
			}
		}
		for (mangaId in db.getTrackLogsDao().findMangaIds()) ids.remove(mangaId)
		for (mangaId in ids) dao.delete(mangaId)
		size - ids.size
	}

	private suspend fun getOrCreateTrack(mangaId: Long): TrackEntity =
		db.getTracksDao().find(mangaId) ?: TrackEntity.create(mangaId)

	private fun TrackEntity.mergeWith(updates: MangaUpdates): TrackEntity {
		return when (updates) {
			is MangaUpdates.Failure -> TrackEntity(
				mangaId = mangaId,
				lastChapterId = lastChapterId,
				newChapters = newChapters,
				lastCheckTime = System.currentTimeMillis(),
				lastChapterDate = lastChapterDate,
				lastResult = TrackEntity.RESULT_FAILED,
				lastError = updates.error?.toString(),
			)

			is MangaUpdates.Success -> TrackEntity(
				mangaId = mangaId,
				lastChapterId = updates.manga.getChapters(updates.branch).lastOrNull()?.id ?: NO_ID,
				newChapters = if (updates.isValid) newChapters + updates.newChapters.size else newChapters,
				lastCheckTime = System.currentTimeMillis(),
				lastChapterDate = updates.lastChapterDate().ifZero { lastChapterDate },
				lastResult = if (updates.isNotEmpty()) TrackEntity.RESULT_HAS_UPDATE else TrackEntity.RESULT_NO_UPDATE,
				lastError = null,
			)
		}
	}

	private suspend fun gcIfNotCalled() {
		if (isGcCalled.compareAndSet(false, true)) gc()
	}
}
