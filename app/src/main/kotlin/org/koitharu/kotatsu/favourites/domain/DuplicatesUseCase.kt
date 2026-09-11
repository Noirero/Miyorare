package org.koitharu.kotatsu.favourites.domain

import dagger.Reusable
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.model.UniversalMangaIdentity
import org.koitharu.kotatsu.core.model.isLocal
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.favourites.data.FavouriteManga
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.favourites.data.PrivateFavouriteManga
import org.koitharu.kotatsu.favourites.data.toManga
import org.koitharu.kotatsu.list.domain.ReadingProgress.Companion.PROGRESS_NONE
import org.koitharu.kotatsu.local.data.isEpub
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import javax.inject.Inject

/**
 * A favourite that looks like the manga the user is about to add.
 *
 * @param chaptersCount chapters stored for it locally, or 0 when its details were never fetched
 * @param categories titles of the favourite categories it currently sits in
 * @param progress how far it has been read, in 0..1, or [PROGRESS_NONE] when it was never opened
 * @param canReplace false when replacing from the active space could mutate another space, or when
 * the entry has no remote counterpart to re-key against
 */
class MangaDuplicate(
	val manga: Manga,
	val chaptersCount: Int,
	val categories: List<String>,
	val progress: Float,
	val canReplace: Boolean,
)

/**
 * Finds favourites that are probably the same series as [manga] inside one [FavouriteSpace].
 *
 * Normal favourites use [UniversalMangaIdentity], shared with source fusion. Private matching stays
 * deliberately isolated on its historical rules so this experiment cannot change Private behaviour.
 * Tracker IDs are read from the local database as an additional identity signal; this use case never
 * performs tracker/network I/O.
 */
@Reusable
class DuplicatesUseCase @Inject constructor(
	private val db: MangaDatabase,
	private val mangaRepositoryFactory: MangaRepository.Factory,
) {

	suspend operator fun invoke(
		manga: Manga,
		space: FavouriteSpace = FavouriteSpace.NORMAL,
	): List<MangaDuplicate> = when (space) {
		FavouriteSpace.NORMAL -> findNormal(manga)
		FavouriteSpace.PRIVATE -> findPrivate(manga)
	}

	private suspend fun findNormal(manga: Manga): List<MangaDuplicate> {
		val titles = manga.allTitles()
		val found = HashMap<Long, MutableList<FavouriteManga>>()
		val favouritesDao = db.getFavouritesDao()
		for (title in titles) {
			val query = title.lowercase()
			if (query.length < MIN_QUERY_LENGTH) continue
			for (row in favouritesDao.findSimilar(manga.id, query)) {
				found.getOrPut(row.manga.id) { ArrayList(1) }.add(row)
			}
		}
		val matched = HashMap<Long, List<FavouriteManga>>(found.size)
		for ((id, rows) in found) {
			val existing = rows.first().toManga()
			if (UniversalMangaIdentity.isLikelySame(manga, existing)) matched[id] = rows
		}
		val linkedIds = db.getScrobblingDao().findLinkedMangaIds(manga.id).filterNot { it in matched }
		if (linkedIds.isNotEmpty()) {
			for ((id, rows) in favouritesDao.findByIds(linkedIds).groupBy { it.manga.id }) matched[id] = rows
		}
		if (matched.isEmpty()) return emptyList()

		val chaptersDao = db.getChaptersDao()
		val historyDao = db.getHistoryDao()
		val isIncomingProse = manga.isEpub
		return matched.values.mapNotNull { rows ->
			val existing = rows.first().toManga()
			if (existing.isEpub != isIncomingProse) return@mapNotNull null
			MangaDuplicate(
				manga = existing,
				chaptersCount = chaptersDao.count(existing.id),
				categories = rows.mapNotNull { row ->
					row.categories.singleOrNull()?.takeIf { it.deletedAt == 0L }?.title
				}.distinct(),
				progress = historyDao.find(existing.id)?.percent ?: PROGRESS_NONE,
				canReplace = !existing.source.isLocal && !manga.source.isLocal,
			)
		}.sortedByDescending { it.chaptersCount }
	}

	private suspend fun findPrivate(manga: Manga): List<MangaDuplicate> {
		val titles = manga.allTitles()
		val found = HashMap<Long, MutableList<PrivateFavouriteManga>>()
		val privateDao = db.getPrivateFavouritesDao()
		for (title in titles) {
			val query = title.lowercase()
			if (query.length < MIN_QUERY_LENGTH) continue
			for (row in privateDao.findSimilar(manga.id, query)) {
				found.getOrPut(row.manga.id) { ArrayList(1) }.add(row)
			}
		}
		val normalizedTitles = normalizedTitles(titles)
		val matched = HashMap<Long, List<PrivateFavouriteManga>>(found.size)
		for ((id, rows) in found) {
			if (rows.first().toManga().matchesAnyOf(normalizedTitles)) matched[id] = rows
		}

		// A tracker link is only a local identity hint here. Private duplicate checking never contacts
		// the service, and linked IDs are intersected with Private membership before becoming results.
		val linkedIds = db.getScrobblingDao().findLinkedMangaIds(manga.id).filterNot { it in matched }
		if (linkedIds.isNotEmpty()) {
			for ((id, rows) in privateDao.findByIds(linkedIds).groupBy { it.manga.id }) matched[id] = rows
		}
		if (matched.isEmpty()) return emptyList()

		val chaptersDao = db.getChaptersDao()
		val historyDao = db.getHistoryDao()
		val isIncomingProse = manga.isEpub
		return matched.values.mapNotNull { rows ->
			val existing = rows.first().toManga()
			if (existing.isEpub != isIncomingProse) return@mapNotNull null
			MangaDuplicate(
				manga = existing,
				chaptersCount = chaptersDao.count(existing.id),
				categories = rows.mapNotNull { row ->
					row.categories.singleOrNull()
						?.takeIf { it.deletedAt == 0L && it.space == FavouriteSpace.PRIVATE.dbValue }
						?.title
				}.distinct(),
				progress = historyDao.find(existing.id)?.percent ?: PROGRESS_NONE,
				canReplace = privateDao.isPrivateOnly(existing.id) &&
					!existing.source.isLocal && !manga.source.isLocal,
			)
		}.sortedByDescending { it.chaptersCount }
	}

	/**
	 * Chapters known for [manga] without asking the network, or `null` when nothing is stored yet.
	 * Used to show a count immediately while [fetchChaptersCount] catches up.
	 */
	suspend fun getLocalChaptersCount(manga: Manga): Int? {
		manga.chapters?.let { return it.size }
		return db.getChaptersDao().count(manga.id).takeIf { it > 0 }
	}

	/**
	 * Chapter count straight from the source. Returns null on any failure — the chapter-difference
	 * arrows are a nicety, never worth surfacing an error over.
	 */
	suspend fun fetchChaptersCount(manga: Manga): Int? = runCatchingCancellable {
		mangaRepositoryFactory.create(manga.source).getDetails(manga).chapters?.size
	}.getOrNull()

	private fun normalizedTitles(titles: List<String>): Set<String> = titles.mapNotNullTo(HashSet()) {
		it.normalizeTitle().takeIf { s -> s.length >= MIN_NORMALIZED_LENGTH }
	}

	private fun Manga.matchesAnyOf(normalizedTitles: Set<String>): Boolean {
		for (candidate in allTitles()) {
			val normalized = candidate.normalizeTitle()
			if (normalized.length < MIN_NORMALIZED_LENGTH) continue
			if (normalizedTitles.any { it.contains(normalized) || normalized.contains(it) }) return true
		}
		return false
	}

	private fun Manga.allTitles(): List<String> = buildList(altTitles.size + 1) {
		add(title)
		addAll(altTitles)
	}.filter { it.isNotBlank() }

	private fun String.normalizeTitle() = buildString(length) {
		for (c in this@normalizeTitle) {
			if (c.isLetterOrDigit()) append(c.lowercaseChar())
		}
	}

	private companion object {
		/** Below this the SQL `instr` net stops being selective and matches most of the library. */
		const val MIN_QUERY_LENGTH = 3

		/** Same idea after normalization, one higher because punctuation is gone by then. */
		const val MIN_NORMALIZED_LENGTH = 4
	}
}
