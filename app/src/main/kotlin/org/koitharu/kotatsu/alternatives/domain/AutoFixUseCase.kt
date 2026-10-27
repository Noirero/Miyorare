package org.koitharu.kotatsu.alternatives.domain

import kotlinx.coroutines.withTimeoutOrNull
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
			MigrationMatchState.NO_REPLACEMENT -> throw NoAlternativesException(ParcelableManga(seed))
		}
		val migrated = migrateSelected(
			mangaId = mangaId,
			selection = MigrationSelection.of(replacement),
			reviewed = false,
			allowReadableSource = false,
		) ?: return seed to null
		return seed to migrated
	}

	/** Local-only seed for the initial migration screen. This never opens the current source. */
	suspend fun loadStoredManga(mangaId: Long): Manga = loadStoredSeed(mangaId)

	/**
	 * Optional current-source check. Dead/challenged sources must never hold the migration screen
	 * indefinitely: failure or timeout means only "not proven readable", not a permanent broken claim.
	 */
	suspend fun checkCurrentSource(
		mangaId: Long,
		onProgress: suspend (MigrationDiscovery) -> Unit = {},
	): MigrationDiscovery {
		val storedSeed = loadStoredSeed(mangaId)
		onProgress(MigrationDiscovery(
			seed = storedSeed,
			progress = MigrationSearchProgress(MigrationSearchPhase.CHECKING_SOURCE),
		))
		return withTimeoutOrNull(CURRENT_SOURCE_CHECK_TIMEOUT_MS) {
			val seed = storedSeed.getDetailsSafe()
			if (seed != storedSeed) {
				onProgress(MigrationDiscovery(
					seed = seed,
					progress = MigrationSearchProgress(MigrationSearchPhase.CHECKING_SOURCE),
				))
			}
			MigrationDiscovery(seed = seed, sourceHealthy = seed.isReadable())
		} ?: MigrationDiscovery(seed = storedSeed, sourceHealthy = false)
	}

	/** Compatibility/count helper. Direct UI no longer waits on this before starting a search. */
	suspend fun searchAvailability(mangaId: Long): MigrationSearchAvailability {
		val seed = loadStoredSeed(mangaId)
		return alternativesUseCase.getMigrationSourcePool(seed).availability()
	}

	suspend fun discover(
		mangaId: Long,
		searchScope: MigrationSearchScope = MigrationSearchScope.ALL,
		onProgress: suspend (MigrationDiscovery) -> Unit = {},
	): MigrationDiscovery {
		val current = checkCurrentSource(mangaId, onProgress)
		if (current.currentSourceReadable) return current
		return searchReplacements(mangaId, searchScope, onProgress = onProgress)
	}

	/**
	 * Search installed sources exactly like Search Everywhere: one lightweight title query per source,
	 * publish results as sources finish, and defer details/health/fingerprint validation until execution.
	 */
	suspend fun searchReplacements(
		mangaId: Long,
		searchScope: MigrationSearchScope,
		currentSourceReadable: Boolean = false,
		revealAllCandidates: Boolean = false,
		onProgress: suspend (MigrationDiscovery) -> Unit = {},
	): MigrationDiscovery {
		val seed = loadStoredSeed(mangaId)
		val pool = alternativesUseCase.getMigrationSourcePool(seed)
		val sources = pool.sources(searchScope)
		val query = buildPrimaryMigrationSearchQuery(seed)
		val observations = ArrayList<MigrationCandidateObservation>()
		val observedKeys = hashSetOf<Pair<String, Long>>()
		val sourceFailures = hashSetOf<String>()
		val sourceSuccesses = hashSetOf<String>()
		var completedSources = 0

		fun assessment(): MigrationMatchAssessment? {
			if (observations.isEmpty()) return null
			if (!revealAllCandidates) return candidateMatcher.assess(seed, observations)
			val ranked = candidateMatcher.rank(seed, observations)
			return MigrationMatchAssessment(
				state = if (ranked.isEmpty()) MigrationMatchState.NO_REPLACEMENT else MigrationMatchState.NEEDS_REVIEW,
				best = ranked.firstOrNull(),
				candidates = ranked,
			)
		}

		suspend fun publish(lastSource: String? = null) {
			onProgress(MigrationDiscovery(
				seed = seed,
				assessment = assessment(),
				sourceHealthy = currentSourceReadable,
				partialFailureCount = pool.providerFailureCount + sourceFailures.size,
				progress = MigrationSearchProgress(
					phase = MigrationSearchPhase.SEARCHING_SOURCES,
					query = query,
					queryIndex = 1,
					queryTotal = 1,
					completedSources = completedSources,
					totalSources = sources.size,
					candidateCount = observedKeys.size,
					lastSource = lastSource,
				),
			))
		}

		publish()
		alternativesUseCase.searchInstalledSourcesDirect(
			query = query,
			sources = sources,
			excluded = seed.source to seed.id,
		).collect { event ->
			when (event) {
				is AlternativeSearchEvent.Result -> {
					val key = event.manga.source.name to event.manga.id
					if (observedKeys.add(key)) {
						observations += MigrationCandidateObservation(event.manga, setOf(query))
					}
				}
				is AlternativeSearchEvent.SourceFinished -> {
					if (event.error != null) sourceFailures += event.source.name else sourceSuccesses += event.source.name
					completedSources++
					publish(event.source.name)
				}
			}
		}

		val incomplete = isMigrationScopeIncomplete(
			scopedSourceNames = sources.map { it.name },
			failedSourceNames = sourceFailures,
			successfulSourceNames = sourceSuccesses,
		)
		val finalAssessment = assessment() ?: MigrationMatchAssessment(
			MigrationMatchState.NO_REPLACEMENT,
			null,
			emptyList(),
		)
		return MigrationDiscovery(
			seed = seed,
			assessment = finalAssessment,
			sourceHealthy = currentSourceReadable,
			incomplete = incomplete,
			partialFailureCount = pool.providerFailureCount + sourceFailures.size,
		)
	}

	/** Refresh the approved destination identity before executing. */
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
		// Direct-search mode deliberately never asks the old source again. Checked mode keeps the
		// readable-source guard, but that re-check is bounded by the same timeout as the UI check.
		val current = if (allowReadableSource) {
			MigrationDiscovery(seed = loadStoredSeed(mangaId), sourceHealthy = false)
		} else {
			checkCurrentSource(mangaId)
		}
		val seed = current.seed
		val currentReadable = current.currentSourceReadable
		if (currentReadable && !allowReadableSource) return current
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

	private suspend fun Manga.isReadable(): Boolean = getReadableDetailsOrNull() != null

	private suspend fun Manga.getReadableDetailsOrNull(): Manga? = probeHealth().getOrNull()

	private suspend fun Manga.probeHealth(): Result<Manga?> = runCatchingCancellable {
		val repo = mangaRepositoryFactory.create(source)
		val details = if (this.chapters != null) this else repo.getDetails(this)
		// Repository chapter lists are normalized oldest-first in the migration-supported adapters;
		// probe the newest entry, which is less likely to be a stale/deleted historical URL.
		val chapter = details.chapters?.lastOrNull() ?: return@runCatchingCancellable null
		val firstPage = repo.getPages(chapter).firstOrNull() ?: return@runCatchingCancellable null
		val pageUrl = repo.getPageUrl(firstPage)
		if (pageUrl.toHttpUrlOrNull() != null || (source.isExternalSource() && pageUrl.isNotEmpty())) details else null
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
		const val CURRENT_SOURCE_CHECK_TIMEOUT_MS = 12_000L
		const val MAX_MIGRATION_DETAIL_CANDIDATES = 10
	}
}

internal fun buildPrimaryMigrationSearchQuery(seed: Manga): String {
	var query = migrationTitleVariants(seed.title).lastOrNull()?.trim().orEmpty().ifEmpty { seed.title.trim() }
	var stripped = 0
	while (stripped < 3) {
		val match = TRAILING_MIGRATION_DECORATOR.find(query) ?: break
		val next = query.removeRange(match.range).trim()
		if (next.isEmpty() || next == query) break
		query = next
		stripped++
	}
	return query.ifEmpty { seed.title.trim() }
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

	addQuery(buildPrimaryMigrationSearchQuery(seed))
	val aliases = seed.altTitles
		.sortedWith(compareByDescending<String> { normalizeMigrationTitle(it).length }.thenBy { normalizeMigrationTitle(it) })
	for (title in buildList { add(seed.title); addAll(aliases) }) {
		for (variant in migrationTitleVariants(title)) addQuery(variant)
		if (result.size >= MAX_MIGRATION_SEARCH_QUERIES) break
	}
	return result.take(MAX_MIGRATION_SEARCH_QUERIES)
}

private val TRAILING_MIGRATION_DECORATOR = Regex(
	"""\s*[-–—:|·]*\s*(?:\[[^\]\r\n]{1,40}]|\([^\)\r\n]{1,40}\)|\{[^}\r\n]{1,40}}|【[^】\r\n]{1,40}】|（[^）\r\n]{1,40}）)\s*$""",
)
private const val MAX_MIGRATION_SEARCH_QUERIES = 10
private const val MIN_MIGRATION_QUERY_LENGTH = 2
