package org.koitharu.kotatsu.details.domain

import kotlinx.coroutines.withTimeoutOrNull
import org.koitharu.kotatsu.core.model.LocalMangaSource
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Contextual recommendations are deliberately independent from Related Titles.
 *
 * Related Titles keeps the legacy title/alt-title keyword behaviour. This use case only feeds the
 * genre/context recommendation lane and is called lazily while that lane is visible.
 */
@Singleton
class ContextualRecommendationUseCase @Inject constructor(
	private val mangaRepositoryFactory: MangaRepository.Factory,
) {

	suspend operator fun invoke(seed: Manga): List<Manga> = runCatchingCancellable {
		load(seed)
	}.onFailure {
		it.printStackTraceDebug()
	}.getOrElse { emptyList() }

	private suspend fun load(seed: Manga): List<Manga> {
		if (seed.source == LocalMangaSource) return emptyList()
		val repository = mangaRepositoryFactory.create(seed.source)
		val candidates = LinkedHashMap<Long, Manga>()

		withTimeoutOrNull(NATIVE_RELATED_TIMEOUT_MS) {
			runCatchingCancellable { repository.getRelated(seed) }
				.getOrElse { emptyList() }
		}.orEmpty().forEach { manga ->
			if (manga.id != seed.id) candidates.putIfAbsent(manga.id, manga)
		}

		var ranked = ContextualRecommendationPolicy.rank(seed, candidates.values.toList())
		if (ranked.size >= PREFERRED_RESULT_COUNT) {
			return ranked.take(MAX_RESULTS)
		}

		val order = SortOrder.RELEVANCE.takeIf { it in repository.sortOrders } ?: repository.defaultSortOrder
		for (keyword in ContextualRecommendationPolicy.tagKeywords(seed).take(MAX_CONTEXT_QUERIES)) {
			val results = withTimeoutOrNull(CONTEXT_SEARCH_TIMEOUT_MS) {
				runCatchingCancellable {
					repository.getList(
						offset = 0,
						order = order,
						filter = MangaListFilter(query = keyword.value),
					)
				}.getOrElse { emptyList() }
			}.orEmpty()
			results.forEach { manga ->
				if (manga.id != seed.id) candidates.putIfAbsent(manga.id, manga)
			}
			ranked = ContextualRecommendationPolicy.rank(seed, candidates.values.toList())
			if (ranked.size >= MAX_RESULTS) break
		}
		return ranked.take(MAX_RESULTS)
	}

	private companion object {
		const val MAX_RESULTS = 12
		const val PREFERRED_RESULT_COUNT = 6
		const val MAX_CONTEXT_QUERIES = 2
		const val NATIVE_RELATED_TIMEOUT_MS = 8_000L
		const val CONTEXT_SEARCH_TIMEOUT_MS = 8_000L
	}
}
