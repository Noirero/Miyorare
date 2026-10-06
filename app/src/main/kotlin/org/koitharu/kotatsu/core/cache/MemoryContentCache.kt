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
	private val pagesCache = ExpiringLruCache<SafeDeferred<List<MangaPage>>>(if (isLowRam) 1 else 4, 10, TimeUnit.MINUTES)
	private val relatedMangaCache = ExpiringLruCache<SafeDeferred<List<Manga>>>(if (isLowRam) 1 else 3, 10, TimeUnit.MINUTES)

	private val detailsRequestMutex = Mutex()
	private val inFlightDetails = ConcurrentHashMap<DetailsRequestKey, SafeDeferred<Manga>>()
	private val pagesRequestMutex = Mutex()
	private val inFlightPages = ConcurrentHashMap<PagesRequestKey, SafeDeferred<List<MangaPage>>>()

	init { application.registerComponentCallbacks(this) }

	suspend fun getDetails(source: MangaSource, url: String): Manga? = detailsCache[Key(source, url)]?.awaitOrNull()
	fun putDetails(source: MangaSource, url: String, details: SafeDeferred<Manga>) { detailsCache[Key(source, url)] = details }

	suspend fun getOrCreateInFlightDetails(source: MangaSource, mangaId: Long, create: suspend () -> SafeDeferred<Manga>): SafeDeferred<Manga> {
		val key = DetailsRequestKey(source.name, mangaId)
		detailsRequestMutex.lock()
		return try {
			inFlightDetails[key] ?: create().also { request ->
				inFlightDetails[key] = request
				request.invokeOnCompletion { inFlightDetails.remove(key, request) }
			}
		} finally { detailsRequestMutex.unlock() }
	}

	suspend fun getPages(source: MangaSource, url: String): List<MangaPage>? = pagesCache[Key(source, url)]?.awaitOrNull()
	fun putPages(source: MangaSource, url: String, pages: SafeDeferred<List<MangaPage>>) { pagesCache[Key(source, url)] = pages }

	suspend fun getPersistentPages(source: MangaSource, chapter: MangaChapter): List<MangaPage>? =
		pageMetadataCache.get(source, chapter)

	suspend fun putPersistentPages(source: MangaSource, chapter: MangaChapter, pages: List<MangaPage>) {
		pageMetadataCache.put(source, chapter, pages)
	}

	suspend fun invalidatePages(source: MangaSource, chapter: MangaChapter) {
		pagesCache.remove(Key(source, chapter.url))
		pageMetadataCache.invalidate(source, chapter)
		val key = PagesRequestKey(source.name, chapter.id, chapter.url)
		inFlightPages[key]?.let { inFlightPages.remove(key, it) }
	}

	suspend fun getOrCreateInFlightPages(
		source: MangaSource,
		chapter: MangaChapter,
		create: suspend () -> SafeDeferred<List<MangaPage>>,
	): SafeDeferred<List<MangaPage>> {
		val key = PagesRequestKey(source.name, chapter.id, chapter.url)
		pagesRequestMutex.lock()
		return try {
			inFlightPages[key] ?: create().also { request ->
				inFlightPages[key] = request
				request.invokeOnCompletion { inFlightPages.remove(key, request) }
			}
		} finally { pagesRequestMutex.unlock() }
	}

	suspend fun getRelatedManga(source: MangaSource, url: String): List<Manga>? = relatedMangaCache[Key(source, url)]?.awaitOrNull()
	fun putRelatedManga(source: MangaSource, url: String, related: SafeDeferred<List<Manga>>) { relatedMangaCache[Key(source, url)] = related }

	fun clear(source: MangaSource) {
		clearCache(detailsCache, source)
		clearCache(pagesCache, source)
		clearCache(relatedMangaCache, source)
		for ((key, request) in inFlightDetails) if (key.sourceName == source.name) inFlightDetails.remove(key, request)
		for ((key, request) in inFlightPages) if (key.sourceName == source.name) inFlightPages.remove(key, request)
	}

	override fun onConfigurationChanged(newConfig: Configuration) = Unit
	@Deprecated("Deprecated in Java") override fun onLowMemory() = Unit

	override fun onTrimMemory(level: Int) {
		trimCache(detailsCache, level); trimCache(pagesCache, level); trimCache(relatedMangaCache, level)
	}

	@Suppress("DEPRECATION")
	private fun trimCache(cache: ExpiringLruCache<*>, level: Int) {
		when (level) {
			ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL, ComponentCallbacks2.TRIM_MEMORY_COMPLETE, ComponentCallbacks2.TRIM_MEMORY_MODERATE -> cache.clear()
			ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN, ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW, ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> cache.trimToSize(1)
			else -> cache.trimToSize(cache.maxSize / 2)
		}
	}

	private fun clearCache(cache: ExpiringLruCache<*>, source: MangaSource) { cache.removeAll(source) }
	private data class DetailsRequestKey(val sourceName: String, val mangaId: Long)
	private data class PagesRequestKey(val sourceName: String, val chapterId: Long, val chapterUrl: String)
	data class Key(val source: MangaSource, val url: String)
}
