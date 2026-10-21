package org.koitharu.kotatsu.details.domain

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.TABLE_CHAPTERS
import org.koitharu.kotatsu.core.db.TABLE_FAVOURITES
import org.koitharu.kotatsu.core.db.TABLE_MANGA
import org.koitharu.kotatsu.core.db.TABLE_PRIVATE_FAVOURITES
import org.koitharu.kotatsu.details.data.ReleasePredictionDate
import org.koitharu.kotatsu.details.data.releasePredictionDatesQuery
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.parsers.model.Manga
import java.time.ZoneId
import javax.inject.Inject

/** Local persisted evidence only: no source repositories, refreshes or network fan-out. */
class FavouriteReleasePredictionsUseCase @Inject constructor(private val database: MangaDatabase) {

	fun observe(space: FavouriteSpace): Flow<List<ChapterReleasePredictionItem>> = database.invalidationTracker.createFlow(
		TABLE_MANGA, TABLE_CHAPTERS,
		if (space == FavouriteSpace.PRIVATE) TABLE_PRIVATE_FAVOURITES else TABLE_FAVOURITES,
	).mapLatest { invoke(space) }.flowOn(Dispatchers.IO)

	suspend operator fun invoke(
		space: FavouriteSpace,
		nowMillis: Long = System.currentTimeMillis(),
	): List<ChapterReleasePredictionItem> = withContext(Dispatchers.IO) {
		val zone = ZoneId.systemDefault()
		val results = ArrayList<ChapterReleasePredictionItem>()
		var afterId: Long? = null
		while (true) {
			ensureActive()
			// Each small batch sees one snapshot: a concurrent source replacement cannot pair old
			// manga metadata with a new source's dates. Do not hold a transaction across the library.
			val (manga, dates) = database.withTransaction {
				val candidates = database.getMangaDao().findReleasePredictionCandidates(space == FavouriteSpace.PRIVATE, afterId, BATCH_SIZE)
				val dates = ArrayList<ReleasePredictionDate>()
				if (candidates.isNotEmpty()) {
					val branches = database.getChaptersDao().findReleasePredictionBranches(candidates.map { it.id }, nowMillis)
					for (batch in branches.chunked(BATCH_SIZE)) {
						ensureActive()
						dates += database.getChaptersDao().findReleasePredictionDates(releasePredictionDatesQuery(batch, nowMillis))
					}
				}
				candidates to dates
			}
			if (manga.isEmpty()) break
			val datesByManga = dates.groupBy { it.mangaId }
			for (candidate in manga) {
				ensureActive()
				persistedReleasePredictionItem(candidate.toManga(), datesByManga[candidate.id].orEmpty(), nowMillis, zone)?.let(results::add)
			}
			afterId = manga.last().id
		}
		ensureActive()
		results.sortedWith(releasePredictionOrder)
	}

	private companion object { const val BATCH_SIZE = 100 }
}

internal val releasePredictionOrder = compareBy<ChapterReleasePredictionItem> { it.prediction.expectedDate }
	.thenBy { it.manga.title }.thenBy { it.manga.id }

private data class PersistedReleasePredictionCandidate(
	val item: ChapterReleasePredictionItem,
	val lineage: ReleasePredictionLineage,
)

private val persistedReleasePredictionPreference = Comparator<PersistedReleasePredictionCandidate> { left, right ->
	val lineage = releasePredictionLineagePreference.compare(left.lineage, right.lineage)
	if (lineage != 0) return@Comparator lineage
	val branch = compareValues(left.item.branch.orEmpty(), right.item.branch.orEmpty())
	if (branch != 0) return@Comparator branch
	compareValues(left.item.scanlator.orEmpty(), right.item.scanlator.orEmpty())
}

internal fun persistedReleasePredictionItem(
	manga: Manga,
	dates: List<ReleasePredictionDate>,
	nowMillis: Long,
	zone: ZoneId,
): ChapterReleasePredictionItem? {
	if (manga.isLocal || (manga.state != null && manga.state != org.koitharu.kotatsu.parsers.model.MangaState.ONGOING)) return null
	return dates.asSequence()
		.filter { it.mangaId == manga.id }
		.groupBy { it.branch }
		.mapNotNull { (branch, evidence) ->
			val lineage = selectReleasePredictionLineage(
				points = evidence.map { ReleasePredictionEvidencePoint(it.uploadDate, it.scanlator) },
				nowMillis = nowMillis,
				zone = zone,
			) ?: return@mapNotNull null
			PersistedReleasePredictionCandidate(
				item = ChapterReleasePredictionItem(
					manga = manga,
					prediction = lineage.prediction,
					releaseDates = lineage.releaseDates,
					branch = branch,
					scanlator = lineage.scanlator,
				),
				lineage = lineage,
			)
		}
		.minWithOrNull(persistedReleasePredictionPreference)
		?.item
}
