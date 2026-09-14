package tsuki.site.all

import org.json.JSONArray
import org.json.JSONObject
import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser
import tsuki.model.*
import tsuki.util.*
import java.util.EnumSet

/**
 * Miyorare-owned Gelbooru source overlay.
 *
 * Gelbooru is a booru, not a chapter-based manga catalog, so each post is represented as one
 * one-page item. This keeps navigation and downloads deterministic without inventing fake galleries.
 */
@MangaSourceParser("GELBOORU", "Gelbooru", type = ContentType.HENTAI)
internal class Gelbooru(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.GELBOORU, PAGE_SIZE) {

    override val configKeyDomain = ConfigKey.Domain("gelbooru.com")

    init {
        paginator.firstPage = 0
        searchPaginator.firstPage = 0
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.NEWEST,
        SortOrder.NEWEST_ASC,
        SortOrder.POPULARITY,
        SortOrder.POPULARITY_ASC,
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
        )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableContentRating = EnumSet.allOf(ContentRating::class.java),
    )

    override suspend fun getFavicons(): Favicons = Favicons(
        listOf(Favicon("https://gelbooru.com/favicon.png", 32, null)),
        domain,
    )

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val requestedTags = filter.query.orEmpty().trim()
        val sortTag = when (order) {
            SortOrder.NEWEST_ASC -> "sort:id:asc"
            SortOrder.POPULARITY -> "sort:score:desc"
            SortOrder.POPULARITY_ASC -> "sort:score:asc"
            else -> "sort:id:desc"
        }
        val tags = buildString {
            append(requestedTags)
            if (!requestedTags.contains("sort:", ignoreCase = true)) {
                if (isNotEmpty()) append(' ')
                append(sortTag)
            }
        }

        val url = apiUrlBuilder()
            .addQueryParameter("limit", PAGE_SIZE.toString())
            .addQueryParameter("pid", page.toString())
            .addQueryParameter("tags", tags)
            .build()
        val json = webClient.httpGet(url).parseJson()
        val posts = json.optJSONArray("post") ?: JSONArray()
        val result = ArrayList<Manga>(posts.length())
        for (index in 0 until posts.length()) {
            val post = posts.optJSONObject(index) ?: continue
            val manga = post.toManga() ?: continue
            if (filter.contentRating.isNotEmpty() && manga.contentRating !in filter.contentRating) continue
            result += manga
        }
        return result
    }

    override suspend fun getDetails(manga: Manga): Manga {
        if (!manga.chapters.isNullOrEmpty()) return manga
        val post = fetchPost(manga.url) ?: return manga
        return post.toManga() ?: manga
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val directUrl = chapter.url.takeIf { it.startsWith("https://") || it.startsWith("http://") }
        if (directUrl != null) {
            return listOf(
                MangaPage(
                    id = generateUid(directUrl),
                    url = directUrl,
                    preview = directUrl,
                    source = source,
                ),
            )
        }
        val post = fetchPost(chapter.url) ?: return emptyList()
        val fileUrl = post.optString("file_url").takeIf(::isSupportedImage) ?: return emptyList()
        return listOf(
            MangaPage(
                id = generateUid(fileUrl),
                url = fileUrl,
                preview = post.optString("sample_url").takeIf { it.isNotBlank() }
                    ?: post.optString("preview_url").takeIf { it.isNotBlank() },
                source = source,
            ),
        )
    }

    private fun apiUrlBuilder() = urlBuilder()
        .addPathSegment("index.php")
        .addQueryParameter("page", "dapi")
        .addQueryParameter("s", "post")
        .addQueryParameter("q", "index")
        .addQueryParameter("json", "1")

    private suspend fun fetchPost(id: String): JSONObject? {
        val url = apiUrlBuilder()
            .addQueryParameter("id", id)
            .addQueryParameter("limit", "1")
            .build()
        val payload = webClient.httpGet(url).parseJson().opt("post")
        return when (payload) {
            is JSONArray -> payload.optJSONObject(0)
            is JSONObject -> payload
            else -> null
        }
    }

    private fun JSONObject.toManga(): Manga? {
        val id = optLong("id", -1L)
        if (id <= 0L) return null
        val fileUrl = optString("file_url").takeIf(::isSupportedImage) ?: return null
        val previewUrl = optString("preview_url").takeIf { it.isNotBlank() } ?: fileUrl
        val sampleUrl = optString("sample_url").takeIf { it.isNotBlank() } ?: previewUrl
        val rawTags = optString("tags")
        val mangaTags = LinkedHashSet<MangaTag>()
        rawTags.split(' ').asSequence().filter { it.isNotBlank() }.take(MAX_TAGS_PER_POST).forEach { key ->
            mangaTags += MangaTag(
                title = key.replace('_', ' '),
                key = key,
                source = source,
            )
        }
        val uploadDate = optLong("change", 0L).coerceAtLeast(0L) * 1000L
        val chapter = MangaChapter(
            id = generateUid("gelbooru:$id"),
            title = "Image",
            number = 1f,
            volume = 0,
            url = fileUrl,
            uploadDate = uploadDate,
            source = source,
            scanlator = null,
            branch = null,
        )
        return Manga(
            id = generateUid(id),
            title = optString("title").takeIf { it.isNotBlank() } ?: "Gelbooru #$id",
            altTitles = emptySet(),
            url = id.toString(),
            publicUrl = "https://$domain/index.php?page=post&s=view&id=$id",
            rating = RATING_UNKNOWN,
            contentRating = mapRating(optString("rating")),
            coverUrl = previewUrl,
            largeCoverUrl = sampleUrl,
            tags = mangaTags,
            state = MangaState.FINISHED,
            authors = emptySet(),
            source = source,
            chapters = listOf(chapter),
        )
    }

    private fun mapRating(value: String): ContentRating = when (value.lowercase()) {
        "general", "safe", "s" -> ContentRating.SAFE
        "sensitive", "questionable", "q" -> ContentRating.SUGGESTIVE
        else -> ContentRating.ADULT
    }

    private fun isSupportedImage(value: String): Boolean {
        if (value.isBlank()) return false
        val path = value.substringBefore('?').lowercase()
        return IMAGE_EXTENSIONS.any(path::endsWith)
    }

    private companion object {
        const val PAGE_SIZE = 50
        const val MAX_TAGS_PER_POST = 100
        val IMAGE_EXTENSIONS = arrayOf(".jpg", ".jpeg", ".png", ".webp", ".gif")
    }
}
