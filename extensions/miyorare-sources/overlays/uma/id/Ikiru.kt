package tsuki.site.id

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser
import tsuki.model.Manga
import tsuki.model.MangaChapter
import tsuki.model.MangaListFilter
import tsuki.model.MangaListFilterCapabilities
import tsuki.model.MangaListFilterOptions
import tsuki.model.MangaPage
import tsuki.model.MangaParserSource
import tsuki.model.MangaTag
import tsuki.model.RATING_UNKNOWN
import tsuki.model.SortOrder
import tsuki.util.generateUid
import tsuki.util.json.getStringOrNull
import tsuki.util.json.mapJSON
import tsuki.util.parseHtml
import tsuki.util.parseJson
import tsuki.util.toAbsoluteUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import java.time.Instant
import java.util.EnumSet

/**
 * First-party replacement for the UMA Ikiru parser.
 *
 * The site was rebuilt as a Nuxt application and the WordPress REST API the old
 * NatsuParser relied on no longer exists. This parser uses the site's public
 * JSON API where one exists (latest updates, quick search, chapter list) and
 * scrapes the server-rendered HTML for details, genres and chapter pages.
 * The mirror subdomain rotates (07 -> 11 at the time of writing), so the domain
 * stays user-configurable through [configKeyDomain].
 */
@MangaSourceParser("IKIRU", "Ikiru", "id")
internal class Ikiru(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.IKIRU, PAGE_SIZE) {

	override val configKeyDomain = ConfigKey.Domain("11.ikiru.wtf")

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.UPDATED)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isMultipleTagsSupported = false,
			isSearchSupported = true,
			isSearchWithFiltersSupported = false,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchGenres(),
	)

	override suspend fun getListPage(
		page: Int,
		order: SortOrder,
		filter: MangaListFilter,
	): List<Manga> {
		val query = filter.query?.takeIf { it.isNotBlank() }
		if (query != null) {
			if (page > 1) return emptyList()
			return search(query)
		}
		val tag = filter.tags.singleOrNull()
		if (tag != null) {
			if (page > 1) return emptyList()
			val doc = webClient.httpGet("https://$domain/genre/${tag.key}").parseHtml()
			return parseCards(doc)
		}
		val url = "https://$domain/api/public/manga/updated".toHttpUrl().newBuilder()
			.addQueryParameter("page", page.toString())
			.build()
		val data = webClient.httpGet(url).parseJson().optJSONArray("data") ?: return emptyList()
		return data.mapJSON { jo ->
			val slug = jo.getString("slug")
			Manga(
				id = generateUid(slug),
				title = jo.getString("title"),
				altTitles = emptySet(),
				url = "/manga/$slug/",
				publicUrl = "https://$domain/manga/$slug",
				rating = jo.optJSONObject("metadata")
					?.optDouble("score", 0.0)
					?.takeIf { it > 0 }
					?.let { (it / 10.0).toFloat() }
					?: RATING_UNKNOWN,
				contentRating = null,
				coverUrl = jo.getStringOrNull("featuredImage"),
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				source = source,
			)
		}
	}

	private suspend fun search(query: String): List<Manga> {
		val url = "https://$domain/api/public/quick-search".toHttpUrl().newBuilder()
			.addQueryParameter("query", query)
			.build()
		val data = webClient.httpGet(url).parseJson().optJSONArray("data") ?: return emptyList()
		return data.mapJSON { jo ->
			val slug = jo.getString("slug")
			Manga(
				id = generateUid(slug),
				title = jo.getString("title"),
				altTitles = emptySet(),
				url = "/manga/$slug/",
				publicUrl = "https://$domain/manga/$slug",
				rating = jo.optJSONObject("metadata")
					?.optDouble("score", 0.0)
					?.takeIf { it > 0 }
					?.let { (it / 10.0).toFloat() }
					?: RATING_UNKNOWN,
				contentRating = null,
				coverUrl = jo.getStringOrNull("featuredImage"),
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				source = source,
			)
		}
	}

	private fun parseCards(doc: Document): List<Manga> {
		return doc.select("a.card-link").mapNotNull { card ->
			val href = card.attr("href")
			val slug = href.removePrefix("/manga/").trim('/')
			if (slug.isEmpty() || slug.contains('/')) return@mapNotNull null
			val img = card.selectFirst("img.cover-img")
			val title = card.selectFirst(".ctitle")?.text()?.trim()
				?: img?.attr("alt")?.trim()
				?: card.attr("aria-label").trim()
			if (title.isEmpty()) return@mapNotNull null
			Manga(
				id = generateUid(slug),
				title = title,
				altTitles = emptySet(),
				url = "/manga/$slug/",
				publicUrl = "https://$domain/manga/$slug",
				rating = RATING_UNKNOWN,
				contentRating = null,
				coverUrl = img?.attr("src")?.takeIf { it.isNotBlank() },
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				source = source,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val slug = manga.url.removePrefix("/manga/").trim('/')
		val doc = webClient.httpGet("https://$domain/manga/$slug").parseHtml()
		val title = doc.selectFirst("h1")?.text()?.trim().orEmpty()
		val cover = doc.selectFirst("img[src*='cdn.ikiru.id/featured/']")?.attr("src")
		val description = doc.selectFirst("div.synopsis")
			?.select("p")
			?.map { it.text().trim() }
			?.filter { it.isNotEmpty() }
			?.joinToString("\n\n")
			?.takeIf { it.isNotEmpty() }
		val tags = doc.select("div.genre-pills a[href^='/genre/'], a.gp-link[href^='/genre/']")
			.mapNotNull { a ->
				val key = a.attr("href").removePrefix("/genre/").trim('/')
				val name = a.text().trim()
				if (key.isEmpty() || name.isEmpty()) null else MangaTag(name, key, source)
			}.toSet()
		return manga.copy(
			title = title.ifEmpty { manga.title },
			coverUrl = cover ?: manga.coverUrl,
			description = description,
			tags = tags,
			chapters = fetchChapters(slug),
		)
	}

	private suspend fun fetchChapters(slug: String): List<MangaChapter> {
		val url = "https://$domain/api/public/manga/$slug/chapter".toHttpUrl().newBuilder()
			.addQueryParameter("all", "true")
			.build()
		val data = webClient.httpGet(url).parseJson().optJSONObject("data") ?: return emptyList()
		val array = data.optJSONArray("chapters") ?: return emptyList()
		return array.mapJSON { jo ->
			val number = jo.optDouble("number", 0.0)
			val numberText = if (number % 1.0 == 0.0) number.toInt().toString() else number.toString()
			MangaChapter(
				id = generateUid("$slug/chapter-$numberText"),
				title = "Chapter $numberText",
				number = number.toFloat(),
				volume = 0,
				url = "/manga/$slug/chapter-$numberText",
				uploadDate = jo.getStringOrNull("createdAt")
					?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
					?: 0L,
				source = source,
				scanlator = null,
				branch = null,
			)
		}.reversed()
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
		val seen = LinkedHashSet<String>()
		for (img in doc.select("img")) {
			val src = img.attr("src").ifBlank { img.attr("data-src") }
			if (src.contains("cdn.ikiru.id/t/")) {
				seen.add(src.replace("&amp;", "&"))
			}
		}
		return seen.map { url ->
			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source,
			)
		}
	}

	private suspend fun fetchGenres(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain/").parseHtml()
		return doc.select("a[href^='/genre/']").mapNotNull { a ->
			val key = a.attr("href").removePrefix("/genre/").trim('/')
			if (key.isEmpty() || key.contains('/')) {
				null
			} else {
				val name = a.text().trim().ifEmpty {
					key.replace('-', ' ').replaceFirstChar { it.uppercaseChar() }
				}
				MangaTag(name, key, source)
			}
		}.toSet()
	}

	private companion object {
		const val PAGE_SIZE = 12
	}
}
