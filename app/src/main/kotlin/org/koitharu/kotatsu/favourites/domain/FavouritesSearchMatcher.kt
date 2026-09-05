package org.koitharu.kotatsu.favourites.domain

import androidx.collection.LongObjectMap
import dagger.Reusable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.ui.model.MangaOverride
import org.koitharu.kotatsu.details.data.MangaNotesRepository
import org.koitharu.kotatsu.favourites.data.FavouriteSearchEntry
import org.koitharu.kotatsu.parsers.model.Manga
import javax.inject.Inject

/** Matches Favourites library items against user-visible metadata plus local Notes. */
@Reusable
class FavouritesSearchMatcher @Inject constructor(
	private val mangaDataRepository: MangaDataRepository,
	private val notesRepository: MangaNotesRepository,
) {

	suspend fun filter(items: List<Manga>, rawQuery: String): List<Manga> {
		val query = rawQuery.trim()
		if (query.isEmpty() || items.isEmpty()) return items
		val context = createContext()
		val result = ArrayList<Manga>()
		for ((index, manga) in items.withIndex()) {
			// Filtering tens of thousands of favourites has very few suspension points. Explicitly check
			// cancellation so a newer debounced query can abandon the old scan instead of finishing it.
			if ((index and CANCELLATION_CHECK_MASK) == 0) currentCoroutineContext().ensureActive()
			val override = context.overrides[manga.id]
			if (
				manga.title.contains(query, ignoreCase = true) ||
				override?.title?.contains(query, ignoreCase = true) == true ||
				manga.authors.any { it.contains(query, ignoreCase = true) } ||
				override?.author?.contains(query, ignoreCase = true) == true ||
				override?.artist?.contains(query, ignoreCase = true) == true ||
				context.notes[manga.id]?.contains(query, ignoreCase = true) == true
			) {
				result += manga
			}
		}
		return result
	}

	/**
	 * Searches only the columns actually needed for category badge counts. This avoids materialising
	 * full Manga objects, tags, covers, and category relations for the entire library just because the
	 * user typed into Favourites search.
	 */
	suspend fun matchingIds(items: List<FavouriteSearchEntry>, rawQuery: String): Set<Long> {
		val query = rawQuery.trim()
		if (items.isEmpty()) return emptySet()
		if (query.isEmpty()) return items.mapTo(HashSet(items.size)) { it.mangaId }
		val context = createContext()
		val result = HashSet<Long>()
		for ((index, item) in items.withIndex()) {
			if ((index and CANCELLATION_CHECK_MASK) == 0) currentCoroutineContext().ensureActive()
			val override = context.overrides[item.mangaId]
			if (
				item.title.contains(query, ignoreCase = true) ||
				override?.title?.contains(query, ignoreCase = true) == true ||
				item.authors?.lineSequence()?.any { it.contains(query, ignoreCase = true) } == true ||
				override?.author?.contains(query, ignoreCase = true) == true ||
				override?.artist?.contains(query, ignoreCase = true) == true ||
				context.notes[item.mangaId]?.contains(query, ignoreCase = true) == true
			) {
				result += item.mangaId
			}
		}
		return result
	}

	private suspend fun createContext() = SearchContext(
		overrides = mangaDataRepository.getOverrides(),
		notes = notesRepository.snapshot(),
	)

	private data class SearchContext(
		val overrides: LongObjectMap<MangaOverride>,
		val notes: Map<Long, String>,
	)

	private companion object {
		// 256 items: cancellation remains responsive without adding a branch on every item.
		const val CANCELLATION_CHECK_MASK = 0xFF
	}
}
