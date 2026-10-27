package org.koitharu.kotatsu.alternatives.domain

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.koitharu.kotatsu.core.model.isNovelContent
import org.koitharu.kotatsu.core.model.isNovelContentSource
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.explore.data.MangaSourcesRepository
import org.koitharu.kotatsu.history.data.HistoryRepository
import org.koitharu.kotatsu.mihon.MihonExtensionManager
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
private const val MAX_DETAIL_CANDIDATES_HARD_LIMIT = 10
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
	private val mihonExtensionManager: MihonExtensionManager,
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

	/**
	 * Migration-only source pool. Runtime families are loaded independently and only when they can
	 * serve the current content domain, so an irrelevant provider failure never poisons repair.
	 */
	suspend fun getMigrationSourcePool(manga: Manga): MigrationSourcePool = coroutineScope {
		val isNovel = manga.isNovelContent
		val results = buildList {
			add(async {
				runCatchingCancellable {
					mihonExtensionManager.ensureReady()
					sourcesRepository.observeMihonSources().first().map { it as MangaSource }
				}
			})
			if (isNovel) {
				add(async { runCatchingCancellable { sourcesRepository.getLnSources().map { it as MangaSource } } })
			} else {
				add(async { runCatchingCancellable { sourcesRepository.getTsukiSources().map { it as MangaSource } } })
				add(async { runCatchingCancellable { sourcesRepository.getKotatsuSources().map { it as MangaSource } } })
			}
		}.awaitAll()
		val merged = mergeMigrationProviderResults(results)
		MigrationSourcePool(
			allSources = merged.values
				.asSequence()
				.filter { it.name != manga.source.name && it.isNovelContentSource == isNovel }
				.distinctBy(MangaSource::name)
				.sortedBy { it.name }
				.toList(),
			providerFailureCount = merged.failureCount,
		)
	}

	suspend fun getSources(
		manga: Manga,
		mode: SearchSourceMode,
		preferredLanguages: Set<String>,
	): List<MangaSource> = getSources(manga.isNovelContent, manga.source, mode, preferredLanguages)

	private suspend fun getSources(
		isNovel: Boolean, excludedSource: MangaSource?, mode: SearchSourceMode, preferredLanguages: Set<String>,
	): List<MangaSource> {
		sourcesRepository.ensureExternalSourcesReady()
		val enabled = sourcesRepository.getEnabledSources()
			.filter { it != excludedSource && it.isNovelContentSource == isNovel }
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
		maxDetailCandidates: Int = MAX_DETAIL_CANDIDATES,
	): Flow<AlternativeSearchEvent> {
		val normalizedQuery = query.trim().ifEmpty { manga.title }
		val sources = precomputedSources ?: getSources(manga, mode, preferredLanguages)
		return searchSources(normalizedQuery, sources, manga.source to manga.id, maxDetailCandidates)
	}

	/** Metadata supplies a title and kind, never a fabricated parser Manga. */
	suspend fun searchTitle(title: String, isNovel: Boolean, mode: SearchSourceMode, preferredLanguages: Set<String>): Flow<AlternativeSearchEvent> {
		val query = title.trim()
		require(query.isNotEmpty())
		return searchSources(query, getSources(isNovel, null, mode, preferredLanguages), null, MAX_DETAIL_CANDIDATES)
	}

	/**
	 * Search-Everywhere-style migration discovery. Each source gets one primary query. Optional
	 * alias queries are tried only when that source returned no primary result. Raw results are
	 * published immediately; detail/page validation remains execution-time work.
	 */
	fun searchInstalledSourcesDirect(
		queries: List<String>,
		sources: List<MangaSource>,
		excluded: Pair<MangaSource, Long>? = null,
	): Flow<AlternativeSearchEvent> {
		val normalizedQueries = queries.asSequence().map(String::trim).filter(String::isNotEmpty).distinct().toList()
		if (normalizedQueries.isEmpty() || sources.isEmpty()) return emptyFlow()
		val sourceSemaphore = Semaphore(MAX_PARALLEL_SOURCES)
		return channelFlow {
			for (source in sources) {
				launch {
					var sourceError: Throwable? = null
					for (query in normalizedQueries) {
						val searchResult = runCatchingCancellable {
							sourceSemaphore.withPermit {
								searchHelperFactory.create(source)(query, SearchKind.TITLE)?.manga
							}
						}
						val list = searchResult.getOrElse { error ->
							sourceError = error
							emptyList()
						}
						val results = list.orEmpty()
							.asSequence()
							.filter { excluded == null || it.source != excluded.first || it.id != excluded.second }
							.distinctBy { it.dedupeKey() }
							.toList()
						if (results.isNotEmpty()) {
							for (result in results) send(AlternativeSearchEvent.Result(result))
							sourceError = null
							break
						}
					}
					send(AlternativeSearchEvent.SourceFinished(source, sourceError))
				}
		}
	}

	/**
	 * Execution-time destination refresh. Search the selected source without truncating its raw list,
	 * find the exact source/id first, and only then fetch details for that one approved candidate.
	 */
	suspend fun refreshExactCandidate(
		query: String,
		source: MangaSource,
		candidateId: Long,
	): Result<Manga?> = runCatchingCancellable {
		val normalizedQuery = query.trim()
		if (normalizedQuery.isEmpty()) return@runCatchingCancellable null
		val raw = searchHelperFactory.create(source)(normalizedQuery, SearchKind.TITLE)?.manga.orEmpty()
		val candidate = raw.firstOrNull { it.source.name == source.name && it.id == candidateId }
			?: return@runCatchingCancellable null
		mangaRepositoryFactory.create(source).getDetails(candidate)
	}

	private fun searchSources(
		normalizedQuery: String, sources: List<MangaSource>, excluded: Pair<MangaSource, Long>?, maxDetailCandidates: Int,
	): Flow<AlternativeSearchEvent> {
		if (sources.isEmpty()) return emptyFlow()
		val detailCandidateLimit = maxDetailCandidates.coerceIn(1, MAX_DETAIL_CANDIDATES_HARD_LIMIT)

		val sourceSemaphore = Semaphore(MAX_PARALLEL_SOURCES)
		val detailsSemaphore = Semaphore(MAX_PARALLEL_DETAILS)
		return channelFlow {
			for (source in sources) {
				launch {
					val searchResult = runCatchingCancellable {
						sourceSemaphore.withPermit {
							searchHelperFactory.create(source)(normalizedQuery, SearchKind.TITLE)?.manga
						}
					}
					val list = searchResult.getOrElse { error ->
						send(AlternativeSearchEvent.SourceFinished(source, error))
						return@launch
					}

					val candidates = list
						?.asSequence()
						// Source is part of manga identity. Different providers may legitimately generate the
						// same numeric id, so id equality alone must never hide a cross-source replacement.
						?.filter { excluded == null || it.source != excluded.first || it.id != excluded.second }
						?.distinctBy { it.dedupeKey() }
						?.take(detailCandidateLimit)
						?.toList()
						.orEmpty()

					if (candidates.isNotEmpty()) {
						val detailed = candidates.map { candidate ->
							async {
								detailsSemaphore.withPermit {
									runCatchingCancellable {
										mangaRepositoryFactory.create(candidate.source).getDetails(candidate)
									}.getOrDefault(candidate)
								}
							}
						}.awaitAll().distinctBy { it.dedupeKey() }
						for (result in detailed) send(AlternativeSearchEvent.Result(result))
					}
					send(AlternativeSearchEvent.SourceFinished(source, null))
				}
			}
		}
	}

	private fun Manga.dedupeKey(): Pair<Long, String> = id to title.trim().lowercase()
}
