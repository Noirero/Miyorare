package org.koitharu.kotatsu.alternatives.domain

import androidx.room.withTransaction
import dagger.Reusable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.prefs.ChapterReadOverridesMigrationStore
import org.koitharu.kotatsu.details.data.ChapterPersonalEntity
import org.koitharu.kotatsu.details.data.MangaNotesRepository
import org.koitharu.kotatsu.favourites.data.FavouriteEntity
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.favourites.data.PrivateFavouriteEntity
import org.koitharu.kotatsu.history.data.HistoryEntity
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.reader.ui.config.MangaReaderProfileStore
import org.koitharu.kotatsu.scrobbling.common.data.ScrobblingEntity
import org.koitharu.kotatsu.tracker.data.TrackEntity
import javax.inject.Inject

/**
 * Non-destructive counterpart of [MigrateUseCase] for Yokai-style Copy.
 *
 * The destination receives the transferable library/reading state while the source remains intact.
 * Activity/ownership ledgers that would double-count the same real-world activity (reading stats,
 * Reader Journey awards, track-log events and download ownership) deliberately stay attached to the
 * source. Because Copy keeps the source entry, leaving those ledgers there is lossless and avoids
 * manufacturing duplicate activity or duplicate ownership.
 */
@Reusable
class LosslessMigrationCopyUseCase @Inject constructor(
	private val database: MangaDatabase,
	private val mangaReaderProfileStore: MangaReaderProfileStore,
	private val mangaNotesRepository: MangaNotesRepository,
	private val chapterReadOverridesMigrationStore: ChapterReadOverridesMigrationStore,
	private val losslessMigrationTransfer: LosslessMigrationTransfer,
) {

	suspend operator fun invoke(oldManga: Manga, newManga: Manga) = copyMutex.withLock {
		checkLibraryAdmission(oldManga.id, newManga.id)
		val plan = losslessMigrationTransfer.prepare(oldManga, newManga)

		var preparedReaderProfile = false
		var preparedNote = false
		var preparedReadOverrides = false
		try {
			preparedReaderProfile = mangaReaderProfileStore.prepareMove(oldManga.id, newManga.id)
			preparedNote = mangaNotesRepository.prepareMove(oldManga.id, newManga.id)
			preparedReadOverrides = losslessMigrationTransfer.prepareReadOverrides(plan)
			if (plan.oldReadOverrides.isNotEmpty()) {
				chapterReadOverridesMigrationStore.persistCurrent(newManga.id)
			}

			database.withTransaction {
				checkLibraryAdmission(oldManga.id, newManga.id)
				copyRoom(plan)
			}
		} catch (error: Throwable) {
			if (preparedReaderProfile) {
				runCatching { mangaReaderProfileStore.rollbackPreparedMove(newManga.id) }
					.onFailure(error::addSuppressed)
			}
			if (preparedNote) {
				runCatching { mangaNotesRepository.rollbackPreparedMove(newManga.id) }
					.onFailure(error::addSuppressed)
			}
			runCatching { losslessMigrationTransfer.rollbackReadOverrides(plan, preparedReadOverrides) }
				.onFailure(error::addSuppressed)
			if (preparedReadOverrides) {
				runCatching { chapterReadOverridesMigrationStore.persistCurrent(newManga.id) }
					.onFailure(error::addSuppressed)
			}
			throw error
		}
	}

	private suspend fun checkLibraryAdmission(oldId: Long, newId: Long) {
		if (!database.getMangaDao().isMigrationEntryVisible(oldId) || database.getPrivateFavouritesDao().isPrivateOnly(newId)) {
			throw MigrateUseCase.SourceChangedException()
		}
	}

	private suspend fun copyRoom(plan: LosslessMigrationTransfer.Plan) {
		val oldId = plan.oldManga.id
		val newId = plan.newManga.id

		val favouritesDao = database.getFavouritesDao()
		val oldFavourites = favouritesDao.findAllRaw(oldId)
		val targetFavourites = favouritesDao.findAllRaw(newId).associateBy { it.categoryId }
		for (source in oldFavourites) {
			val target = targetFavourites[source.categoryId]
			if (target != null && !sameFavourite(source, target)) {
				conflict("Favourite category ${source.categoryId} conflicts at destination")
			}
			if (target == null) favouritesDao.upsert(source.copy(mangaId = newId))
		}

		val privateFavouritesDao = database.getPrivateFavouritesDao()
		val oldPrivateFavourites = privateFavouritesDao.findAllRaw(oldId)
		val targetPrivateFavourites = privateFavouritesDao.findAllRaw(newId).associateBy { it.categoryId }
		for (source in oldPrivateFavourites) {
			val target = targetPrivateFavourites[source.categoryId]
			if (target != null && !samePrivateFavourite(source, target)) {
				conflict("Private favourite category ${source.categoryId} conflicts at destination")
			}
			if (target == null) privateFavouritesDao.upsert(source.copy(mangaId = newId))
		}

		copyLibraryGroups(plan)

		val preferencesDao = database.getPreferencesDao()
		val oldPreferences = preferencesDao.find(oldId)
		val targetPreferences = preferencesDao.find(newId)
		if (oldPreferences != null && targetPreferences != null && oldPreferences.copy(mangaId = newId) != targetPreferences) {
			conflict("Destination manga already has different per-manga preferences")
		}
		if (oldPreferences != null && targetPreferences == null) {
			preferencesDao.upsert(oldPreferences.copy(mangaId = newId))
		}

		copyScrobblingRows(oldId, newId)
		copyBookmarks(plan)
		copyHistory(plan)
		copyTrack(plan)
		copyChapterPersonal(plan)
	}

	private suspend fun copyLibraryGroups(plan: LosslessMigrationTransfer.Plan) {
		val groupsDao = database.getLibraryGroupsDao()
		for (space in listOf(FavouriteSpace.NORMAL, FavouriteSpace.PRIVATE)) {
			val oldMembers = groupsDao.findMembersByMangaIds(listOf(plan.oldManga.id), space.dbValue)
			if (oldMembers.isEmpty()) continue
			if (oldMembers.size != 1) {
				conflict("Source manga belongs to multiple library groups in ${space.name}")
			}
			val source = oldMembers.single()
			val targetMembers = groupsDao.findMembersByMangaIds(listOf(plan.newManga.id), space.dbValue)
			if (targetMembers.any { it.groupId != source.groupId }) {
				conflict("Destination manga already belongs to a different library group in ${space.name}")
			}
			val target = targetMembers.singleOrNull()
			if (target != null && target.position != source.position) {
				conflict("Library group ${source.groupId} has a different destination position")
			}
			if (target == null) groupsDao.insertMembers(listOf(source.copy(mangaId = plan.newManga.id)))

			val timeline = groupsDao.findTimeline(source.groupId)
			val targetTimeline = timeline.filter { it.mangaId == plan.newManga.id }.associateBy { it.chapterId }
			val inserts = ArrayList<org.koitharu.kotatsu.favourites.groups.data.LibraryGroupTimelineItemEntity>()
			for (item in timeline.filter { it.mangaId == plan.oldManga.id }) {
				val mappedChapterId = mappedChapterId(plan, item.chapterId, "library group timeline")
				val copied = item.copy(mangaId = plan.newManga.id, chapterId = mappedChapterId)
				val existing = targetTimeline[mappedChapterId]
				if (existing != null && existing.position != copied.position) {
					conflict("Library group ${source.groupId} timeline conflicts for chapter $mappedChapterId")
				}
				if (existing == null) inserts += copied
			}
			if (inserts.isNotEmpty()) groupsDao.insertTimeline(inserts)
		}
	}

	private suspend fun copyScrobblingRows(oldId: Long, newId: Long) {
		val dao = database.getScrobblingDao()
		for (source in dao.findAll(oldId)) {
			val target = dao.find(source.scrobbler, newId)
			if (target != null && !sameScrobbling(source, target)) {
				conflict("Destination manga already has different scrobbling state for ${source.scrobbler}")
			}
			if (target == null) {
				dao.upsert(
					ScrobblingEntity(
						scrobbler = source.scrobbler,
						id = source.id,
						mangaId = newId,
						targetId = source.targetId,
						status = source.status,
						chapter = source.chapter,
						comment = source.comment,
						rating = source.rating,
					),
				)
			}
		}
	}

	private suspend fun copyBookmarks(plan: LosslessMigrationTransfer.Plan) {
		val dao = database.getBookmarksDao()
		val targetBookmarks = dao.findAll(plan.newManga.id).associateBy { it.pageId }
		val missing = dao.findAll(plan.oldManga.id).map { source ->
			val copied = source.copy(
				mangaId = plan.newManga.id,
				chapterId = mappedChapterId(plan, source.chapterId, "bookmark"),
			)
			val target = targetBookmarks[copied.pageId]
			if (target != null && target != copied) {
				conflict("Destination bookmark ${copied.pageId} conflicts with source bookmark")
			}
			copied
		}.filter { targetBookmarks[it.pageId] == null }
		if (missing.isNotEmpty()) dao.upsert(missing)
	}

	private suspend fun copyHistory(plan: LosslessMigrationTransfer.Plan) {
		val dao = database.getHistoryDao()
		val source = dao.findIncludingDeleted(plan.oldManga.id) ?: return
		val copied = source.copyForCopy(
			mangaId = plan.newManga.id,
			chapterId = mappedChapterId(plan, source.chapterId, "history"),
			chaptersCount = plan.newManga.chapters.orEmpty().count {
				it.branch == plan.chapterTargets[source.chapterId]?.branch
			}.takeIf { it > 0 } ?: source.chaptersCount,
		)
		val target = dao.findIncludingDeleted(plan.newManga.id)
		if (target != null && !sameHistory(copied, target)) {
			conflict("Destination manga already has different reading history")
		}
		if (target == null) dao.insert(copied)
	}

	private suspend fun copyTrack(plan: LosslessMigrationTransfer.Plan) {
		val dao = database.getTracksDao()
		val source = dao.find(plan.oldManga.id) ?: return
		val copied = TrackEntity(
			mangaId = plan.newManga.id,
			lastChapterId = if (source.lastChapterId == 0L) 0L else mappedChapterId(plan, source.lastChapterId, "tracker state"),
			newChapters = source.newChapters,
			lastCheckTime = source.lastCheckTime,
			lastChapterDate = source.lastChapterDate,
			lastResult = source.lastResult,
			lastError = source.lastError,
		)
		val target = dao.find(plan.newManga.id)
		if (target != null && !sameTrack(copied, target)) {
			conflict("Destination manga already has different tracker state")
		}
		if (target == null) dao.upsert(copied)
	}

	private suspend fun copyChapterPersonal(plan: LosslessMigrationTransfer.Plan) {
		val dao = database.getChapterPersonalDao()
		val sourceByKey = plan.oldManga.chapters.orEmpty().associateBy { it.source.name to it.url }
		val mergedByTarget = LinkedHashMap<Pair<String, String>, ChapterPersonalEntity>()
		for (source in dao.findAll(listOf(plan.oldManga.id))) {
			val oldChapter = sourceByKey[source.source to source.url]
				?: conflict("Per-chapter note/rating no longer resolves to a source chapter")
			val targetChapter = plan.chapterTargets[oldChapter.id]
				?: conflict("Per-chapter note/rating chapter cannot be mapped safely")
			val key = targetChapter.source.name to targetChapter.url
			val copied = ChapterPersonalEntity(
				mangaId = plan.newManga.id,
				source = key.first,
				url = key.second,
				rating = source.rating,
				note = source.note,
			)
			val previous = mergedByTarget[key]
			mergedByTarget[key] = if (previous == null) copied else mergeChapterPersonal(previous, copied)
		}
		for ((key, source) in mergedByTarget) {
			val target = dao.find(plan.newManga.id, key.first, key.second)
			dao.upsert(if (target == null) source else mergeChapterPersonal(target, source))
		}
	}

	private fun mappedChapterId(plan: LosslessMigrationTransfer.Plan, oldChapterId: Long, owner: String): Long {
		if (oldChapterId == 0L) return 0L
		return plan.chapterTargets[oldChapterId]?.id
			?: conflict("$owner references chapter $oldChapterId that cannot be mapped safely")
	}

	private fun mergeChapterPersonal(a: ChapterPersonalEntity, b: ChapterPersonalEntity): ChapterPersonalEntity {
		if (a.rating != null && b.rating != null && a.rating != b.rating) conflict("Per-chapter ratings conflict")
		if (!a.note.isNullOrBlank() && !b.note.isNullOrBlank() && a.note != b.note) conflict("Per-chapter notes conflict")
		return a.copy(
			rating = a.rating ?: b.rating,
			note = a.note?.takeIf { it.isNotBlank() } ?: b.note,
		)
	}

	private fun sameFavourite(a: FavouriteEntity, b: FavouriteEntity): Boolean =
		a.categoryId == b.categoryId && a.sortKey == b.sortKey && a.isPinned == b.isPinned &&
			a.createdAt == b.createdAt && a.deletedAt == b.deletedAt

	private fun samePrivateFavourite(a: PrivateFavouriteEntity, b: PrivateFavouriteEntity): Boolean =
		a.categoryId == b.categoryId && a.sortKey == b.sortKey && a.isPinned == b.isPinned &&
			a.createdAt == b.createdAt && a.deletedAt == b.deletedAt

	private fun sameHistory(a: HistoryEntity, b: HistoryEntity): Boolean =
		a.createdAt == b.createdAt && a.updatedAt == b.updatedAt && a.chapterId == b.chapterId &&
			a.page == b.page && a.scroll == b.scroll && a.percent == b.percent &&
			a.deletedAt == b.deletedAt && a.chaptersCount == b.chaptersCount

	private fun sameTrack(a: TrackEntity, b: TrackEntity): Boolean =
		a.lastChapterId == b.lastChapterId && a.newChapters == b.newChapters &&
			a.lastCheckTime == b.lastCheckTime && a.lastChapterDate == b.lastChapterDate &&
			a.lastResult == b.lastResult && a.lastError == b.lastError

	private fun sameScrobbling(a: ScrobblingEntity, b: ScrobblingEntity): Boolean =
		a.targetId == b.targetId && a.status == b.status && a.chapter == b.chapter &&
			a.comment == b.comment && a.rating == b.rating

	private fun HistoryEntity.copyForCopy(mangaId: Long, chapterId: Long, chaptersCount: Int) = HistoryEntity(
		mangaId = mangaId,
		createdAt = createdAt,
		updatedAt = updatedAt,
		chapterId = chapterId,
		page = page,
		scroll = scroll,
		percent = percent,
		deletedAt = deletedAt,
		chaptersCount = chaptersCount,
	)

	private fun conflict(message: String): Nothing = throw LosslessMigrationConflictException(message)

	private companion object {
		val copyMutex = Mutex()
	}
}
