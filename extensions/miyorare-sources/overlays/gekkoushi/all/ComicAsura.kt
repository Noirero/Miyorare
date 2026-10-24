package tsuki.site.all

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
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
 * Miyorare-owned Tsuki adapter for Comic Asura (https://comicasura.net/official).
 *
 * Catalogue/search, details, chapter rows and reader selectors are adapted natively to Tsuki and
 * cross-checked against Keiyoushi's Apache-2.0 Comic Asura MangaThemesia implementation. The
 * Keiyoushi runtime is not embedded in Miyorare Global.
 */
@MangaSourceParser("COMICASURA", "Comic Asura", type = ContentType.OTHER)
internal class ComicAsura(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.COMICASURA, PAGE_SIZE) {

    override val configKeyDomain = ConfigKey.Domain("comicasura.net")

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

    override suspend fun getFilterOptions() = MangaListFilterOptions()

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = urlBuilder()
            .addPathSegments("advanced-search/")
            .addQueryParameter("name", filter.query?.trim().orEmpty())
            .addQueryParameter("page", page.toString())
            .addQueryParameter("sort", if (order == SortOrder.POPULARITY) "rating" else "latest")
            .build()
        return parseList(webClient.httpGet(url).parseHtml())
    }

    private fun parseList(document: Document): List<Manga> {
        val seen = HashSet<String>()
        return document.select(LIST_SELECTOR).mapNotNull { element ->
            val href = element.attrAsRelativeUrl("href")
            if (href.isBlank() || !href.contains("/manga/") || !seen.add(href)) return@mapNotNull null

            val image = element.selectFirst("img")
            val title = image?.attr("title")?.trim().orEmpty()
                .ifBlank { image?.attr("alt")?.trim().orEmpty() }
                .ifBlank { element.text().trim() }
            if (title.isBlank()) return@mapNotNull null

            Manga(
                id = generateUid(href),
                title = title,
                altTitles = emptySet(),
                url = href,
                publicUrl = element.absUrl("href").ifBlank { href.toAbsoluteUrl(domain) },
                rating = RATING_UNKNOWN,
                contentRating = null,
                coverUrl = image?.imageUrl(),
                tags = emptySet(),
                state = null,
                authors = emptySet(),
                source = source,
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val document = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
        val info = document.selectFirst(DETAILS_SELECTOR) ?: document

        val title = info.selectFirst(TITLE_SELECTOR)?.text()?.trim().orEmpty().ifBlank { manga.title }
        val cover = info.selectFirst("img[alt=poster]")?.imageUrl() ?: manga.coverUrl
        val genres = info.select(GENRE_SELECTOR).mapNotNullTo(LinkedHashSet()) { element ->
            val text = element.text().trim().takeIf(String::isNotBlank) ?: return@mapNotNullTo null
            MangaTag(title = text, key = text, source = source)
        }
        val statusText = info.selectFirst(STATUS_SELECTOR)?.text()?.trim()
        val authors = labelValue(info, "Author")
            ?.split(',', ';')
            ?.mapNotNullTo(LinkedHashSet()) { it.trim().takeIf(String::isNotBlank) }
            .orEmpty()
        val description = info.selectFirst(DESCRIPTION_SELECTOR)
            ?.wholeText()
            ?.trim()
            ?.takeIf(String::isNotBlank)
        val adult = genres.any { tag ->
            ADULT_GENRES.any { adultGenre -> tag.title.contains(adultGenre, ignoreCase = true) }
        }

        return manga.copy(
            title = title,
            publicUrl = manga.url.toAbsoluteUrl(domain),
            coverUrl = cover,
            largeCoverUrl = cover ?: manga.largeCoverUrl,
            authors = authors.ifEmpty { manga.authors },
            description = description ?: manga.description,
            tags = genres.ifEmpty { manga.tags },
            state = parseState(statusText) ?: manga.state,
            contentRating = if (adult) ContentRating.ADULT else manga.contentRating,
            chapters = parseChapters(document),
        )
    }

    private fun parseChapters(document: Document): List<MangaChapter> =
        document.select(CHAPTER_SELECTOR).mapNotNull { element ->
            val link = element.selectFirst("a[href]") ?: return@mapNotNull null
            val href = link.attrAsRelativeUrl("href")
            if (href.isBlank()) return@mapNotNull null

            val title = element.selectFirst(CHAPTER_TITLE_SELECTOR)?.text()?.trim().orEmpty()
                .ifBlank { link.text().trim() }
                .ifBlank { "Chapter" }
            val number = CHAPTER_NUMBER_REGEX.find(title)?.groupValues?.getOrNull(1)?.toFloatOrNull() ?: 0f
            val date = element.selectFirst(CHAPTER_DATE_SELECTOR)?.text()?.trim()

            MangaChapter(
                id = generateUid(href),
                title = title,
                number = number,
                volume = 0,
                url = href,
                scanlator = null,
                uploadDate = parseDate(date),
                branch = null,
                source = source,
            )
        }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val document = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
        val seen = HashSet<String>()
        return document.select(PAGE_SELECTOR).mapNotNull { image ->
            val url = image.imageUrl() ?: return@mapNotNull null
            if (!seen.add(url)) return@mapNotNull null
            MangaPage(
                id = generateUid(url),
                url = url,
                preview = null,
                source = source,
            )
        }
    }

    private fun Element.imageUrl(): String? {
        for (name in IMAGE_ATTRIBUTES) {
            val raw = attr(name).trim()
            if (raw.isBlank() || raw.startsWith("data:")) continue
            return absUrl(name).ifBlank { raw.toAbsoluteUrl(domain) }
        }
        return null
    }

    private fun labelValue(root: Element, label: String): String? =
        root.selectFirst("div:containsOwn($label) + div")
            ?.text()
            ?.trim()
            ?.takeIf(String::isNotBlank)

    private fun parseState(value: String?): MangaState? = when {
        value.isNullOrBlank() -> null
        value.contains("ongoing", ignoreCase = true) || value.contains("publishing", ignoreCase = true) -> MangaState.ONGOING
        value.contains("completed", ignoreCase = true) || value.contains("finished", ignoreCase = true) -> MangaState.FINISHED
        value.contains("hiatus", ignoreCase = true) || value.contains("on hold", ignoreCase = true) -> MangaState.PAUSED
        else -> null
    }

    private fun parseDate(value: String?): Long {
        val normalized = value
            ?.replace(DATE_SUFFIX_REGEX, "")
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: return 0L
        return runCatching {
            SimpleDateFormat("MMMM d yyyy", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
                isLenient = false
            }.parse(normalized)?.time ?: 0L
        }.getOrDefault(0L)
    }

    private companion object {
        const val PAGE_SIZE = 20
        const val LIST_SELECTOR = ".grid > a[href*=manga], .flex-wrap.flex a[href*=manga]"
        const val DETAILS_SELECTOR = ".bg-\\[\\#222222\\]:has(h1)"
        const val TITLE_SELECTOR = ".comic-title-content"
        const val DESCRIPTION_SELECTOR = ".comic-content.mobile"
        const val GENRE_SELECTOR = "div.hidden div:contains(Genres) + div > a"
        const val STATUS_SELECTOR = "div:contains(Status) + div"
        const val CHAPTER_SELECTOR = ".chapter-items"
        const val CHAPTER_TITLE_SELECTOR = ".text-sm.text-white"
        const val CHAPTER_DATE_SELECTOR = ".text-xs.text-\\[\\#A2A2A2\\]:not(:has(span))"
        const val PAGE_SELECTOR = "div > img.object-cover.mx-auto"

        val IMAGE_ATTRIBUTES = arrayOf("data-lazy-src", "data-src", "data-original", "src")
        val CHAPTER_NUMBER_REGEX = Regex("(?i)(?:chapter|ch\\.?)\\s*(\\d+(?:\\.\\d+)?)")
        val DATE_SUFFIX_REGEX = Regex("(?<=\\d)(st|nd|rd|th)", RegexOption.IGNORE_CASE)
        val ADULT_GENRES = arrayOf("adult", "hentai", "smut", "mature")
    }
}
