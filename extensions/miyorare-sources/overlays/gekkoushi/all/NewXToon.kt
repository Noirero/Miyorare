package tsuki.site.all

import okhttp3.Headers
import org.jsoup.nodes.Document
import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser
import tsuki.model.*
import tsuki.util.*
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale
import java.util.TimeZone

/**
 * Miyorare-owned Tsuki adapter for New XTOON.
 *
 * Request paths, catalogue/detail selectors, chapter JSON pagination and reader selectors mirror
 * the behavior of the Mihon/Keiyoushi extension supplied as the compatibility reference. The
 * implementation remains native Tsuki so it can ship inside Miyorare Global.
 */
@MangaSourceParser("NEWXTOON", "New XTOON", type = ContentType.MANHWA)
internal class NewXToon(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.NEWXTOON, PAGE_SIZE) {

    override val configKeyDomain = ConfigKey.Domain("newxtoon1.com")

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(isSearchSupported = true)

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED, MangaState.PAUSED),
    )

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val query = filter.query?.trim().orEmpty()
        val builder = urlBuilder()
        if (query.isNotEmpty()) {
            builder.addPathSegment("search").addQueryParameter("q", query)
        } else {
            builder.addPathSegment("comics")
                .addQueryParameter("sort", if (order == SortOrder.POPULARITY) "popular" else "latest")
        }
        builder.addQueryParameter("page", page.toString())
        return parseComicList(webClient.httpGet(builder.build()).parseHtml())
    }

    private fun parseComicList(document: Document): List<Manga> {
        val seen = HashSet<String>()
        return document.select("a.comic-link[href*=/comics/]:not([data-cover-ad])").mapNotNull { element ->
            val href = element.attr("href")
            val id = COMIC_ID_REGEX.find(href)?.groupValues?.getOrNull(1) ?: return@mapNotNull null
            if (!seen.add(id)) return@mapNotNull null
            val title = element.selectFirst("h3")?.text()?.trim().orEmpty()
            if (title.isBlank()) return@mapNotNull null
            val cover = element.selectFirst("img.cover-image")?.absUrl("src")?.takeIf { it.isNotBlank() }
            Manga(
                id = generateUid(id),
                title = title,
                altTitles = emptySet(),
                url = id,
                publicUrl = "https://$domain/comics/$id",
                rating = RATING_UNKNOWN,
                contentRating = null,
                coverUrl = cover,
                tags = emptySet(),
                state = null,
                authors = emptySet(),
                source = source,
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val id = normalizeComicId(manga.url)
        val document = webClient.httpGet("https://$domain/comics/$id").parseHtml()
        val info = document.selectFirst("section[aria-labelledby=comic-title]") ?: document
        val title = info.selectFirst("#comic-title")?.text()?.trim().orEmpty().ifBlank { manga.title }
        val cover = info.selectFirst("img[alt$=표지]")?.absUrl("src")?.takeIf { it.isNotBlank() } ?: manga.coverUrl
        val authors = info.select("#comic-title + p a").mapNotNullTo(LinkedHashSet()) {
            it.text().trim().takeIf(String::isNotBlank)
        }
        val tags = info.select("a[href*='/comics?category=']").mapNotNullTo(LinkedHashSet()) { tag ->
            val text = tag.text().trim().takeIf(String::isNotBlank) ?: return@mapNotNullTo null
            MangaTag(title = text, key = text, source = source)
        }
        val state = when {
            info.selectFirst("strong:containsOwn(완결)") != null -> MangaState.FINISHED
            info.selectFirst("strong:containsOwn(연재중)") != null -> MangaState.ONGOING
            info.selectFirst("strong:containsOwn(휴재)") != null -> MangaState.PAUSED
            else -> manga.state
        }
        val adult = info.text().contains("성인만화") || tags.any { it.title.contains("성인") || it.title.contains("고수위") }
        return manga.copy(
            title = title,
            url = id,
            publicUrl = "https://$domain/comics/$id",
            coverUrl = cover,
            largeCoverUrl = cover ?: manga.largeCoverUrl,
            authors = authors.ifEmpty { manga.authors },
            description = info.selectFirst("[data-comic-description]")?.wholeText()?.trim()?.takeIf { it.isNotBlank() },
            tags = tags.ifEmpty { manga.tags },
            state = state,
            contentRating = if (adult) ContentRating.ADULT else manga.contentRating,
            chapters = fetchChapters(id),
        )
    }

    private suspend fun fetchChapters(mangaId: String): List<MangaChapter> {
        val result = ArrayList<MangaChapter>()
        val seen = HashSet<Long>()
        var page = 1
        var hasMore: Boolean
        do {
            val url = urlBuilder()
                .addPathSegments("comics/$mangaId/chapters")
                .addQueryParameter("sort", "latest")
                .addQueryParameter("page", page.toString())
                .build()
            val payload = webClient.httpGet(url, JSON_HEADERS).parseJson()
            val chapters = payload.optJSONArray("chapters") ?: break
            for (index in 0 until chapters.length()) {
                val chapter = chapters.optJSONObject(index) ?: continue
                val chapterId = chapter.optLong("id", -1L)
                if (chapterId <= 0L || !seen.add(chapterId)) continue
                val title = chapter.optString("title").trim().ifBlank { "Chapter" }
                val path = "/comics/$mangaId/chapters/$chapterId"
                result += MangaChapter(
                    id = generateUid(path),
                    title = title,
                    number = CHAPTER_NUMBER_REGEX.find(title)?.value?.toFloatOrNull() ?: 0f,
                    volume = 0,
                    url = path,
                    scanlator = null,
                    uploadDate = parseDate(chapter.optString("date")),
                    branch = null,
                    source = source,
                )
            }
            hasMore = payload.optBoolean("has_more", false)
            page++
        } while (hasMore && page <= MAX_CHAPTER_PAGES)
        return result
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val document = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
        val seen = HashSet<String>()
        return document.select("[data-reader-canvas] [data-reader-page] img[data-reader-image]").mapNotNull { image ->
            val url = image.absUrl("src").ifBlank { image.attr("src").toAbsoluteUrl(domain) }
            if (url.isBlank() || !seen.add(url)) return@mapNotNull null
            MangaPage(id = generateUid(url), url = url, preview = null, source = source)
        }
    }

    private fun normalizeComicId(value: String): String =
        COMIC_ID_REGEX.find(value)?.groupValues?.getOrNull(1) ?: value.trim('/').substringAfterLast('/')

    private fun parseDate(value: String): Long = runCatching {
        SimpleDateFormat("yyyy.MM.dd", Locale.KOREA).apply {
            timeZone = TimeZone.getTimeZone("Asia/Seoul")
            isLenient = false
        }.parse(value.trim())?.time ?: 0L
    }.getOrDefault(0L)

    private companion object {
        const val PAGE_SIZE = 24
        const val MAX_CHAPTER_PAGES = 200
        val COMIC_ID_REGEX = Regex("/comics/(\\d+)")
        val CHAPTER_NUMBER_REGEX = Regex("\\d+(?:\\.\\d+)?")
        val JSON_HEADERS = Headers.headersOf(
            "Accept", "application/json",
            "X-Requested-With", "XMLHttpRequest",
        )
    }
}
