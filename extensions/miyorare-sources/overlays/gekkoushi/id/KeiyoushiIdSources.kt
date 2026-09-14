package tsuki.site.id

import org.jsoup.nodes.Element
import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser
import tsuki.model.*
import tsuki.site.madara.MadaraParser
import tsuki.site.mangareader.MangaReaderParser
import tsuki.site.zeistmanga.ZeistMangaParser
import tsuki.site.zmanga.ZMangaParser
import tsuki.util.*
import java.util.EnumSet

/**
 * Miyorare-owned Tsuki adapters for Indonesian providers tracked by Keiyoushi.
 *
 * Theme-backed sources use Gekkoushi's native Tsuki theme parsers. Sources that need custom
 * Keiyoushi implementations are initially exposed through the conservative HTML compatibility
 * parser below so the whole Indonesia intake can ship in one batch and be corrected provider by
 * provider after device testing. No Keiyoushi APK is embedded in the Miyorare source pack.
 *
 * Fidelity rule: chapter title/name and scanlator/group are semantic data. When Keiyoushi exposes
 * them separately, Miyorare keeps them separately too. The app then writes new CBZs as
 * `<scanlator>_<chapter>.cbz` without Mihon's trailing six-character URL hash, while still reading
 * existing Keiyoushi files with or without that hash.
 *
 * Provider metadata and implementation behavior were cross-checked against
 * keiyoushi/extensions-source (Apache-2.0); see ATTRIBUTION.md.
 */

@MangaSourceParser("ASTRAL_SCANS", "Astral Scans", "id", ContentType.HENTAI)
internal class AstralScans(context: MangaLoaderContext) :
    MangaReaderParser(context, MangaParserSource.ASTRAL_SCANS, "astralscans.top", 20, 10)

@MangaSourceParser("CROTPEDIA", "CrotPedia", "id", ContentType.HENTAI)
internal class CrotPedia(context: MangaLoaderContext) :
    ZMangaParser(context, MangaParserSource.CROTPEDIA, "crotpedia.net")

@MangaSourceParser("DAILYSUKA", "DailySuka", "id")
internal class DailySuka(context: MangaLoaderContext) :
    MangaReaderParser(context, MangaParserSource.DAILYSUKA, "dailysuka.com", 20, 10)

@MangaSourceParser("KAGUYA", "Kaguya", "id", ContentType.HENTAI)
internal class Kaguya(context: MangaLoaderContext) :
    MadaraParser(context, MangaParserSource.KAGUYA, "02.kaguya.pro") {
    override val withoutAjax = true
}

@MangaSourceParser("KUMAPOI", "KumaPoi", "id", ContentType.HENTAI)
internal class KumaPoi(context: MangaLoaderContext) :
    MangaReaderParser(context, MangaParserSource.KUMAPOI, "kumapoi.info", 20, 10)

@MangaSourceParser("KUMOPOI", "KumoPoi", "id", ContentType.HENTAI)
internal class KumoPoi(context: MangaLoaderContext) :
    MangaReaderParser(context, MangaParserSource.KUMOPOI, "kumopoi.org", 20, 10)

@MangaSourceParser("KURO_MANGA", "Kuro Manga", "id")
internal class KuroManga(context: MangaLoaderContext) :
    MangaReaderParser(context, MangaParserSource.KURO_MANGA, "kuromanga.id", 20, 10)

@MangaSourceParser("MANGA_CAN", "Manga Can", "id")
internal class MangaCan(context: MangaLoaderContext) :
    MangaReaderParser(context, MangaParserSource.MANGA_CAN, "mangacanblog.com", 20, 10)

@MangaSourceParser("MANGALAY", "Mangalay", "id")
internal class Mangalay(context: MangaLoaderContext) :
    ZeistMangaParser(context, MangaParserSource.MANGALAY, "mangalay.blogspot.com")

@MangaSourceParser("PORNHWA18", "Pornhwa18", "id", ContentType.HENTAI)
internal class Pornhwa18(context: MangaLoaderContext) :
    MadaraParser(context, MangaParserSource.PORNHWA18, "pornhwa18.com") {
    override val withoutAjax = true
}

@MangaSourceParser("DREAMTEAMS_SCANS", "DreamTeams Scans", "id", ContentType.HENTAI)
internal class DreamTeamsScans(context: MangaLoaderContext) :
    MiyorareKeiyoushiHtmlParser(context, MangaParserSource.DREAMTEAMS_SCANS, "dreamteams.space", "comic")

@MangaSourceParser("KOMIKNESIA", "KomikNesia", "id")
internal class KomikNesia(context: MangaLoaderContext) :
    MiyorareKeiyoushiHtmlParser(context, MangaParserSource.KOMIKNESIA, "v1.komiknesiaku.com", "komik")

@MangaSourceParser("KOMIK_NEXT_G_ONLINE", "Komik Next G Online", "id")
internal class KomikNextGOnline(context: MangaLoaderContext) :
    MiyorareKeiyoushiHtmlParser(context, MangaParserSource.KOMIK_NEXT_G_ONLINE, "komiknextgonline.com", "manga")

@MangaSourceParser("MANGAKURI", "Mangakuri", "id", ContentType.HENTAI)
internal class Mangakuri(context: MangaLoaderContext) :
    MiyorareKeiyoushiHtmlParser(context, MangaParserSource.MANGAKURI, "lc2.mangakuri.online", "manga")

@MangaSourceParser("NARASININJA", "NarasiNinja", "id")
internal class NarasiNinja(context: MangaLoaderContext) :
    MiyorareKeiyoushiHtmlParser(context, MangaParserSource.NARASININJA, "narasininja.net", "manga")

@MangaSourceParser("PRAMRAMADHAN", "Pramramadhan", "id")
internal class Pramramadhan(context: MangaLoaderContext) :
    MiyorareKeiyoushiHtmlParser(context, MangaParserSource.PRAMRAMADHAN, "01.pramramadhan.my.id", "manga")

@MangaSourceParser("RIZTRANSLATION", "Riztranslation", "id")
internal class Riztranslation(context: MangaLoaderContext) :
    MiyorareKeiyoushiHtmlParser(context, MangaParserSource.RIZTRANSLATION, "riztranslation.pages.dev", "")

@MangaSourceParser("ROSEVEIL", "Roseveil", "id")
internal class Roseveil(context: MangaLoaderContext) :
    MiyorareKeiyoushiHtmlParser(context, MangaParserSource.ROSEVEIL, "roseveil.org", "manga")

@MangaSourceParser("RYUKOMIK", "Ryukomik", "id", ContentType.HENTAI)
internal class Ryukomik(context: MangaLoaderContext) :
    MiyorareKeiyoushiHtmlParser(context, MangaParserSource.RYUKOMIK, "ryukomik.my.id", "komik")

@MangaSourceParser("SOFTKOMIK", "Softkomik", "id")
internal class Softkomik(context: MangaLoaderContext) :
    MiyorareKeiyoushiHtmlParser(context, MangaParserSource.SOFTKOMIK, "softkomik.co", "manga")

/**
 * Conservative compatibility parser for providers whose Keiyoushi implementation is custom rather
 * than one of the Tsuki theme families already present in Gekkoushi. It intentionally supports the
 * common WordPress/Madara/MangaReader DOM conventions first. Provider-specific fixes can override
 * this implementation without changing the stable runtime source key.
 */
internal abstract class MiyorareKeiyoushiHtmlParser(
    context: MangaLoaderContext,
    source: MangaParserSource,
    domain: String,
    private val listingPath: String,
) : PagedMangaParser(context, source, 20, 20) {

    override val configKeyDomain = ConfigKey.Domain(domain)

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
        )

    override suspend fun getFilterOptions() = MangaListFilterOptions()

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = if (!filter.query.isNullOrBlank()) {
            buildString {
                append("https://")
                append(domain)
                if (page > 1) {
                    append("/page/")
                    append(page)
                    append('/')
                } else {
                    append('/')
                }
                append("?s=")
                append(filter.query!!.urlEncoded())
                append("&post_type=wp-manga")
            }
        } else {
            buildString {
                append("https://")
                append(domain)
                append('/')
                if (listingPath.isNotBlank()) {
                    append(listingPath.trim('/'))
                    append('/')
                }
                append("?page=")
                append(page)
                when (order) {
                    SortOrder.POPULARITY -> append("&order=popular")
                    SortOrder.ALPHABETICAL -> append("&order=title")
                    else -> append("&order=update")
                }
            }
        }

        val doc = webClient.httpGet(url).parseHtml()
        val seen = HashSet<String>()
        return doc.select(LIST_SELECTOR).mapNotNull { card ->
            val anchor = card.select("a[href]").firstOrNull { candidate ->
                val href = candidate.attr("href")
                MANGA_PATH_HINTS.any { hint -> hint in href }
            } ?: card.selectFirst("a[href]") ?: return@mapNotNull null

            val href = anchor.attr("href").trim()
            if (href.isBlank()) return@mapNotNull null
            val relative = if (href.startsWith("http://") || href.startsWith("https://")) {
                href.toRelativeUrl(domain)
            } else {
                href
            }
            if (!seen.add(relative)) return@mapNotNull null

            val title = card.selectFirst(TITLE_SELECTOR)?.text()?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: anchor.attr("title").trim().takeIf { it.isNotBlank() }
                ?: anchor.text().trim().takeIf { it.isNotBlank() }
                ?: return@mapNotNull null

            val cover = card.selectFirst("img")?.let { img ->
                val raw = img.attr("data-src").ifBlank { img.attr("data-lazy-src") }.ifBlank { img.attr("src") }.trim()
                raw.takeIf { it.isNotBlank() }?.let { if (it.startsWith("http")) it else it.toAbsoluteUrl(domain) }
            }

            Manga(
                id = generateUid(relative),
                url = relative,
                publicUrl = if (href.startsWith("http")) href else href.toAbsoluteUrl(domain),
                title = title,
                altTitles = emptySet(),
                authors = emptySet(),
                description = null,
                tags = emptySet(),
                rating = RATING_UNKNOWN,
                state = null,
                coverUrl = cover,
                contentRating = if (isNsfwSource) ContentRating.ADULT else null,
                source = source,
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()

        val chapterElements = doc.select(CHAPTER_SELECTOR)
        val seenChapters = HashSet<String>()
        val chapters = chapterElements.mapIndexedNotNull { index, anchor ->
            val href = anchor.attr("href").trim()
            if (href.isBlank()) return@mapIndexedNotNull null
            val relative = if (href.startsWith("http://") || href.startsWith("https://")) href.toRelativeUrl(domain) else href
            if (!seenChapters.add(relative)) return@mapIndexedNotNull null

            // Keep the same semantic split Keiyoushi uses: chapter name stays the chapter name,
            // scanlator/group stays scanlator. Do not flatten the group into the title here.
            val chapterTitle = anchor.selectFirst(CHAPTER_TITLE_SELECTOR)?.text()?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: anchor.text().trim().takeIf { it.isNotBlank() }
                ?: "Chapter ${chapterElements.size - index}"
            val scanlator = findChapterScanlator(anchor, chapterTitle)
            val number = CHAPTER_NUMBER.find(chapterTitle)?.value?.toFloatOrNull()
                ?: (chapterElements.size - index).toFloat()
            MangaChapter(
                id = generateUid(relative),
                title = chapterTitle,
                number = number,
                volume = 0,
                url = relative,
                scanlator = scanlator,
                uploadDate = 0L,
                branch = null,
                source = source,
            )
        }

        val tags = doc.select(TAG_SELECTOR).mapNotNullTo(LinkedHashSet()) { element ->
            val title = element.text().trim().takeIf { it.isNotBlank() } ?: return@mapNotNullTo null
            val key = element.attr("href").substringBefore('?').trimEnd('/').substringAfterLast('/').ifBlank { title }
            MangaTag(title = title, key = key, source = source)
        }

        val authors = doc.select(AUTHOR_SELECTOR).mapNotNullTo(LinkedHashSet()) {
            it.text().trim().takeIf(String::isNotBlank)
        }

        val description = doc.selectFirst(DESCRIPTION_SELECTOR)?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: manga.description
        val cover = doc.selectFirst(COVER_SELECTOR)?.let { img ->
            val raw = img.attr("data-src").ifBlank { img.attr("data-lazy-src") }.ifBlank { img.attr("src") }.trim()
            raw.takeIf { it.isNotBlank() }?.let { if (it.startsWith("http")) it else it.toAbsoluteUrl(domain) }
        } ?: manga.coverUrl

        val pageText = doc.text().lowercase()
        val state = when {
            "completed" in pageText || "tamat" in pageText || "end" in pageText -> MangaState.FINISHED
            "hiatus" in pageText || "on hold" in pageText -> MangaState.PAUSED
            "dropped" in pageText || "cancelled" in pageText || "canceled" in pageText -> MangaState.ABANDONED
            "ongoing" in pageText || "berjalan" in pageText -> MangaState.ONGOING
            else -> manga.state
        }

        val title = doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: manga.title
        val adult = isNsfwSource || tags.any { tag -> ADULT_TAGS.any { it in tag.title.lowercase() } }

        return manga.copy(
            title = title,
            publicUrl = manga.url.toAbsoluteUrl(domain),
            coverUrl = cover,
            largeCoverUrl = cover ?: manga.largeCoverUrl,
            authors = authors.ifEmpty { manga.authors },
            tags = tags.ifEmpty { manga.tags },
            description = description,
            state = state,
            contentRating = if (adult) ContentRating.ADULT else manga.contentRating,
            chapters = chapters,
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
        val seen = HashSet<String>()
        return doc.select(PAGE_SELECTOR).mapNotNull { img ->
            val raw = img.attr("data-src").ifBlank { img.attr("data-lazy-src") }.ifBlank { img.attr("src") }.trim()
            if (raw.isBlank() || raw.startsWith("data:") || raw.contains("logo", ignoreCase = true) || raw.contains("avatar", ignoreCase = true)) {
                return@mapNotNull null
            }
            val url = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else raw.toAbsoluteUrl(domain)
            if (!seen.add(url)) return@mapNotNull null
            MangaPage(
                id = generateUid(url),
                url = url,
                preview = null,
                source = source,
            )
        }
    }

    private fun findChapterScanlator(anchor: Element, chapterTitle: String): String? {
        var context: Element? = anchor
        repeat(CHAPTER_CONTEXT_DEPTH) {
            context = context?.parent() ?: return@repeat
            val current = context ?: return@repeat

            current.attr("data-scanlator").trim().takeIf { it.isNotBlank() }?.let { return it }
            current.attr("data-group").trim().takeIf { it.isNotBlank() }?.let { return it }

            current.selectFirst("[data-scanlator], [data-group]")?.let { tagged ->
                tagged.attr("data-scanlator").trim().takeIf { it.isNotBlank() }?.let { return it }
                tagged.attr("data-group").trim().takeIf { it.isNotBlank() }?.let { return it }
            }

            current.selectFirst(SCANLATOR_SELECTOR)?.text()?.trim()
                ?.takeIf { it.isNotBlank() && !it.equals(chapterTitle, ignoreCase = true) }
                ?.let { return it }
        }
        return null
    }

    private companion object {
        const val LIST_SELECTOR = ".page-item-detail.manga, .c-tabs-item__content, .listupd .bs, .listupd .bsx, .bsx, .manga-item, .comic-item, article"
        const val TITLE_SELECTOR = "h3, h2, .post-title, .tt, .manga-name, .item-title, .post-title h3"
        const val CHAPTER_SELECTOR = ".wp-manga-chapter a[href], #chapterlist a[href], .chapter-list a[href], .eph-num a[href], a[href*='/chapter-'], a[href*='/chapter/'], a[href*='/read/']"
        const val CHAPTER_TITLE_SELECTOR = ".chapternum, .ch-title, .epl-num, .chapter-title, .chapter-name"
        const val SCANLATOR_SELECTOR = ".scanlator, .scanlators, .chapter-scanlator, .chapter-release-group, .release-group, .translation-group, .translator-group"
        const val TAG_SELECTOR = ".genres-content a, .mgen a, .seriestugenre a, .series-genres a, a[href*='/genre/'], a[href*='genre=']"
        const val AUTHOR_SELECTOR = ".author-content a, .artist-content a, .tsinfo div:contains(Author) a, .infotable td:contains(Author) + td a"
        const val DESCRIPTION_SELECTOR = ".summary__content, .description-summary, .entry-content, .series-synops, .synopsis, #synopsis"
        const val COVER_SELECTOR = ".summary_image img, .thumb img, .series-thumb img, .seriestucontent img, article img"
        const val PAGE_SELECTOR = ".reading-content img, #readerarea img, .reader-area img, .chapter-content img, .entry-content img, article#reader img, main img"
        const val CHAPTER_CONTEXT_DEPTH = 4
        val CHAPTER_NUMBER = Regex("\\d+(?:\\.\\d+)?")
        val MANGA_PATH_HINTS = listOf("/manga/", "/komik/", "/comic/", "/series/", "/title/", "/book/")
        val ADULT_TAGS = setOf("adult", "hentai", "smut", "mature", "18+", "pornhwa")
    }
}
