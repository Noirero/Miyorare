package org.koitharu.kotatsu.core.parser

import android.content.Context
import androidx.annotation.AnyThread
import androidx.collection.ArrayMap
import dagger.hilt.android.qualifiers.ApplicationContext
import org.koitharu.kotatsu.core.cache.MemoryContentCache
import org.koitharu.kotatsu.core.model.LocalMangaSource
import org.koitharu.kotatsu.core.model.MangaSource as ResolveMangaSource
import org.koitharu.kotatsu.core.model.MissingMangaSource
import org.koitharu.kotatsu.core.model.MangaSourceInfo
import org.koitharu.kotatsu.core.model.UnknownMangaSource
import org.koitharu.kotatsu.local.data.LocalRoutingMangaRepository
import org.koitharu.kotatsu.lnreader.LnMangaRepository
import org.koitharu.kotatsu.lnreader.LnPluginManager
import org.koitharu.kotatsu.lnreader.js.JsHost
import org.koitharu.kotatsu.lnreader.model.LnMangaSource
import org.koitharu.kotatsu.mihon.LazyMihonMangaRepository
import org.koitharu.kotatsu.mihon.MihonExtensionManager
import org.koitharu.kotatsu.mihon.MihonMangaRepository
import org.koitharu.kotatsu.mihon.model.MihonMangaSource
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import okhttp3.Headers
import okhttp3.Response
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.tsuki.TsukiMangaRepository
import org.koitharu.kotatsu.tsuki.TsukiPluginManager
import org.koitharu.kotatsu.tsuki.model.TsukiMangaSource
import org.koitharu.kotatsu.tsuki.model.TsukiSourceIdentity
import org.koitharu.kotatsu.tsuki.runtime.TsukiPluginRuntime
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

interface MangaRepository {

	val source: MangaSource
	val sortOrders: Set<SortOrder>
	var defaultSortOrder: SortOrder
	val filterCapabilities: MangaListFilterCapabilities

	suspend fun getList(offset: Int, order: SortOrder?, filter: MangaListFilter?): List<Manga>
	suspend fun getDetails(manga: Manga): Manga
	suspend fun getPages(chapter: MangaChapter): List<MangaPage>
	suspend fun getPageUrl(page: MangaPage): String
	suspend fun getChapterUrl(chapter: MangaChapter): String? = null
	suspend fun getChapterHtml(chapter: MangaChapter): String? = null
	suspend fun getImageRequestHeaders(imageUrl: String, page: MangaPage): Headers? = null
	suspend fun getImageStream(pageUrl: String, page: MangaPage): Response? = null
	suspend fun getCoverStream(url: String): Response? = null
	suspend fun getFilterOptions(): MangaListFilterOptions
	suspend fun getRelated(seed: Manga): List<Manga>

	suspend fun find(manga: Manga): Manga? {
		val list = getList(0, SortOrder.RELEVANCE, MangaListFilter(query = manga.title))
		return list.find { x -> x.id == manga.id }
	}

	@Singleton
	class Factory @Inject constructor(
		private val localMangaRepository: LocalRoutingMangaRepository,
		private val contentCache: MemoryContentCache,
		private val mihonExtensionManager: MihonExtensionManager,
		private val lnPluginManager: LnPluginManager,
		private val jsHost: JsHost,
		private val tsukiPluginManager: TsukiPluginManager,
		private val tsukiPluginRuntimeProvider: Provider<TsukiPluginRuntime>,
		@ApplicationContext private val context: Context,
	) {
		private val cache = ArrayMap<MangaSource, WeakReference<MangaRepository>>()

		@AnyThread
		fun create(source: MangaSource): MangaRepository {
			val unwrapped = resolveFreshSource(unwrap(source))
			val isExternalMissing = unwrapped is MissingMangaSource && unwrapped.name.startsWith("MIHON_")
			when (unwrapped) {
				LocalMangaSource -> return localMangaRepository
				UnknownMangaSource -> return EmptyMangaRepository(unwrapped)
				is MihonMangaSource -> mihonExtensionManager.initialize()
				is LnMangaSource -> lnPluginManager.initialize()
				is TsukiMangaSource -> tsukiPluginManager.initialize()
			}
			if (isExternalMissing) {
				cache[unwrapped]?.get()?.let { cached -> if (cached !is EmptyMangaRepository) return cached }
				return synchronized(cache) {
					cache[unwrapped]?.get()?.let { cached -> if (cached !is EmptyMangaRepository) return cached }
					val lazyRepo = LazyMihonMangaRepository(
						source = unwrapped,
						extensionManager = mihonExtensionManager,
						cache = contentCache,
						context = context,
					)
					cache[unwrapped] = WeakReference(lazyRepo)
					lazyRepo
				}
			}
			cache[unwrapped]?.get()?.let { cached ->
				if (unwrapped !is TsukiMangaSource || cached !is EmptyMangaRepository) return cached
			}
			return synchronized(cache) {
				cache[unwrapped]?.get()?.let { cached ->
					if (unwrapped !is TsukiMangaSource || cached !is EmptyMangaRepository) return cached
				}
				val repository = createRepository(unwrapped)
				if (repository != null) {
					cache[unwrapped] = WeakReference(repository)
					repository
				} else {
					EmptyMangaRepository(unwrapped).also { cache[unwrapped] = WeakReference(it) }
				}
			}
		}

		private fun unwrap(source: MangaSource): MangaSource = when (source) {
			is MangaSourceInfo -> source.mangaSource
			else -> source
		}

		private fun resolveFreshSource(source: MangaSource): MangaSource {
			if (source is MissingMangaSource && source.name.startsWith("MIHON_")) {
				mihonExtensionManager.initialize()
				return ResolveMangaSource(source.name)
			}
			if (source is MissingMangaSource && source.name.startsWith("LN_")) {
				lnPluginManager.initialize()
				return ResolveMangaSource(source.name)
			}
			if (source is MissingMangaSource && source.name.startsWith(TsukiSourceIdentity.PREFIX)) {
				tsukiPluginManager.initialize()
				return tsukiPluginManager.resolveSource(source.name) ?: source
			}
			return source
		}

		private fun createRepository(source: MangaSource): MangaRepository? = when (source) {
			is MihonMangaSource -> MihonMangaRepository(source = source, cache = contentCache, context = context)
			is LnMangaSource -> LnMangaRepository(source = source, cache = contentCache, jsHost = jsHost, pluginManager = lnPluginManager)
			is TsukiMangaSource -> TsukiMangaRepository(
				source = source,
				cache = contentCache,
				context = context,
				runtime = tsukiPluginRuntimeProvider.get(),
			)
			else -> null
		}
	}
}

interface FreshMangaDetailsRepository {
	suspend fun getFreshDetails(manga: Manga): Manga
}

interface ProgressiveMangaDetailsRepository {
	suspend fun getDetailsProgressively(manga: Manga, onIntermediate: suspend (Manga) -> Unit): Manga
}
