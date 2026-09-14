package tsuki.site.id

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Element
import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser
import tsuki.model.*
import tsuki.util.*
import java.util.Base64
import java.util.Calendar
import java.util.EnumSet

/**
 * Miyorare-owned HoloToon parser for holodek.run.
 *
 * The site also serves novels and embeds scraper honeypots. Keep catalogue parsing scoped to the
 * real /comic/ grid and always request media=comic so novel entries never leak into Manga state.
 * Site behavior/selectors were independently verified against the public site and cross-checked
 * with Keiyoushi's Apache-2.0 Holotoon extension; see extensions/miyorare-sources/ATTRIBUTION.md.
 */
@MangaSourceParser("HOLOTOON", "HoloToon", "id")
internal class Holotoon(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.HOLOTOON, PAGE_SIZE) {

    override val configKeyDomain = ConfigKey.Domain("holodek.run")

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.RATING,
        SortOrder.ALPHABETICAL,
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
        )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = fetchGenres(),
        availableStates = EnumSet.of(
            MangaState.ONGOING,
            MangaState.FINISHED,
            MangaState.PAUSED,
        ),
        availableContentTypes = EnumSet.of(
            ContentType.MANGA,
            ContentType.MANHWA,
            ContentType.MANHUA,
            ContentType.COMICS,
        ),
    )

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val builder = urlBuilder()
            .addPathSegment("browse")
            .addQueryParameter("sort", order.toSiteSort())
            .addQueryParameter("media", "comic")

        filter.query?.trim()?.takeIf { it.isNotEmpty() }?.let {
            builder.addQueryParameter("q", it)
        }
        if (filter.states.size == 1) {
            filter.states.firstOrNull()?.toSiteStatus()?.let { builder.addQueryParameter("status", it) }
        }
        if (filter.types.size == 1) {
            filter.types.firstOrNull()?.toSiteType()?.let { builder.addQueryParameter("type", it) }
        }
        filter.tags.firstOrNull()?.key?.takeIf { it.isNotBlank() }?.let {
            builder.addQueryParameter("genre", it)
        }
        if (page > 1) {
            builder.addQueryParameter("page", page.toString())
        }

        val doc = webClient.httpGet(builder.build()).parseHtml()
        val grid = doc.select("div.grid:has(a[href^='/comic/'])").lastOrNull() ?: return emptyList()
        val seen = HashSet<String>()
        return grid.select("a.group[href^='/comic/']").mapNotNull { card ->
            val path = normalizeMangaPath(card.attr("href"))
            if (!path.startsWith("/comic/") || !seen.add(path)) return@mapNotNull null
            val title = card.selectFirst("h3")?.text()?.trim().orEmpty()
            if (title.isBlank()) return@mapNotNull null
            val cover = card.selectFirst("img")?.let(::imageUrl)?.takeIf { it.isNotBlank() }
            Manga(
                id = generateUid(path),
                title = title,
                altTitles = emptySet(),
                url = path,
                publicUrl = "https://$domain$path",
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
        val path = normalizeMangaPath(manga.url)
        val doc = webClient.httpGet(path.toAbsoluteUrl(domain)).parseHtml()
        val titleElement = doc.selectFirst("h1")
        val title = titleElement?.text()?.trim().orEmpty().ifBlank { manga.title }
        val detailsRoot = findDetailsRoot(titleElement)
        val metadata = detailsRoot?.text().orEmpty()

        val descriptionNode = doc.selectFirst(
            "#synopsis-wrapper div[data-sr], div[data-sr][class*=synopsis], div.prose, div[class*=description]",
        )
        val description = descriptionNode?.attr("data-sr")
            ?.takeIf { it.isNotBlank() }
            ?.let(::decodeBase64)
            ?: descriptionNode?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: manga.description

        val tags = detailsRoot
            ?.select("a[href*='genre=']")
            .orEmpty()
            .mapNotNullTo(LinkedHashSet()) { element ->
                val tagTitle = element.text().trim().takeIf { it.isNotEmpty() } ?: return@mapNotNullTo null
                val href = element.attr("href")
                val tagKey = href.substringAfter("genre=", tagTitle).substringBefore('&').ifBlank { tagTitle }
                MangaTag(title = tagTitle, key = tagKey, source = source)
            }

        val author = AUTHOR_REGEX.find(metadata)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
        val artist = ARTIST_REGEX.find(metadata)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
        val authors = setOfNotNull(author, artist)

        val cover = doc.select("img")
            .firstOrNull { it.attr("alt").trim().equals(title, ignoreCase = true) }
            ?.let(::imageUrl)
            ?.takeIf { it.isNotBlank() }
            ?: manga.coverUrl

        return manga.copy(
            title = title,
            publicUrl = "https://$domain$path",
            coverUrl = cover,
            largeCoverUrl = cover ?: manga.largeCoverUrl,
            authors = authors.ifEmpty { manga.authors },
            tags = tags.ifEmpty { manga.tags },
            description = description,
            state = parseState(detailsRoot ?: doc.body()),
            contentRating = if (tags.any { isAdultTag(it.title) }) ContentRating.ADULT else manga.contentRating,
            chapters = parseChapters(doc.select("a[href^='/read/'][data-chapter]")),
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val path = normalizeChapterPath(chapter.url)
        val doc = webClient.httpGet(path.toAbsoluteUrl(domain)).parseHtml()
        val primary = doc.select("#reader-pages img")
        val images = if (primary.isNotEmpty()) {
            primary
        } else {
            doc.select("main img[src*='/image/comic/'], main img[data-src*='/image/comic/']")
        }
        val seen = HashSet<String>()
        return images.mapNotNull { image ->
            val url = imageUrl(image)
            if (
                url.isBlank() ||
                url.contains("/chapter-header/") ||
                url.contains("/chapter-footer/") ||
                !seen.add(url)
            ) {
                return@mapNotNull null
            }
            MangaPage(
                id = generateUid(url),
                url = url,
                preview = null,
                source = source,
            )
        }
    }

    private suspend fun fetchGenres(): Set<MangaTag> {
        val doc = webClient.httpGet(urlBuilder().addPathSegment("browse").build()).parseHtml()
        return doc.select("select[name=genre] option").mapNotNullTo(LinkedHashSet()) { option ->
            val key = option.attr("value").trim()
            val title = option.text().trim()
            if (key.isBlank() || title.isBlank()) return@mapNotNullTo null
            MangaTag(title = title, key = key, source = source)
        }
    }

    private fun parseChapters(elements: Iterable<Element>): List<MangaChapter> {
        val result = ArrayList<MangaChapter>()
        val seen = HashSet<String>()
        for (element in elements) {
            val path = normalizeChapterPath(element.attr("href"))
            if (!path.startsWith("/read/") || !seen.add(path)) continue
            val rawChapter = element.attr("data-chapter")
            val number = CHAPTER_NUMBER_REGEX.find(rawChapter)?.value?.toFloatOrNull() ?: 0f
            val label = element.selectFirst("span.font-semibold")?.text()?.trim()
            val subtitle = element.selectFirst("span.truncate")?.text()?.trim()
                ?.removePrefix("—")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            val title = when {
                !label.isNullOrBlank() && subtitle != null -> "$label - $subtitle"
                !label.isNullOrBlank() -> label
                subtitle != null -> subtitle
                number > 0f -> "Chapter ${number.toString().removeSuffix(".0")}"
                else -> rawChapter.ifBlank { "Chapter" }
            }
            result += MangaChapter(
                id = generateUid(path),
                title = title,
                number = number,
                volume = 0,
                url = path,
                scanlator = null,
                uploadDate = parseRelativeDate(
                    element.selectFirst("span.text-right, span[class*=tabular-nums]:last-child")?.text(),
                ),
                branch = null,
                source = source,
            )
        }
        return result
    }

    private fun findDetailsRoot(titleElement: Element?): Element? {
        var current = titleElement?.parent()
        repeat(6) {
            val candidate = current ?: return null
            if (candidate.select("a[href*='genre=']").isNotEmpty()) return candidate
            current = candidate.parent()
        }
        return titleElement?.parent()
    }

    private fun parseState(root: Element): MangaState? {
        val value = root.select("span")
            .asSequence()
            .map { it.text().trim().lowercase() }
            .firstOrNull { it in STATUS_VALUES }
        return when (value) {
            "ongoing" -> MangaState.ONGOING
            "completed" -> MangaState.FINISHED
            "hiatus" -> MangaState.PAUSED
            "dropped" -> MangaState.ABANDONED
            else -> null
        }
    }

    private fun parseRelativeDate(text: String?): Long {
        if (text.isNullOrBlank()) return 0L
        val normalized = text.trim().lowercase()
        if (normalized == "baru saja" || normalized == "just now") return System.currentTimeMillis()
        val amount = NUMBER_REGEX.find(normalized)?.value?.toIntOrNull() ?: return 0L
        val calendar = Calendar.getInstance()
        when {
            "detik" in normalized || "second" in normalized -> calendar.add(Calendar.SECOND, -amount)
            "menit" in normalized || "minute" in normalized -> calendar.add(Calendar.MINUTE, -amount)
            "jam" in normalized || "hour" in normalized -> calendar.add(Calendar.HOUR, -amount)
            "hari" in normalized || "day" in normalized -> calendar.add(Calendar.DAY_OF_MONTH, -amount)
            "minggu" in normalized || "week" in normalized -> calendar.add(Calendar.WEEK_OF_YEAR, -amount)
            "bulan" in normalized || "month" in normalized -> calendar.add(Calendar.MONTH, -amount)
            "tahun" in normalized || "year" in normalized -> calendar.add(Calendar.YEAR, -amount)
            else -> return 0L
        }
        return calendar.timeInMillis
    }

    private fun imageUrl(element: Element): String {
        val absolute = element.absUrl("src").ifBlank { element.absUrl("data-src") }
        if (absolute.isNotBlank()) return absolute
        val raw = element.attr("src").ifBlank { element.attr("data-src") }.trim()
        if (raw.isBlank()) return ""
        return if (raw.toHttpUrlOrNull() != null) raw else raw.toAbsoluteUrl(domain)
    }

    private fun normalizeMangaPath(value: String): String {
        val path = value.toHttpUrlOrNull()?.encodedPath ?: value.substringBefore('?').substringBefore('#')
        val normalized = if (path.startsWith('/')) path else "/$path"
        return when {
            normalized.startsWith("/komik/") -> normalized.replaceFirst("/komik/", "/comic/")
            else -> normalized
        }.trimEnd('/')
    }

    private fun normalizeChapterPath(value: String): String {
        val path = value.toHttpUrlOrNull()?.encodedPath ?: value.substringBefore('?').substringBefore('#')
        val normalized = if (path.startsWith('/')) path else "/$path"
        if (!normalized.startsWith("/komik/")) return normalized.trimEnd('/')
        val parts = normalized.trim('/').split('/')
        return if (parts.size >= 3) {
            "/read/${parts[1]}/${parts.drop(2).joinToString("/")}".trimEnd('/')
        } else {
            normalized.trimEnd('/')
        }
    }

    private fun decodeBase64(value: String): String? = runCatching {
        String(Base64.getDecoder().decode(value), Charsets.UTF_8).trim().takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun SortOrder.toSiteSort(): String = when (this) {
        SortOrder.POPULARITY -> "popular"
        SortOrder.RATING -> "rating"
        SortOrder.ALPHABETICAL -> "az"
        else -> "latest"
    }

    private fun MangaState.toSiteStatus(): String? = when (this) {
        MangaState.ONGOING -> "ongoing"
        MangaState.FINISHED -> "completed"
        MangaState.PAUSED -> "hiatus"
        else -> null
    }

    private fun ContentType.toSiteType(): String? = when (this) {
        ContentType.MANGA -> "manga"
        ContentType.MANHWA -> "manhwa"
        ContentType.MANHUA -> "manhua"
        ContentType.COMICS -> "comic"
        else -> null
    }

    private fun isAdultTag(title: String): Boolean = title.lowercase() in ADULT_TAGS

    private companion object {
        const val PAGE_SIZE = 24
        val AUTHOR_REGEX = Regex("Author:\\s*(.*?)\\s+Artist:", RegexOption.IGNORE_CASE)
        val ARTIST_REGEX = Regex("Artist:\\s*(.*?)\\s+(?:Year:|Views:|Uploaded by:)", RegexOption.IGNORE_CASE)
        val CHAPTER_NUMBER_REGEX = Regex("\\d+(?:\\.\\d+)?")
        val NUMBER_REGEX = Regex("\\d+")
        val STATUS_VALUES = setOf("ongoing", "completed", "hiatus", "dropped")
        val ADULT_TAGS = setOf("adult", "hentai", "smut", "mature", "erotica", "18+")
    }
}
