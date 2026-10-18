package org.koitharu.kotatsu.alternatives.domain

import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.local.data.index.LocalMangaIndexEntity
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import javax.inject.Inject

/**
 * Transfers durable state that belongs to a logical title but is keyed by source-specific manga or
 * chapter identities. The plan is deliberately conservative: only chapters with an exact
 * (volume, number) match are re-keyed. Unmatched source state is retained instead of being deleted.
 */
class MigrationDataTransfer @Inject constructor(
	private val database: MangaDatabase,
	private val settings: AppSettings,
) {
	data class Plan(
		val oldManga: Manga,
		val newManga: Manga,
		val chapterIds: Map<Long, Long>,
		val readOverrides: Map<Long, Boolean>,
	)

	suspend fun prepare(oldManga: Manga, newManga: Manga): Plan {
		val chapterIds = mapChapterIds(oldManga, newManga)
		val migratedOverrides = LinkedHashMap<Long, Boolean>()
		for ((oldChapterId, value) in settings.getChapterReadOverrides(oldManga.id)) {
			val newChapterId = chapterIds[oldChapterId] ?: continue
			val previous = migratedOverrides.put(newChapterId, value)
			require(previous == null || previous == value) {
				"Migration collapses conflicting read overrides onto chapter id=$newChapterId"
			}
		}
		val targetOverrides = settings.getChapterReadOverrides(newManga.id)
		for ((chapterId, value) in migratedOverrides) {
			require(targetOverrides[chapterId] == null || targetOverrides[chapterId] == value) {
				"Migration target already has a different read override for chapter id=$chapterId"
			}
		}

		preflightRoom(oldManga, newManga, chapterIds)
		return Plan(oldManga, newManga, chapterIds, migratedOverrides)
	}

	private suspend fun preflightRoom(oldManga: Manga, newManga: Manga, chapterIds: Map<Long, Long>) {
		val prefs = database.getPreferencesDao()
		val oldPrefs = prefs.find(oldManga.id)
		val newPrefs = prefs.find(newManga.id)
		require(oldPrefs == null || newPrefs == null || oldPrefs.copy(mangaId = newManga.id) == newPrefs) {
			"Migration target already has different manga preferences"
		}

		val history = database.getHistoryDao()
		val oldHistory = history.find(oldManga.id)
		val newHistory = history.find(newManga.id)
		require(oldHistory == null || newHistory == null) {
			"Migration target already has reading history; refusing to overwrite it"
		}

		val localIndex = database.getLocalMangaIndexDao()
		val oldLocal = localIndex.findPath(oldManga.id)
		val newLocal = localIndex.findPath(newManga.id)
		require(oldLocal == null || newLocal == null || oldLocal == newLocal) {
			"Migration target already owns a different local/download path"
		}

		val downloadIndex = database.getFavouriteDownloadIndexDao()
		val targetDownloads = downloadIndex.findEntries(listOf(newManga.id)).associateBy { it.space }
		for (entry in downloadIndex.findEntries(listOf(oldManga.id))) {
			val target = targetDownloads[entry.space]
			require(target == null || target.path == entry.path) {
				"Migration target already owns a different download path in space=${entry.space}"
			}
		}

		val targetBookmarks = database.getBookmarksDao().findAll(newManga.id).associateBy { it.pageId }
		for (bookmark in database.getBookmarksDao().findAll(oldManga.id)) {
			val mappedChapter = chapterIds[bookmark.chapterId] ?: continue
			val migrated = bookmark.copy(mangaId = newManga.id, chapterId = mappedChapter)
			val target = targetBookmarks[migrated.pageId]
			require(target == null || target == migrated) {
				"Migration target already has a conflicting bookmark page id=${migrated.pageId}"
			}
		}

		val targetStats = database.getStatsDao().findAll(newManga.id).associateBy { it.startedAt }
		for (stat in database.getStatsDao().findAll(oldManga.id)) {
			val migrated = stat.copy(mangaId = newManga.id)
			val target = targetStats[stat.startedAt]
			require(target == null || target == migrated) {
				"Migration target already has conflicting reading stats at ${stat.startedAt}"
			}
		}

		val personalDao = database.getChapterPersonalDao()
		val oldPersonal = personalDao.findAll(listOf(oldManga.id))
		val oldChapterByKey = oldManga.chapters.orEmpty().associateBy { it.source.name to it.url }
		val newChapterById = newManga.chapters.orEmpty().associateBy { it.id }
		for (personal in oldPersonal) {
			val oldChapter = oldChapterByKey[personal.source to personal.url] ?: continue
			val newChapter = chapterIds[oldChapter.id]?.let(newChapterById::get) ?: continue
			val target = personalDao.find(newManga.id, newChapter.source.name, newChapter.url)
			require(target == null || target.rating == personal.rating && target.note == personal.note) {
				"Migration target already has different chapter note/rating for ${newChapter.url}"
			}
		}
	}

	suspend fun moveIdentityState(plan: Plan) {
		moveGroups(plan)
		moveDownloadIndexes(plan)
	}

	private suspend fun moveGroups(plan: Plan) {
		val dao = database.getLibraryGroupsDao()
		for (space in listOf(FavouriteSpace.NORMAL, FavouriteSpace.PRIVATE)) {
			val oldMemberships = dao.findMembersByMangaIds(listOf(plan.oldManga.id), space.dbValue)
			if (oldMemberships.isEmpty()) continue
			val oldGroupIds = oldMemberships.mapTo(LinkedHashSet()) { it.groupId }
			val targetMemberships = dao.findMembersByMangaIds(listOf(plan.newManga.id), space.dbValue).associateBy { it.groupId }
			require(targetMemberships.keys.all { it in oldGroupIds }) {
				"Migration target already belongs to a different library group in ${space.name}"
			}
			for (member in oldMemberships) {
				val target = targetMemberships[member.groupId]
				if (target == null) dao.insertMembers(listOf(member.copy(mangaId = plan.newManga.id)))
				val timeline = dao.findTimeline(member.groupId)
				val existing = timeline.asSequence().filter { it.mangaId == plan.newManga.id }.mapTo(HashSet()) { it.chapterId }
				val migrated = timeline.asSequence()
					.filter { it.mangaId == plan.oldManga.id }
					.mapNotNull { item -> plan.chapterIds[item.chapterId]?.let { item.copy(mangaId = plan.newManga.id, chapterId = it) } }
					.filter { existing.add(it.chapterId) }
					.toList()
				if (migrated.isNotEmpty()) dao.insertTimeline(migrated)
				dao.deleteMember(member.groupId, plan.oldManga.id)
			}
		}
	}

	private suspend fun moveDownloadIndexes(plan: Plan) {
		val localDao = database.getLocalMangaIndexDao()
		localDao.findPath(plan.oldManga.id)?.let { path ->
			if (localDao.findPath(plan.newManga.id) == null) {
				localDao.upsert(LocalMangaIndexEntity(plan.newManga.id, path))
			}
			localDao.delete(plan.oldManga.id)
		}
		val downloadDao = database.getFavouriteDownloadIndexDao()
		val target = downloadDao.findEntries(listOf(plan.newManga.id)).associateBy { it.space }
		for (entry in downloadDao.findEntries(listOf(plan.oldManga.id))) {
			if (target[entry.space] == null) downloadDao.upsert(entry.copy(mangaId = plan.newManga.id))
			downloadDao.delete(entry.space, plan.oldManga.id)
		}
	}

	suspend fun moveChapterBoundState(plan: Plan) {
		moveBookmarks(plan)
		moveChapterPersonal(plan)
		moveReaderJourney(plan)
		moveTrackLogs(plan)
	}

	private suspend fun moveBookmarks(plan: Plan) {
		val dao = database.getBookmarksDao()
		for (bookmark in dao.findAll(plan.oldManga.id)) {
			val chapterId = plan.chapterIds[bookmark.chapterId] ?: continue
			dao.upsert(listOf(bookmark.copy(mangaId = plan.newManga.id, chapterId = chapterId)))
			dao.delete(bookmark)
		}
	}

	private suspend fun moveChapterPersonal(plan: Plan) {
		val dao = database.getChapterPersonalDao()
		val oldChapterByKey = plan.oldManga.chapters.orEmpty().associateBy { it.source.name to it.url }
		val newChapterById = plan.newManga.chapters.orEmpty().associateBy { it.id }
		for (personal in dao.findAll(listOf(plan.oldManga.id))) {
			val oldChapter = oldChapterByKey[personal.source to personal.url] ?: continue
			val newChapter = plan.chapterIds[oldChapter.id]?.let(newChapterById::get) ?: continue
			dao.upsert(personal.copy(mangaId = plan.newManga.id, source = newChapter.source.name, url = newChapter.url))
			dao.delete(personal.mangaId, personal.source, personal.url)
		}
	}

	private suspend fun moveReaderJourney(plan: Plan) {
		val dao = database.getReaderJourneyDao()
		var changed = false
		for (award in dao.findChapterAwards(plan.oldManga.id)) {
			val chapterId = plan.chapterIds[award.chapterId] ?: continue
			dao.mergeChapterAward(award.copy(mangaId = plan.newManga.id, chapterId = chapterId))
			dao.deleteChapterAward(plan.oldManga.id, award.chapterId)
			changed = true
		}
		if (changed) dao.rebuildProfileFromLedger()
	}

	private suspend fun moveTrackLogs(plan: Plan) {
		val dao = database.getTrackLogsDao()
		for (log in dao.findAllForManga(plan.oldManga.id)) {
			val chapterIds = log.chapterIds.split('\n').joinToString("\n") { raw ->
				raw.toLongOrNull()?.let { plan.chapterIds[it] }?.toString() ?: raw
			}
			dao.insert(log.copy(mangaId = plan.newManga.id, chapterIds = chapterIds))
		}
	}

	suspend fun moveStatsAfterHistory(plan: Plan) {
		val dao = database.getStatsDao()
		for (stat in dao.findAll(plan.oldManga.id)) dao.upsert(stat.copy(mangaId = plan.newManga.id))
	}

	fun finishReadOverrides(plan: Plan) {
		if (plan.readOverrides.isEmpty()) return
		val target = settings.getChapterReadOverrides(plan.newManga.id).toMutableMap()
		target.putAll(plan.readOverrides)
		settings.setChapterReadOverrides(plan.newManga.id, target)
		val source = settings.getChapterReadOverrides(plan.oldManga.id).toMutableMap()
		val migratedOldIds = plan.chapterIds.filterValues { it in plan.readOverrides }.keys
		for (chapterId in migratedOldIds) source.remove(chapterId)
		settings.setChapterReadOverrides(plan.oldManga.id, source)
	}

	private fun mapChapterIds(oldManga: Manga, newManga: Manga): Map<Long, Long> {
		val targets = newManga.chapters.orEmpty().filter { it.number > 0f }.groupBy { it.volume to it.number }
		val result = LinkedHashMap<Long, Long>()
		for (chapter in oldManga.chapters.orEmpty()) {
			if (chapter.number <= 0f) continue
			val candidates = targets[chapter.volume to chapter.number].orEmpty()
			if (candidates.size == 1) result[chapter.id] = candidates.single().id
		}
		return result
	}
}
