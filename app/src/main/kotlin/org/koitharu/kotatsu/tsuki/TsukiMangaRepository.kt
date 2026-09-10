@file:OptIn(org.koitharu.kotatsu.parsers.InternalParsersApi::class)

package org.koitharu.kotatsu.tsuki

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.Response
import org.koitharu.kotatsu.core.cache.MemoryContentCache
import org.koitharu.kotatsu.core.parser.CachingMangaRepository
import org.koitharu.kotatsu.core.prefs.SourceSettings
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.tsuki.model.TsukiMangaSource
import org.koitharu.kotatsu.tsuki.runtime.TsukiPluginRuntime
import org.koitharu.kotatsu.tsuki.runtime.toMiyorare
import org.koitharu.kotatsu.tsuki.runtime.toTsuki
import org.koitharu.kotatsu.tsuki.runtime.withTsukiExceptions
import java.util.EnumSet

/**
 * Adapts one optional Tsuki source to Miyorare's existing repository contract. No database,
 * favourites, history, reader or download schema changes are needed: everything above this layer
 * continues to see ordinary Kotatsu model objects carrying a namespaced [TsukiMangaSource].
 */
class TsukiMangaRepository(
	override val source: TsukiMangaSource,
	cache: MemoryContentCache,
	context: Context,
	private val runtime: TsukiPluginRuntime,
) : CachingMangaRepository(cache) {

	private val sourceSettings = SourceSettings(context, source)

	override val sortOrders: Set<SortOrder>
		get() {
			val parser = runtime.peekHandle(source)?.parser ?: return EnumSet.of(
				SortOrder.POPULARITY,
				SortOrder.RELEVANCE,
			)
			val mapped = parser.availableSortOrders.mapTo(mutableSetOf()) { it.toMiyorare() }
			return if (mapped.isEmpty()) EnumSet.of(SortOrder.POPULARITY) else EnumSet.copyOf(mapped)
		}

	override var defaultSortOrder: SortOrder
		get() {
			sourceSettings.defaultSortOrder?.let { stored ->
				val parser = runtime.peekHandle(source)?.parser
				if (parser == null || runCatching { stored.toTsuki() in parser.availableSortOrders }.getOrDefault(false)) {
					return stored
				}
			}
			return runtime.peekHandle(source)?.parser?.availableSortOrders?.firstOrNull()?.toMiyorare()
				?: SortOrder.POPULARITY
		}
		set(value) {
			sourceSettings.defaultSortOrder = value
		}

	/**
	 * Do not open/verify a JAR from this synchronous property. Before first use, expose only the
	 * capability Global Search needs; the exact capability set replaces it once the parser is loaded
	 * from a suspend/background repository call.
	 */
	override val filterCapabilities: MangaListFilterCapabilities
		get() = runtime.peekHandle(source)?.parser?.filterCapabilities?.toMiyorare()
			?: MangaListFilterCapabilities(isSearchSupported = true)

	override suspend fun getList(
		offset: Int,
		order: SortOrder?,
		filter: MangaListFilter?,
	): List<Manga> = withContext(Dispatchers.IO) {
		val handle = runtime.getHandle(source)
		withTsukiExceptions(handle.source) {
			val requestedFilter = filter ?: MangaListFilter.EMPTY
			if (!requestedFilter.query.isNullOrBlank() && !handle.parser.filterCapabilities.isSearchSupported) {
				return@withTsukiExceptions emptyList()
			}
			val available = handle.parser.availableSortOrders
			val requested = order?.let { runCatching { it.toTsuki() }.getOrNull() }?.takeIf { it in available }
			val stored = sourceSettings.defaultSortOrder
				?.let { runCatching { it.toTsuki() }.getOrNull() }
				?.takeIf { it in available }
			val actualOrder = requested ?: stored ?: available.firstOrNull()
				?: error("Tsuki source ${handle.source.displayName} exposes no sort orders")
			val actualFilter = requestedFilter.toTsuki(handle.rawSource)
			handle.parser.getList(offset, actualOrder, actualFilter).map { it.toMiyorare(handle.source) }
		}
	}

	override suspend fun getDetailsImpl(manga: Manga): Manga {
		val handle = runtime.getHandle(source)
		return withTsukiExceptions(handle.source) {
			handle.parser.getDetails(manga.toTsuki(handle.rawSource, handle.source))
				.toMiyorare(handle.source)
				.copy(id = manga.id)
		}
	}

	override suspend fun getPagesImpl(chapter: MangaChapter): List<MangaPage> {
		val handle = runtime.getHandle(source)
		return withTsukiExceptions(handle.source) {
			handle.parser.getPages(chapter.toTsuki(handle.rawSource, handle.source))
				.map { it.toMiyorare(handle.source) }
		}
	}

	override suspend fun getPageUrl(page: MangaPage): String {
		val handle = runtime.getHandle(source)
		return withTsukiExceptions(handle.source) {
			handle.parser.getPageUrl(page.toTsuki(handle.rawSource, handle.source)).also {
				check(it.isNotBlank()) { "Tsuki page URL is empty" }
			}
		}
	}

	override suspend fun getFilterOptions(): MangaListFilterOptions = withContext(Dispatchers.IO) {
		val handle = runtime.getHandle(source)
		withTsukiExceptions(handle.source) {
			handle.parser.getFilterOptions().toMiyorare(handle.source)
		}
	}

	override suspend fun getRelatedMangaImpl(seed: Manga): List<Manga> {
		val handle = runtime.getHandle(source)
		return withTsukiExceptions(handle.source) {
			handle.parser.getRelatedManga(seed.toTsuki(handle.rawSource, handle.source))
				.map { it.toMiyorare(handle.source) }
		}
	}

	override suspend fun getImageRequestHeaders(imageUrl: String, page: MangaPage): Headers = withContext(Dispatchers.IO) {
		runtime.getRequestHeaders(source)
	}

	/**
	 * Reader/download images intentionally go through the plugin's private client. This executes the
	 * Tsuki parser interceptor, which is where a source may decrypt or otherwise transform images.
	 */
	override suspend fun getImageStream(pageUrl: String, page: MangaPage): Response = withContext(Dispatchers.IO) {
		val handle = runtime.getHandle(source)
		withTsukiExceptions(handle.source) {
			handle.httpClient.newCall(runtime.createTaggedRequest(handle.source, pageUrl)).execute()
		}
	}

	override suspend fun getCoverStream(url: String): Response = withContext(Dispatchers.IO) {
		val handle = runtime.getHandle(source)
		withTsukiExceptions(handle.source) {
			handle.httpClient.newCall(runtime.createTaggedRequest(handle.source, url)).execute()
		}
	}
}
