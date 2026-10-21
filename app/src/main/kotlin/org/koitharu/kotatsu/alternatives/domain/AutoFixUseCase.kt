package org.koitharu.kotatsu.alternatives.domain

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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

	/**
	 * Discovery is a preview, never permission to mutate user data.
	 *
	 * [onProgress] receives provisional, search-derived evidence while discovery is still running.
	 * Callers must keep execution disabled until this suspend function returns its final, health-checked
	 * result. This lets review UI surface credible candidates early without weakening migration safety.
	 */
	suspend fun discover(
		mangaId: Long,
		onProgress: (MigrationDiscovery) -> Unit = {},
	): MigrationDiscovery {
		// Show the locally persisted identity before any broken-source network call can stall the screen.
		val storedSeed = loadStoredSeed(mangaId)
		onProgress(MigrationDiscovery(storedSeed))
		val seed = storedSeed.getDetailsSafe()
		if (seed != storedSeed) onProgress(MigrationDiscovery(seed))
		if (seed.isHealthy()) {
			return MigrationDiscovery(seed, sourceHealthy = true)
		}

		// Broken-source migration must search the whole enabled source domain. Pinned/language filters
		// are manual Alternatives preferences, not identity constraints for an automatic repair.
		val sources = alternativesUseCase.getSources(seed, SearchSourceMode.ALL_SOURCES, emptySet())
		val observations = ArrayList<MigrationCandidateObservation>()
		var lastPreviewSignature: List<Triple<String, Long, Int>> = emptyList()
		var previewDirty = false
		var discoveryError: Throwable? = null

		fun publishPreview() {
			if (!previewDirty) return
			previewDirty = false
			val assessment = candidateMatcher.assess(seed, observations)
			if (assessment.candidates.isEmpty()) return
			val signature = assessment.candidates.map { candidate ->
				Triple(candidate.manga.source.name, candidate.manga.id, candidate.evidence.score)
			}
			if (signature == lastPreviewSignature) return
			lastPreviewSignature = signature
			onProgress(MigrationDiscovery(seed, assessment))
		}

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
						previewDirty = true
					}
					is AlternativeSearchEvent.SourceFinished -> {
						if (event.error != null && discoveryError == null) discoveryError = event.error
						// Results from one source are emitted before its SourceFinished event. Coalesce that
						// burst into one matcher/UI update instead of rescoring after every individual row.
						publishPreview()
					}
				}
			}
		}

		// Rank identity evidence before the page-serving health checks. Probe several candidates in
		// bounded parallel: the final order remains the deterministic matcher order, while one slow
		// provider no longer serially blocks every other health check.
		val ranked = candidateMatcher.rank(seed, observations).take(MAX_HEALTH_CHECKS)
		val healthSemaphore = Semaphore(MAX_PARALLEL_HEALTH_CHECKS)
		val probes = coroutineScope {
			ranked.map { candidate ->
				async {
					healthSemaphore.withPermit {
						candidate to candidate.manga.probeHealth()
					}
				}
			}.awaitAll()
		}
		val healthy = ArrayList<MigrationCandidateObservation>(probes.size)
		for ((candidate, probe) in probes) {
			if (probe.isFailure && discoveryError == null) discoveryError = probe.exceptionOrNull()
			val details = probe.getOrNull() ?: continue
			healthy += MigrationCandidateObservation(details, candidate.matchedQueries)
		}
		val assessment = candidateMatcher.assess(seed, healthy)
		return MigrationDiscovery(seed, assessment, incomplete = discoveryError != null)
	}

	/** Refresh the approved identity before executing; a UI snapshot is never identity authority. */
	suspend fun migrateSelected(mangaId: Long, selection: MigrationSelection, reviewed: Boolean): Manga? {
		val current = validateSelection(mangaId, selection)
		if (current.sourceHealthy) return null
		val replacement = MigrationExecutionPolicy.select(current, selection, reviewed)
			?: throw SelectionChangedException(current)
		migrateUseCase(oldManga = current.seed, newManga = replacement, lossless = true, requireLibraryEntry = true)
		return replacement
	}

	private suspend fun validateSelection(mangaId: Long, selection: MigrationSelection): MigrationDiscovery {
		val seed = loadSeed(mangaId)
		if (seed.isHealthy()) return MigrationDiscovery(seed, sourceHealthy = true)
		val selectedSources = alternativesUseCase.getSources(seed, SearchSourceMode.ALL_SOURCES, emptySet())
			.filter { it.name == selection.source }
		if (selectedSources.isEmpty()) return MigrationDiscovery(seed)

		var selected: Manga? = null
		var validationError: Throwable? = null
		for (query in buildSearchQueries(seed)) {
			alternativesUseCase(
				manga = seed,
				mode = SearchSourceMode.ALL_SOURCES,
				preferredLanguages = emptySet(),
				query = query,
				precomputedSources = selectedSources,
				maxDetailCandidates = MAX_MIGRATION_DETAIL_CANDIDATES,
			).collect { event ->
				when (event) {
					is AlternativeSearchEvent.Result -> {
						if (event.manga.source.name == selection.source && event.manga.id == selection.id) {
							selected = event.manga
						}
					}
					is AlternativeSearchEvent.SourceFinished -> {
						if (event.error != null && validationError == null) validationError = event.error
					}
				}
			}
			if (selected != null) break
		}
		val approved = selected ?: return MigrationDiscovery(seed, incomplete = validationError != null)
		val probe = approved.probeHealth()
		if (probe.isFailure && validationError == null) validationError = probe.exceptionOrNull()
		val fresh = probe.getOrNull() ?: return MigrationDiscovery(seed, incomplete = validationError != null)
		if (MigrationSelection.of(fresh) != selection) return MigrationDiscovery(seed)
		return MigrationDiscovery(
			seed = seed,
			assessment = candidateMatcher.assess(seed, listOf(MigrationCandidateObservation(fresh))),
			incomplete = validationError != null,
		)
	}

	private suspend fun loadStoredSeed(mangaId: Long): Manga = checkNotNull(
		mangaDataRepository.findMangaById(mangaId, withChapters = true),
	) { "Manga $mangaId not found" }

	private suspend fun loadSeed(mangaId: Long): Manga = loadStoredSeed(mangaId).getDetailsSafe()

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
		const val MAX_PARALLEL_HEALTH_CHECKS = 4
		const val MIN_ALIAS_QUERY_LENGTH = 3
	}
}
