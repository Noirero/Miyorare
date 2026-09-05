package org.koitharu.kotatsu.alternatives.domain

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.koitharu.kotatsu.core.model.isNovelSource
import org.koitharu.kotatsu.core.parser.MangaRepository
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
		val isNovel = manga.source.isNovelSource
		return sourcesRepository.getEnabledSources()
			.asSequence()
			.filter { it != manga.source && it.isNovelSource == isNovel }
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
		val isNovel = manga.source.isNovelSource
		val enabled = sourcesRepository.getEnabledSources()
			.filter { it != manga.source && it.isNovelSource == isNovel }
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
	): Flow<AlternativeSearchEvent> {
		val normalizedQuery = query.trim().ifEmpty { manga.title }
		val sources = precomputedSources ?: getSources(manga, mode, preferredLanguages)
		if (sources.isEmpty()) return emptyFlow()

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
						?.filter { it.id != manga.id }
						?.distinctBy { it.dedupeKey() }
						?.take(MAX_DETAIL_CANDIDATES)
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
