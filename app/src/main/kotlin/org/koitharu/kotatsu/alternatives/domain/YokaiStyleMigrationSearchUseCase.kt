package org.koitharu.kotatsu.alternatives.domain

import dagger.Reusable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.koitharu.kotatsu.core.model.isNovelContent
import org.koitharu.kotatsu.core.model.isNovelContentSource
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.util.levenshteinDistance
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.tsuki.MiyorareOfficialSourcePacks
import org.koitharu.kotatsu.tsuki.TsukiPluginManager
import org.koitharu.kotatsu.tsuki.model.TsukiPluginProvider
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import kotlin.math.max

enum class MigrationSourceStrategy {
	FIRST_MATCH,
	MOST_CHAPTERS,
}

/**
 * Migration discovery modeled after Yokai's migration flow.
 *
 * Source selection/order is supplied by the user. Each source receives one normal/global-search query.
 * FIRST_MATCH stops at the first eligible result in source order. MOST_CHAPTERS checks the selected
 * sources with bounded parallelism and chooses the eligible candidate with the largest chapter list.
 * Discovery only chooses and persists candidate identity; execution remains Miyorare's lossless path.
 */
@Reusable
class YokaiStyleMigrationSearchUseCase @Inject constructor(
	private val mangaDataRepository: MangaDataRepository,
	private val alternativesUseCase: AlternativesUseCase,
	private val appSettings: AppSettings,
	private val tsukiPluginManager: TsukiPluginManager,
	private val globalSearch: MigrationGlobalSearchBridge,
	private val mangaRepositoryFactory: MangaRepository.Factory,
	private val candidateMatcher: MigrationCandidateMatcher,
) {

	suspend fun sourcePool(mangaId: Long): MigrationSourcePool {
		val seed = loadSeed(mangaId)
		val base = alternativesUseCase.getMigrationSourcePool(seed, excludeCurrent = false)
		if (seed.isNovelContent) return base

		// Explore/global search intentionally exposes only explicitly enabled Tsuki sources. Migration
		// needs a slightly wider view for official Miyorare packs: an installed usable source must stay
		// selectable even when it is hidden from the normal catalog. Keep third-party Tsuki providers
		// on their existing explicit-enabled semantics and preserve the normal NSFW/content-domain gates.
		val officialMiyorareSources = tsukiPluginManager.getAvailableSources()
			.asSequence()
			.filter { source ->
				source.plugin.provider == TsukiPluginProvider.MIYORARE &&
					MiyorareOfficialSourcePacks.findByInstalledPluginId(source.plugin.pluginId) != null
			}
			.filter { source -> source.isNovelContentSource == seed.isNovelContent }
			.filterNot { source ->
				appSettings.isNsfwContentDisabled && source.descriptor.contentType.equals("HENTAI", ignoreCase = true)
			}
			.map { it as MangaSource }
			.toList()

		return base.copy(
			allSources = (base.allSources + officialMiyorareSources)
				.distinctBy(MangaSource::name)
				.sortedBy(MangaSource::name),
		)
	}

	suspend operator fun invoke(
		mangaId: Long,
		selectedSourceNames: List<String>,
		strategy: MigrationSourceStrategy,
		extraSearchParams: String? = null,
		onProgress: suspend (MigrationDiscovery) -> Unit = {},
	): MigrationDiscovery {
		val seed = loadSeed(mangaId)
		val pool = sourcePool(mangaId)
		val byName = pool.allSources.associateBy(MangaSource::name)
		val selected = selectedSourceNames.mapNotNull(byName::get).distinctBy(MangaSource::name)
		// Yokai keeps the current source when it is the only selected source, but removes it when
		// multiple destinations are configured so the automatic pass cannot choose the same source.
		val sources = if (selected.size == 1) selected else selected.filter { it.name != seed.source.name }
		val query = buildQuery(seed.title, extraSearchParams)
		if (sources.isEmpty()) {
			return noReplacement(seed, incomplete = pool.providerFailureCount > 0, pool.providerFailureCount)
		}

		onProgress(progress(seed, query, 0, sources.size, null, pool.providerFailureCount))
		return when (strategy) {
			MigrationSourceStrategy.FIRST_MATCH -> firstMatch(seed, query, sources, pool.providerFailureCount, onProgress)
			MigrationSourceStrategy.MOST_CHAPTERS -> mostChapters(seed, query, sources, pool.providerFailureCount, onProgress)
		}
	}

	private suspend fun firstMatch(
		seed: Manga,
		query: String,
		sources: List<MangaSource>,
		providerFailures: Int,
		onProgress: suspend (MigrationDiscovery) -> Unit,
	): MigrationDiscovery {
		var sourceFailures = 0
		var successfulSources = 0
		for ((index, source) in sources.withIndex()) {
			val result = findCandidate(seed, query, source, requireDetails = false)
			if (result.failed) sourceFailures++ else successfulSources++
			onProgress(progress(seed, query, index + 1, sources.size, source.name, providerFailures + sourceFailures,
				candidate = result.manga))
			result.manga?.let { candidate ->
				persistCandidate(candidate)
				return ready(seed, candidate, result.candidates, query, providerFailures + sourceFailures)
			}
		}
		return noReplacement(
			seed = seed,
			incomplete = successfulSources == 0 && (sourceFailures > 0 || providerFailures > 0),
			failures = providerFailures + sourceFailures,
		)
	}

	private suspend fun mostChapters(
		seed: Manga,
		query: String,
		sources: List<MangaSource>,
		providerFailures: Int,
		onProgress: suspend (MigrationDiscovery) -> Unit,
	): MigrationDiscovery = coroutineScope {
		val semaphore = Semaphore(MAX_PARALLEL_SOURCES)
		val completed = AtomicInteger(0)
		val results = sources.map { source ->
			async {
				val result = semaphore.withPermit { findCandidate(seed, query, source, requireDetails = true) }
				val done = completed.incrementAndGet()
				onProgress(progress(seed, query, done, sources.size, source.name, providerFailures,
					candidate = result.manga))
				result
			}
		}.awaitAll()
		val failures = results.count(SourceResult::failed)
		val successful = results.size - failures
		val candidate = results.mapNotNull(SourceResult::manga).maxWithOrNull(
			compareBy<Manga> { it.chapters.orEmpty().size }
				.thenBy { titleSimilarity(seed.title, it.title) },
		)
		if (candidate != null) {
			persistCandidate(candidate)
			ready(seed, candidate, results.flatMap(SourceResult::candidates), query, providerFailures + failures)
		} else {
			noReplacement(seed, successful == 0 && (failures > 0 || providerFailures > 0), providerFailures + failures)
		}
	}

	private suspend fun findCandidate(
		seed: Manga,
		query: String,
		source: MangaSource,
		requireDetails: Boolean,
	): SourceResult {
		val searched = globalSearch.search(source, query)
		if (searched.isFailure) return SourceResult(null, true)
		// MangaEntity uses manga_id as its sole primary key. Different source implementations may
		// legitimately emit the same numeric id, so such a candidate cannot be persisted alongside
		// the source manga safely. Skip it rather than overwriting user state before migration starts.
		val eligible = searched.getOrDefault(emptyList()).filterNot { result -> result.id == seed.id }
		val candidate = chooseCandidate(seed.title, eligible) ?: return SourceResult(null, false)
		if (!canPersistCandidate(candidate)) return SourceResult(null, false)
		if (!requireDetails) return SourceResult(candidate, false, eligible)
		val details = runCatchingCancellable { mangaRepositoryFactory.create(source).getDetails(candidate) }.getOrNull()
			?: return SourceResult(null, true)
		if (details.id != candidate.id || details.source.name != candidate.source.name) {
			return SourceResult(null, true)
		}
		if (!canPersistCandidate(details)) return SourceResult(null, false)
		return SourceResult(details, false, eligible.map { if (it.id == candidate.id && it.source.name == candidate.source.name) details else it })
	}

	private suspend fun canPersistCandidate(candidate: Manga): Boolean {
		val existing = mangaDataRepository.findMangaById(candidate.id, withChapters = false) ?: return true
		return existing.source.name == candidate.source.name
	}

	private suspend fun persistCandidate(candidate: Manga) {
		mangaDataRepository.storeManga(
			manga = candidate,
			replaceExisting = true,
			stripAppliedOverride = false,
			detailsFetched = candidate.chapters != null,
		)
	}

	private fun chooseCandidate(seedTitle: String, results: List<Manga>): Manga? {
		if (results.isEmpty()) return null
		if (results.size == 1) return results.single()
		return results.asSequence()
			.map { manga -> manga to titleSimilarity(seedTitle, manga.title) }
			.filter { (_, similarity) -> similarity >= MIN_ELIGIBLE_SIMILARITY }
			.maxByOrNull { it.second }
			?.first
	}

	private fun ready(
		seed: Manga,
		candidate: Manga,
		candidates: List<Manga>,
		query: String,
		failures: Int,
	): MigrationDiscovery {
		// Baseline candidate selection follows the configured source order / chapter-count strategy.
		// Ranking can order alternatives for manual search, but it is not an execution gate here.
		val ranked = candidateMatcher.rank(
			seed,
			(candidates + candidate).distinctBy { it.source.name to it.id }
				.map { MigrationCandidateObservation(it, setOf(query)) },
		)
		val selected = ranked.firstOrNull {
			it.manga.id == candidate.id && it.manga.source.name == candidate.source.name
		} ?: error("Search result must be representable as a migration candidate")
		return MigrationDiscovery(
			seed = seed,
			assessment = MigrationMatchAssessment(MigrationMatchState.READY, selected, ranked),
			sourceHealthy = false,
			partialFailureCount = failures,
		)
	}

	private fun noReplacement(seed: Manga, incomplete: Boolean, failures: Int) = MigrationDiscovery(
		seed = seed,
		assessment = MigrationMatchAssessment(MigrationMatchState.NO_REPLACEMENT, null, emptyList()),
		sourceHealthy = false,
		incomplete = incomplete,
		partialFailureCount = failures,
	)

	private fun progress(
		seed: Manga,
		query: String,
		completed: Int,
		total: Int,
		lastSource: String?,
		failures: Int,
		candidate: Manga? = null,
	): MigrationDiscovery {
		val assessment = candidate?.let { manga ->
			candidateMatcher.rank(seed, listOf(MigrationCandidateObservation(manga, setOf(query))))
				.firstOrNull()?.let { MigrationMatchAssessment(MigrationMatchState.READY, it, listOf(it)) }
		}
		return MigrationDiscovery(
			seed = seed,
			assessment = assessment,
			partialFailureCount = failures,
			progress = MigrationSearchProgress(
				phase = MigrationSearchPhase.SEARCHING_SOURCES,
				query = query,
				queryIndex = 1,
				queryTotal = 1,
				completedSources = completed,
				totalSources = total,
				candidateCount = if (candidate == null) 0 else 1,
				lastSource = lastSource,
			),
		)
	}

	private fun buildQuery(title: String, extraSearchParams: String?): String = buildString {
		append(title.trim().replace("’", "'"))
		extraSearchParams?.trim()?.takeIf(String::isNotEmpty)?.let { append(' ').append(it) }
	}.trim()

	private fun titleSimilarity(first: String, second: String): Double {
		val a = normalizeForSimilarity(first)
		val b = normalizeForSimilarity(second)
		if (a.isEmpty() || b.isEmpty()) return 0.0
		val longest = max(a.length, b.length)
		return 1.0 - a.levenshteinDistance(b).toDouble() / longest.toDouble()
	}

	/** Match Yokai's String.toNormalized(): only normalize the smart apostrophe. */
	private fun normalizeForSimilarity(value: String): String = value.replace("’", "'")

	private suspend fun loadSeed(mangaId: Long): Manga = checkNotNull(
		mangaDataRepository.findMangaById(mangaId, withChapters = true),
	) { "Manga $mangaId not found" }

	private data class SourceResult(
		val manga: Manga?,
		val failed: Boolean,
		val candidates: List<Manga> = emptyList(),
	)

	private companion object {
		const val MIN_ELIGIBLE_SIMILARITY = 0.4
		const val MAX_PARALLEL_SOURCES = 3
	}
}
