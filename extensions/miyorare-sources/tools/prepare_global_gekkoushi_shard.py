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

    # Search pagination must not depend on an ephemeral parser instance or an Int hash of the filter.
    # ExHentai uses a cursor ("next"), so a recreated parser reconstructs any missing cursor chain
    # from page zero (or the nearest cached page) instead of returning an empty page before HTTP.
    old_cursor_import = "import androidx.collection.MutableIntObjectMap\n"
    if text.count(old_cursor_import) != 1:
        fail("Pinned ExHentai parser changed: cursor map import not found exactly once")
    text = text.replace(old_cursor_import, "", 1)

    old_cursor_store = "    private val nextPages = MutableIntObjectMap<MutableIntLongMap>()\n"
    new_cursor_store = "    private val nextPages = mutableMapOf<String, MutableIntLongMap>()\n"
    if text.count(old_cursor_store) != 1:
        fail("Pinned ExHentai parser changed: nextPages declaration not found exactly once")
    text = text.replace(old_cursor_store, new_cursor_store, 1)

    old_cursor_lookup = '''        val next = synchronized(nextPages) {
            nextPages[filter.hashCode()]?.getOrDefault(page, 0L) ?: 0L
        }

        if (page > 0 && next == 0L) {
            assert(false) { "Page timestamp not found" }
            return emptyList()
        }
'''
    new_cursor_lookup = '''        val paginationKey = paginationKey(filter)
        val next = resolveNextCursor(page, order, filter)

        if (page > 0 && next == 0L) {
            return emptyList()
        }
'''
    if text.count(old_cursor_lookup) != 1:
        fail("Pinned ExHentai parser changed: cursor lookup block not found exactly once")
    text = text.replace(old_cursor_lookup, new_cursor_lookup, 1)

    old_cursor_write = '''        synchronized(nextPages) {
            nextPages.getOrPut(filter.hashCode()) {
                MutableIntLongMap()
            }.put(page + 1, nextTimestamp)
        }
'''
    new_cursor_write = '''        synchronized(nextPages) {
            nextPages.getOrPut(paginationKey) {
                MutableIntLongMap()
            }.put(page + 1, nextTimestamp)
        }
'''
    if text.count(old_cursor_write) != 1:
        fail("Pinned ExHentai parser changed: cursor write block not found exactly once")
    text = text.replace(old_cursor_write, new_cursor_write, 1)

    # The website currently serves more than one table layout. Gallery identity/title/tags are
    # discoverable by semantic selectors, so do not reject a valid row only because it has 4 cells
    # instead of the historical 2-cell shape.
    old_row_mapping = '''        return root.children().mapNotNull { tr ->
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
'''
    new_row_mapping = '''        return root.children().mapNotNull { tr ->
            val gLink = tr.selectFirst("div.glink") ?: return@mapNotNull null
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
                rating = tr.selectFirst("div.ir")?.parseRating() ?: RATING_UNKNOWN,
                contentRating = ContentRating.ADULT,
                coverUrl = tr.selectFirst("img")?.attrAsAbsoluteUrlOrNull("src"),
'''
    if text.count(old_row_mapping) != 1:
        fail("Pinned ExHentai parser changed: list row mapping block not found exactly once")
    text = text.replace(old_row_mapping, new_row_mapping, 1)

    old_next_helper = '''    private fun getNextTimestamp(root: Element): Long {
        return root.getElementById("unext")
            ?.attrAsAbsoluteUrlOrNull("href")
            ?.toHttpUrlOrNull()
            ?.queryParameter("next")
            ?.toLongOrNull() ?: 1
    }
'''
    new_next_helper = '''    private fun paginationKey(filter: MangaListFilter): String = buildString {
        append(domain)
        append('\\u0000')
        append(filter.toSearchQuery().orEmpty())
        append('\\u0000')
        append(filter.types.toFCats())
        append('\\u0000')
        append(config[suspiciousContentKey])
    }

    private suspend fun resolveNextCursor(
        page: Int,
        order: SortOrder,
        filter: MangaListFilter,
    ): Long {
        if (page == 0) {
            return 0L
        }
        val key = paginationKey(filter)
        val cached = synchronized(nextPages) {
            nextPages[key]?.getOrDefault(page, 0L) ?: 0L
        }
        if (cached != 0L) {
            return cached
        }

        // Rebuild only missing links. This makes recreated parsers, repeated pages and non-sequential
        // requests correct without depending on filter.hashCode() or previous repository instances.
        for (previousPage in 0 until page) {
            val targetPage = previousPage + 1
            val known = synchronized(nextPages) {
                nextPages[key]?.getOrDefault(targetPage, 0L) ?: 0L
            }
            if (known != 0L) {
                continue
            }
            getListPage(previousPage, order, filter, updateDm = false)
            val resolved = synchronized(nextPages) {
                nextPages[key]?.getOrDefault(targetPage, 0L) ?: 0L
            }
            if (resolved == 0L) {
                return 0L
            }
        }
        return synchronized(nextPages) {
            nextPages[key]?.getOrDefault(page, 0L) ?: 0L
        }
    }

    private fun getNextTimestamp(root: Element): Long {
        return root.getElementById("unext")
            ?.attrAsAbsoluteUrlOrNull("href")
            ?.toHttpUrlOrNull()
            ?.queryParameter("next")
            ?.toLongOrNull() ?: 0L
    }
'''
    if text.count(old_next_helper) != 1:
        fail("Pinned ExHentai parser changed: next cursor helper not found exactly once")
    text = text.replace(old_next_helper, new_next_helper, 1)

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
