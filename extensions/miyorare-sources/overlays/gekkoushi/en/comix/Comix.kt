package tsuki.site.en.comix

import org.jsoup.nodes.Element
import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser
import tsuki.model.*
import tsuki.network.CommonHeaders
import tsuki.util.*
import java.util.EnumSet

/**
 * Miyorare-owned Comix recovery overlay.
 *
 * Comix moved its internal API from /api/v2 to a signed/encrypted /api/v1
 * protocol. The public title/reader pages remain server-rendered, so use that
 * stable public surface instead of coupling Miyorare to private crypto tables.
 */
@MangaSourceParser("COMIX", "Comix", "en", ContentType.MANGA)
internal class Comix(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.COMIX, 28) {

    override val configKeyDomain = ConfigKey.Domain("comix.to")

    override fun getRequestHeaders() = super.getRequestHeaders().newBuilder()
        .add(CommonHeaders.REFERER, "https://$domain/")
        .build()

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(isSearchSupported = true)

    override val availableSortOrders: Set<SortOrder> = linkedSetOf(
        SortOrder.RELEVANCE,
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.NEWEST,
        SortOrder.ALPHABETICAL,
    )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableContentRating = EnumSet.of(ContentRating.ADULT),
    )

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val sort = when (order) {
            SortOrder.RELEVANCE -> "relevance:desc"
            SortOrder.UPDATED -> "chapter_updated_at:desc"
            SortOrder.POPULARITY -> "views_30d:desc"
            SortOrder.NEWEST -> "created_at:desc"
            SortOrder.ALPHABETICAL -> "title:asc"
            else -> "chapter_updated_at:desc"
        }
        val url = buildString {
            append("https://").append(domain).append("/browse?order=").append(sort.urlEncoded())
            filter.query?.takeIf { it.isNotBlank() }?.let { append("&q=").append(it.urlEncoded()) }
            if (page > 1) append("&page=").append(page)
        }
        val doc = webClient.httpGet(url).parseHtml()
        val seen = HashSet<String>()
        return doc.select("a[href^=/title/]").mapNotNull { anchor ->
            val href = anchor.attrAsRelativeUrl("href").substringBefore('?')
            if (!seen.add(href) || href.count { it == '/' } != 2) return@mapNotNull null
            val card = anchor.closest("article, li, div") ?: anchor.parent()
            val title = firstNonBlank(
                anchor.attr("title"),
                card?.selectFirst("h2, h3, h4, [class*=title]")?.text(),
                anchor.selectFirst("img")?.attr("alt"),
                anchor.text(),
            ) ?: return@mapNotNull null
            val img = card?.selectFirst("img") ?: anchor.selectFirst("img")
            val cover = img?.let(::imageUrl).orEmpty()
            Manga(
                id = generateUid(href),
                url = href,
                publicUrl = href.toAbsoluteUrl(domain),
                coverUrl = cover,
                title = title,
                altTitles = emptySet(),
                description = null,
                rating = RATING_UNKNOWN,
                tags = emptySet(),
                authors = emptySet(),
                state = null,
                source = source,
                contentRating = null,
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
        val title = doc.selectFirst("h1")?.textOrNull() ?: manga.title
        val description = doc.selectFirst("meta[name=description]")?.attr("content")?.nullIfEmpty()
            ?: doc.selectFirst("[class*=synopsis], [class*=description]")?.textOrNull()
            ?: manga.description
        val cover = doc.selectFirst("meta[property=og:image]")?.attr("content")?.nullIfEmpty()
            ?: doc.selectFirst("main img")?.let(::imageUrl)?.nullIfEmpty()
            ?: manga.coverUrl
        val authors = doc.select("a[href*=authors], a[href*=artists]")
            .mapNotNullTo(linkedSetOf()) { it.textOrNull() }
        val tags = doc.select("a[href*=genres], a[href*=genre]").mapNotNullTo(linkedSetOf()) { a ->
            val name = a.textOrNull() ?: return@mapNotNullTo null
            val key = a.attr("href").substringAfterLast('=').substringAfterLast('/').nullIfEmpty() ?: name
            MangaTag(title = name, key = key, source = source)
        }
        val chapters = parseChapters(doc, manga.url)
        return manga.copy(
            title = title,
            coverUrl = cover,
            description = description,
            authors = authors,
            tags = tags,
            chapters = chapters,
        )
    }

    private fun parseChapters(root: Element, mangaUrl: String): List<MangaChapter> {
        val prefix = mangaUrl.substringBefore('?').trimEnd('/') + "/"
        val seen = HashSet<String>()
        return root.select("a[href^=$prefix]").mapNotNull { a ->
            val href = a.attrAsRelativeUrl("href").substringBefore('?')
            if (!seen.add(href) || !href.substringAfterLast('/').contains("chapter", ignoreCase = true)) {
                return@mapNotNull null
            }
            val slug = href.substringAfterLast('/')
            val numberText = Regex("chapter-([0-9]+(?:\\.[0-9]+)?)", RegexOption.IGNORE_CASE)
                .find(slug)?.groupValues?.getOrNull(1)
                ?: Regex("(?:ch\\.?|chapter)\\s*([0-9]+(?:\\.[0-9]+)?)", RegexOption.IGNORE_CASE)
                    .find(a.text())?.groupValues?.getOrNull(1)
            val number = numberText?.toFloatOrNull() ?: return@mapNotNull null
            val row = a.closest("tr, li, div")
            val scanlator = row?.selectFirst("[class*=group], [class*=scanlator]")?.textOrNull()
            MangaChapter(
                id = generateUid(href),
                title = a.textOrNull() ?: "Chapter $number",
                number = number,
                volume = 0,
                url = href,
                uploadDate = 0L,
                source = source,
                scanlator = scanlator,
                branch = scanlator,
            )
        }.sortedBy { it.number }
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
        val seen = HashSet<String>()
        return doc.select("img[alt^=Page], img[alt*=page], main img").mapNotNull { img ->
            val alt = img.attr("alt")
            val isReaderImage = alt.contains("page", ignoreCase = true) ||
                img.hasAttr("data-page") ||
                img.classNames().any { it.contains("page", ignoreCase = true) }
            if (!isReaderImage) return@mapNotNull null
            val url = imageUrl(img).nullIfEmpty() ?: return@mapNotNull null
            if (!seen.add(url)) return@mapNotNull null
            MangaPage(
                id = generateUid(url),
                url = url,
                preview = null,
                source = source,
            )
        }
    }

    private fun imageUrl(img: Element): String = firstNonBlank(
        img.attr("src"),
        img.attr("data-src"),
        img.attr("data-lazy-src"),
        img.attr("data-original"),
    )?.let { if (it.startsWith("//")) "https:$it" else it } ?: ""

    private fun firstNonBlank(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }?.trim()
}
