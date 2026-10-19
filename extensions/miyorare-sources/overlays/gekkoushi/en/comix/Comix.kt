package tsuki.site.en.comix

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Element
import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.bitmap.Rect
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser
import tsuki.exception.ParseException
import tsuki.model.*
import tsuki.network.CommonHeaders
import tsuki.util.*
import java.util.EnumSet

/**
 * Miyorare-owned Comix recovery overlay.
 *
 * Current Comix uses a browser-side /api/v1 client whose signer/decrypter changes with the site
 * build. Public browse/title HTML remains useful, while reader page data is requested through the
 * site's own loaded client inside Miyorare's shared-session WebView. No rotating private crypto
 * table is copied into the source.
 */
@MangaSourceParser("COMIX", "Comix", "en", ContentType.MANGA)
internal class Comix(
    private val loaderContext: MangaLoaderContext,
) : PagedMangaParser(loaderContext, MangaParserSource.COMIX, 28) {

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
        val (sortKey, direction) = when (order) {
            SortOrder.RELEVANCE -> "relevance" to "desc"
            SortOrder.UPDATED -> "chapter_updated_at" to "desc"
            SortOrder.POPULARITY -> "score" to "desc"
            SortOrder.NEWEST -> "created_at" to "desc"
            SortOrder.ALPHABETICAL -> "title" to "asc"
            else -> "chapter_updated_at" to "desc"
        }
        val url = buildString {
            append("https://").append(domain).append("/browse?order%5B")
                .append(sortKey.urlEncoded()).append("%5D=").append(direction)
            filter.query?.takeIf { it.isNotBlank() }?.let {
                append("&keyword=").append(it.urlEncoded())
            }
            append("&page=").append(page)
        }
        val doc = webClient.httpGet(url).parseHtml()
        val seen = HashSet<String>()
        return doc.select("a[href]").mapNotNull { anchor ->
            val href = anchor.attrAsRelativeUrl("href").substringBefore('?')
            if (!href.startsWith("/title/") || href.count { it == '/' } != 2 || !seen.add(href)) {
                return@mapNotNull null
            }
            val card = anchor.closest("article, li, div") ?: anchor.parent()
            val title = firstNonBlank(
                anchor.attr("title"),
                card?.selectFirst("h2, h3, h4, [class*=title]")?.text(),
                anchor.selectFirst("img")?.attr("alt"),
                anchor.text(),
            ) ?: return@mapNotNull null
            val img = card?.selectFirst("img") ?: anchor.selectFirst("img")
            Manga(
                id = generateUid(href),
                url = href,
                publicUrl = href.toAbsoluteUrl(domain),
                coverUrl = img?.let(::imageUrl).orEmpty(),
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
        val authors = doc.select("a[href*=authors], a[href*=artists], a[href*=author], a[href*=artist]")
            .mapNotNullTo(linkedSetOf()) { it.textOrNull() }
        val tags = doc.select("a[href*=genres], a[href*=genre]").mapNotNullTo(linkedSetOf()) { a ->
            val name = a.textOrNull() ?: return@mapNotNullTo null
            val key = a.attr("href").substringAfterLast('=').substringAfterLast('/').nullIfEmpty() ?: name
            MangaTag(title = name, key = key, source = source)
        }
        return manga.copy(
            title = title,
            coverUrl = cover,
            description = description,
            authors = authors,
            tags = tags,
            chapters = parseChapters(doc, manga.url),
        )
    }

    private fun parseChapters(root: Element, mangaUrl: String): List<MangaChapter> {
        val prefix = mangaUrl.substringBefore('?').trimEnd('/') + "/"
        val seen = HashSet<String>()
        return root.select("a[href]").mapNotNull { anchor ->
            val href = anchor.attrAsRelativeUrl("href").substringBefore('?')
            if (!href.startsWith(prefix) || !seen.add(href)) return@mapNotNull null

            val numberText = CHAPTER_SLUG_REGEX.find(href.substringAfterLast('/'))
                ?.groupValues?.getOrNull(1)
                ?: CHAPTER_TEXT_REGEX.find(anchor.text())?.groupValues?.getOrNull(1)
            val number = numberText?.toFloatOrNull() ?: return@mapNotNull null
            val row = anchor.closest("tr, li, article, div")
            val scanlator = row?.selectFirst("[class*=group], [class*=scanlator], a[href*=group]")?.textOrNull()
            MangaChapter(
                id = generateUid(href),
                title = anchor.textOrNull() ?: "Chapter $number",
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
        val chapterUrl = chapter.url.toAbsoluteUrl(domain)

        // Keep the ordinary request first. If Comix presents a Cloudflare challenge, Miyorare's
        // existing interceptor raises the normal Solve action. After the user solves it, the source
        // screen/reader retries and the shared CookieManager session is available to the WebView.
        val doc = webClient.httpGet(chapterUrl).parseHtml()
        parseStaticPages(doc).takeIf { it.isNotEmpty() }?.let { return it }

        val chapterId = chapter.url.substringAfterLast('/').substringBefore("-chapter-")
            .toIntOrNull() ?: readerError("Could not find Comix chapter id", chapter.url)
        val raw = loaderContext.evaluateJs(
            chapterUrl,
            PAGE_NAVIGATION_MARKER + chapterPagesScript(chapterId),
        ) ?: readerError("Comix reader did not return page data", chapter.url)
        val payload = JSONObject(decodeJavascriptValue(raw))
        payload.optString("error").nullIfEmpty()?.let { readerError(it, chapter.url) }

        val base = payload.optString("baseUrl").trimEnd('/')
        val items = payload.optJSONArray("items") ?: readerError("Comix reader returned no pages", chapter.url)
        return buildList(items.length()) {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val path = item.optString("url").nullIfEmpty() ?: continue
                val absolute = when {
                    path.startsWith("http://") || path.startsWith("https://") -> path
                    path.startsWith("//") -> "https:$path"
                    base.isNotEmpty() -> "$base/${path.trimStart('/')}"
                    else -> continue
                }
                val parsed = absolute.toHttpUrlOrNull() ?: continue
                val marked = parsed.newBuilder().apply {
                    if (item.optInt("s", 0) == 1 && "v3" !in parsed.queryParameterNames) {
                        addQueryParameter("v3", null)
                    }
                    fragment(PAGE_FRAGMENT)
                }.build().toString()
                add(
                    MangaPage(
                        id = generateUid(marked),
                        url = marked,
                        preview = null,
                        source = source,
                    ),
                )
            }
        }.ifEmpty {
            readerError("Comix reader returned an empty page list", chapter.url)
        }
    }

    private fun parseStaticPages(root: Element): List<MangaPage> {
        val seen = HashSet<String>()
        return root.select("img[alt^=Page], img[alt*=page], img[data-page], [class*=page] img")
            .mapNotNull { img ->
                val url = imageUrl(img).nullIfEmpty() ?: return@mapNotNull null
                if (!url.startsWith("http") || !seen.add(url)) return@mapNotNull null
                val marked = url.toHttpUrlOrNull()?.newBuilder()?.fragment(PAGE_FRAGMENT)?.build()?.toString()
                    ?: return@mapNotNull null
                MangaPage(
                    id = generateUid(marked),
                    url = marked,
                    preview = null,
                    source = source,
                )
            }
    }

    /**
     * Comix reader images reject Referer/Origin and may be byte-encoded and/or 5x5 scrambled.
     * RoutingInterceptor calls this parser interceptor for the private Tsuki client, so the fix is
     * source-scoped and does not alter the app's global image/network policy.
     */
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val request = if (original.url.fragment == PAGE_FRAGMENT) {
            original.newBuilder()
                .removeHeader(CommonHeaders.REFERER)
                .removeHeader(CommonHeaders.ORIGIN)
                .build()
        } else {
            original
        }
        val response = chain.proceed(request)
        if (!response.isSuccessful || original.url.fragment != PAGE_FRAGMENT) return response

        val encSeed = response.header("x-enc-seed")?.toLongOrNull()?.toInt()
        val encLen = response.header("x-enc-len")?.toIntOrNull()
        val scrambleSeed = response.header("x-scramble-seed")?.toLongOrNull()?.toInt()
        val scrambleHash = decodeScrambleHash(response.header("x-scramble-hash"))
        val scrambleGrid = response.header("x-scramble-grid")
        val scrambleAlgo = response.header("x-scramble-algo")
        val needsXor = encSeed != null && encSeed != 0 && encLen != null
        val needsGrid = scrambleGrid == "5x5" &&
            scrambleSeed != null && scrambleSeed != 0 &&
            (scrambleAlgo == null || scrambleAlgo in setOf("1", "2", "3"))
        if (!needsXor && !needsGrid) return response

        val mediaType = response.body.contentType()
        val originalBytes = response.body.bytes()
        val decoded = if (needsXor) {
            decodeEncodedBytes(
                originalBytes,
                requireNotNull(encSeed),
                requireNotNull(encLen),
                response.header("x-enc-algo"),
            )
        } else {
            originalBytes
        }
        val decodedResponse = response.newBuilder()
            .removeHeader("Content-Encoding")
            .removeHeader("Content-Length")
            .body(decoded.toResponseBody(mediaType))
            .build()
        if (!needsGrid) return decodedResponse

        val seed = requireNotNull(scrambleSeed) xor scrambleHash
        val redrawBase = decodedResponse.newBuilder().removeHeader("Content-Type").build()
        return loaderContext.redrawImageResponse(redrawBase) { bitmap ->
            val width = bitmap.width
            val height = bitmap.height
            val tileWidth = width / GRID_COLS
            val tileHeight = height / GRID_ROWS
            val order = if (scrambleAlgo == "3") {
                buildOrderXorshift(seed, NUM_TILES)
            } else {
                buildOrderLcg(seed, NUM_TILES)
            }
            val result = loaderContext.createBitmap(width, height)
            for (dstIndex in 0 until NUM_TILES) {
                val srcIndex = order[dstIndex]
                val srcCol = srcIndex % GRID_COLS
                val srcRow = srcIndex / GRID_COLS
                val dstCol = dstIndex % GRID_COLS
                val dstRow = dstIndex / GRID_COLS
                result.drawBitmap(
                    bitmap,
                    Rect(
                        srcCol * tileWidth,
                        srcRow * tileHeight,
                        (srcCol + 1) * tileWidth,
                        (srcRow + 1) * tileHeight,
                    ),
                    Rect(
                        dstCol * tileWidth,
                        dstRow * tileHeight,
                        (dstCol + 1) * tileWidth,
                        (dstRow + 1) * tileHeight,
                    ),
                )
            }
            result
        }
    }

    private fun imageUrl(img: Element): String = firstNonBlank(
        img.attr("data-src"),
        img.attr("data-lazy-src"),
        img.attr("data-original"),
        img.attr("src"),
    )?.takeUnless { it.startsWith("data:") || it.startsWith("blob:") }
        ?.let { if (it.startsWith("//")) "https:$it" else it } ?: ""

    private fun firstNonBlank(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }?.trim()

    private fun readerError(message: String, url: String): Nothing = throw ParseException(message, url)

    private fun decodeJavascriptValue(value: String): String {
        val trimmed = value.trim()
        return if (trimmed.startsWith('"')) JSONArray("[$trimmed]").getString(0) else trimmed
    }

    private fun chapterPagesScript(chapterId: Int): String = """
        (function () {
            const resultKey = '__miyorareComixPages';
            const errorKey = '__miyorareComixPagesError';
            if (window[resultKey]) return JSON.stringify(window[resultKey]);
            if (window[errorKey]) return JSON.stringify({error: window[errorKey]});
            if (window.__miyorareComixPagesStarted) return null;
            window.__miyorareComixPagesStarted = true;

            const findSiteApi = async (select) => {
                const isGet = (value) => typeof value === 'function' &&
                    String(value).includes('.get(') && String(value).endsWith('.data');
                const deadline = Date.now() + 10000;
                for (;;) {
                    const scripts = performance.getEntriesByType('resource')
                        .map((entry) => entry.name)
                        .filter((url) => /\/assets\/.*\.js$/.test(url));
                    const entry = scripts.find((url) => /\/main-[^/]+\.js$/.test(url));
                    if (entry) {
                        const directory = entry.slice(0, entry.lastIndexOf('/') + 1);
                        const members = [];
                        for (const url of [...new Set(scripts.filter((url) => url.startsWith(directory)))]) {
                            try {
                                for (const exported of Object.values(await import(url))) {
                                    if (exported && typeof exported === 'object') {
                                        for (const [key, member] of Object.entries(exported)) {
                                            members.push({owner: exported, key, value: member});
                                            if (member && typeof member === 'object') {
                                                for (const [nestedKey, nested] of Object.entries(member)) {
                                                    members.push({owner: member, key: nestedKey, value: nested});
                                                }
                                            }
                                        }
                                    }
                                }
                            } catch (e) {}
                        }
                        const found = select(members, isGet);
                        if (found) return found;
                    }
                    if (Date.now() >= deadline) return null;
                    await new Promise((resolve) => setTimeout(resolve, 250));
                }
            };

            (async () => {
                const chapter = await findSiteApi((members, isGet) => {
                    const route = members.map((member) => member.value).find((value) =>
                        typeof value === 'function' &&
                        /^\(?[\w${'$'}]+\)?=>`\/chapters\/\${'$'}\{[\w${'$'}]+\}`${'$'}/.test(String(value))
                    );
                    const get = members.map((member) => member.value).find(isGet);
                    return route && get ? (id) => get(route(id)) : null;
                });
                if (!chapter) throw new Error("Could not find the site's chapter API");
                const response = await chapter($chapterId);
                const pages = [response, ...Object.values(response || {})]
                    .map((value) => Array.isArray(value) ? {items: value} : value)
                    .find((value) => value && Array.isArray(value.items) && value.items.length > 0 &&
                        value.items.every((item) => item && typeof item.url === 'string'));
                if (!pages) throw new Error("Chapter pages came back in an unknown shape");
                window[resultKey] = pages;
            })().catch((error) => {
                const status = error && error.response && error.response.status;
                window[errorKey] = status ? `Chapter pages failed: HTTP ${'$'}{status}` :
                    (error && error.message || String(error));
            });
            return null;
        })();
    """.trimIndent()

    private fun decodeEncodedBytes(bytes: ByteArray, seed: Int, length: Int, algo: String?): ByteArray {
        if (algo != "2") return decodeWithLcg(bytes, seed, length)
        val candidates = listOf(
            decodeWithXorshift(bytes, seed or 1, length, false),
            decodeWithXorshift(bytes, seed, length, false),
            decodeWithXorshift(bytes, seed or 1, length, true),
            decodeWithLcg(bytes, seed, length),
        )
        return candidates.firstOrNull { it.hasImageSignature() } ?: candidates.first()
    }

    private fun decodeWithXorshift(bytes: ByteArray, initialState: Int, length: Int, highByte: Boolean): ByteArray {
        val result = bytes.copyOf()
        var state = initialState
        val limit = minOf(result.size, length)
        for (index in 0 until limit) {
            state = nextXorshiftState(state)
            val key = if (highByte) state ushr 24 else state and 0xFF
            result[index] = (result[index].toInt() xor key).toByte()
        }
        return result
    }

    private fun decodeWithLcg(bytes: ByteArray, seed: Int, length: Int): ByteArray {
        val result = bytes.copyOf()
        var state = seed
        val limit = minOf(result.size, length)
        for (index in 0 until limit) {
            state = state * ENC_MULTIPLIER + ENC_INCREMENT
            result[index] = (result[index].toInt() xor (state ushr 24)).toByte()
        }
        return result
    }

    private fun nextXorshiftState(state: Int): Int {
        var next = state
        next = next xor (next shl 13)
        next = next xor (next ushr 17)
        return next xor (next shl 5)
    }

    private fun decodeScrambleHash(hash: String?): Int = when (hash?.trim()) {
        "03632" -> 58414
        "02900" -> 117532
        else -> 0
    }

    private fun ByteArray.hasImageSignature(): Boolean = size >= 12 && (
        (this[0] == 'R'.code.toByte() && this[1] == 'I'.code.toByte() &&
            this[2] == 'F'.code.toByte() && this[3] == 'F'.code.toByte() &&
            this[8] == 'W'.code.toByte() && this[9] == 'E'.code.toByte() &&
            this[10] == 'B'.code.toByte() && this[11] == 'P'.code.toByte()) ||
            (this[0] == 0xFF.toByte() && this[1] == 0xD8.toByte()) ||
            (this[0] == 0x89.toByte() && this[1] == 'P'.code.toByte() &&
                this[2] == 'N'.code.toByte() && this[3] == 'G'.code.toByte())
        )

    private fun buildOrderXorshift(seed: Int, size: Int): IntArray {
        val values = IntArray(size) { it }
        var state = seed or 1
        for (index in size - 1 downTo 1) {
            state = nextXorshiftState(state)
            val swap = ((state.toLong() and 0xFFFFFFFFL) % (index + 1)).toInt()
            val temp = values[index]
            values[index] = values[swap]
            values[swap] = temp
        }
        return inverseOrder(values)
    }

    private fun buildOrderLcg(seed: Int, size: Int): IntArray {
        val values = IntArray(size) { it }
        var state = seed
        for (index in size - 1 downTo 1) {
            state = state * LCG_MULTIPLIER + LCG_INCREMENT
            val swap = ((state.toLong() and 0xFFFFFFFFL) % (index + 1)).toInt()
            val temp = values[index]
            values[index] = values[swap]
            values[swap] = temp
        }
        return inverseOrder(values)
    }

    private fun inverseOrder(values: IntArray): IntArray = IntArray(values.size).also { inverse ->
        for (index in values.indices) inverse[values[index]] = index
    }

    private companion object {
        const val PAGE_NAVIGATION_MARKER = "/*miyorare:navigate-page*/"
        const val PAGE_FRAGMENT = "miyorare-comix-page"
        const val GRID_COLS = 5
        const val GRID_ROWS = 5
        const val NUM_TILES = GRID_COLS * GRID_ROWS
        const val ENC_MULTIPLIER = 1_000_005
        const val ENC_INCREMENT = 1_234_567_891
        const val LCG_MULTIPLIER = 1_664_525
        const val LCG_INCREMENT = 1_013_904_223
        val CHAPTER_SLUG_REGEX = Regex("(?:^|-)chapter-([0-9]+(?:\\.[0-9]+)?)", RegexOption.IGNORE_CASE)
        val CHAPTER_TEXT_REGEX = Regex("(?:ch\\.?|chapter)\\s*([0-9]+(?:\\.[0-9]+)?)", RegexOption.IGNORE_CASE)
    }
}
