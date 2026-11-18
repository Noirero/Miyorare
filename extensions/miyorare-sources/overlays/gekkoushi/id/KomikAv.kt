package tsuki.site.id

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Element
import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser
import tsuki.model.*
import tsuki.util.*
import java.util.Calendar
import java.util.EnumSet

/**
 * Miyorare-owned KomikAv parser for komikav.net.
 *
 * Replaces the upstream Gekkoushi KomikAvParser, which is annotated @Broken: it subclasses the
 * shared MangaReaderParser for the site's old WordPress theme, but komikav.net has been rebuilt
 * as a server-rendered web app, so the theme selectors no longer match anything. The builder
 * prunes the upstream file because this overlay re-declares the KOMIKAV key.
 *
 * The rebuilt site server-renders everything this parser needs, so plain HTML parsing works:
 * catalogue cards are /manga/<slug>/ anchors wrapping a cover image, the catalogue endpoints
 * (/comic-list/, /popular/, /tax/genre/<slug>/, /type/<slug>/) return cumulative pages of 18
 * cards via ?page=, search lives at /search/<query>, detail pages carry the chapter list as
 * /manga/<slug>/chapter-<number>/ anchors with Indonesian relative dates, and reader pages mark
 * page images with the imgku class. Selectors verified against the live site on 2026-11-18.
 */
@MangaSourceParser("KOMIKAV", "KomikAv", "id")
internal class KomikAv(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.KOMIKAV, PAGE_SIZE) {

    override val configKeyDomain = ConfigKey.Domain("komikav.net")

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.ALPHABETICAL,
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = false,
            isMultipleTagsSupported = false,
        )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = fetchGenres(),
        availableContentTypes = EnumSet.of(
            ContentType.MANGA,
            ContentType.MANHWA,
            ContentType.MANHUA,
        ),
    )

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val query = filter.query?.trim()?.takeIf { it.isNotEmpty() }
        if (query != null) {
            if (page > 1) return emptyList()
            val doc = webClient.httpGet(
                urlBuilder().addPathSegment("search").addPathSegment(query).build(),
            ).parseHtml()
            return parseCards(doc)
        }
        val tag = filter.tags.singleOrNull()
        val type = filter.types.singleOrNull()
        return when {
            tag != null -> fetchCumulativePage("tax/genre/${tag.key}", page)
            type != null -> fetchCumulativePage("type/${type.toSiteSlug()}", page)
            order == SortOrder.POPULARITY -> fetchCumulativePage("popular", page)
            order == SortOrder.ALPHABETICAL -> fetchCumulativePage("comic-list", page)
            else -> {
                // Latest updates: the home page is a single server-rendered page.
                if (page > 1) emptyList()
                else parseCards(webClient.httpGet(urlBuilder().build()).parseHtml())
            }
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val path = normalizePath(manga.url)
        val doc = webClient.httpGet(path.toAbsoluteUrl(domain)).parseHtml()

        val title = doc.select("h1")
            .map { it.text().trim() }
            .firstOrNull { it.isNotEmpty() && it !in CHROME_HEADINGS }
            ?: manga.title
        val cover = doc.select("img")
            .firstOrNull { it.attr("alt").trim().equals(title, ignoreCase = true) }
            ?.let(::imageUrl)
            ?.takeIf { it.isNotBlank() }
            ?: manga.coverUrl
        val description = doc.select("p")
            .maxByOrNull { it.text().length }
            ?.text()
            ?.trim()
            ?.takeIf { it.length >= MIN_DESCRIPTION_LENGTH }
            ?: manga.description
        val tags = doc.select("a[href^='/tax/genre/']").mapNotNullTo(LinkedHashSet()) { element ->
            val key = element.attr("href").trim('/').substringAfterLast('/')
            val tagTitle = element.text().trim()
            if (key.isBlank() || tagTitle.isBlank()) null
            else MangaTag(title = tagTitle, key = key, source = source)
        }
        val authors = doc.select("a[href^='/tax/author/']")
            .mapNotNullTo(LinkedHashSet()) { it.text().trim().takeIf { text -> text.isNotEmpty() } }

        return manga.copy(
            title = title,
            publicUrl = "https://$domain$path",
            coverUrl = cover,
            largeCoverUrl = cover ?: manga.largeCoverUrl,
            description = description,
            tags = tags.ifEmpty { manga.tags },
            authors = authors.ifEmpty { manga.authors },
            chapters = parseChapters(doc, path),
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val path = normalizePath(chapter.url)
        val doc = webClient.httpGet(path.toAbsoluteUrl(domain)).parseHtml()
        val images = doc.select("img.imgku").ifEmpty { doc.select("div.bg-reader img") }
        val seen = HashSet<String>()
        return images.mapNotNull { image ->
            val url = imageUrl(image)
            if (url.isBlank() || !seen.add(url)) return@mapNotNull null
            MangaPage(
                id = generateUid(url),
                url = url,
                preview = null,
                source = source,
            )
        }
    }

    private suspend fun fetchGenres(): Set<MangaTag> {
        val doc = webClient.httpGet(
            urlBuilder().addPathSegment("tax").addPathSegment("genre").build(),
        ).parseHtml()
        return doc.select("a[href^='/tax/genre/']").mapNotNullTo(LinkedHashSet()) { element ->
            val key = element.attr("href").trim('/').substringAfterLast('/')
            val title = element.text().trim()
            if (key.isBlank() || title.isBlank() || key == "genre") null
            else MangaTag(title = title, key = key, source = source)
        }
    }

    /**
     * The catalogue endpoints ignore ?page= in the usual sense: page N returns the first
     * N * PAGE_SIZE cards server-side. Fetch it and keep only this page's slice.
     */
    private suspend fun fetchCumulativePage(path: String, page: Int): List<Manga> {
        val builder = urlBuilder()
        for (segment in path.split('/')) {
            builder.addPathSegment(segment)
        }
        if (page > 1) {
            builder.addQueryParameter("page", page.toString())
        }
        val all = parseCards(webClient.httpGet(builder.build()).parseHtml())
        val from = (page - 1) * PAGE_SIZE
        if (from >= all.size) return emptyList()
        return all.subList(from, minOf(from + PAGE_SIZE, all.size))
    }

    private fun parseCards(doc: org.jsoup.nodes.Document): List<Manga> {
        val seen = HashSet<String>()
        return doc.select("a[href^='/manga/']").mapNotNull { card ->
            val path = normalizePath(card.attr("href"))
            if (!MANGA_PATH_REGEX.matches(path) || !seen.add(path)) return@mapNotNull null
            val image = card.selectFirst("img") ?: return@mapNotNull null
            val title = image.attr("alt").trim()
                .ifBlank { card.text().trim() }
            if (title.isBlank()) return@mapNotNull null
            Manga(
                id = generateUid(path),
                title = title,
                altTitles = emptySet(),
                url = path,
                publicUrl = "https://$domain$path",
                rating = RATING_UNKNOWN,
                contentRating = null,
                coverUrl = imageUrl(image).takeIf { it.isNotBlank() },
                tags = emptySet(),
                state = null,
                authors = emptySet(),
                source = source,
            )
        }
    }

    private fun parseChapters(doc: org.jsoup.nodes.Document, mangaPath: String): List<MangaChapter> {
        // The detail page links the first/last chapter twice (shortcut buttons plus the list
        // itself); keep the list entry, whose text starts with "Chapter".
        val byPath = LinkedHashMap<String, Element>()
        for (element in doc.select("a[href*='/chapter-']")) {
            val path = normalizePath(element.attr("href"))
            if (!CHAPTER_PATH_REGEX.matches(path)) continue
            val existing = byPath[path]
            if (existing == null || element.text().trim().startsWith("Chapter")) {
                byPath[path] = element
            }
        }
        return byPath.map { (path, element) ->
            val numberText = path.substringAfterLast("chapter-")
            val number = numberText.toFloatOrNull() ?: 0f
            val text = element.text().trim()
            val title = CHAPTER_TITLE_REGEX.find(text)?.groupValues?.getOrNull(1)?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: "Chapter $numberText"
            MangaChapter(
                id = generateUid(path),
                title = title,
                number = number,
                volume = 0,
                url = path,
                scanlator = null,
                uploadDate = parseRelativeDate(text),
                branch = null,
                source = source,
            )
        }
    }

    private fun parseRelativeDate(text: String): Long {
        val normalized = text.lowercase()
        if ("baru saja" in normalized) return System.currentTimeMillis()
        if ("kemarin" in normalized) {
            return System.currentTimeMillis() - DAY_MILLIS
        }
        val match = RELATIVE_DATE_REGEX.find(normalized) ?: return 0L
        val amount = match.groupValues[1].toLongOrNull() ?: return 0L
        val unitMillis = when (match.groupValues[2]) {
            "detik" -> 1_000L
            "menit" -> 60_000L
            "jam" -> 3_600_000L
            "hari" -> DAY_MILLIS
            "minggu" -> 7 * DAY_MILLIS
            "bulan" -> 30 * DAY_MILLIS
            else -> 365 * DAY_MILLIS // thn / tahun
        }
        return System.currentTimeMillis() - amount * unitMillis
    }

    private fun imageUrl(element: Element): String {
        val srcsetUrl = element.attr("data-srcset")
            .split(',')
            .firstOrNull()
            ?.trim()
            ?.substringBefore(' ')
            ?.trim()
            .orEmpty()
        if (srcsetUrl.startsWith("http")) return srcsetUrl
        val dataSrc = element.attr("data-src").trim()
        if (dataSrc.startsWith("http")) return dataSrc
        val src = element.attr("src").trim()
        if (src.startsWith("http")) return src
        val absolute = element.absUrl("src")
        return absolute.takeIf { it.startsWith("http") }.orEmpty()
    }

    private fun normalizePath(value: String): String {
        val path = value.toHttpUrlOrNull()?.encodedPath ?: value.substringBefore('?').substringBefore('#')
        val normalized = if (path.startsWith('/')) path else "/$path"
        return normalized.trimEnd('/')
    }

    private fun ContentType.toSiteSlug(): String = when (this) {
        ContentType.MANHWA -> "manhwa"
        ContentType.MANHUA -> "manhua"
        else -> "manga"
    }

    private companion object {
        const val PAGE_SIZE = 18
        const val MIN_DESCRIPTION_LENGTH = 40
        const val DAY_MILLIS = 86_400_000L
        val CHROME_HEADINGS = setOf("KomikAV", "Chapter List", "Comments")
        val MANGA_PATH_REGEX = Regex("^/manga/[^/]+$")
        val CHAPTER_PATH_REGEX = Regex("^/manga/[^/]+/chapter-[\\d.]+$")
        val CHAPTER_TITLE_REGEX = Regex("^(Chapter\\s+[\\d.]+)")
        val RELATIVE_DATE_REGEX = Regex("(\\d+)\\s*(detik|menit|jam|hari|minggu|bulan|thn|tahun)\\s*lalu")
    }
}
