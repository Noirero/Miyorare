package tsuki.site.all

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser
import tsuki.model.*
import tsuki.util.*
import java.util.EnumSet

/**
 * Miyorare-owned Tsuki adapter for RawDEX.
 *
 * RawDEX moved away from its old Madara markup to rdx-* pages. Keep this parser aligned with the
 * current Mihon/Keiyoushi source contract instead of relying on Gekkoushi's pinned legacy Madara
 * implementation.
 */
@MangaSourceParser("RAWDEX", "RawDEX", type = ContentType.HENTAI)
internal class RawDex(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.RAWDEX, PAGE_SIZE) {

    override val configKeyDomain = ConfigKey.Domain("rawdex.net")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(isSearchSupported = true)

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val query = filter.query?.trim().orEmpty()
        val url = if (query.isNotEmpty()) {
            urlBuilder().apply {
                addQueryParameter("s", query)
                if (page > 1) addQueryParameter("spage", page.toString())
            }.build()
        } else {
            urlBuilder()
                .addPathSegments("manga/page/$page/")
                .addQueryParameter("m_orderby", if (order == SortOrder.POPULARITY) "views" else "latest")
                .build()
        }
        return parseBrowsePage(webClient.httpGet(url).parseHtml())
    }

    private fun parseBrowsePage(document: Document): List<Manga> =
        document.select("article.rdx-library-card").mapNotNull(::browseMangaFromElement)

    private fun browseMangaFromElement(element: Element): Manga? {
        val link = element.selectFirst(".rdx-library-card__body h2 a") ?: return null
        val title = link.text().trim().takeIf(String::isNotEmpty) ?: return null
        val href = link.absUrl("href").takeIf(String::isNotEmpty) ?: return null
        val path = href.toRelativeMangaPath()
        val cover = element.selectFirst(".rdx-library-card__cover img")?.absUrl("src")?.takeIf(String::isNotEmpty)
        return Manga(
            id = generateUid(path),
            title = title,
            altTitles = emptySet(),
            url = path,
            publicUrl = href,
            rating = RATING_UNKNOWN,
            contentRating = ContentRating.ADULT,
            coverUrl = cover,
            tags = emptySet(),
            state = null,
            authors = emptySet(),
            source = source,
        )
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val document = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
        val title = document.selectFirst(".rdx-manga-heading h1")?.text()?.trim().orEmpty().ifBlank { manga.title }
        val cover = document.selectFirst("img.rdx-manga-cover")?.absUrl("src")?.takeIf(String::isNotEmpty) ?: manga.coverUrl
        var author: String? = null
        var artist: String? = null
        document.select("dl.rdx-manga-meta div").forEach { element ->
            val value = element.selectFirst("dd")?.text()?.trim().orEmpty()
            when (element.selectFirst("dt")?.text()?.trim()?.lowercase()) {
                "author" -> author = value.takeIf(String::isNotEmpty)
                "artist" -> artist = value.takeIf(String::isNotEmpty)
            }
        }
        val authors = linkedSetOf<String>().apply {
            author?.let(::add)
            artist?.let(::add)
        }
        val tags = document.select(".rdx-manga-tags a").mapNotNullTo(LinkedHashSet()) { element ->
            val text = element.text().trim().takeIf(String::isNotEmpty) ?: return@mapNotNullTo null
            MangaTag(title = text, key = text, source = source)
        }
        val altTitles = document.selectFirst(".rdx-manga-alternative")?.text()
            ?.split('/', ';')
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.toSet()
            .orEmpty()
        val state = when (document.selectFirst(".rdx-manga-status")?.text()?.trim()?.lowercase()) {
            "on-going" -> MangaState.ONGOING
            "end" -> MangaState.FINISHED
            else -> manga.state
        }
        return manga.copy(
            title = title,
            altTitles = altTitles.ifEmpty { manga.altTitles },
            coverUrl = cover,
            largeCoverUrl = cover ?: manga.largeCoverUrl,
            description = document.selectFirst(".rdx-manga-summary")?.text()?.trim()?.takeIf(String::isNotEmpty),
            tags = tags.ifEmpty { manga.tags },
            state = state,
            authors = authors.ifEmpty { manga.authors },
            contentRating = ContentRating.ADULT,
            chapters = parseChapterList(document),
        )
    }

    private fun parseChapterList(document: Document): List<MangaChapter> =
        document.select(".rdx-chapter-list > a.rdx-chapter-row").mapNotNull { element ->
            val title = element.selectFirst(".rdx-chapter-row__label")?.text()?.trim()
                ?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
            val href = element.absUrl("href").takeIf(String::isNotEmpty) ?: return@mapNotNull null
            val path = href.toRelativeMangaPath()
            MangaChapter(
                id = generateUid(path),
                title = title,
                number = CHAPTER_NUMBER_REGEX.find(title)?.groupValues?.getOrNull(1)?.toFloatOrNull() ?: 0f,
                volume = 0,
                url = path,
                scanlator = null,
                uploadDate = element.selectFirst(".rdx-chapter-row__date")?.text()?.let(::parseRelativeDate) ?: 0L,
                branch = null,
                source = source,
            )
        }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val document = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
        return document.select(".rdx-reader-page img").mapNotNull { image ->
            val url = image.absUrl("src").takeIf(String::isNotEmpty) ?: return@mapNotNull null
            MangaPage(id = generateUid(url), url = url, preview = null, source = source)
        }
    }

    private fun String.toRelativeMangaPath(): String {
        val marker = "://$domain"
        return if (contains(marker)) substringAfter(marker).ifBlank { "/" } else this
    }

    private fun parseRelativeDate(value: String): Long {
        val match = RELATIVE_DATE_REGEX.find(value.trim()) ?: return 0L
        val amount = match.groupValues[1].toLongOrNull() ?: return 0L
        val unitMillis = when (match.groupValues[2]) {
            "second" -> 1_000L
            "minute" -> 60_000L
            "hour" -> 3_600_000L
            "day" -> 86_400_000L
            "week" -> 604_800_000L
            "month" -> 2_592_000_000L
            "year" -> 31_536_000_000L
            else -> return 0L
        }
        return System.currentTimeMillis() - amount * unitMillis
    }

    private companion object {
        const val PAGE_SIZE = 40
        val CHAPTER_NUMBER_REGEX = Regex("(\\d+(?:\\.\\d+)?)")
        val RELATIVE_DATE_REGEX = Regex("(\\d+)\\s+(second|minute|hour|day|week|month|year)s?\\s+ago")
    }
}
