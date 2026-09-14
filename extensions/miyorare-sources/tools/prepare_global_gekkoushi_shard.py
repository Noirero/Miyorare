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
    """Apply Miyorare's one-gallery model and complete language presets to EXHENTAI."""
    parser = gekkoushi_upstream / "src/main/kotlin/tsuki/site/all/ExHentaiParser.kt"
    if not parser.is_file():
        fail(f"ExHentai parser not found: {parser}")

    text = parser.read_text(encoding="utf-8")

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
    new_get_pages = '''    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
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
        val pages = ArrayList<MangaPage>()
        for (galleryPage in 0 until pageCount) {
            val doc = if (galleryPage == 0) {
                firstDoc
            } else {
                webClient.httpGet("$baseUrl?p=$galleryPage".toAbsoluteUrl(domain)).parseHtml()
            }
            val root = doc.body().requireElementById("gdt")
            root.select("a").forEach { a ->
                val url = a.attrAsRelativeUrl("href")
                pages += MangaPage(
                    id = generateUid(url),
                    url = url,
                    preview = a.children().firstOrNull()?.extractPreview(),
                    source = source,
                )
            }
        }
        return pages
    }
'''
    if text.count(old_get_pages) != 1:
        fail("Pinned ExHentai parser changed: getPages block not found exactly once")
    text = text.replace(old_get_pages, new_get_pages, 1)

    parser.write_text(text, encoding="utf-8")
    print("Applied Miyorare EXHENTAI canonical-family overlay")


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
