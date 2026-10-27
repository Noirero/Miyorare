package org.koitharu.kotatsu.alternatives.domain

import dagger.Reusable
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.search.domain.SearchKind
import org.koitharu.kotatsu.search.domain.SearchV2Helper
import javax.inject.Inject

/**
 * Thin migration adapter over the same SearchV2Helper + SIMPLE search semantics used by Global Search.
 * It intentionally contains no migration-specific title post-filter or candidate health gate.
 */
@Reusable
class MigrationGlobalSearchBridge @Inject constructor(
	private val searchHelperFactory: SearchV2Helper.Factory,
) {
	suspend fun search(source: MangaSource, query: String): Result<List<Manga>> = runCatchingCancellable {
		searchHelperFactory.create(source)(query.trim(), SearchKind.SIMPLE)?.manga.orEmpty()
	}
}
