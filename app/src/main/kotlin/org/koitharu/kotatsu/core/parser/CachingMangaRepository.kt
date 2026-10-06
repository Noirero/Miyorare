package org.koitharu.kotatsu.core.parser

import android.util.Log
import androidx.collection.MutableLongSet
import coil3.request.CachePolicy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.core.cache.MemoryContentCache
import org.koitharu.kotatsu.core.cache.SafeDeferred
import org.koitharu.kotatsu.core.util.MultiMutex
import org.koitharu.kotatsu.core.util.ext.processLifecycleScope
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import kotlin.coroutines.ContinuationInterceptor

abstract class CachingMangaRepository(
	private val cache: MemoryContentCache,
) : MangaRepository, FreshMangaDetailsRepository, FreshChapterPagesRepository {

	private val relatedMangaMutex = MultiMutex<Long>()

	final override suspend fun getDetails(manga: Manga): Manga = getDetails(manga, CachePolicy.ENABLED)
	final override suspend fun getFreshDetails(manga: Manga): Manga = getDetails(manga, CachePolicy.WRITE_ONLY)
	final override suspend fun getPages(chapter: MangaChapter): List<MangaPage> = getPages(chapter, forceRefresh = false)
	final override suspend fun getFreshPages(chapter: MangaChapter): List<MangaPage> = getPages(chapter, forceRefresh = true)

	private suspend fun getPages(chapter: MangaChapter, forceRefresh: Boolean): List<MangaPage> {
		if (forceRefresh) {
			cache.invalidatePages(source, chapter)
		} else {
			cache.getPages(source, chapter.url)?.let { return it }
			cache.getPersistentPages(source, chapter)?.let { return it }
		}
		val pages = cache.getOrCreateInFlightPages(source, chapter) {
			asyncSafe {
				getPagesImpl(chapter).distinctById().also { resolved ->
					cache.putPersistentPages(source, chapter, resolved)
				}
			}
		}
		cache.putPages(source, chapter.url, pages)
		return pages.await()
	}

	final override suspend fun getRelated(seed: Manga): List<Manga> = relatedMangaMutex.withLock(seed.id) {
		cache.getRelatedManga(source, seed.url)?.let { return it }
		val related = asyncSafe { getRelatedMangaImpl(seed).filterNot { it.id == seed.id } }
		cache.putRelatedManga(source, seed.url, related)
		related
	}.await()

	suspend fun getDetails(manga: Manga, cachePolicy: CachePolicy): Manga {
		if (cachePolicy.readEnabled) {
			cache.getDetails(source, manga.url)?.let { cached -> if (isCachedDetailsUsable(manga, cached)) return cached }
		}
		val details = cache.getOrCreateInFlightDetails(source, manga.id) { asyncSafe { getDetailsImpl(manga) } }
		if (cachePolicy.writeEnabled) cache.putDetails(source, manga.url, details)
		return details.await()
	}

	fun invalidateCache() { cache.clear(source) }
	protected open fun isCachedDetailsUsable(requested: Manga, cached: Manga): Boolean = true
	protected abstract suspend fun getDetailsImpl(manga: Manga): Manga
	protected abstract suspend fun getRelatedMangaImpl(seed: Manga): List<Manga>
	protected abstract suspend fun getPagesImpl(chapter: MangaChapter): List<MangaPage>

	private suspend fun <T> asyncSafe(block: suspend CoroutineScope.() -> T): SafeDeferred<T> {
		var dispatcher = currentCoroutineContext()[ContinuationInterceptor] as? CoroutineDispatcher
		if (dispatcher == null || dispatcher is MainCoroutineDispatcher) dispatcher = Dispatchers.Default
		return SafeDeferred(processLifecycleScope.async(dispatcher) { runCatchingCancellable { block() } })
	}

	private fun List<MangaPage>.distinctById(): List<MangaPage> {
		if (isEmpty()) return emptyList()
		val result = ArrayList<MangaPage>(size)
		val set = MutableLongSet(size)
		for (page in this) {
			if (set.add(page.id)) result.add(page) else if (BuildConfig.DEBUG) Log.w(null, "Duplicate page: $page")
		}
		return result
	}
}
