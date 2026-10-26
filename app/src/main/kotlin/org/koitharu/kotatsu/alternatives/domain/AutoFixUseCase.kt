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
		val discovery = discover(mangaId, MigrationSearchScope.ALL)
		val seed = discovery.seed
		if (discovery.currentSourceReadable) return seed to null
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

	/** Only decides whether the current manga can still serve a readable page. */
	suspend fun checkCurrentSource(
		mangaId: Long,
		onProgress: suspend (MigrationDiscovery) -> Unit = {},
	): MigrationDiscovery {
		val storedSeed = loadStoredSeed(mangaId)
		onProgress(MigrationDiscovery(
			seed = storedSeed,
			progress = MigrationSearchProgress(MigrationSearchPhase.CHECKING_SOURCE),
		))
		val seed = storedSeed.getDetailsSafe()
		if (seed != storedSeed) {
			onProgress(MigrationDiscovery(
				seed = seed,
				progress = MigrationSearchProgress(MigrationSearchPhase.CHECKING_SOURCE),
			))
		}
		return MigrationDiscovery(seed = seed, sourceHealthy = seed.isReadable())
	}

	suspend fun searchAvailability(mangaId: Long): MigrationSearchAvailability {
		val seed = loadStoredSeed(mangaId).getDetailsSafe()
		return alternativesUseCase.getMigrationSourcePool(seed).availability()
	}

	/**
	 * Discovery is a preview, never permission to mutate user data. The current source being
	 * unavailable is the reason to search, not an error state. Provider/source failures are isolated
	 * and only become incomplete when the selected scope could not be meaningfully inspected at all.
	 */
	suspend fun discover(
		mangaId: Long,
		searchScope: MigrationSearchScope = MigrationSearchScope.ALL,
		onProgress: suspend (MigrationDiscovery) -> Unit = {},
	): MigrationDiscovery {
		val current = checkCurrentSource(mangaId, onProgress)
		if (current.currentSourceReadable) return current
		return searchReplacements(mangaId, searchScope, onProgress = onProgress)
	}

	suspend fun searchReplacements(
		mangaId: Long,
		searchScope: MigrationSearchScope,
		currentSourceReadable: Boolean = false,
		onProgress: suspend (MigrationDiscovery) -> Unit = {},
	): MigrationDiscovery {
		val seed = loadStoredSeed(mangaId).getDetailsSafe()
		val pool = alternativesUseCase.getMigrationSourcePool(seed)
		val sources = pool.sources(searchScope)
		val queries = buildMigrationSearchQueries(seed)
		val observations = ArrayList<MigrationCandidateObservation>()
		val observedKeys = hashSetOf<Pair<String, Long>>()
		val sourceFailures = hashSetOf<String>()
		val sourceSuccesses = hashSetOf<String>()

		suspend fun publishSearchProgress(
			query: String,
			queryIndex: Int,
			completedSources: Int,
			lastSource: String? = null,
		) {
			val assessment = observations.takeIf { it.isNotEmpty() }?.let { candidateMatcher.assess(seed, it) }
			onProgress(MigrationDiscovery(
				seed = seed,
				assessment = assessment,
				sourceHealthy = currentSourceReadable,
				partialFailureCount = pool.providerFailureCount + sourceFailures.size,
				progress = MigrationSearchProgress(
					phase = MigrationSearchPhase.SEARCHING_SOURCES,
					query = query,
					queryIndex = queryIndex,
					queryTotal = queries.size,
					completedSources = completedSources,
					totalSources = sources.size,
					candidateCount = observedKeys.size,
					lastSource = lastSource,
				),
			))
		}

		for ((index, query) in queries.withIndex()) {
			var completedSources = 0
			publishSearchProgress(query, index + 1, completedSources)
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
						observedKeys += event.manga.source.name to event.manga.id
					}
					is AlternativeSearchEvent.SourceFinished -> {
						if (event.error != null) sourceFailures += event.source.name else sourceSuccesses += event.source.name
						completedSources++
						publishSearchProgress(query, index + 1, completedSources, event.source.name)
					}
				}
			}
		}

		// Rank first, then health-check a broad review set. Low-confidence results remain visible after
		// they prove they can actually serve pages; ranking is guidance, not a visibility gate.
		val ranked = candidateMatcher.rank(seed, observations).take(MAX_HEALTH_CHECKS)
		onProgress(MigrationDiscovery(
			seed = seed,
			assessment = observations.takeIf { it.isNotEmpty() }?.let { candidateMatcher.assess(seed, it) },
			sourceHealthy = currentSourceReadable,
			partialFailureCount = pool.providerFailureCount + sourceFailures.size,
			progress = MigrationSearchProgress(
				phase = MigrationSearchPhase.VERIFYING_CANDIDATES,
				candidateCount = ranked.size,
			),
		))
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
			val details = probe.getOrNull() ?: continue
			healthy += MigrationCandidateObservation(details, candidate.matchedQueries)
		}
		val assessment = candidateMatcher.assess(seed, healthy)
		val incomplete = isMigrationScopeIncomplete(
			scopedSourceNames = sources.map(MangaSource::name),
			failedSourceNames = sourceFailures,
			successfulSourceNames = sourceSuccesses,
		)
		return MigrationDiscovery(
			seed = seed,
			assessment = assessment,
			sourceHealthy = currentSourceReadable,
			incomplete = incomplete,
			partialFailureCount = pool.providerFailureCount + sourceFailures.size,
		)
	}

	/** Refresh the approved identity before executing; a UI snapshot is never identity authority. */
	suspend fun migrateSelected(
		mangaId: Long,
		selection: MigrationSelection,
		reviewed: Boolean,
		allowReadableSource: Boolean = false,
	): Manga? {
		val current = validateSelection(mangaId, selection, allowReadableSource)
		if (current.currentSourceReadable && !allowReadableSource) return null
		val replacement = MigrationExecutionPolicy.select(current, selection, reviewed, allowReadableSource)
			?: throw SelectionChangedException(current)
		migrateUseCase(oldManga = current.seed, newManga = replacement, lossless = true, requireLibraryEntry = true)
		return replacement
	}

	private suspend fun validateSelection(
		mangaId: Long,
		selection: MigrationSelection,
		allowReadableSource: Boolean,
	): MigrationDiscovery {
		val seed = loadSeed(mangaId)
		val currentReadable = seed.isReadable()
		if (currentReadable && !allowReadableSource) return MigrationDiscovery(seed, sourceHealthy = true)
		val pool = alternativesUseCase.getMigrationSourcePool(seed)
		val selectedSources = pool.allSources.filter { it.name == selection.source }
		if (selectedSources.isEmpty()) return MigrationDiscovery(
			seed = seed,
			sourceHealthy = currentReadable,
			incomplete = pool.providerFailureCount > 0,
		)

		var selected: MigrationCandidateObservation? = null
		var validationError: Throwable? = null
		for (query in buildMigrationSearchQueries(seed)) {
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
							selected = MigrationCandidateObservation(event.manga, setOf(query))
						}
					}
					is AlternativeSearchEvent.SourceFinished -> {
						if (event.error != null && validationError == null) validationError = event.error
					}
				}
			}
			if (selected != null) break
		}
		val approved = selected ?: return MigrationDiscovery(
			seed = seed,
			sourceHealthy = currentReadable,
			incomplete = validationError != null,
		)
		val probe = approved.manga.probeHealth()
		if (probe.isFailure && validationError == null) validationError = probe.exceptionOrNull()
		val fresh = probe.getOrNull() ?: return MigrationDiscovery(
			seed = seed,
			sourceHealthy = currentReadable,
			incomplete = validationError != null,
		)
		if (MigrationSelection.of(fresh) != selection) return MigrationDiscovery(seed, sourceHealthy = currentReadable)
		return MigrationDiscovery(
			seed = seed,
			assessment = candidateMatcher.assess(
				seed,
				listOf(MigrationCandidateObservation(fresh, approved.matchedQueries)),
			),
			sourceHealthy = currentReadable,
			incomplete = validationError != null,
		)
	}

	private suspend fun loadStoredSeed(mangaId: Long): Manga = checkNotNull(
		mangaDataRepository.findMangaById(mangaId, withChapters = true),
	) { "Manga $mangaId not found" }

	private suspend fun loadSeed(mangaId: Long): Manga = loadStoredSeed(mangaId).getDetailsSafe()

	private suspend fun Manga.isReadable(): Boolean = getReadableDetailsOrNull() != null

	private suspend fun Manga.getReadableDetailsOrNull(): Manga? = probeHealth().getOrNull()

	private suspend fun Manga.probeHealth(): Result<Manga?> = runCatchingCancellable {
		val repo = mangaRepositoryFactory.create(source)
		val details = if (this.chapters != null) this else repo.getDetails(this)
		val firstChapter = details.chapters?.firstOrNull() ?: return@runCatchingCancellable null
		val firstPage = repo.getPages(firstChapter).firstOrNull() ?: return@runCatchingCancellable null
		val pageUrl = repo.getPageUrl(firstPage)
		if (pageUrl.toHttpUrlOrNull() != null || (source.isExternalSource() && pageUrl.isNotEmpty())) {
			details
		} else {
			null
		}
	}

	private suspend fun Manga.getDetailsSafe() = runCatchingCancellable {
		mangaRepositoryFactory.create(source).getDetails(this)
	}.getOrDefault(this)

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
		const val MAX_MIGRATION_DETAIL_CANDIDATES = 10
		const val MAX_HEALTH_CHECKS = 24
		const val MAX_PARALLEL_HEALTH_CHECKS = 4
	}
}

internal fun buildMigrationSearchQueries(seed: Manga): List<String> {
	val seen = hashSetOf<String>()
	val result = ArrayList<String>(MAX_MIGRATION_SEARCH_QUERIES)
	fun addQuery(value: String) {
		val query = value.trim()
		if (query.isEmpty()) return
		val key = normalizeMigrationTitle(query)
		if (key.isEmpty() || key in seen) return
		if (result.isNotEmpty() && key.length < MIN_MIGRATION_QUERY_LENGTH) return
		seen += key
		result += query
	}

	val aliases = seed.altTitles
		.sortedWith(compareByDescending<String> { normalizeMigrationTitle(it).length }.thenBy { normalizeMigrationTitle(it) })
	for (title in buildList { add(seed.title); addAll(aliases) }) {
		for (variant in migrationTitleVariants(title)) addQuery(variant)
		if (result.size >= MAX_MIGRATION_SEARCH_QUERIES) break
	}
	return result.take(MAX_MIGRATION_SEARCH_QUERIES)
}

private const val MAX_MIGRATION_SEARCH_QUERIES = 10
private const val MIN_MIGRATION_QUERY_LENGTH = 2
