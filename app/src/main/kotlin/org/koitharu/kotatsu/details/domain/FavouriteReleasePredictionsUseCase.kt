package org.koitharu.kotatsu.details.domain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.details.data.MangaDetails
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.favourites.domain.FavouritesRepository
import org.koitharu.kotatsu.parsers.model.Manga
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/**
 * Builds the release-prediction picker from persisted favourites only.
 *
 * This deliberately never refreshes a source. Opening the prediction calendar must not fan out
 * network requests across a large library. A favourite without a usable persisted chapter snapshot
 * simply does not appear until normal app activity has populated one.
 */
class FavouriteReleasePredictionsUseCase @Inject constructor(
	private val favouritesRepository: FavouritesRepository,
	private val mangaDataRepository: MangaDataRepository,
) {
	data class Item(
		val manga: Manga,
		val prediction: NextChapterReleasePrediction,
		val releaseDates: List<LocalDate>,
	)

	suspend operator fun invoke(
		space: FavouriteSpace,
		nowMillis: Long = System.currentTimeMillis(),
		zone: ZoneId = ZoneId.systemDefault(),
	): List<Item> = withContext(Dispatchers.IO) {
		favouritesRepository.getAllManga(space).mapNotNull { favourite ->
			ensureActive()
			val stored = mangaDataRepository.findMangaById(favourite.id, withChapters = true) ?: return@mapNotNull null
			if (stored.chapters.isNullOrEmpty()) return@mapNotNull null
			val details = MangaDetails(stored, null, null, null, true)
			val sourceChapters = details.sourceManga.chapters.orEmpty().filter { it.source == stored.source }
			val candidates = sourceChapters
				.groupBy { it.branch }
				.mapNotNull { (branch, _) ->
					predictSourceChapterRelease(details, branch, nowMillis, zone)?.let { branch to it }
				}
			if (candidates.isEmpty()) return@mapNotNull null
			val (branch, prediction) = candidates.minBy { it.second.expectedDate }
			val dates = sourceChapters.asSequence()
				.filter { it.branch == branch && it.uploadDate > 0L && it.uploadDate <= nowMillis }
				.map { Instant.ofEpochMilli(it.uploadDate).atZone(zone).toLocalDate() }
				.distinct()
				.sorted()
				.toList()
			Item(stored, prediction, dates)
		}.sortedWith(compareBy<Item> { it.prediction.expectedDate }.thenBy { it.manga.title })
	}
}
