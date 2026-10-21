@file:OptIn(org.koitharu.kotatsu.parsers.InternalParsersApi::class)

package org.koitharu.kotatsu.kotatsu

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers
import org.json.JSONArray
import org.koitharu.kotatsu.core.cache.MemoryContentCache
import org.koitharu.kotatsu.core.parser.CachingMangaRepository
import org.koitharu.kotatsu.core.prefs.SourceSettings
import org.koitharu.kotatsu.kotatsu.model.KotatsuMangaSource
import org.koitharu.kotatsu.kotatsu.runtime.KotatsuRuntimeManager
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.SortOrder
import java.util.EnumSet

/** Adapts protocol-v1 JSON from the isolated Kotatsu Runtime Pack to Miyorare's native models. */
class KotatsuMangaRepository(
	override val source: KotatsuMangaSource,
	cache: MemoryContentCache,
	context: Context,
	private val runtimeManager: KotatsuRuntimeManager,
) : CachingMangaRepository(cache) {
	private val settings = SourceSettings(context, source)

	override val sortOrders: Set<SortOrder>
		get() {
			val mapped = runCatching { runtimeManager.runtime().sortOrders(source.sourceKey) }
				.getOrDefault(emptySet()).mapNotNullTo(LinkedHashSet()) { runCatching { SortOrder.valueOf(it) }.getOrNull() }
			return if (mapped.isEmpty()) EnumSet.of(SortOrder.POPULARITY, SortOrder.RELEVANCE) else EnumSet.copyOf(mapped)
		}

	override var defaultSortOrder: SortOrder
		get() = settings.defaultSortOrder?.takeIf { it in sortOrders } ?: sortOrders.first()
		set(value) { settings.defaultSortOrder = value }

	override val filterCapabilities: MangaListFilterCapabilities
		get() = runCatching { runtimeManager.runtime().filterCapabilities(source.sourceKey).toRuntimeCapabilities() }
			.getOrElse { MangaListFilterCapabilities(isSearchSupported = true) }

	override suspend fun getList(offset: Int, order: SortOrder?, filter: MangaListFilter?): List<Manga> = withContext(Dispatchers.IO) {
		val session = runtimeManager.runtime()
		val actualOrder = order?.takeIf { it in sortOrders } ?: defaultSortOrder
		decodeRuntimeMangaList(session.getList(source.sourceKey, offset, actualOrder.name, filter.toKotatsuRuntimeJson()), source)
	}

	override suspend fun getDetailsImpl(manga: Manga): Manga = withContext(Dispatchers.IO) {
		decodeRuntimeManga(runtimeManager.runtime().getDetails(source.sourceKey, manga.toKotatsuRuntimeJson()), source).copy(id = manga.id)
	}

	override suspend fun getPagesImpl(chapter: MangaChapter): List<MangaPage> = withContext(Dispatchers.IO) {
		decodeRuntimePages(runtimeManager.runtime().getPages(source.sourceKey, chapter.toKotatsuRuntimeJson()), source)
	}

	override suspend fun getPageUrl(page: MangaPage): String = withContext(Dispatchers.IO) {
		runtimeManager.runtime().getPageUrl(source.sourceKey, page.toKotatsuRuntimeJson()).also { check(it.isNotBlank()) }
	}

	override suspend fun getFilterOptions(): MangaListFilterOptions = withContext(Dispatchers.IO) {
		runtimeManager.runtime().filterOptions(source.sourceKey).toRuntimeOptions(source)
	}

	override suspend fun getRelatedMangaImpl(seed: Manga): List<Manga> = withContext(Dispatchers.IO) {
		decodeRuntimeMangaList(runtimeManager.runtime().getRelated(source.sourceKey, seed.toKotatsuRuntimeJson()), source)
	}

	override suspend fun getImageRequestHeaders(imageUrl: String, page: MangaPage): Headers = withContext(Dispatchers.IO) {
		val root = runtimeManager.runtime().requestHeaders(source.sourceKey)
		Headers.Builder().apply {
			for (name in root.keys()) {
				when (val value = root.opt(name)) {
					is JSONArray -> for (i in 0 until value.length()) add(name, value.getString(i))
					is String -> add(name, value)
				}
			}
		}.build()
	}
}
