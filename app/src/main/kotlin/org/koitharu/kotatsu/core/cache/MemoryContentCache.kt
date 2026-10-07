package org.koitharu.kotatsu.core.cache

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import kotlinx.coroutines.sync.Mutex
import org.koitharu.kotatsu.core.util.ext.isLowRamDevice
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaSource
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MemoryContentCache @Inject constructor(
	application: Application,
	private val pageMetadataCache: ChapterPageMetadataCache,
) : ComponentCallbacks2 {

	private val isLowRam = application.isLowRamDevice()

	private val detailsCache = ExpiringLruCache<SafeDeferred<Manga>>(if (isLowRam) 1 else 4, 5, TimeUnit.MINUTES)
	private val pagesCache =
		ExpiringLruCache<SafeDeferred<List<MangaPage>>>(if (isLowRam) 1 else 4, 10, TimeUnit.MINUTES)
	private val relatedMangaCache =
		ExpiringLruCache<SafeDeferred<List<Manga>>>(if (isLowRam) 1 else 3, 10, TimeUnit.MINUTES)

	// Active details requests are coordination state, not completed content cache. Keep them outside
	// the small LRU so unrelated entries cannot evict unfinished work, and share them process-wide
	// across Details, Library Update and any repository wrapper resolving the same source + manga.
	private val detailsRequestMutex = Mutex()
	private val inFlightDetails = ConcurrentHashMap<DetailsRequestKey, SafeDeferred<Manga>>()
	// Page-list resolution has the same ownership requirement: Reader, Downloader and recreated
	// repository wrappers must share one source request instead of racing independent getPageList calls.
	private val pagesRequestMutex = Mutex()
	private val inFlightPages = ConcurrentHashMap<PagesRequestKey, SafeDeferred<List<MangaPage>>>()
	private val pageGenerations = ConcurrentHashMap<PagesRequestKey, Long>()

	init {
		application.registerComponentCallbacks(this)
	}

	suspend fun getDetails(source: MangaSource, url: String): Manga? {
		return detailsCache[Key(source, url)]?.awaitOrNull()
	}

	fun putDetails(source: MangaSource, url: String, details: SafeDeferred<Manga>) {
		detailsCache[Key(source, url)] = details
	}

	/**
	 * Return the active details request for this source/manga, or create exactly one new request.
	 * Completed requests are removed immediately and remain available only through the normal LRU
	 * when the caller chose a write-enabled cache policy, so a later fresh request still revalidates.
	 */
	suspend fun getOrCreateInFlightDetails(
		source: MangaSource,
		mangaId: Long,
		create: suspend () -> SafeDeferred<Manga>,
	): SafeDeferred<Manga> {
		val key = DetailsRequestKey(sourceName = source.name, mangaId = mangaId)
		detailsRequestMutex.lock()
		return try {
			inFlightDetails[key] ?: create().also { request ->
				inFlightDetails[key] = request
				request.invokeOnCompletion {
					inFlightDetails.remove(key, request)
				}
			}
		} finally {
			detailsRequestMutex.unlock()
		}
	}

	suspend fun getPages(source: MangaSource, url: String): List<MangaPage>? {
		return pagesCache[Key(source, url)]?.awaitOrNull()
	}

	fun putPages(source: MangaSource, url: String, pages: SafeDeferred<List<MangaPage>>) {
		pagesCache[Key(source, url)] = pages
	}

	suspend fun getPersistentPages(source: MangaSource, chapter: MangaChapter): List<MangaPage>? {
		return pageMetadataCache.get(source, chapter)
	}

	suspend fun putPersistentPagesIfCurrent(
		source: MangaSource,
		chapter: MangaChapter,
		generation: Long,
		pages: List<MangaPage>,
	) {
		val key = PagesRequestKey(source.name, chapter.id, chapter.url)
		pagesRequestMutex.lock()
		try {
			if (pageGenerations[key] ?: 0L == generation) {
				pageMetadataCache.put(source, chapter, pages)
			}
		} finally {
			pagesRequestMutex.unlock()
		}
	}

	/**
	 * Detach future callers from the current page-list generation without cancelling existing
	 * consumers. This is retained for explicit invalidation paths that do not immediately resolve a
	 * replacement generation.
	 */
	suspend fun invalidatePages(source: MangaSource, chapter: MangaChapter) {
		val key = PagesRequestKey(source.name, chapter.id, chapter.url)
		pagesRequestMutex.lock()
		try {
			pagesCache.remove(Key(source, chapter.url))
			pageGenerations[key] = (pageGenerations[key] ?: 0L) + 1L
			pageMetadataCache.invalidate(source, chapter)
			inFlightPages[key]?.let { request -> inFlightPages.remove(key, request) }
		} finally {
			pagesRequestMutex.unlock()
		}
	}

	suspend fun getOrCreateInFlightPages(
		source: MangaSource,
		chapter: MangaChapter,
		create: suspend (generation: Long) -> SafeDeferred<List<MangaPage>>,
	): SafeDeferred<List<MangaPage>> {
		val key = PagesRequestKey(source.name, chapter.id, chapter.url)
		pagesRequestMutex.lock()
		return try {
			inFlightPages[key] ?: create(pageGenerations[key] ?: 0L).also { request ->
				registerPagesRequest(key, request)
			}
		} finally {
			pagesRequestMutex.unlock()
		}
	}

	/**
	 * Atomically detach the old generation and install one fresh generation. Existing consumers keep
	 * their old SafeDeferred, while callers arriving after this operation join the new request instead
	 * of racing between invalidation and getOrCreateInFlightPages(). The generation also prevents the
	 * detached request from writing stale metadata after this invalidation.
	 */
	suspend fun createFreshInFlightPages(
		source: MangaSource,
		chapter: MangaChapter,
		create: suspend (generation: Long) -> SafeDeferred<List<MangaPage>>,
	): SafeDeferred<List<MangaPage>> {
		val key = PagesRequestKey(source.name, chapter.id, chapter.url)
		pagesRequestMutex.lock()
		return try {
			pagesCache.remove(Key(source, chapter.url))
			val generation = (pageGenerations[key] ?: 0L) + 1L
			pageGenerations[key] = generation
			pageMetadataCache.invalidate(source, chapter)
			inFlightPages[key]?.let { request -> inFlightPages.remove(key, request) }
			create(generation).also { request -> registerPagesRequest(key, request) }
		} finally {
			pagesRequestMutex.unlock()
		}
	}

	private fun registerPagesRequest(key: PagesRequestKey, request: SafeDeferred<List<MangaPage>>) {
		inFlightPages[key] = request
		request.invokeOnCompletion {
			inFlightPages.remove(key, request)
		}
	}

	suspend fun getRelatedManga(source: MangaSource, url: String): List<Manga>? {
		return relatedMangaCache[Key(source, url)]?.awaitOrNull()
	}

	fun putRelatedManga(source: MangaSource, url: String, related: SafeDeferred<List<Manga>>) {
		relatedMangaCache[Key(source, url)] = related
	}

	fun clear(source: MangaSource) {
		clearCache(detailsCache, source)
		clearCache(pagesCache, source)
		clearCache(relatedMangaCache, source)
		// Invalidation detaches future callers from the old generation. The process-scoped request is
		// deliberately not cancelled: an existing caller may still consume its result, while a later
		// request is free to start a new generation.
		for ((key, request) in inFlightDetails) {
			if (key.sourceName == source.name) inFlightDetails.remove(key, request)
		}
		for ((key, request) in inFlightPages) {
			if (key.sourceName == source.name) inFlightPages.remove(key, request)
		}
		for (key in pageGenerations.keys) {
			if (key.sourceName == source.name) pageGenerations.remove(key)
		}
	}

	override fun onConfigurationChanged(newConfig: Configuration) = Unit

	@Deprecated("Deprecated in Java")
	override fun onLowMemory() = Unit

	override fun onTrimMemory(level: Int) {
		trimCache(detailsCache, level)
		trimCache(pagesCache, level)
		trimCache(relatedMangaCache, level)
	}

	@Suppress("DEPRECATION")
	private fun trimCache(cache: ExpiringLruCache<*>, level: Int) {
		when (level) {
			ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
			ComponentCallbacks2.TRIM_MEMORY_COMPLETE,
			ComponentCallbacks2.TRIM_MEMORY_MODERATE -> cache.clear()

			ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN,
			ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
			ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> cache.trimToSize(1)

			else -> cache.trimToSize(cache.maxSize / 2)
		}
	}

	private fun clearCache(cache: ExpiringLruCache<*>, source: MangaSource) {
		cache.removeAll(source)
	}

	private data class DetailsRequestKey(
		val sourceName: String,
		val mangaId: Long,
	)

	private data class PagesRequestKey(
		val sourceName: String,
		val chapterId: Long,
		val chapterUrl: String,
	)

	data class Key(
		val source: MangaSource,
		val url: String,
	)
}
