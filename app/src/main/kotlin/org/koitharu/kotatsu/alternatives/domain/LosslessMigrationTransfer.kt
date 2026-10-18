package org.koitharu.kotatsu.alternatives.domain

import dagger.Reusable
import org.koitharu.kotatsu.bookmarks.data.BookmarkEntity
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.details.data.ChapterPersonalEntity
import org.koitharu.kotatsu.details.data.MangaNotesRepository
import org.koitharu.kotatsu.favourites.data.FavouriteDownloadIndexEntity
import org.koitharu.kotatsu.favourites.data.FavouriteEntity
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.favourites.data.PrivateFavouriteEntity
import org.koitharu.kotatsu.history.data.HistoryEntity
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.reader.ui.config.MangaReaderProfileStore
import org.koitharu.kotatsu.readerjourney.data.ReaderJourneyChapterEntity
import org.koitharu.kotatsu.scrobbling.common.data.ScrobblingEntity
import org.koitharu.kotatsu.stats.data.StatsEntity
import org.koitharu.kotatsu.tracker.data.TrackEntity
import org.koitharu.kotatsu.tracker.data.TrackLogEntity
import java.io.File
import javax.inject.Inject

/**
 * Lossless state-transfer authority for source replacement.
 *
 * This deliberately does not replace the compatibility behaviour of [MigrateUseCase]. Only callers
 * that explicitly request lossless source replacement use this path. Any ambiguous chapter mapping
 * or incompatible destination state aborts before destructive source cleanup.
 */
@Reusable
internal class LosslessMigrationTransfer @Inject constructor(
	private val database: MangaDatabase,
	private val settings: AppSettings,
	private val notesRepository: MangaNotesRepository,
	private val readerProfileStore: MangaReaderProfileStore,
) {

	suspend fun prepare(oldManga: Manga, newManga: Manga): Plan {
		require(oldManga.id != newManga.id) { "Lossless migration requires a new manga identity" }
		val chapterTargets = buildConservativeChapterMap(oldManga, newManga)

		val oldNote = notesRepository.get(oldManga.id)
		val newNote = notesRepository.get(newManga.id)
		if (oldNote != null && newNote != null && oldNote != newNote) {
			conflict("Destination manga already has a different manga note")
		}

		val oldProfile = readerProfileStore.get(oldManga.id)
		val newProfile = readerProfileStore.get(newManga.id)
		if (oldProfile != null && newProfile != null && oldProfile != newProfile) {
			conflict("Destination manga already has a different Reader profile")
		}

		val oldOverrides = settings.getChapterReadOverrides(oldManga.id)
		val mappedOverrides = LinkedHashMap<Long, Boolean>(oldOverrides.size)
		for ((oldChapterId, isRead) in oldOverrides) {
			val newChapterId = mappedChapterId(chapterTargets, oldChapterId, "chapter read override")
			val previous = mappedOverrides.put(newChapterId, isRead)
			if (previous != null && previous != isRead) {
				conflict("Chapter read overrides collapse onto conflicting target chapter $newChapterId")
			}
		}
		val targetOverrides = settings.getChapterReadOverrides(newManga.id)
		val mergedOverrides = LinkedHashMap(targetOverrides)
		for ((chapterId, value) in mappedOverrides) {
			val previous = mergedOverrides[chapterId]
			if (previous != null && previous != value) {
				conflict("Destination manga already has a conflicting chapter read override for $chapterId")
			}
			mergedOverrides[chapterId] = value
		}

		return Plan(
			oldManga = oldManga,
			newManga = newManga,
			chapterTargets = chapterTargets,
			oldReadOverrides = oldOverrides,
			targetReadOverrides = targetOverrides,
			mergedReadOverrides = mergedOverrides,
		)
	}

	fun prepareReadOverrides(plan: Plan): Boolean {
		if (plan.oldReadOverrides.isEmpty() || plan.mergedReadOverrides == plan.targetReadOverrides) return false
		settings.setChapterReadOverrides(plan.newManga.id, plan.mergedReadOverrides)
		return true
	}

	fun rollbackReadOverrides(plan: Plan, prepared: Boolean) {
		if (!prepared) return
		settings.setChapterReadOverrides(plan.newManga.id, plan.targetReadOverrides)
	}

	fun finishReadOverrides(plan: Plan) {
		if (plan.oldReadOverrides.isNotEmpty()) {
			settings.setChapterReadOverrides(plan.oldManga.id, emptyMap())
		}
	}

	/** Must be invoked from the same Room transaction as the rest of the migration. */
	suspend fun applyRoom(plan: Plan): Result {
		val oldId = plan.oldManga.id
		val newId = plan.newManga.id

		val favouritesDao = database.getFavouritesDao()
		val privateFavouritesDao = database.getPrivateFavouritesDao()
		val oldFavourites = favouritesDao.findAllRaw(oldId)
		val targetFavourites = favouritesDao.findAllRaw(newId).associateBy { it.categoryId }
		for (source in oldFavourites) {
			targetFavourites[source.categoryId]?.let { target ->
				if (!sameFavourite(source, target)) conflict("Favourite category ${source.categoryId} conflicts at destination")
			}
		}
		val oldPrivateFavourites = privateFavouritesDao.findAllRaw(oldId)
		val targetPrivateFavourites = privateFavouritesDao.findAllRaw(newId).associateBy { it.categoryId }
		for (source in oldPrivateFavourites) {
			targetPrivateFavourites[source.categoryId]?.let { target ->
				if (!samePrivateFavourite(source, target)) conflict("Private favourite category ${source.categoryId} conflicts at destination")
			}
		}
		val wasPrivateOnly = oldPrivateFavourites.isNotEmpty() && oldFavourites.isEmpty()

		if (oldFavourites.isNotEmpty()) {
			favouritesDao.delete(oldId)
			for (entry in oldFavourites) favouritesDao.upsert(entry.copy(mangaId = newId))
		}
		if (oldPrivateFavourites.isNotEmpty()) {
			privateFavouritesDao.delete(oldId)
			for (entry in oldPrivateFavourites) privateFavouritesDao.upsert(entry.copy(mangaId = newId))
		}

		migrateLibraryGroups(plan)

		val preferencesDao = database.getPreferencesDao()
		val oldPreferences = preferencesDao.find(oldId)
		val targetPreferences = preferencesDao.find(newId)
		if (oldPreferences != null && targetPreferences != null && oldPreferences.copy(mangaId = newId) != targetPreferences) {
			conflict("Destination manga already has different per-manga preferences")
		}
		if (oldPreferences != null) {
			preferencesDao.delete(oldId)
			if (targetPreferences == null) preferencesDao.upsert(oldPreferences.copy(mangaId = newId))
		}

		val migratedScrobblers = migrateScrobblingRows(oldId, newId)

		val bookmarksDao = database.getBookmarksDao()
		val oldBookmarks = bookmarksDao.findAll(oldId)
		val targetBookmarks = bookmarksDao.findAll(newId).associateBy { it.pageId }
		val migratedBookmarks = oldBookmarks.map { source ->
			val migrated = source.copy(
				mangaId = newId,
				chapterId = mappedChapterId(plan.chapterTargets, source.chapterId, "bookmark"),
			)
			targetBookmarks[migrated.pageId]?.let { target ->
				if (target != migrated) conflict("Destination bookmark ${migrated.pageId} conflicts with source bookmark")
			}
			migrated
		}
		if (oldBookmarks.isNotEmpty()) {
			bookmarksDao.deleteAll(oldId)
			val missing = migratedBookmarks.filter { targetBookmarks[it.pageId] == null }
			if (missing.isNotEmpty()) bookmarksDao.upsert(missing)
		}

		val historyDao = database.getHistoryDao()
		val oldHistory = historyDao.findIncludingDeleted(oldId)
		val migratedHistory = oldHistory?.let { history ->
			history.copyForMigration(
				mangaId = newId,
				chapterId = mappedChapterId(plan.chapterTargets, history.chapterId, "history"),
				chaptersCount = plan.newManga.chapters.orEmpty().count {
					it.branch == plan.chapterTargets[history.chapterId]?.branch
				}.takeIf { it > 0 } ?: history.chaptersCount,
			)
		}
		val targetHistory = historyDao.findIncludingDeleted(newId)
		if (migratedHistory != null && targetHistory != null && !sameHistory(migratedHistory, targetHistory)) {
			conflict("Destination manga already has different reading history")
		}
		if (migratedHistory != null && targetHistory == null) {
			historyDao.insert(migratedHistory)
		}

		migrateStats(oldId, newId)

		if (oldHistory != null && oldHistory.deletedAt == 0L) {
			historyDao.delete(oldId)
		}

		val tracksDao = database.getTracksDao()
		val oldTrack = tracksDao.find(oldId)
		val migratedTrack = oldTrack?.let { track ->
			TrackEntity(
				mangaId = newId,
				lastChapterId = if (track.lastChapterId == 0L) 0L else mappedChapterId(
					plan.chapterTargets,
					track.lastChapterId,
					"tracker state",
				),
				newChapters = track.newChapters,
				lastCheckTime = track.lastCheckTime,
				lastChapterDate = track.lastChapterDate,
				lastResult = track.lastResult,
				lastError = track.lastError,
			)
		}
		val targetTrack = tracksDao.find(newId)
		if (migratedTrack != null && targetTrack != null && !sameTrack(migratedTrack, targetTrack)) {
			conflict("Destination manga already has different tracker state")
		}
		if (oldTrack != null) {
			tracksDao.delete(oldId)
			if (targetTrack == null) tracksDao.upsert(checkNotNull(migratedTrack))
		}

		migrateTrackLogs(plan)
		migrateChapterPersonal(plan)
		migrateReaderJourney(plan)
		migrateDownloadOwnership(oldId, newId)

		return Result(
			wasPrivateOnly = wasPrivateOnly,
			newHistory = migratedHistory?.takeIf { it.deletedAt == 0L } ?: targetHistory?.takeIf { it.deletedAt == 0L },
			migratedScrobblers = migratedScrobblers,
		)
	}

	private suspend fun migrateLibraryGroups(plan: Plan) {
		val groupsDao = database.getLibraryGroupsDao()
		for (space in listOf(FavouriteSpace.NORMAL, FavouriteSpace.PRIVATE)) {
			val oldMembers = groupsDao.findMembersByMangaIds(listOf(plan.oldManga.id), space.dbValue)
			if (oldMembers.isEmpty()) continue
			val targetMembers = groupsDao.findMembersByMangaIds(listOf(plan.newManga.id), space.dbValue).associateBy { it.groupId }
			for (source in oldMembers) {
				val target = targetMembers[source.groupId]
				if (target != null && target.position != source.position) {
					conflict("Library group ${source.groupId} has a different destination position")
				}
				if (target == null) groupsDao.insertMembers(listOf(source.copy(mangaId = plan.newManga.id)))

				val timeline = groupsDao.findTimeline(source.groupId)
				val targetTimeline = timeline.filter { it.mangaId == plan.newManga.id }.associateBy { it.chapterId }
				val inserts = ArrayList<org.koitharu.kotatsu.favourites.groups.data.LibraryGroupTimelineItemEntity>()
				for (item in timeline.filter { it.mangaId == plan.oldManga.id }) {
					val mappedChapterId = mappedChapterId(plan.chapterTargets, item.chapterId, "library group timeline")
					val migrated = item.copy(mangaId = plan.newManga.id, chapterId = mappedChapterId)
					val existing = targetTimeline[mappedChapterId]
					if (existing != null && existing.position != migrated.position) {
						conflict("Library group ${source.groupId} timeline conflicts for chapter $mappedChapterId")
					}
					if (existing == null) inserts += migrated
				}
				if (inserts.isNotEmpty()) groupsDao.insertTimeline(inserts)
				groupsDao.deleteMember(source.groupId, plan.oldManga.id)
			}
		}
	}

	private suspend fun migrateStats(oldId: Long, newId: Long) {
		val statsDao = database.getStatsDao()
		val oldStats = statsDao.findAll(oldId)
		if (oldStats.isEmpty()) return
		val targetStats = statsDao.findAll(newId).associateBy { it.startedAt }
		for (source in oldStats) {
			val migrated = source.copy(mangaId = newId)
			val target = targetStats[source.startedAt]
			if (target != null && target != migrated) conflict("Reading stats conflict at ${source.startedAt}")
			if (target == null) statsDao.upsert(migrated)
		}
		database.openHelper.writableDatabase.execSQL(
			"DELETE FROM stats WHERE manga_id = ?",
			arrayOf(oldId),
		)
	}

	private suspend fun migrateTrackLogs(plan: Plan) {
		val dao = database.getTrackLogsDao()
		for (source in dao.findAllForManga(plan.oldManga.id)) {
			val chapterIds = source.chapterIds.split('\n').joinToString("\n") { raw ->
				val id = raw.toLongOrNull() ?: return@joinToString raw
				if (id == 0L) raw else mappedChapterId(plan.chapterTargets, id, "new-chapter feed log").toString()
			}
			dao.insert(
				TrackLogEntity(
					id = source.id,
					mangaId = plan.newManga.id,
					chapters = source.chapters,
					chapterIds = chapterIds,
					createdAt = source.createdAt,
					isUnread = source.isUnread,
				),
			)
		}
	}

	private suspend fun migrateChapterPersonal(plan: Plan) {
		val dao = database.getChapterPersonalDao()
		val sourceByKey = plan.oldManga.chapters.orEmpty().associateBy { it.source.name to it.url }
		val mergedByTarget = LinkedHashMap<Pair<String, String>, ChapterPersonalEntity>()
		for (source in dao.findAll(listOf(plan.oldManga.id))) {
			val oldChapter = sourceByKey[source.source to source.url]
				?: conflict("Per-chapter note/rating no longer resolves to a source chapter")
			val targetChapter = plan.chapterTargets[oldChapter.id]
				?: conflict("Per-chapter note/rating chapter cannot be mapped safely")
			val key = targetChapter.source.name to targetChapter.url
			val migrated = ChapterPersonalEntity(
				mangaId = plan.newManga.id,
				source = key.first,
				url = key.second,
				rating = source.rating,
				note = source.note,
			)
			val previousSource = mergedByTarget[key]
			mergedByTarget[key] = if (previousSource == null) migrated else mergeChapterPersonal(previousSource, migrated)
		}

		for ((key, source) in mergedByTarget) {
			val target = dao.find(plan.newManga.id, key.first, key.second)
			val merged = if (target == null) source else mergeChapterPersonal(target, source)
			dao.upsert(merged)
		}
		for (source in dao.findAll(listOf(plan.oldManga.id))) {
			dao.delete(plan.oldManga.id, source.source, source.url)
		}
	}

	private fun mergeChapterPersonal(a: ChapterPersonalEntity, b: ChapterPersonalEntity): ChapterPersonalEntity {
		if (a.rating != null && b.rating != null && a.rating != b.rating) conflict("Per-chapter ratings conflict")
		if (!a.note.isNullOrBlank() && !b.note.isNullOrBlank() && a.note != b.note) conflict("Per-chapter notes conflict")
		return a.copy(
			rating = a.rating ?: b.rating,
			note = a.note?.takeIf { it.isNotBlank() } ?: b.note,
		)
	}

	private suspend fun migrateReaderJourney(plan: Plan) {
		val oldAwards = findReaderJourneyAwards(plan.oldManga.id)
		if (oldAwards.isEmpty()) return
		val dao = database.getReaderJourneyDao()
		for (source in oldAwards) {
			dao.mergeChapterAward(
				source.copy(
					mangaId = plan.newManga.id,
					chapterId = mappedChapterId(plan.chapterTargets, source.chapterId, "Reader Journey"),
				),
			)
		}
		database.openHelper.writableDatabase.execSQL(
			"DELETE FROM reader_journey_chapters WHERE manga_id = ?",
			arrayOf(plan.oldManga.id),
		)
		dao.rebuildProfileFromLedger()
	}

	private fun findReaderJourneyAwards(mangaId: Long): List<ReaderJourneyChapterEntity> {
		val result = ArrayList<ReaderJourneyChapterEntity>()
		val cursor = database.openHelper.readableDatabase.query(
			"SELECT manga_id, chapter_id, is_novel, reading_units, completion_count, awarded_xp, first_completed_at, last_completed_at " +
				"FROM reader_journey_chapters WHERE manga_id = ?",
			arrayOf(mangaId),
		)
		cursor.use {
			while (it.moveToNext()) {
				result += ReaderJourneyChapterEntity(
					mangaId = it.getLong(0),
					chapterId = it.getLong(1),
					isNovel = it.getInt(2) != 0,
					readingUnits = it.getInt(3),
					completionCount = it.getInt(4),
					awardedXp = it.getLong(5),
					firstCompletedAt = it.getLong(6),
					lastCompletedAt = it.getLong(7),
				)
			}
		}
		return result
	}

	private suspend fun migrateDownloadOwnership(oldId: Long, newId: Long) {
		val dao = database.getFavouriteDownloadIndexDao()
		for (source in dao.findEntries(listOf(oldId))) {
			val target = dao.findEntry(source.space, newId)
			if (target != null && !samePath(target.path, source.path)) {
				conflict("Downloaded ownership path conflicts for favourite space ${source.space}")
			}
			if (target == null) {
				dao.upsert(FavouriteDownloadIndexEntity(newId, source.space, source.path))
			}
			dao.delete(source.space, oldId)
		}
		// local_index is intentionally not re-keyed here. It describes the physical container identity;
		// source replacement must not move or rewrite downloaded files merely to change remote source.
	}

	private suspend fun migrateScrobblingRows(oldId: Long, newId: Long): Set<Int> {
		val dao = database.getScrobblingDao()
		val migrated = LinkedHashSet<Int>()
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
				migrated += source.scrobbler
			}
			dao.delete(source.scrobbler, oldId)
		}
		return migrated
	}

	private fun buildConservativeChapterMap(oldManga: Manga, newManga: Manga): Map<Long, MangaChapter> {
		val oldChapters = oldManga.chapters.orEmpty()
		val newChapters = newManga.chapters.orEmpty()
		if (oldChapters.isEmpty() || newChapters.isEmpty()) return emptyMap()

		val newByNumber = newChapters.filter { it.number > 0f }.groupBy { it.volume to it.number }
		val tentative = LinkedHashMap<Long, MangaChapter>()
		for (oldChapter in oldChapters) {
			if (oldChapter.number <= 0f) continue
			val candidates = newByNumber[oldChapter.volume to oldChapter.number].orEmpty()
			val target = when {
				candidates.size == 1 -> candidates.single()
				candidates.size > 1 -> candidates.filter { it.branch == oldChapter.branch }.singleOrNull()
				else -> null
			}
			if (target != null) tentative[oldChapter.id] = target
		}

		// A lossless transfer must not collapse two distinct source chapter identities into one target.
		val targetCounts = tentative.values.groupingBy { it.id }.eachCount()
		return tentative.filterValues { targetCounts[it.id] == 1 }
	}

	private fun mappedChapterId(targets: Map<Long, MangaChapter>, oldChapterId: Long, owner: String): Long {
		if (oldChapterId == 0L) return 0L
		return targets[oldChapterId]?.id ?: conflict("$owner references chapter $oldChapterId that cannot be mapped safely")
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

	private fun samePath(a: String, b: String): Boolean =
		runCatching { File(a).canonicalPath }.getOrDefault(File(a).absolutePath) ==
			runCatching { File(b).canonicalPath }.getOrDefault(File(b).absolutePath)

	private fun HistoryEntity.copyForMigration(mangaId: Long, chapterId: Long, chaptersCount: Int) = HistoryEntity(
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

	data class Plan(
		val oldManga: Manga,
		val newManga: Manga,
		val chapterTargets: Map<Long, MangaChapter>,
		val oldReadOverrides: Map<Long, Boolean>,
		val targetReadOverrides: Map<Long, Boolean>,
		val mergedReadOverrides: Map<Long, Boolean>,
	)

	data class Result(
		val wasPrivateOnly: Boolean,
		val newHistory: HistoryEntity?,
		val migratedScrobblers: Set<Int>,
	)
}

internal class LosslessMigrationConflictException(message: String) : IllegalStateException(message)
