package org.koitharu.kotatsu.mihon

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Headers
import okhttp3.Response
import org.koitharu.kotatsu.core.cache.MemoryContentCache
import org.koitharu.kotatsu.core.exceptions.UnsupportedSourceException
import org.koitharu.kotatsu.core.model.MissingMangaSource
import org.koitharu.kotatsu.core.parser.FreshChapterPagesRepository
import org.koitharu.kotatsu.core.parser.FreshMangaDetailsRepository
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.parsers.InternalParsersApi
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.SortOrder
import java.util.EnumSet

@OptIn(InternalParsersApi::class)
class LazyMihonMangaRepository(
	override val source: MissingMangaSource,
	private val extensionManager: MihonExtensionManager,
	private val cache: MemoryContentCache,
	private val context: Context,
) : MangaRepository, FreshMangaDetailsRepository, FreshChapterPagesRepository, MihonFilterHost {

	@Volatile private var delegate: MihonMangaRepository? = null
	private val resolveMutex = Mutex()
	override val supportsDynamicFilters: Boolean get() = true
	override val inAppListingsExclusive: Boolean get() = true
	override suspend fun loadDefaultFilterList() = resolve().loadDefaultFilterList()
	override val sortOrders: Set<SortOrder> get() = delegate?.sortOrders ?: EnumSet.allOf(SortOrder::class.java)
	override var defaultSortOrder: SortOrder
	get() = delegate?.defaultSortOrder ?: SortOrder.POPULARITY
	set(value) { delegate?.defaultSortOrder = value }
	override val filterCapabilities: MangaListFilterCapabilities get() = delegate?.filterCapabilities ?: MangaListFilterCapabilities()
	override suspend fun getList(offset: Int, order: SortOrder?, filter: MangaListFilter?): List<Manga> = resolve().getList(offset, order, filter)
	override suspend fun getDetails(manga: Manga): Manga = resolve(manga).getDetails(manga)
	override suspend fun getFreshDetails(manga: Manga): Manga = resolve(manga).getFreshDetails(manga)
	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> = resolve().getPages(chapter)
	override suspend fun getFreshPages(chapter: MangaChapter): List<MangaPage> = resolve().getFreshPages(chapter)
	override suspend fun getPageUrl(page: MangaPage): String = resolve().getPageUrl(page)
	override suspend fun getImageRequestHeaders(imageUrl: String, page: MangaPage): Headers? = resolve().getImageRequestHeaders(imageUrl, page)
	override suspend fun getImageStream(pageUrl: String, page: MangaPage): Response? = resolve().getImageStream(pageUrl, page)
	override suspend fun getCoverStream(url: String): Response? = resolve().getCoverStream(url)
	override suspend fun getFilterOptions(): MangaListFilterOptions = resolve().getFilterOptions()
	override suspend fun getRelated(seed: Manga): List<Manga> = resolve().getRelated(seed)
	override suspend fun find(manga: Manga): Manga? = resolve(manga).find(manga)

	private suspend fun resolve(manga: Manga? = null): MihonMangaRepository {
		delegate?.let { return it }
		return resolveMutex.withLock {
			delegate?.let { return@withLock it }
			extensionManager.ensureReady()
			val mihonSource = extensionManager.getMihonMangaSourceByName(source.name)
				?: throw UnsupportedSourceException("Install the matching Mihon extension to read this manga", manga)
			MihonMangaRepository(mihonSource, cache, context).also { delegate = it }
		}
	}
}
