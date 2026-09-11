package org.koitharu.kotatsu.alternatives.domain

import android.os.SystemClock
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.koitharu.kotatsu.core.model.isNovelContent
import org.koitharu.kotatsu.core.model.isNovelContentSource
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.parser.SourceHealthRepository
import org.koitharu.kotatsu.explore.data.MangaSourcesRepository
import org.koitharu.kotatsu.history.data.HistoryRepository
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.search.domain.LANGUAGE_LOCAL
import org.koitharu.kotatsu.search.domain.SearchKind
import org.koitharu.kotatsu.search.domain.SearchSourceMode
import org.koitharu.kotatsu.search.domain.SearchV2Helper
import org.koitharu.kotatsu.search.domain.matchesPreferredLanguage
import org.koitharu.kotatsu.search.domain.searchLanguageCode
import javax.inject.Inject

private const val MAX_PARALLEL_SOURCES = 5
private const val MAX_PARALLEL_DETAILS = 5
private const val MAX_DETAIL_CANDIDATES = 3
private const val MAX_FUSION_QUERIES = 3
private const val POPULAR_SOURCE_LIMIT = 100

sealed interface AlternativeSearchEvent {
	data class Result(val manga: Manga) : AlternativeSearchEvent
	data class SourceFinished(val source: MangaSource, val error: Throwable?) : AlternativeSearchEvent
}

class AlternativesUseCase @Inject constructor(
	private val sourcesRepository: MangaSourcesRepository,
	private val searchHelperFactory: SearchV2Helper.Factory,
	private val mangaRepositoryFactory: MangaRepository.Factory,
	private val historyRepository: HistoryRepository,
	private val sourceHealthRepository: SourceHealthRepository,
) {

	fun hasPinnedSources(): Boolean = sourcesRepository.getPinnedSources().isNotEmpty()

	fun defaultMode(): SearchSourceMode = if (hasPinnedSources()) {
		SearchSourceMode.PINNED_ONLY
	} else {
		SearchSourceMode.ALL_SOURCES
	}

	/** Compatibility path for background auto-fix: preserve its old Flow<Manga> contract. */
	suspend operator fun invoke(manga: Manga): Flow<Manga> {
		sourcesRepository.ensureExternalSourcesReady()
		return invoke(manga, defaultMode(), emptySet(), manga.title).transform { event ->
			if (event is AlternativeSearchEvent.Result) emit(event.manga)
		}
	}

	fun getAvailableLanguages(manga: Manga): List<String> {
		val isNovel = manga.isNovelContent
		return sourcesRepository.getEnabledSources()
			.asSequence()
			.filter { it != manga.source && it.isNovelContentSource == isNovel }
			.map { it.searchLanguageCode() }
			.filter { it != LANGUAGE_LOCAL }
			.distinct()
			.sorted()
			.toList()
	}

	suspend fun getSources(
		manga: Manga,
		mode: SearchSourceMode,
		preferredLanguages: Set<String>,
	): List<MangaSource> {
		sourcesRepository.ensureExternalSourcesReady()
		val isNovel = manga.isNovelContent
		val enabled = sourcesRepository.getEnabledSources()
			.filter { it != manga.source && it.isNovelContentSource == isNovel }
		val pinned = sourcesRepository.getPinnedSources().toSet()
		val popularOrder = historyRepository.getPopularSources(POPULAR_SOURCE_LIMIT)
			.withIndex().associate { (index, source) -> source to index }

		val scoped = when (mode) {
			SearchSourceMode.PINNED_ONLY -> enabled.filter { it in pinned }
			SearchSourceMode.PREFERRED_LANGUAGES -> enabled.filter {
				it in pinned || it.matchesPreferredLanguage(preferredLanguages)
			}
			SearchSourceMode.ALL_SOURCES -> enabled
		}
		return scoped.sortedWith(
			compareBy<MangaSource>(
				{ if (it in pinned) 0 else 1 },
				{ if (it.matchesPreferredLanguage(preferredLanguages)) 0 else 1 },
				{ sourceHealthRepository.rankingPenalty(it) },
				{ popularOrder[it] ?: Int.MAX_VALUE },
			),
		)
	}

	suspend operator fun invoke(
		manga: Manga,
		mode: SearchSourceMode,
		preferredLanguages: Set<String>,
		query: String = manga.title,
		precomputedSources: List<MangaSource>? = null,
	): Flow<AlternativeSearchEvent> {
		val normalizedQuery = query.trim().ifEmpty { manga.title }
		val sources = precomputedSources ?: getSources(manga, mode, preferredLanguages)
		if (sources.isEmpty()) return emptyFlow()
		val fusionQueries = buildFusionQueries(manga, normalizedQuery)

		val sourceSemaphore = Semaphore(MAX_PARALLEL_SOURCES)
		val detailsSemaphore = Semaphore(MAX_PARALLEL_DETAILS)
		return channelFlow {
			for (source in sources) {
				launch {
					val candidates = ArrayList<Manga>()
					var searchError: Throwable? = null
					var hadSuccessfulSearch = false
					var queryLatencyTotal = 0L
					var queryAttempts = 0
					for (fusionQuery in fusionQueries) {
						var queryLatency = 0L
						val searchResult = runCatchingCancellable {
							sourceSemaphore.withPermit {
								val queryStartedAt = SystemClock.elapsedRealtime()
								try {
									searchHelperFactory.create(source)(fusionQuery, SearchKind.TITLE)?.manga
								} finally {
									queryLatency = SystemClock.elapsedRealtime() - queryStartedAt
								}
							}
						}
						queryAttempts++
						queryLatencyTotal += queryLatency
						if (searchResult.isSuccess) {
							hadSuccessfulSearch = true
						} else {
							searchResult.exceptionOrNull()?.let { searchError = it }
						}
						searchResult.getOrNull().orEmpty().forEach { candidate ->
							if (candidates.none { it.dedupeKey() == candidate.dedupeKey() }) {
								candidates += candidate
							}
						}
						if (candidates.any {
								SourceFusionScorer.score(manga, it) >= SourceFusionScorer.STRONG_SEARCH_CANDIDATE_SCORE
							}) {
							break
						}
					}

					val averageQueryLatency = if (queryAttempts == 0) 0L else queryLatencyTotal / queryAttempts
					if (candidates.isEmpty() && !hadSuccessfulSearch && searchError != null) {
						sourceHealthRepository.recordFailure(source, averageQueryLatency)
						send(AlternativeSearchEvent.SourceFinished(source, searchError))
						return@launch
					}
					if (hadSuccessfulSearch) sourceHealthRepository.recordSuccess(source, averageQueryLatency)

					// IDs are source-local. Never drop a mirror merely because another source happens to reuse
					// the same numeric id as the reference manga.
					val rankedCandidates = candidates
						.asSequence()
						.distinctBy { it.dedupeKey() }
						.map { candidate -> candidate to SourceFusionScorer.score(manga, candidate) }
						.filter { (_, score) -> score >= SourceFusionScorer.MIN_SEARCH_CANDIDATE_SCORE }
						.sortedByDescending { (_, score) -> score }
						.take(MAX_DETAIL_CANDIDATES)
						.map { (candidate, _) -> candidate }
						.toList()

					if (rankedCandidates.isNotEmpty()) {
						val detailed = rankedCandidates.map { candidate ->
							async {
								detailsSemaphore.withPermit {
									runCatchingCancellable {
										mangaRepositoryFactory.create(candidate.source).getDetails(candidate)
									}.getOrDefault(candidate)
								}
							}
						}.awaitAll()
							.distinctBy { it.dedupeKey() }
							.sortedByDescending { SourceFusionScorer.score(manga, it) }
						for (result in detailed) send(AlternativeSearchEvent.Result(result))
					}
					send(AlternativeSearchEvent.SourceFinished(source, null))
				}
			}
		}
	}

	private fun buildFusionQueries(manga: Manga, primary: String): List<String> = buildList {
		fun addDistinct(value: String?) {
			val normalized = value?.trim().orEmpty()
			if (normalized.isEmpty()) return
			if (none { it.equals(normalized, ignoreCase = true) }) add(normalized)
		}
		addDistinct(primary)
		addDistinct(manga.title)
		for (title in manga.altTitles) {
			addDistinct(title)
			if (size >= MAX_FUSION_QUERIES) break
		}
	}.take(MAX_FUSION_QUERIES)

	private fun Manga.dedupeKey(): Pair<Long, String> = id to title.trim().lowercase()
}
