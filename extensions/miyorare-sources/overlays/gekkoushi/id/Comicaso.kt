package tsuki.site.mangareader.id

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import tsuki.Broken
import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser
import tsuki.exception.ParseException
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
import tsuki.util.parseJson
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale
import java.util.TimeZone

/**
 * Miyorare-owned Comicaso parser for the rebuilt Comicaso platform.
 *
 * Replaces the upstream Gekkoushi parser, which subclasses the shared MangaReaderParser for
 * the old comicaso.xyz WordPress site. That domain is parked; the platform now lives as a web
 * app backed by a JSON API (endpoints /home.php, /manga.php, /chapter.php, /genres.php — note
 * the API host serves them without an /api prefix). The default domain is the Indonesian
 * mirror v3.mangacaso.web.id because the canonical v3.comicaso.pro host is unreachable from
 * Indonesian networks (connection reset); the API host is derived from the configured domain
 * the same way the platform's own web app derives it (api. + the domain's base). The catalogue
 * aggregates the platform's member sources (currently comicazen and medusa), so every title is
 * identified by its member source plus slug.
 *
 * The list and search endpoints are open. The detail and chapter endpoints are gated per
 * member source: comicazen requires the platform's human verification (HTTP 428,
 * {"need_challenge": true}) and medusa requires a Comicaso account login (HTTP 403,
 * {"locked": true}). Once the user has signed in / passed the verification in the app's
 * browser, the shared cookie jar lets these calls through; until then this parser surfaces a
 * clear instruction error instead of failing obscurely. API shapes verified against the live
 * mirror API on 2026-11-18.
 */
@Broken("Details and chapters are gated by the platform (Medusa needs an account login, ComicAzen a human verification); only the catalogue loads without signing in")
@MangaSourceParser("COMICASO", "Comicaso", "id")
internal class Comicaso(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.COMICASO, pageSize = 24) {

	override val configKeyDomain = ConfigKey.Domain("v3.mangacaso.web.id")
	private val apiUrl: String
		get() = "https://api." + domain.substringAfter('.')

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.UPDATED)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
			isMultipleTagsSupported = false,
			isTagsExclusionSupported = false,
		)

	override fun getRequestHeaders() = super.getRequestHeaders().newBuilder()
		.set("Referer", "https://$domain/")
		.build()

	override suspend fun getFilterOptions(): MangaListFilterOptions {
		val tags = runCatching {
			val json = fetchJson("$apiUrl/genres.php?source=all")
			val data = json.optJSONObject("data")
			val result = LinkedHashMap<String, MangaTag>()
			if (data != null) {
				val sources = data.keys()
				while (sources.hasNext()) {
					val entries = data.optJSONArray(sources.next()) ?: continue
					for (i in 0 until entries.length()) {
						val entry = entries.getJSONObject(i)
						val name = entry.optString("genre").trim()
						val slug = entry.optString("genre_slug").trim()
							.ifBlank { name.lowercase(Locale.ROOT) }
						if (name.isNotBlank() && slug.isNotBlank()) {
							result.putIfAbsent(slug, MangaTag(name, slug, source))
						}
					}
				}
			}
			result.values.toSet()
		}.getOrDefault(emptySet())
		return MangaListFilterOptions(
			availableTags = tags,
			availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED, MangaState.PAUSED),
		)
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = "$apiUrl/home.php".toHttpUrl().newBuilder()
			.addQueryParameter("source", "all")
			.addQueryParameter("q", filter.query?.trim().orEmpty())
			.addQueryParameter("mode", "update")
			.addQueryParameter("type", "all")
			.apply {
				filter.tags.firstOrNull()?.let { addQueryParameter("genre", it.key) }
			}
			.addQueryParameter("limit", pageSize.toString())
			.addQueryParameter("offset", ((page - 1) * pageSize).toString())
			.build()
		val json = fetchJson(url.toString())
		val data = json.optJSONArray("data") ?: return emptyList()
		val result = ArrayList<Manga>(data.length())
		for (i in 0 until data.length()) {
			result += parseListItem(data.getJSONObject(i))
		}
		return result.filter { manga ->
			filter.states.isEmpty() || manga.state in filter.states
		}
	}

	private fun parseListItem(obj: JSONObject): Manga {
		val memberSource = obj.optString("source").trim()
		val slug = obj.optString("slug").trim()
		val title = obj.optString("title").trim()
		val itemUrl = "/$memberSource/$slug"
		return Manga(
			id = generateUid(itemUrl),
			url = itemUrl,
			publicUrl = "https://$domain/",
			title = title,
			coverUrl = obj.optString("thumbnail").takeIf { it.isNotBlank() },
			altTitles = emptySet(),
			rating = RATING_UNKNOWN,
			contentRating = null,
			tags = genreTags(obj.optJSONArray("genres")),
			authors = setOfNotNull(
				obj.optString("author").trim().takeIf { it.isNotBlank() },
			),
			state = parseState(obj.optString("status")),
			source = source,
		)
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val segments = manga.url.trim('/').split('/')
		if (segments.size < 2) {
			throw ParseException("Invalid manga URL: ${manga.url}", manga.url)
		}
		val memberSource = segments[0]
		val slug = segments[1]
		val url = "$apiUrl/manga.php".toHttpUrl().newBuilder()
			.addQueryParameter("source", memberSource)
			.addQueryParameter("slug", slug)
			.addQueryParameter("platform", "web")
			.build()
		val json = fetchJson(url.toString())
		val data = json.optJSONObject("data") ?: json.optJSONObject("manga")
			?: throw ParseException("Details not found for '${manga.title}'", manga.publicUrl)

		val chaptersJson = data.optJSONArray("chapters") ?: JSONArray()
		val chapters = ArrayList<MangaChapter>(chaptersJson.length())
		for (i in 0 until chaptersJson.length()) {
			val obj = chaptersJson.getJSONObject(i)
			val chapterSlug = obj.optString("slug").ifBlank { obj.optString("chapter_slug") }.trim()
			if (chapterSlug.isBlank()) continue
			val token = obj.optString("chapter_token").ifBlank { obj.optString("token") }.trim()
			val chapterUrl = buildString {
				append("/").append(memberSource).append("/").append(slug).append("/").append(chapterSlug)
				if (token.isNotBlank()) append("?token=").append(token)
			}
			val chapterTitle = obj.optString("title").ifBlank { obj.optString("chapter_title") }.trim()
			val number = parseChapterNumber(obj, chapterTitle, chapterSlug) ?: continue
			chapters += MangaChapter(
				id = generateUid(chapterUrl),
				title = chapterTitle.ifBlank { "Chapter $number" },
				url = chapterUrl,
				number = number,
				volume = 0,
				scanlator = null,
				uploadDate = parseDate(
					obj.optString("date").ifBlank { obj.optString("created_at") }
						.ifBlank { obj.optString("updated_at") },
				),
				branch = null,
				source = source,
			)
		}

		return manga.copy(
			title = data.optString("title").ifBlank { manga.title },
			coverUrl = data.optString("thumbnail").ifBlank { data.optString("cover") }
				.takeIf { it.isNotBlank() } ?: manga.coverUrl,
			description = data.optString("synopsis").takeIf { it.isNotBlank() },
			authors = setOfNotNull(
				data.optString("author").trim().takeIf { it.isNotBlank() },
				data.optString("artist").trim().takeIf { it.isNotBlank() },
			).ifEmpty { manga.authors },
			tags = genreTags(data.optJSONArray("genres")).ifEmpty { manga.tags },
			state = parseState(data.optString("status")) ?: manga.state,
			chapters = chapters.sortedBy { it.number },
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val path = chapter.url.substringBefore('?')
		val segments = path.trim('/').split('/')
		if (segments.size < 3) {
			throw ParseException("Invalid chapter URL: ${chapter.url}", chapter.url)
		}
		val token = chapter.url.substringAfter("?token=", "").trim()
		val url = "$apiUrl/chapter.php".toHttpUrl().newBuilder()
			.addQueryParameter("source", segments[0])
			.addQueryParameter("manga", segments[1])
			.addQueryParameter("chapter", segments[2])
			.addQueryParameter("platform", "web")
			.apply {
				if (token.isNotBlank()) addQueryParameter("token", token)
			}
			.build()
		val json = fetchJson(url.toString())
		val data = json.optJSONObject("data") ?: json.optJSONObject("chapter") ?: json
		val images = data.optJSONArray("images")
			?: throw ParseException("No pages found for this chapter", chapter.url)
		val pages = ArrayList<MangaPage>(images.length())
		for (i in 0 until images.length()) {
			val entry = images.opt(i)
			val imageUrl = when (entry) {
				is String -> entry.trim()
				is JSONObject -> entry.optString("url").ifBlank { entry.optString("src") }
					.ifBlank { entry.optString("image") }.trim()
				else -> ""
			}
			if (imageUrl.isNotBlank()) {
				pages += MangaPage(
					id = generateUid(imageUrl),
					url = imageUrl,
					preview = null,
					source = source,
				)
			}
		}
		if (pages.isEmpty()) {
			throw ParseException("No pages found for this chapter", chapter.url)
		}
		return pages
	}

	private suspend fun fetchJson(url: String): JSONObject {
		val json = try {
			webClient.httpGet(url, getRequestHeaders()).parseJson()
		} catch (e: Exception) {
			if (e.message?.contains("428") == true) {
				throw verificationException(url)
			}
			if (e.message?.contains("403") == true) {
				throw lockedException(url)
			}
			throw ParseException("Failed to load $url: ${e.message}", url, e)
		}
		if (json.optBoolean("need_challenge", false)) {
			throw verificationException(url)
		}
		if (json.optBoolean("locked", false)) {
			throw lockedException(url)
		}
		return json
	}

	private fun verificationException(url: String) = ParseException(
		"Comicaso asks for a one-time human verification before opening this content. " +
			"Open the Comicaso site in the app's browser, complete the verification, then try again.",
		url,
	)

	private fun lockedException(url: String) = ParseException(
		"This title's collection is locked on Comicaso: it needs an account login. " +
			"Open the Comicaso site in the app's browser, sign in, then try again.",
		url,
	)

	private fun genreTags(array: JSONArray?): Set<MangaTag> {
		if (array == null) return emptySet()
		val result = LinkedHashSet<MangaTag>()
		for (i in 0 until array.length()) {
			val entry = array.opt(i)
			val name = when (entry) {
				is String -> entry.trim()
				is JSONObject -> entry.optString("name").ifBlank { entry.optString("genre") }.trim()
				else -> ""
			}
			if (name.isNotBlank()) {
				result += MangaTag(name, name.lowercase(Locale.ROOT), source)
			}
		}
		return result
	}

	private fun parseState(status: String): MangaState? = when {
		status.contains("complete", ignoreCase = true) ||
			status.contains("finished", ignoreCase = true) -> MangaState.FINISHED
		status.contains("hiatus", ignoreCase = true) ||
			status.contains("hold", ignoreCase = true) -> MangaState.PAUSED
		status.contains("ongoing", ignoreCase = true) ||
			status.contains("on-going", ignoreCase = true) -> MangaState.ONGOING
		else -> null
	}

	private fun parseChapterNumber(obj: JSONObject, title: String, slug: String): Float? {
		for (key in listOf("number", "chapter_number", "num")) {
			val value = obj.optString(key).trim().toFloatOrNull()
			if (value != null) return value
		}
		val fromTitle = CHAPTER_NUMBER_REGEX.find(title)?.groupValues?.get(1)?.toFloatOrNull()
		if (fromTitle != null) return fromTitle
		return CHAPTER_NUMBER_REGEX.find(slug)?.groupValues?.get(1)?.toFloatOrNull()
	}

	private fun parseDate(raw: String): Long {
		val value = raw.trim()
		if (value.isBlank()) return 0L
		for (pattern in DATE_PATTERNS) {
			val parsed = runCatching {
				SimpleDateFormat(pattern, Locale.ROOT).apply {
					timeZone = TimeZone.getTimeZone("UTC")
				}.parse(value)?.time
			}.getOrNull()
			if (parsed != null) return parsed
		}
		return 0L
	}

	private companion object {
		val CHAPTER_NUMBER_REGEX = Regex("""(\d+(?:\.\d+)?)""")
		val DATE_PATTERNS = listOf(
			"yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
			"yyyy-MM-dd'T'HH:mm:ss'Z'",
			"yyyy-MM-dd HH:mm:ss",
			"yyyy-MM-dd",
		)
	}
}
