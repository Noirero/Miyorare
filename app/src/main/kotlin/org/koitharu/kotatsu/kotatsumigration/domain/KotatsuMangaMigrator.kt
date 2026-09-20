package org.koitharu.kotatsu.kotatsumigration.domain

import androidx.room.withTransaction
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.details.data.MangaNotesRepository
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.history.data.HistoryEntity
import org.koitharu.kotatsu.local.data.index.LocalMangaIndexEntity
import org.koitharu.kotatsu.mihon.model.mihonChapterId
import org.koitharu.kotatsu.mihon.model.mihonMangaId
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.scrobbling.common.data.ScrobblingEntity
import org.koitharu.kotatsu.tracker.data.TrackEntity
import org.koitharu.kotatsu.tracker.data.TrackLogEntity
import javax.inject.Inject

/**
 * Re-keys a restored Kotatsu library entry onto its mapped Mihon source **offline** — no network.
 *
 * Because a Mihon manga's id is a pure hash of (source name, url) ([mihonMangaId]), the canonical
 * new id is computable without fetching. We store the same manga under that new id with the Mihon
 * source, move all user data (Normal/Private favourites, groups, history with **percent preserved**,
 * bookmarks, tracker/feed, preferences, notes/read overrides, download indexes, scrobbling and stats) onto it, keep the
 * cached chapters (so "continue reading" still resolves), and
 * delete the old row — its remaining children fall away via `ON DELETE CASCADE`.
 *
 * Manga details (live chapter list) load lazily the first time the user opens the manga, exactly
 * like any other manga. If the Kotatsu url happens to differ from the extension's url scheme, the
 * open-time `getDetails` 404s and the app's existing [org.koitharu.kotatsu.explore.domain.RecoverMangaUseCase]
 * repairs the url by title-search — keeping the same id, so favourites/history stay attached.
 */
class KotatsuMangaMigrator @Inject constructor(
	private val mangaDataRepository: MangaDataRepository,
	private val database: MangaDatabase,
	private val settings: AppSettings,
	private val notesRepository: MangaNotesRepository,
) {

	/**
	 * @param newSource the target Mihon source: the running [org.koitharu.kotatsu.mihon.model.MihonMangaSource]
	 *  when its extension is installed, otherwise a `MissingMangaSource("MIHON_<id>", title)` so the
	 *  entry still converts and shows its cached title until the extension is installed.
	 * @return the new manga id, or null if nothing changed (already migrated).
	 */
	suspend operator fun invoke(oldManga: Manga, newSource: MangaSource): Long? {
		val oldId = oldManga.id
		val newUrl = oldManga.url.toMihonUrl()
		val newId = mihonMangaId(newSource.name, newUrl)
		if (newId == oldId) {
			return null
		}
		// Re-key cached chapters exactly like the live Mihon adapter. Keeping their Kotatsu ids here
		// makes the first refresh lose the chapter pointer and recover it from the old percentage.
		val migratedChapters = oldManga.chapters?.map { it.forMihonSource(newSource) }
		val chapterIds = oldManga.chapters.orEmpty()
			.zip(migratedChapters.orEmpty())
			.associate { (old, new) -> old.id to new.id }
		fun migrateChapterId(id: Long) = chapterIds[id] ?: id

		val oldNote = notesRepository.get(oldId)
		val targetNote = notesRepository.get(newId)
		require(oldNote == null || targetNote == null || oldNote == targetNote) {
			"Migration target manga already has a different note"
		}

		val migratedReadOverrides = LinkedHashMap<Long, Boolean>()
		for ((oldChapterId, isRead) in settings.getChapterReadOverrides(oldId)) {
			val migratedChapterId = migrateChapterId(oldChapterId)
			val previous = migratedReadOverrides.put(migratedChapterId, isRead)
			require(previous == null || previous == isRead) {
				"Migration collapses conflicting chapter read overrides onto chapter id=$migratedChapterId"
			}
		}
		val targetReadOverrides = settings.getChapterReadOverrides(newId)
		require(
			migratedReadOverrides.isEmpty() ||
				targetReadOverrides.isEmpty() ||
				targetReadOverrides == migratedReadOverrides,
		) {
			"Migration target manga already has different chapter read overrides"
		}

		val newManga = oldManga.copy(id = newId, url = newUrl, source = newSource, chapters = migratedChapters)
		database.getMangaDao().find(newId)?.manga?.let { existing ->
			require(existing.source == newSource.name && existing.url == newUrl) {
				"Migration target identity collision for id=$newId: existing=${existing.source}:${existing.url}, target=${newSource.name}:$newUrl"
			}
		}
		mangaDataRepository.storeManga(newManga, replaceExisting = true)

		database.withTransaction {
			// favourites — copy onto the new id (old rows fall away with the old manga via CASCADE)
			val favouritesDao = database.getFavouritesDao()
			for (f in favouritesDao.findAllRaw(oldId)) {
				favouritesDao.upsert(f.copy(mangaId = newId))
			}

			// Private favourites use the same category ids but a separate membership table.
			// Copy them before deleting the old manga so FK cascade cannot silently drop Private membership.
			val privateFavouritesDao = database.getPrivateFavouritesDao()
			for (f in privateFavouritesDao.findAllRaw(oldId)) {
				privateFavouritesDao.upsert(f.copy(mangaId = newId))
			}

			// Advanced Library Groups are also keyed by manga id. Preserve memberships and timeline
			// chapter pointers before the old member rows disappear via cascade.
			val groupsDao = database.getLibraryGroupsDao()
			for (space in listOf(FavouriteSpace.NORMAL, FavouriteSpace.PRIVATE)) {
				val oldMemberships = groupsDao.findMembersByMangaIds(listOf(oldId), space.dbValue)
				if (oldMemberships.isEmpty()) continue
				val oldGroupIds = oldMemberships.mapTo(LinkedHashSet()) { it.groupId }
				require(oldGroupIds.size == 1) {
					"Migration source manga belongs to multiple library groups in ${space.name}"
				}
				val targetMemberships = groupsDao.findMembersByMangaIds(listOf(newId), space.dbValue)
					.associateBy { it.groupId }
				require(targetMemberships.keys.all { it in oldGroupIds }) {
					"Migration target manga already belongs to a different library group in ${space.name}"
				}
				for (member in oldMemberships) {
					val targetMember = targetMemberships[member.groupId]
					if (targetMember == null) {
						groupsDao.insertMembers(listOf(member.copy(mangaId = newId)))
					} else if (targetMember.position != member.position) {
						groupsDao.updateMemberPosition(member.groupId, newId, member.position)
					}
					val timeline = groupsDao.findTimeline(member.groupId)
					val existingTargetChapterIds = timeline.asSequence()
						.filter { it.mangaId == newId }
						.mapTo(HashSet()) { it.chapterId }
					val migratedTimeline = timeline.asSequence()
						.filter { it.mangaId == oldId }
						.map { item ->
							item.copy(
								mangaId = newId,
								chapterId = migrateChapterId(item.chapterId),
							)
						}
						.filter { existingTargetChapterIds.add(it.chapterId) }
						.toList()
					if (migratedTimeline.isNotEmpty()) groupsDao.insertTimeline(migratedTimeline)
				}
			}

			// Per-manga reader/override preferences are user state too. Keep the stored cover URL as-is:
			// local custom-cover files remain readable, while future sync/backup can re-materialize them
			// under the new manga id.
			val preferencesDao = database.getPreferencesDao()
			preferencesDao.find(oldId)?.let { prefs ->
				preferencesDao.upsert(prefs.copy(mangaId = newId))
			}

			// Preserve new-chapter feed rows. Keep their stable row ids and translate persisted chapter ids.
			val logsDao = database.getTrackLogsDao()
			for (log in logsDao.findAllForManga(oldId)) {
				val migratedChapterIds = log.chapterIds
					.split('\n')
					.joinToString("\n") { raw ->
						raw.toLongOrNull()?.let(::migrateChapterId)?.toString() ?: raw
					}
				logsDao.insert(
					TrackLogEntity(
						id = log.id,
						mangaId = newId,
						chapters = log.chapters,
						chapterIds = migratedChapterIds,
						createdAt = log.createdAt,
						isUnread = log.isUnread,
					),
				)
			}

			// Preserve download discovery/ownership rows. Paths remain valid because re-keying changes
			// database identity only; it does not move downloaded files on disk.
			val localIndexDao = database.getLocalMangaIndexDao()
			for (entry in localIndexDao.findEntries(listOf(oldId))) {
				localIndexDao.upsert(LocalMangaIndexEntity(mangaId = newId, path = entry.path))
			}
			val downloadIndexDao = database.getFavouriteDownloadIndexDao()
			val downloadOwnership = downloadIndexDao.findEntries(listOf(oldId))
			if (downloadOwnership.isNotEmpty()) {
				downloadIndexDao.upsert(downloadOwnership.map { it.copy(mangaId = newId) })
			}

			// history — percent preserved and chapter pointer translated to the Mihon identity
			val historyDao = database.getHistoryDao()
			historyDao.find(oldId)?.let { h ->
				historyDao.upsert(
					HistoryEntity(
						mangaId = newId,
						createdAt = h.createdAt,
						updatedAt = h.updatedAt,
						chapterId = migrateChapterId(h.chapterId),
						page = h.page,
						scroll = h.scroll,
						percent = h.percent,
						deletedAt = 0L,
						chaptersCount = h.chaptersCount,
					),
				)
			}

			// stats — FK -> history(manga_id), so the new history above must already exist
			val statsDao = database.getStatsDao()
			for (s in statsDao.findAll(oldId)) {
				statsDao.upsert(s.copy(mangaId = newId))
			}

			// tracker
			val tracksDao = database.getTracksDao()
			tracksDao.find(oldId)?.let { t ->
				tracksDao.upsert(
					TrackEntity(
						mangaId = newId,
						lastChapterId = migrateChapterId(t.lastChapterId),
						newChapters = t.newChapters,
						lastCheckTime = t.lastCheckTime,
						lastChapterDate = t.lastChapterDate,
						lastResult = t.lastResult,
						lastError = t.lastError,
					),
				)
			}

			// bookmarks — translate their chapter pointers with the cached chapters
			val bookmarksDao = database.getBookmarksDao()
			val oldBookmarks = bookmarksDao.findAll(oldId)
			if (oldBookmarks.isNotEmpty()) {
				bookmarksDao.upsert(oldBookmarks.map {
					it.copy(mangaId = newId, chapterId = migrateChapterId(it.chapterId))
				})
			}

			// scrobbling — no manga FK, so move explicitly and delete the old rows
			val scrobblingDao = database.getScrobblingDao()
			val oldScrobblings = scrobblingDao.findAll(oldId)
			for (s in oldScrobblings) {
				scrobblingDao.upsert(
					ScrobblingEntity(
						scrobbler = s.scrobbler,
						id = s.id,
						mangaId = newId,
						targetId = s.targetId,
						status = s.status,
						chapter = s.chapter,
						comment = s.comment,
						rating = s.rating,
					),
				)
			}
			for (scrobbler in oldScrobblings.map { it.scrobbler }.distinct()) {
				scrobblingDao.delete(scrobbler, oldId)
			}

			// retire the old entry — CASCADE removes its favourites/history/bookmarks/tracks/stats/
			// chapters/tags that still reference the old id.
			database.getMangaDao().find(oldId)?.manga?.let { oldEntity ->
				database.getMangaDao().delete(listOf(oldEntity))
			}
		}

		if (oldNote != null) notesRepository.set(newId, oldNote)
		notesRepository.set(oldId, null)
		if (migratedReadOverrides.isNotEmpty()) {
			settings.setChapterReadOverrides(newId, migratedReadOverrides)
		}
		settings.setChapterReadOverrides(oldId, emptyMap())
		return newId
	}
}

internal fun MangaChapter.forMihonSource(source: MangaSource): MangaChapter = url.toMihonUrl().let { newUrl ->
	copy(
		id = mihonChapterId(source.name, newUrl),
		url = newUrl,
		source = source,
	)
}

/**
 * Kotatsu parsers are inconsistent about url form — many store a relative path, but a good number
 * (DemonicScans, for one) store the full absolute url. Mihon's `HttpSource` always resolves
 * `baseUrl + url`, so an absolute url both breaks fetching (`demonicscans.orghttps://…`: an
 * UnknownHostException) and yields an id that can never match the one browsing the same source
 * produces — so the entry looks unfavourited when found through search.
 *
 * Reducing an absolute url to its path (+query) is what Mihon extensions expect and makes the
 * migrated id identical to the live one. Relative urls are returned untouched.
 */
internal fun String.toMihonUrl(): String {
	val http = toHttpUrlOrNull() ?: return this
	return http.encodedPath + http.encodedQuery?.let { "?$it" }.orEmpty()
}

/**
 * Whether a request an extension built from a stored **absolute** url shows the signature of
 * `baseUrl + url` concatenation — the failure [toMihonUrl] prevents, detected here so already-broken
 * entries can be repaired. OkHttp normalizes the two glue shapes differently, so both are matched:
 *
 *  - `baseUrl` without a trailing slash — `demonicscans.org` + `https://demonicscans.org/manga/x`
 *    parses to the host `demonicscans.orghttps`: the base host with junk appended (the
 *    UnknownHostException users hit).
 *  - `baseUrl` with a trailing slash — the host stays valid and the whole url lands in the path as
 *    `/https://demonicscans.org/manga/x`, so a scheme inside the path is the tell.
 *
 * Deliberately narrow: a source that owns its absolute urls requests them unchanged (same host, no
 * scheme in the path), and one that fetches from a sibling api host doesn't match the base-host
 * prefix. Both are reported healthy and left alone.
 */
internal fun isGluedUrl(baseHost: String, requestHost: String, requestPath: String): Boolean {
	return (requestHost != baseHost && requestHost.startsWith(baseHost)) || requestPath.contains("://")
}
