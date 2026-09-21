#!/usr/bin/env python3
"""Prepare the Gekkoushi-only Miyorare Global shard.

The pinned upstream checkout is used as the build base. Miyorare-specific compatibility overlays
may be applied to that ephemeral checkout before compilation so the upstream repository itself
remains untouched.
"""

from __future__ import annotations

import argparse
import json
import shutil
from pathlib import Path

from prepare_gekkoushi_shard import collect_sources, fail, git_head


EHENTAI_LANGUAGE_PRESETS = (
    "all",
    "en",
    "ja",
    "zh",
    "ko",
    "de",
    "es",
    "fr",
    "it",
    "hu",
    "nl",
    "pl",
    "pt-BR",
    "ru",
    "th",
    "vi",
    "none",
    "other",
)


def patch_exhentai_family(gekkoushi_upstream: Path) -> None:
    """Apply Miyorare's one-gallery model, fast-path chapter seed and language presets to EXHENTAI."""
    parser = gekkoushi_upstream / "src/main/kotlin/tsuki/site/all/ExHentaiParser.kt"
    if not parser.is_file():
        fail(f"ExHentai parser not found: {parser}")

    text = parser.read_text(encoding="utf-8")

    import_anchor = "import androidx.collection.ArraySet\n"
    import_patch = (
        "import androidx.collection.ArraySet\n"
        "import kotlinx.coroutines.async\n"
        "import kotlinx.coroutines.awaitAll\n"
        "import kotlinx.coroutines.coroutineScope\n"
    )
    if text.count(import_anchor) != 1:
        fail("Pinned ExHentai parser changed: import anchor not found exactly once")
    text = text.replace(import_anchor, import_patch, 1)


    # ExHentai search pagination is cursor-based. Upstream keeps the cursor table only in the parser
    # instance and keys it by filter.hashCode(); Miyorare intentionally evicts parser instances from
    # its bounded runtime cache. A recreated parser (or a non-sequential page request) therefore has
    # no cursor and upstream returns an empty page before repository/UI mapping. Replace only that
    # pagination state machine: use an exact request-signature key and reconstruct missing cursors by
    # walking the website's own next links. No search rows are synthesized or discarded here.
    old_cursor_imports = """import androidx.collection.MutableIntLongMap
import androidx.collection.MutableIntObjectMap
"""
    if text.count(old_cursor_imports) != 1:
        fail("Pinned ExHentai parser changed: cursor imports not found exactly once")
    text = text.replace(old_cursor_imports, "", 1)

    old_cursor_field = "    private val nextPages = MutableIntObjectMap<MutableIntLongMap>()\n"
    new_cursor_field = "    private val nextPages = mutableMapOf<String, MutableMap<Int, Long>>()\n"
    if text.count(old_cursor_field) != 1:
        fail("Pinned ExHentai parser changed: nextPages field not found exactly once")
    text = text.replace(old_cursor_field, new_cursor_field, 1)

    old_get_list = '''    private suspend fun getListPage(
        page: Int,
        order: SortOrder,
        filter: MangaListFilter,
        updateDm: Boolean,
    ): List<Manga> {
        val next = synchronized(nextPages) {
            nextPages[filter.hashCode()]?.getOrDefault(page, 0L) ?: 0L
        }

        if (page > 0 && next == 0L) {
            assert(false) { "Page timestamp not found" }
            return emptyList()
        }

        val url = urlBuilder()
        url.addEncodedQueryParameter("next", next.toString())
        url.addQueryParameter("f_search", filter.toSearchQuery())

        val fCats = filter.types.toFCats()
        if (fCats != 0) {
            url.addEncodedQueryParameter("f_cats", (1023 - fCats).toString())
        }
        if (updateDm) {
            // by unknown reason cookie "sl=dm_2" is ignored, so, we should request it again
            url.addQueryParameter("inline_set", "dm_e")
        }
        url.addQueryParameter("advsearch", "1")
        if (config[suspiciousContentKey]) {
            url.addQueryParameter("f_sh", "on")
        }
        val body = webClient.httpGet(url.build()).parseHtml().body()
        val root = body.selectFirst("table.itg")?.selectFirst("tbody")
        if (root == null) {
            if (updateDm) {
                if (body.getElementsContainingText("No hits found").isNotEmpty()) {
                    return emptyList()
                } else {
                    body.parseFailed("Cannot find root")
                }
            } else {
                return getListPage(page, order, filter, updateDm = true)
            }
        }
        val nextTimestamp = getNextTimestamp(body)
        synchronized(nextPages) {
            nextPages.getOrPut(filter.hashCode()) {
                MutableIntLongMap()
            }.put(page + 1, nextTimestamp)
        }

        return root.children().mapNotNull { tr ->
            if (tr.childrenSize() != 2) return@mapNotNull null
            val (td1, td2) = tr.children()
            val gLink = td2.selectFirstOrThrow("div.glink")
            val a = gLink.parents().select("a").first() ?: gLink.parseFailed("link not found")
            val href = a.attrAsRelativeUrl("href")
            val tagsDiv = gLink.nextElementSibling() ?: gLink.parseFailed("tags div not found")
            val rawTitle = gLink.text()
            val author = tagsDiv.getElementsContainingOwnText("artist:").first()
                ?.nextElementSibling()?.textOrNull()
            Manga(
                id = generateUid(href),
                title = rawTitle.cleanupTitle(),
                altTitles = emptySet(),
                url = href,
                publicUrl = a.absUrl("href"),
                rating = td2.selectFirst("div.ir")?.parseRating() ?: RATING_UNKNOWN,
                contentRating = ContentRating.ADULT,
                coverUrl = td1.selectFirst("img")?.attrAsAbsoluteUrlOrNull("src"),
                tags = tagsDiv.parseTags(),
                state = when {
                    rawTitle.contains("(ongoing)", ignoreCase = true) -> MangaState.ONGOING
                    else -> null
                },
                authors = setOfNotNull(author),
                source = source,
            )
        }
    }
'''
    new_get_list = '''    private suspend fun getListPage(
        page: Int,
        order: SortOrder,
        filter: MangaListFilter,
        updateDm: Boolean,
    ): List<Manga> {
        val key = paginationKey(filter)
        val next = ensurePageCursor(page, filter, key)
        if (page > 0 && next == 0L) {
            return emptyList()
        }

        var body = requestListBody(next, filter, updateDm)
        var root = body.selectFirst("table.itg")?.selectFirst("tbody")
        if (root == null) {
            if (updateDm) {
                if (body.getElementsContainingText("No hits found").isNotEmpty()) {
                    return emptyList()
                }
                body.parseFailed("Cannot find root")
            }
            body = requestListBody(next, filter, updateDm = true)
            root = body.selectFirst("table.itg")?.selectFirst("tbody")
            if (root == null) {
                if (body.getElementsContainingText("No hits found").isNotEmpty()) {
                    return emptyList()
                }
                body.parseFailed("Cannot find root")
            }
        }

        val nextTimestamp = getNextTimestamp(body)
        synchronized(nextPages) {
            nextPages.getOrPut(key, ::mutableMapOf)[page + 1] = nextTimestamp
        }

        return root.children().mapNotNull { tr ->
            if (tr.childrenSize() != 2) return@mapNotNull null
            val (td1, td2) = tr.children()
            val gLink = td2.selectFirstOrThrow("div.glink")
            val a = gLink.parents().select("a").first() ?: gLink.parseFailed("link not found")
            val href = a.attrAsRelativeUrl("href")
            val tagsDiv = gLink.nextElementSibling() ?: gLink.parseFailed("tags div not found")
            val rawTitle = gLink.text()
            val author = tagsDiv.getElementsContainingOwnText("artist:").first()
                ?.nextElementSibling()?.textOrNull()
            Manga(
                id = generateUid(href),
                title = rawTitle.cleanupTitle(),
                altTitles = emptySet(),
                url = href,
                publicUrl = a.absUrl("href"),
                rating = td2.selectFirst("div.ir")?.parseRating() ?: RATING_UNKNOWN,
                contentRating = ContentRating.ADULT,
                coverUrl = td1.selectFirst("img")?.attrAsAbsoluteUrlOrNull("src"),
                tags = tagsDiv.parseTags(),
                state = when {
                    rawTitle.contains("(ongoing)", ignoreCase = true) -> MangaState.ONGOING
                    else -> null
                },
                authors = setOfNotNull(author),
                source = source,
            )
        }
    }

    private fun paginationKey(filter: MangaListFilter): String = buildString {
        append(domain)
        append('|')
        append(filter.toSearchQuery().orEmpty())
        append('|')
        append(filter.types.toFCats())
        append('|')
        append(config[suspiciousContentKey])
    }

    private suspend fun ensurePageCursor(page: Int, filter: MangaListFilter, key: String): Long {
        if (page <= 0) {
            return 0L
        }

        synchronized(nextPages) {
            nextPages[key]?.get(page)?.let { return it }
        }

        var cursorPage = 0
        var cursor = 0L
        synchronized(nextPages) {
            nextPages[key]
                ?.entries
                ?.asSequence()
                ?.filter { (cachedPage, cachedCursor) ->
                    cachedPage in 1 until page && cachedCursor > 0L
                }
                ?.maxByOrNull { it.key }
                ?.let { nearest ->
                    cursorPage = nearest.key
                    cursor = nearest.value
                }
        }

        while (cursorPage < page) {
            var body = requestListBody(cursor, filter, updateDm = false)
            var root = body.selectFirst("table.itg")?.selectFirst("tbody")
            if (root == null && body.getElementsContainingText("No hits found").isEmpty()) {
                body = requestListBody(cursor, filter, updateDm = true)
                root = body.selectFirst("table.itg")?.selectFirst("tbody")
            }
            if (root == null) {
                if (body.getElementsContainingText("No hits found").isNotEmpty()) {
                    return 0L
                }
                body.parseFailed("Cannot find root while rebuilding ExHentai cursor")
            }

            val nextTimestamp = getNextTimestamp(body)
            if (nextTimestamp <= 0L || nextTimestamp == cursor) {
                return 0L
            }
            cursorPage += 1
            cursor = nextTimestamp
            synchronized(nextPages) {
                nextPages.getOrPut(key, ::mutableMapOf)[cursorPage] = cursor
            }
        }
        return cursor
    }

    private suspend fun requestListBody(
        next: Long,
        filter: MangaListFilter,
        updateDm: Boolean,
    ): Element {
        val url = urlBuilder()
        url.addEncodedQueryParameter("next", next.toString())
        url.addQueryParameter("f_search", filter.toSearchQuery())

        val fCats = filter.types.toFCats()
        if (fCats != 0) {
            url.addEncodedQueryParameter("f_cats", (1023 - fCats).toString())
        }
        if (updateDm) {
            // by unknown reason cookie "sl=dm_2" is ignored, so, we should request it again
            url.addQueryParameter("inline_set", "dm_e")
        }
        url.addQueryParameter("advsearch", "1")
        if (config[suspiciousContentKey]) {
            url.addQueryParameter("f_sh", "on")
        }
        return webClient.httpGet(url.build()).parseHtml().body()
    }
'''
    if text.count(old_get_list) != 1:
        fail("Pinned ExHentai parser changed: cursor-based list block not found exactly once")
    text = text.replace(old_get_list, new_get_list, 1)

    old_next_fallback = '''            ?.queryParameter("next")
            ?.toLongOrNull() ?: 1
'''
    new_next_fallback = '''            ?.queryParameter("next")
            ?.toLongOrNull() ?: 0
'''
    if text.count(old_next_fallback) != 1:
        fail("Pinned ExHentai parser changed: next cursor fallback not found exactly once")
    text = text.replace(old_next_fallback, new_next_fallback, 1)

    old_locales = '''        availableLocales = setOf(
            Locale.JAPANESE,
            Locale.ENGLISH,
            Locale.CHINESE,
            Locale("nl"),
            Locale.FRENCH,
            Locale.GERMAN,
            Locale("hu"),
            Locale.ITALIAN,
            Locale("kr"),
            Locale("pl"),
            Locale("pt"),
            Locale("ru"),
            Locale("es"),
            Locale("th"),
            Locale("vi"),
        ),
'''
    new_locales = '''        // `All` is represented by leaving the locale filter empty. Keep every language bucket
        // supported by the E-Hentai extension available as a preset inside this one canonical source.
        availableLocales = setOf(
            Locale.ENGLISH,
            Locale.JAPANESE,
            Locale.CHINESE,
            Locale.KOREAN,
            Locale.GERMAN,
            Locale("es"),
            Locale.FRENCH,
            Locale.ITALIAN,
            Locale("hu"),
            Locale("nl"),
            Locale("pl"),
            Locale("pt"),
            Locale("ru"),
            Locale("th"),
            Locale("vi"),
            Locale("none"),
            Locale("other"),
        ),
'''
    if text.count(old_locales) != 1:
        fail("Pinned ExHentai parser changed: locale preset block not found exactly once")
    text = text.replace(old_locales, new_locales, 1)

    # List rows already know the canonical gallery URL. Seed the one stable chapter immediately so
    # Details can render a usable Read/Continue action without waiting for a second network round-trip.
    # getDetails() later enriches the same chapter id with upload date/language metadata.
    old_list_tail = '''                authors = setOfNotNull(author),
                source = source,
            )
'''
    new_list_tail = '''                authors = setOfNotNull(author),
                chapters = listOf(
                    MangaChapter(
                        id = generateUid(href),
                        title = "Chapter",
                        number = 1f,
                        volume = 0,
                        url = href,
                        scanlator = null,
                        uploadDate = 0L,
                        branch = null,
                        source = source,
                    ),
                ),
                source = source,
            )
'''
    if text.count(old_list_tail) != 1:
        fail("Pinned ExHentai parser changed: list Manga tail not found exactly once")
    text = text.replace(old_list_tail, new_list_tail, 1)

    tabs_line = '        val tabs = doc.body().selectFirst("table.ptt")?.selectFirst("tr")\n'
    if text.count(tabs_line) != 1:
        fail("Pinned ExHentai parser changed: pagination table declaration not found exactly once")
    text = text.replace(tabs_line, "", 1)

    old_chapters = '''            chapters = tabs?.select("a")?.findLast { a ->
                a.text().toIntOrNull() != null
            }?.let { a ->
                val count = a.text().toInt()
                val chapters = ChaptersListBuilder(count)
                for (i in 1..count) {
                    val url = "${manga.url}?p=${i - 1}"
                    chapters += MangaChapter(
                        id = generateUid(url),
                        title = null,
                        number = i.toFloat(),
                        volume = 0,
                        url = url,
                        uploadDate = uploadDate,
                        source = source,
                        scanlator = uploader,
                        branch = lang,
                    )
                }
                chapters.toList()
            },
'''
    new_chapters = '''            // E-Hentai pagination is part of one gallery, not a real chapter boundary.
            // Keep one stable chapter. The gallery uploader is not a scanlator; leaving scanlator null also
            // preserves the long-standing `Chapter.cbz` artifact name used by E-Hentai downloads.
            chapters = listOf(
                MangaChapter(
                    id = generateUid(manga.url),
                    title = "Chapter",
                    number = 1f,
                    volume = 0,
                    url = manga.url,
                    uploadDate = uploadDate,
                    source = source,
                    scanlator = null,
                    branch = lang,
                ),
            ),
'''
    if text.count(old_chapters) != 1:
        fail("Pinned ExHentai parser changed: multi-chapter pagination block not found exactly once")
    text = text.replace(old_chapters, new_chapters, 1)

    old_get_pages = '''    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
        val root = doc.body().requireElementById("gdt")
        return root.select("a").map { a ->
            val url = a.attrAsRelativeUrl("href")
            MangaPage(
                id = generateUid(url),
                url = url,
                preview = a.children().firstOrNull()?.extractPreview(),
                source = source,
            )
        }
    }
'''
    new_get_pages = '''    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> = coroutineScope {
        val baseUrl = chapter.url.substringBefore('?')
        val firstDoc = webClient.httpGet(baseUrl.toAbsoluteUrl(domain)).parseHtml()
        val pageCount = firstDoc.body()
            .selectFirst("table.ptt")
            ?.selectFirst("tr")
            ?.select("a")
            ?.findLast { a -> a.text().toIntOrNull() != null }
            ?.text()
            ?.toIntOrNull()
            ?: 1

        fun parseGalleryPage(doc: org.jsoup.nodes.Document): List<MangaPage> {
            val root = doc.body().requireElementById("gdt")
            return root.select("a").map { a ->
                val url = a.attrAsRelativeUrl("href")
                MangaPage(
                    id = generateUid(url),
                    url = url,
                    preview = a.children().firstOrNull()?.extractPreview(),
                    source = source,
                )
            }
        }

        val pages = ArrayList<MangaPage>()
        pages.addAll(parseGalleryPage(firstDoc))

        // Fetch only a small number of gallery index pages concurrently. This removes the long
        // sequential wait seen on multi-page galleries while avoiding an unbounded request burst
        // that could trigger E-Hentai/ExHentai throttling. awaitAll preserves request order here,
        // so Reader page order remains identical to the website.
        val parallelism = 3
        var batchStart = 1
        while (batchStart < pageCount) {
            val batchEnd = minOf(pageCount, batchStart + parallelism)
            val batch = (batchStart until batchEnd).map { galleryPage ->
                async {
                    val doc = webClient.httpGet("$baseUrl?p=$galleryPage".toAbsoluteUrl(domain)).parseHtml()
                    parseGalleryPage(doc)
                }
            }.awaitAll()
            batch.forEach(pages::addAll)
            batchStart = batchEnd
        }
        pages
    }
'''
    if text.count(old_get_pages) != 1:
        fail("Pinned ExHentai parser changed: getPages block not found exactly once")
    text = text.replace(old_get_pages, new_get_pages, 1)

    parser.write_text(text, encoding="utf-8")
    print("Applied Miyorare EXHENTAI canonical-family performance overlay")


def prepare(manifest: Path, gekkoushi_upstream: Path, pack_name: str) -> None:
    root = json.loads(manifest.read_text(encoding="utf-8"))
    if root.get("schema") != 1:
        fail("Unsupported packs.json schema")
    pack = (root.get("packs") or {}).get(pack_name)
    if not isinstance(pack, dict):
        fail(f"Unknown pack: {pack_name}")
    if pack.get("language") != "all":
        fail("Global Gekkoushi pack must use language 'all'")

    gekkoushi_meta = (root.get("additionalUpstreams") or {}).get("gekkoushi")
    if not isinstance(gekkoushi_meta, dict):
        fail("packs.json is missing additionalUpstreams.gekkoushi")
    expected_gekkoushi = gekkoushi_meta["commit"]
    actual_gekkoushi = git_head(gekkoushi_upstream)
    if actual_gekkoushi != expected_gekkoushi:
        fail(f"Gekkoushi HEAD mismatch: expected {expected_gekkoushi}, got {actual_gekkoushi}")

    site_root = gekkoushi_upstream / "src/main/kotlin/tsuki/site"
    if not site_root.is_dir():
        fail(f"Gekkoushi site directory not found: {site_root}")
    compiled_names, locales, parser_files, source_icons = collect_sources(site_root)
    compiled_set = set(compiled_names)

    selected = pack.get("gekkoushiSources") or []
    if not selected or len(selected) != len(set(selected)) or not all(isinstance(name, str) and name for name in selected):
        fail(f"Pack {pack_name} must contain a non-empty unique gekkoushiSources list")
    selected_set = set(selected)
    missing = sorted(selected_set - compiled_set)
    if missing:
        fail("Global pack references missing Gekkoushi sources: " + ", ".join(missing))
    wrong_locale = sorted(name for name in selected_set if locales.get(name) != "all")
    if wrong_locale:
        fail("Global pack may expose only locale-independent sources: " + ", ".join(wrong_locale))

    if "EXHENTAI" in selected_set:
        patch_exhentai_family(gekkoushi_upstream)

    shutil.rmtree(gekkoushi_upstream / "build", ignore_errors=True)
    summary = gekkoushi_upstream / ".github/summary.yaml"
    if summary.exists():
        summary.unlink()

    exposed = selected_set
    hidden = compiled_set - exposed
    exposed_icons = {name: url for name, url in source_icons.items() if name in exposed}
    plugin_id = pack["pluginId"]
    asset_name = f"miyorare-{pack_name}-gekkoushi.jar"
    metadata = {
        "schema": 2,
        "logicalPackId": pack["pluginId"],
        "logicalDisplayName": pack["displayName"],
        "shard": "gekkoushi",
        "pluginId": plugin_id,
        "displayName": f"{pack['displayName']} / Gekkoushi",
        "language": "all",
        "assetName": asset_name,
        "tsukiApi": root["tsukiApi"],
        "buildUpstream": gekkoushi_meta,
        "upstreams": [gekkoushi_meta],
        "sourceFilesCount": len(set(parser_files.get("all", []))),
        "sourceCount": len(exposed),
        "sourceNames": sorted(exposed),
        "sourceIcons": dict(sorted(exposed_icons.items())),
        "compiledSourceCount": len(compiled_set),
        "compiledSourceNames": sorted(compiled_set),
        "hiddenSupportSourceCount": len(hidden),
        "hiddenSupportSourceNames": sorted(hidden),
        "targetLanguageSourceCount": len(exposed),
        "targetLanguageSourceNames": sorted(exposed),
        "skippedExistingSourceCount": 0,
        "skippedExistingSourceNames": [],
        "dedupeAuthority": "global-single-owner",
        "buildArtifact": "build/libs/gekkoushi.jar",
    }
    if "EXHENTAI" in selected_set:
        metadata["sourcePresets"] = {
            "EXHENTAI": {
                "identity": "ehentai-gallery-id",
                "languages": list(EHENTAI_LANGUAGE_PRESETS),
            }
        }
    (gekkoushi_upstream / "miyorare-pack.json").write_text(
        json.dumps(metadata, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )
    print(
        f"Prepared {pack['displayName']} Gekkoushi shard: {len(exposed)} exposed global source(s), "
        f"kept {len(hidden)} compiled support sources with Miyorare compatibility overlays"
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--gekkoushi-upstream", type=Path, required=True)
    parser.add_argument("--pack", choices=("global",), default="global")
    args = parser.parse_args()
    prepare(args.manifest.resolve(), args.gekkoushi_upstream.resolve(), args.pack)


if __name__ == "__main__":
    main()
