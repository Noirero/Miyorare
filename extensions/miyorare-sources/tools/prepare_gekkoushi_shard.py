#!/usr/bin/env python3
"""Prepare an independent Gekkoushi shard for a logical Miyorare ID/EN pack.

Gekkoushi is never copied into the UMA source tree. Its checkout stays intact so its own helpers,
Gradle configuration, KSP processor and dependency graph remain authoritative. Miyorare only writes
provenance/visibility metadata: matching-language runtime source keys are exposed unless curated UMA
already supplies the same key. Other compiled Gekkoushi sources remain internal support entries.
"""

from __future__ import annotations

import argparse
import json
import shutil
import subprocess
from pathlib import Path
from typing import NoReturn


ANNOTATION_MARKER = "@MangaSourceParser"
PATH_LOCALES = {"id", "en", "all"}


def fail(message: str) -> NoReturn:
    raise SystemExit(message)


def git_head(repo: Path) -> str:
    try:
        return subprocess.check_output(
            ["git", "-C", str(repo), "rev-parse", "HEAD"],
            text=True,
            stderr=subprocess.STDOUT,
        ).strip()
    except (OSError, subprocess.CalledProcessError) as exc:
        fail(f"Could not read upstream git HEAD for {repo}: {exc}")


def annotation_blocks(content: str, file_name: str) -> list[str]:
    """Extract balanced MangaSourceParser argument lists without being confused by quoted parens."""
    result: list[str] = []
    search_from = 0
    while True:
        marker = content.find(ANNOTATION_MARKER, search_from)
        if marker < 0:
            break
        open_paren = marker + len(ANNOTATION_MARKER)
        while open_paren < len(content) and content[open_paren].isspace():
            open_paren += 1
        if open_paren >= len(content) or content[open_paren] != "(":
            fail(f"Malformed {ANNOTATION_MARKER} in {file_name}")

        depth = 1
        in_string = False
        escaped = False
        index = open_paren + 1
        while index < len(content):
            char = content[index]
            if in_string:
                if escaped:
                    escaped = False
                elif char == "\\":
                    escaped = True
                elif char == '"':
                    in_string = False
            else:
                if char == '"':
                    in_string = True
                elif char == "(":
                    depth += 1
                elif char == ")":
                    depth -= 1
                    if depth == 0:
                        result.append(content[open_paren + 1:index])
                        search_from = index + 1
                        break
            index += 1
        else:
            fail(f"Unterminated {ANNOTATION_MARKER} in {file_name}")
    return result


def string_literals(arguments: str) -> list[str]:
    result: list[str] = []
    index = 0
    while index < len(arguments):
        if arguments[index] != '"':
            index += 1
            continue
        index += 1
        chars: list[str] = []
        escaped = False
        while index < len(arguments):
            char = arguments[index]
            if escaped:
                chars.append(char)
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                result.append("".join(chars))
                index += 1
                break
            else:
                chars.append(char)
            index += 1
        else:
            fail("Unterminated string literal inside MangaSourceParser annotation")
    return result


def infer_path_locale(relative: Path) -> str | None:
    matches = [part.lower() for part in relative.parts[:-1] if part.lower() in PATH_LOCALES]
    if not matches:
        return None
    unique = set(matches)
    if len(unique) != 1:
        fail(f"Ambiguous locale path for {relative.as_posix()}: {sorted(unique)}")
    return matches[-1]


def parse_source(arguments: str, relative: Path) -> tuple[str, str]:
    strings = string_literals(arguments)
    if len(strings) < 2:
        fail(f"Could not parse {ANNOTATION_MARKER} arguments in {relative.as_posix()}")
    name = strings[0]
    explicit_locale = strings[2].lower() if len(strings) >= 3 else None
    path_locale = infer_path_locale(relative)
    locale = explicit_locale or path_locale or "all"
    if explicit_locale is not None and path_locale in {"id", "en"} and explicit_locale != path_locale:
        fail(
            f"Locale mismatch for {name} in {relative.as_posix()}: "
            f"annotation={explicit_locale}, path={path_locale}"
        )
    return name, locale


def collect_sources(site_root: Path) -> tuple[list[str], dict[str, str], dict[str, list[str]]]:
    names: list[str] = []
    locales: dict[str, str] = {}
    files: dict[str, list[str]] = {}
    for file in sorted(site_root.rglob("*.kt")):
        relative = file.relative_to(site_root)
        blocks = annotation_blocks(file.read_text(encoding="utf-8"), relative.as_posix())
        if not blocks:
            continue
        for arguments in blocks:
            name, locale = parse_source(arguments, relative)
            if name in locales:
                fail(f"Gekkoushi contains duplicate runtime source name: {name}")
            names.append(name)
            locales[name] = locale
            files.setdefault(locale, []).append(relative.as_posix())
    return names, locales, files


def prepare(manifest: Path, uma_upstream: Path, gekkoushi_upstream: Path, pack_name: str) -> None:
    root = json.loads(manifest.read_text(encoding="utf-8"))
    if root.get("schema") != 1:
        fail("Unsupported packs.json schema")
    pack = (root.get("packs") or {}).get(pack_name)
    if not isinstance(pack, dict):
        fail(f"Unknown pack: {pack_name}")

    expected_uma = root["upstream"]["commit"]
    actual_uma = git_head(uma_upstream)
    if actual_uma != expected_uma:
        fail(f"UMA HEAD mismatch: expected {expected_uma}, got {actual_uma}")
    gekkoushi_meta = (root.get("additionalUpstreams") or {}).get("gekkoushi")
    if not isinstance(gekkoushi_meta, dict):
        fail("packs.json is missing additionalUpstreams.gekkoushi")
    expected_gekkoushi = gekkoushi_meta["commit"]
    actual_gekkoushi = git_head(gekkoushi_upstream)
    if actual_gekkoushi != expected_gekkoushi:
        fail(f"Gekkoushi HEAD mismatch: expected {expected_gekkoushi}, got {actual_gekkoushi}")

    uma_metadata_file = uma_upstream / "miyorare-pack.json"
    if not uma_metadata_file.is_file():
        fail("Prepared UMA shard metadata missing; run prepare_pack.py first")
    uma_metadata = json.loads(uma_metadata_file.read_text(encoding="utf-8"))
    language = pack["language"]
    if uma_metadata.get("language") != language:
        fail("UMA shard language does not match requested logical pack")
    uma_names = set(uma_metadata.get("sourceNames") or [])
    if not uma_names:
        fail("Prepared UMA shard contains no runtime source names")

    site_root = gekkoushi_upstream / "src/main/kotlin/tsuki/site"
    if not site_root.is_dir():
        fail(f"Gekkoushi site directory not found: {site_root}")
    compiled_names, locales, parser_files = collect_sources(site_root)
    compiled_set = set(compiled_names)
    target_names = {name for name, locale in locales.items() if locale == language}
    skipped_existing = target_names & uma_names
    exposed = target_names - uma_names
    if not exposed:
        fail(f"No new Gekkoushi {language} sources remain after UMA deduplication")
    hidden = compiled_set - exposed

    # Build Gekkoushi exactly as Gekkoushi expects. Only stale generated output is removed.
    shutil.rmtree(gekkoushi_upstream / "build", ignore_errors=True)
    summary = gekkoushi_upstream / ".github/summary.yaml"
    if summary.exists():
        summary.unlink()

    plugin_id = f"{pack['pluginId']}-gekkoushi"
    asset_name = f"miyorare-{pack_name}-gekkoushi.jar"
    metadata = {
        "schema": 2,
        "logicalPackId": pack["pluginId"],
        "logicalDisplayName": pack["displayName"],
        "shard": "gekkoushi",
        "pluginId": plugin_id,
        "displayName": f"{pack['displayName']} / Gekkoushi",
        "language": language,
        "assetName": asset_name,
        "tsukiApi": root["tsukiApi"],
        "buildUpstream": gekkoushi_meta,
        "upstreams": [gekkoushi_meta],
        "sourceFilesCount": len(set(parser_files.get(language, []))),
        "sourceCount": len(exposed),
        "sourceNames": sorted(exposed),
        "compiledSourceCount": len(compiled_set),
        "compiledSourceNames": sorted(compiled_set),
        "hiddenSupportSourceCount": len(hidden),
        "hiddenSupportSourceNames": sorted(hidden),
        "targetLanguageSourceCount": len(target_names),
        "targetLanguageSourceNames": sorted(target_names),
        "skippedExistingSourceCount": len(skipped_existing),
        "skippedExistingSourceNames": sorted(skipped_existing),
        "dedupeAuthority": "uma",
        "buildArtifact": "build/libs/gekkoushi.jar",
    }
    (gekkoushi_upstream / "miyorare-pack.json").write_text(
        json.dumps(metadata, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )
    print(
        f"Prepared {pack['displayName']} Gekkoushi shard: {len(exposed)} exposed {language} sources, "
        f"skipped {len(skipped_existing)} UMA-authoritative duplicates, "
        f"kept {len(hidden)} compiled support sources without modifying Gekkoushi code"
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--uma-upstream", type=Path, required=True)
    parser.add_argument("--gekkoushi-upstream", type=Path, required=True)
    parser.add_argument("--pack", choices=("id", "en"), required=True)
    args = parser.parse_args()
    prepare(
        args.manifest.resolve(),
        args.uma_upstream.resolve(),
        args.gekkoushi_upstream.resolve(),
        args.pack,
    )


if __name__ == "__main__":
    main()
