package tsuki.site.id

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser
import tsuki.model.ContentRating
import tsuki.model.Manga
import tsuki.model.MangaChapter
import tsuki.model.MangaListFilter
import tsuki.model.MangaListFilterCapabilities
import tsuki.model.MangaListFilterOptions
import tsuki.model.MangaPage
import tsuki.model.MangaParserSource
import tsuki.model.MangaState
import tsuki.model.MangaTag
import tsuki.model.RATING_UNKNOWN
import tsuki.model.SortOrder
import tsuki.util.generateUid
import tsuki.util.mapNotNullToSet
import tsuki.util.parseHtml
import tsuki.util.toAbsoluteUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.EnumSet

/**
 * First-party replacement for the UMA Siikomik parser.
 *
 * siikomik.com is no longer a Madara/WordPress site: it was rebuilt as a
 * custom Next.js application. The stock MadaraParser selectors match
 * nothing in the new markup and its admin-ajax endpoint is gone, so the
 * old parser cannot load anything. This parser reads the server-rendered
 * pages of the new site directly:
 *
 * - list:    GET /komik/ (s4-card markup; ?page=, ?sort=popular|rating,
 *            ?genre=<slug>)
 * - search:  GET /cari/?q=<query> (same card markup)
 * - details: h1 title, /api/media/ cover, metadata definition list
 *            (Rating / Status / Alternative / Type), genre chips and the
 *            inline chapter list (no dates are published)
 * - pages:   the reader page embeds signed /api/media/ image URLs in its
 *            payload; they are extracted from the raw body
 */
@MangaSourceParser("SIIKOMIK", "Siikomik", "id")
internal class Siikomik(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.SIIKOMIK, PAGE_SIZE) {

	override val configKeyDomain = ConfigKey.Domain("siikomik.com")

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.RATING,
	)

	override suspend fun getListPage(
		page: Int,
		order: SortOrder,
		filter: MangaListFilter,
	): List<Manga> {
		val query = filter.query?.trim().orEmpty()
		val doc = if (query.isNotEmpty()) {
			if (page > 1) return emptyList()
			val url = "https://$domain/cari/".toHttpUrl().newBuilder()
				.addQueryParameter("q", query)
				.build()
			webClient.httpGet(url).parseHtml()
		} else {
			val url = "https://$domain/komik/".toHttpUrl().newBuilder().apply {
				if (page > 1) addQueryParameter("page", page.toString())
				when (order) {
					SortOrder.POPULARITY -> addQueryParameter("sort", "popular")
					SortOrder.RATING -> addQueryParameter("sort", "rating")
					else -> Unit
				}
				filter.tags.firstOrNull()?.let { addQueryParameter("genre", it.key) }
			}.build()
			webClient.httpGet(url).parseHtml()
		}
		return doc.select("a.s4-card").mapNotNull { a ->
			val href = a.attr("href").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
			val title = a.attr("title").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
			Manga(
				id = generateUid(href),
				title = title,
				altTitles = emptySet(),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				rating = RATING_UNKNOWN,
				contentRating = null,
				coverUrl = a.selectFirst("img")?.attr("src")?.takeIf { it.isNotEmpty() },
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				source = source,
			)
		}
	}

	override suspend fun getFilterOptions(): MangaListFilterOptions {
		return MangaListFilterOptions(availableTags = fetchTags())
	}

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isMultipleTagsSupported = false,
			isSearchSupported = true,
			isSearchWithFiltersSupported = false,
		)

	private suspend fun fetchTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain/komik/").parseHtml()
		return doc.select("a.manga-filter-box[href*=?genre=]").mapNotNullToSet { a ->
			val slug = a.attr("href").substringAfter("?genre=", "").substringBefore("&")
				.takeIf { it.isNotEmpty() } ?: return@mapNotNullToSet null
			val title = a.selectFirst("span")?.text()?.takeIf { it.isNotEmpty() }
				?: return@mapNotNullToSet null
			MangaTag(title = title, key = slug, source = source)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()

		val meta = HashMap<String, String>()
		for (dt in doc.select("dt")) {
			val dd = dt.nextElementSibling() ?: continue
			meta[dt.text().trim().lowercase()] = dd.text().trim()
		}

		val tags = LinkedHashSet<MangaTag>()
		for (a in doc.select("a[href*=?genre=]")) {
			val slug = a.attr("href").substringAfter("?genre=", "").substringBefore("&")
				.takeIf { it.isNotEmpty() } ?: continue
			val name = a.text().trim().takeIf { it.isNotEmpty() } ?: continue
			tags += MangaTag(title = name, key = slug, source = source)
		}
		meta["type"]?.takeIf { it.isNotEmpty() }?.let { type ->
			tags += MangaTag(title = type, key = type.lowercase(), source = source)
		}

		val description = doc.select("p")
			.map { it.text().trim() }
			.filter { it.length >= 60 }
			.take(3)
			.joinToString("\n\n")
			.takeIf { it.isNotEmpty() }

		val chapterByHref = LinkedHashMap<String, MangaChapter>()
		for (a in doc.select("a[href*=/chapter-]")) {
			val href = a.attr("href")
			val number = CHAPTER_NUMBER_REGEX.find(href)?.groupValues?.get(1)?.toFloatOrNull()
				?: continue
			val text = a.text().trim()
			val existing = chapterByHref[href]
			if (existing != null && !text.startsWith("Chapter")) continue
			chapterByHref[href] = MangaChapter(
				id = generateUid(href),
				title = if (text.startsWith("Chapter")) text else "Chapter ${formatNumber(number)}",
				number = number,
				volume = 0,
				url = href,
				uploadDate = 0L,
				source = source,
				scanlator = null,
				branch = null,
			)
		}

		return manga.copy(
			title = doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotEmpty() } ?: manga.title,
			altTitles = meta["alternative"]
				?.split('/', ',')
				?.map { it.trim() }
				?.filter { it.isNotEmpty() }
				?.toSet()
				?: manga.altTitles,
			url = manga.url,
			publicUrl = manga.url.toAbsoluteUrl(domain),
			rating = meta["rating"]
				?.let { FLOAT_REGEX.find(it)?.value?.toFloatOrNull() }
				?.div(5f)
				?: manga.rating,
			contentRating = if (tags.any { it.key in ADULT_TAG_KEYS }) {
				ContentRating.ADULT
			} else {
				manga.contentRating
			},
			coverUrl = doc.selectFirst("img[src*=/api/media/]")?.attr("src")
				?.takeIf { it.isNotEmpty() } ?: manga.coverUrl,
			tags = tags,
			state = when {
				meta["status"]?.contains("ongoing", ignoreCase = true) == true -> MangaState.ONGOING
				meta["status"]?.contains("completed", ignoreCase = true) == true -> MangaState.FINISHED
				meta["status"]?.contains("hiatus", ignoreCase = true) == true -> MangaState.PAUSED
				else -> manga.state
			},
			authors = manga.authors,
			description = description ?: manga.description,
			chapters = chapterByHref.values.sortedBy { it.number },
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val body = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).body.string()
		val byPath = LinkedHashMap<String, String>()
		for (match in PAGE_URL_REGEX.findAll(body)) {
			val url = match.value.replace("&amp;", "&")
			byPath.putIfAbsent(url.substringBefore('?'), url)
		}
		return byPath.entries
			.sortedBy { entry ->
				PAGE_NUMBER_REGEX.find(entry.key)?.groupValues?.get(1)?.toIntOrNull()
					?: Int.MAX_VALUE
			}
			.map { (_, url) ->
				MangaPage(
					id = generateUid(url.substringBefore('?')),
					url = url,
					preview = null,
					source = source,
				)
			}
	}

	private fun formatNumber(number: Float): String {
		return if (number % 1f == 0f) number.toInt().toString() else number.toString()
	}

	private companion object {
		const val PAGE_SIZE = 24
		val ADULT_TAG_KEYS = setOf("adult", "smut", "mature")
		val FLOAT_REGEX = Regex("""\d+(?:\.\d+)?""")
		val CHAPTER_NUMBER_REGEX = Regex("""/chapter-([0-9]+(?:\.[0-9]+)?)/""")
		val PAGE_URL_REGEX = Regex(
			"""https://siikomik\.com/api/media/legacy/manga_[^\s"'<>\\]+?\.webp(?:\?[^\s"'<>\\]*)?""",
		)
		val PAGE_NUMBER_REGEX = Regex("""/(\d+)\.webp$""")
	}
}
