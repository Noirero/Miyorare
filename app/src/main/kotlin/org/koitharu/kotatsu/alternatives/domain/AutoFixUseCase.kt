package org.koitharu.kotatsu.alternatives.domain

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.koitharu.kotatsu.core.model.isExternalSource
import org.koitharu.kotatsu.core.model.parcelable.ParcelableManga
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.search.domain.SearchSourceMode
import javax.inject.Inject

class AutoFixUseCase @Inject constructor(
	private val mangaRepositoryFactory: MangaRepository.Factory,
	private val alternativesUseCase: AlternativesUseCase,
	private val migrateUseCase: MigrateUseCase,
	private val mangaDataRepository: MangaDataRepository,
	private val candidateMatcher: MigrationCandidateMatcher,
) {

	suspend operator fun invoke(mangaId: Long): Pair<Manga, Manga?> {
		val discovery = discover(mangaId)
		val seed = discovery.seed
		if (discovery.sourceHealthy) return seed to null
		if (discovery.incomplete) throw CandidateDiscoveryIncompleteException(ParcelableManga(seed))
		val assessment = checkNotNull(discovery.assessment)
		val replacement = when (assessment.state) {
			MigrationMatchState.READY -> checkNotNull(assessment.best).manga
			MigrationMatchState.NEEDS_REVIEW -> throw NeedsReviewException(
				seed = ParcelableManga(seed),
				candidates = assessment.candidates.map { ParcelableManga(it.manga) },
			)
			MigrationMatchState.NO_REPLACEMENT -> {
				throw NoAlternativesException(ParcelableManga(seed))
			}
		}

		migrateUseCase(
			oldManga = seed,
			newManga = replacement,
			lossless = true,
		)
		return seed to replacement
	}

	/** Discovery is a preview, never permission to mutate user data. */
	suspend fun discover(mangaId: Long): MigrationDiscovery {
		val seed = checkNotNull(
			mangaDataRepository.findMangaById(mangaId, withChapters = true),
		) { "Manga $mangaId not found" }.getDetailsSafe()
		if (seed.isHealthy()) {
			return MigrationDiscovery(seed, sourceHealthy = true)
		}

		// Broken-source migration must search the whole enabled source domain. Pinned/language filters
		// are manual Alternatives preferences, not identity constraints for an automatic repair.
		val sources = alternativesUseCase.getSources(seed, SearchSourceMode.ALL_SOURCES, emptySet())
		val observations = ArrayList<MigrationCandidateObservation>()
		var discoveryError: Throwable? = null
		for (query in buildSearchQueries(seed)) {
			alternativesUseCase(
				manga = seed,
				mode = SearchSourceMode.ALL_SOURCES,
				preferredLanguages = emptySet(),
				query = query,
				precomputedSources = sources,
				maxDetailCandidates = MAX_MIGRATION_DETAIL_CANDIDATES,
			).collect { event ->
				when (event) {
					is AlternativeSearchEvent.Result -> {
						observations += MigrationCandidateObservation(event.manga, setOf(query))
					}
					is AlternativeSearchEvent.SourceFinished -> {
						if (event.error != null && discoveryError == null) discoveryError = event.error
					}
				}
			}
		}

		// Rank identity evidence before doing the more expensive page-serving health checks. The cap is
		// deterministic and large enough to keep multiple plausible sources available for ambiguity
		// detection without reverting to the old timing-dependent "first few arrivals" behaviour.
		val healthy = ArrayList<MigrationCandidateObservation>()
		for (candidate in candidateMatcher.rank(seed, observations).take(MAX_HEALTH_CHECKS)) {
			val probe = candidate.manga.probeHealth()
			if (probe.isFailure && discoveryError == null) discoveryError = probe.exceptionOrNull()
			val details = probe.getOrNull() ?: continue
			healthy += MigrationCandidateObservation(details, candidate.matchedQueries)
		}
		val assessment = candidateMatcher.assess(seed, healthy)
		return MigrationDiscovery(seed, assessment, incomplete = discoveryError != null)
	}

	/** Re-discover immediately before executing; a UI snapshot is never identity authority. */
	suspend fun migrateSelected(mangaId: Long, selection: MigrationSelection, reviewed: Boolean): Manga? {
		val current = discover(mangaId)
		if (current.sourceHealthy) return null
		val replacement = MigrationExecutionPolicy.select(current, selection, reviewed)
			?: throw SelectionChangedException(current)
		migrateUseCase(oldManga = current.seed, newManga = replacement, lossless = true, requireLibraryEntry = true)
		return replacement
	}

	private suspend fun Manga.isHealthy(): Boolean = getHealthyDetailsOrNull() != null

	private suspend fun Manga.getHealthyDetailsOrNull(): Manga? = probeHealth().getOrNull()

	private suspend fun Manga.probeHealth(): Result<Manga?> = runCatchingCancellable {
		val repo = mangaRepositoryFactory.create(source)
		val details = if (this.chapters != null) this else repo.getDetails(this)
		val firstChapter = details.chapters?.firstOrNull() ?: return@runCatchingCancellable null
		val firstPage = repo.getPages(firstChapter).firstOrNull() ?: return@runCatchingCancellable null
		val pageUrl = repo.getPageUrl(firstPage)
		// Most sources expose a fetchable http(s) page url. Some extensions (e.g. MangaDex) return a
		// relative imageUrl whose host is resolved internally by getImage(), so a non-blank url from
		// an external source still indicates a healthy, page-serving source.
		if (pageUrl.toHttpUrlOrNull() != null || (source.isExternalSource() && pageUrl.isNotEmpty())) {
			details
		} else {
			null
		}
	}

	private suspend fun Manga.getDetailsSafe() = runCatchingCancellable {
		mangaRepositoryFactory.create(source).getDetails(this)
	}.getOrDefault(this)

	private fun buildSearchQueries(seed: Manga): List<String> {
		val primary = seed.title.trim()
		val primaryKey = normalizeQuery(primary)
		val aliases = seed.altTitles
			.asSequence()
			.map(String::trim)
			.filter(String::isNotEmpty)
			.map { it to normalizeQuery(it) }
			.filter { (_, key) -> key.length >= MIN_ALIAS_QUERY_LENGTH && key != primaryKey }
			.distinctBy { (_, key) -> key }
			.sortedWith(compareByDescending<Pair<String, String>> { it.second.length }.thenBy { it.second })
			.map { it.first }
			.take(MAX_AUTO_FIX_QUERIES - 1)
			.toList()
		return buildList(1 + aliases.size) {
			if (primary.isNotEmpty()) add(primary)
			addAll(aliases)
		}
	}

	private fun normalizeQuery(value: String): String = buildString(value.length) {
		for (char in value.lowercase()) if (char.isLetterOrDigit()) append(char)
	}

	class NoAlternativesException(val seed: ParcelableManga) : NoSuchElementException()

	class NeedsReviewException(
		val seed: ParcelableManga,
		val candidates: List<ParcelableManga>,
	) : IllegalStateException("Possible replacement requires review")

	class CandidateDiscoveryIncompleteException(
		val seed: ParcelableManga,
		cause: Throwable? = null,
	) : IllegalStateException("Replacement search did not complete", cause)

	class SelectionChangedException(val discovery: MigrationDiscovery) : IllegalStateException("Replacement changed; review again")

	private companion object {
		const val MAX_AUTO_FIX_QUERIES = 6
		const val MAX_MIGRATION_DETAIL_CANDIDATES = 5
		const val MAX_HEALTH_CHECKS = 12
		const val MIN_ALIAS_QUERY_LENGTH = 3
	}
}
