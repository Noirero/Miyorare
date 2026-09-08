package org.koitharu.kotatsu.history.data

import androidx.room.withTransaction
import dagger.Reusable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.toEntity
import org.koitharu.kotatsu.core.db.entity.toManga
import org.koitharu.kotatsu.core.db.entity.toMangaList
import org.koitharu.kotatsu.core.db.entity.toMangaTags
import org.koitharu.kotatsu.core.db.entity.toMangaTagsList
import org.koitharu.kotatsu.core.model.MangaHistory
import org.koitharu.kotatsu.core.model.isLocal
import org.koitharu.kotatsu.core.model.isNsfw
import org.koitharu.kotatsu.core.model.toMangaSources
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.ProgressIndicatorMode
import org.koitharu.kotatsu.core.ui.util.ReversibleHandle
import org.koitharu.kotatsu.core.util.ext.mapItems
import org.koitharu.kotatsu.history.domain.model.MangaWithHistory
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.list.domain.ListFilterOption
import org.koitharu.kotatsu.list.domain.ListSortOrder
import org.koitharu.kotatsu.list.domain.ReadingProgress
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.util.findById
import org.koitharu.kotatsu.parsers.util.levenshteinDistance
import org.koitharu.kotatsu.scrobbling.common.domain.Scrobbler
import org.koitharu.kotatsu.scrobbling.common.domain.tryScrobble
import org.koitharu.kotatsu.search.domain.SearchKind
import org.koitharu.kotatsu.tracker.domain.CheckNewChaptersUseCase
import kotlin.math.ceil
import javax.inject.Inject
import javax.inject.Provider

@Reusable
class HistoryRepository @Inject constructor(
	private val db: MangaDatabase,
	private val settings: AppSettings,
	private val scrobblers: Set<@JvmSuppressWildcards Scrobbler>,
	private val mangaRepository: MangaDataRepository,
	private val localObserver: HistoryLocalObserver,
	private val newChaptersUseCaseProvider: Provider<CheckNewChaptersUseCase>,
) {

	suspend fun getList(offset: Int, limit: Int): List<Manga> {
		val entities = db.getHistoryDao().findAll(offset, limit)
		return entities.map { it.toManga() }
	}

	suspend fun search(query: String, kind: SearchKind, limit: Int): List<Manga> {
		val dao = db.getHistoryDao()
		val q = "%$query%"
		val entities = when (kind) {
			SearchKind.SIMPLE,
			SearchKind.TITLE -> dao.searchByTitle(q, limit).sortedBy { it.manga.title.levenshteinDistance(query) }

			SearchKind.AUTHOR -> dao.searchByAuthor(q, limit)
			SearchKind.TAG -> dao.searchByTag(q, limit)
		}
		return entities.toMangaList()
	}

	suspend fun getLastOrNull(): Manga? {
		val entity = db.getHistoryDao().findAll(0, 1).firstOrNull() ?: return null
		return entity.toManga()
	}

	fun observeLast(): Flow<Manga?> {
		return db.getHistoryDao().observeAll(1).map {
			val first = it.firstOrNull()
			first?.toManga()
		}
	}

	fun observeAll(): Flow<List<Manga>> {
		return db.getHistoryDao().observeAll().mapItems {
			it.toManga()
		}
	}

	fun observeAll(limit: Int): Flow<List<Manga>> {
		return db.getHistoryDao().observeAll(limit).mapItems {
			it.toManga()
		}
	}

	fun observeAllWithHistory(
		order: ListSortOrder,
		filterOptions: Set<ListFilterOption>,
		limit: Int,
		minUpdatedAt: Long = 0L,
		space: FavouriteSpace = FavouriteSpace.NORMAL,
	): Flow<List<MangaWithHistory>> {
		if (space == FavouriteSpace.NORMAL && ListFilterOption.Downloaded in filterOptions) {
			return localObserver.observeAll(order, filterOptions, limit, minUpdatedAt)
		}
		val flow = if (space == FavouriteSpace.PRIVATE) {
			db.getHistoryDao().observeAllPrivate(order, filterOptions, limit, minUpdatedAt)
		} else {
			db.getHistoryDao().observeAll(order, filterOptions, limit, minUpdatedAt)
		}
		return flow.mapItems {
			MangaWithHistory(
				it.toManga(),
				it.history.toMangaHistory(),
			)
		}.distinctUntilChanged()
	}

	fun observeOne(id: Long): Flow<MangaHistory?> {
		return db.getHistoryDao().observe(id).map {
			it?.toMangaHistory()
		}
	}

	suspend fun addOrUpdate(manga: Manga, chapterId: Long, page: Int, scroll: Int, percent: Float, force: Boolean) {
		if (!force && shouldSkip(manga)) return
		assert(manga.chapters != null)
		db.withTransaction {
			addOrUpdateLocalLocked(manga, chapterId, page, scroll, percent)
		}
		// Source checks and tracker requests can involve network I/O. Keeping them outside Room's
		// transaction prevents a slow source/tracker from holding the database writer and stalling UI.
		newChaptersUseCaseProvider.get()(manga, chapterId)
		if (!isPrivateOnly(manga.id)) {
			scrobblers.forEach { it.tryScrobble(manga, chapterId) }
		}
	}

	suspend fun advanceFromTracking(
		manga: Manga,
		chapters: List<MangaChapter>,
		targetIndex: Int,
	): Boolean {
		if (shouldSkip(manga) || targetIndex !in chapters.indices) return false
		val advanced = db.withTransaction {
			val history = db.getHistoryDao().findIncludingDeleted(manga.id)
			if (!canAdvanceFromTracking(history, chapters, targetIndex)) return@withTransaction false
			val target = chapters[targetIndex]
			addOrUpdateLocalLocked(
				manga = manga,
				chapterId = target.id,
				page = 0,
				scroll = 0,
				percent = (targetIndex + 1) / chapters.size.toFloat(),
			)
			true
		}
		if (advanced) {
			newChaptersUseCaseProvider.get()(manga, chapters[targetIndex].id)
		}
		return advanced
	}

	/** Local atomic history/feed mutation only. Never perform source/tracker I/O from this function. */
	private suspend fun addOrUpdateLocalLocked(
		manga: Manga,
		chapterId: Long,
		page: Int,
		scroll: Int,
		percent: Float,
	) {
		mangaRepository.storeManga(manga.copy(chapters = null), replaceExisting = true)
		val branch = manga.chapters?.findById(chapterId)?.branch
		db.getHistoryDao().upsert(
			HistoryEntity(
				mangaId = manga.id,
				createdAt = System.currentTimeMillis(),
				updatedAt = System.currentTimeMillis(),
				chapterId = chapterId,
				page = page,
				scroll = scroll.toFloat(),
				percent = percent,
				chaptersCount = manga.chapters?.count { it.branch == branch } ?: 0,
				deletedAt = 0L,
			),
		)
		val unreadLogs = db.getTrackLogsDao().findUnreadByManga(manga.id)
		if (unreadLogs.isNotEmpty()) {
			val allChapters = db.getChaptersDao().findAll(manga.id)
			val lastReadChapterIndex = allChapters.indexOfFirst { it.chapterId == chapterId }
			if (lastReadChapterIndex != -1) {
				for (log in unreadLogs) {
					val logChapterIds = log.chapterIds.split('\n').mapNotNull { it.toLongOrNull() }
					val allLogChaptersRead = logChapterIds.all { chId ->
						val chIndex = allChapters.indexOfFirst { it.chapterId == chId }
						chIndex != -1 && chIndex <= lastReadChapterIndex
					}
					if (allLogChaptersRead) db.getTrackLogsDao().markLogAsRead(log.id)
				}
			}
		}
	}

	private suspend fun isPrivateOnly(mangaId: Long): Boolean =
		db.getPrivateFavouritesDao().isPrivateOnly(mangaId)

	suspend fun getOne(manga: Manga): MangaHistory? {
		return db.getHistoryDao().find(manga.id)?.recoverIfNeeded(manga)?.toMangaHistory()
	}

	suspend fun getProgress(mangaId: Long, mode: ProgressIndicatorMode): ReadingProgress? {
		val entity = db.getHistoryDao().find(mangaId) ?: return null
		val fixedPercent = if (ReadingProgress.isCompleted(entity.percent)) 1f else entity.percent
		return ReadingProgress(
			percent = fixedPercent,
			totalChapters = entity.chaptersCount,
			mode = mode,
		).takeIf { it.isValid() }
	}

	suspend fun clear() {
		db.getHistoryDao().clear()
	}

	suspend fun delete(manga: Manga) = db.withTransaction {
		db.getHistoryDao().delete(manga.id)
		mangaRepository.gcChaptersCache()
	}

	suspend fun deleteAfter(minDate: Long) = db.withTransaction {
		db.getHistoryDao().deleteAfter(minDate)
		mangaRepository.gcChaptersCache()
	}

	suspend fun deleteNotFavorite() = db.withTransaction {
		db.getHistoryDao().deleteNotFavorite()
		mangaRepository.gcChaptersCache()
	}

	suspend fun delete(ids: Collection<Long>): ReversibleHandle {
		db.withTransaction {
			for (id in ids) db.getHistoryDao().delete(id)
			mangaRepository.gcChaptersCache()
		}
		return ReversibleHandle { recover(ids) }
	}

	suspend fun deleteOrSwap(manga: Manga, alternative: Manga?) {
		if (alternative == null || db.getMangaDao().update(alternative.toEntity()) <= 0) delete(manga)
	}

	suspend fun getPopularTags(limit: Int): List<MangaTag> =
		db.getHistoryDao().findPopularTags(limit).toMangaTagsList()

	suspend fun getPopularSources(limit: Int): List<MangaSource> =
		db.getHistoryDao().findPopularSources(limit).toMangaSources()

	fun shouldSkip(manga: Manga): Boolean = settings.isIncognitoModeEnabled(manga.isNsfw())

	fun observeShouldSkip(manga: Manga): Flow<Boolean> {
		return settings.observe(AppSettings.KEY_INCOGNITO_MODE, AppSettings.KEY_INCOGNITO_NSFW)
			.map { shouldSkip(manga) }
			.distinctUntilChanged()
	}

	private suspend fun recover(ids: Collection<Long>) {
		db.withTransaction {
			for (id in ids) db.getHistoryDao().recover(id)
		}
	}

	private suspend fun HistoryEntity.recoverIfNeeded(manga: Manga): HistoryEntity {
		val chapters = manga.chapters
		if (manga.isLocal || chapters.isNullOrEmpty() || chapters.findById(chapterId) != null) return this
		val index = ceil(chapters.size * percent.toDouble()).toInt() - 1
		val newChapterId = chapters.getOrNull(index.coerceIn(chapters.indices))?.id ?: return this
		val newEntity = copy(chapterId = newChapterId)
		db.getHistoryDao().update(newEntity)
		return newEntity
	}

	private fun HistoryWithManga.toManga() = manga.toManga(tags.toMangaTags(), null)
}

/**
 * External tracking may only create/advance local history. It must never resurrect explicitly
 * deleted history, move progress backwards, or guess across an unknown chapter/branch mapping.
 */
internal fun canAdvanceFromTracking(
	history: HistoryEntity?,
	chapters: List<MangaChapter>,
	targetIndex: Int,
): Boolean {
	if (targetIndex !in chapters.indices || history?.deletedAt?.let { it != 0L } == true) {
		return false
	}
	val currentIndex = history?.let { item -> chapters.indexOfFirst { it.id == item.chapterId } } ?: -1
	return history == null || currentIndex >= 0 && targetIndex > currentIndex
}
