#!/usr/bin/env python3
"""Merge Gekkoushi ID/EN sources with curated UMA into one physical Miyorare JAR.

The curated UMA implementation remains authoritative for runtime source keys it already provides.
Gekkoushi contributes every other source for the selected language. UMA internals are namespace-
isolated inside the combined project so both upstream codebases can coexist without class/package
collisions. Gekkoushi classes needed by shared parsers remain available, but only the selected
language's non-duplicate @MangaSourceParser annotations stay registered with KSP.

Both upstream checkouts must already exist at the exact commits recorded in packs.json. The script
does not download anything.
"""

from __future__ import annotations

import argparse
import json
import re
import shutil
import subprocess
from pathlib import Path
from typing import NoReturn


ANNOTATION_MARKER = "@MangaSourceParser"
STRING_RE = re.compile(r'"((?:\\.|[^"\\])*)"')
PATH_LOCALES = {"id", "en", "all"}
UMA_INTERNAL_PREFIXES = ("parsers", "site", "util")
UMA_NAMESPACE = "miyorare.uma"


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


def infer_path_locale(relative: Path) -> str | None:
    """Infer only locale buckets relevant to this intake from a parser path."""
    matches = [part.lower() for part in relative.parts[:-1] if part.lower() in PATH_LOCALES]
    if not matches:
        return None
    unique = set(matches)
    if len(unique) != 1:
        fail(f"Ambiguous locale path for {relative.as_posix()}: {sorted(unique)}")
    return matches[-1]


def annotation_blocks(content: str, file_name: str) -> list[tuple[int, int, str]]:
    """Extract balanced MangaSourceParser argument lists and source spans."""
    result: list[tuple[int, int, str]] = []
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
                        result.append((marker, index + 1, content[open_paren + 1:index]))
                        search_from = index + 1
                        break
            index += 1
        else:
            fail(f"Unterminated {ANNOTATION_MARKER} in {file_name}")
    return result


def parsed_annotation(arguments: str, file_name: str) -> tuple[str, str | None]:
    strings = STRING_RE.findall(arguments)
    if len(strings) < 2:
        fail(f"Could not parse {ANNOTATION_MARKER} arguments in {file_name}")
    source_name = strings[0]
    explicit_locale = strings[2].lower() if len(strings) >= 3 else None
    return source_name, explicit_locale


def resolve_locale(
    source_name: str,
    explicit_locale: str | None,
    path_locale: str | None,
    relative: str,
) -> str:
    locale = explicit_locale or path_locale
    if locale is None:
        # Some shared Gekkoushi parsers intentionally omit a locale. They are not eligible for the
        # ID/EN intake unless the path or annotation identifies the locale explicitly.
        return "all"
    if explicit_locale is not None and path_locale in {"id", "en"} and explicit_locale != path_locale:
        fail(
            f"Locale mismatch for {source_name} in {relative}: "
            f"annotation={explicit_locale}, path={path_locale}"
        )
    return locale


def resolved_annotation_blocks(
    content: str,
    relative_path: Path,
) -> list[tuple[int, int, str, str]]:
    relative = relative_path.as_posix()
    path_locale = infer_path_locale(relative_path)
    result: list[tuple[int, int, str, str]] = []
    for start, end, arguments in annotation_blocks(content, relative):
        source_name, explicit_locale = parsed_annotation(arguments, relative)
        locale = resolve_locale(source_name, explicit_locale, path_locale, relative)
        result.append((start, end, source_name, locale))
    return result


def blank_span(content: str, start: int, end: int) -> str:
    """Neutralize code while preserving line layout for readable compiler diagnostics."""
    replacement = "".join("\n" if char == "\n" else " " for char in content[start:end])
    return content[:start] + replacement + content[end:]


def filter_gekkoushi_annotations(
    site_root: Path,
    language: str,
    base_source_names: set[str],
) -> tuple[set[str], set[str], list[str]]:
    """Keep only new target-language Gekkoushi source registrations.

    Parser classes themselves are retained. This is deliberate: shared parser families can depend on
    sibling classes from other locales. Removing only the registration annotation keeps the original
    Gekkoushi dependency graph compile-safe while KSP sees exactly the intended Miyorare source set.
    """
    added_names: set[str] = set()
    skipped_existing: set[str] = set()
    kept_parser_files: list[str] = []

    for file in sorted(site_root.rglob("*.kt")):
        relative_path = file.relative_to(site_root)
        relative = relative_path.as_posix()
        content = file.read_text(encoding="utf-8")
        blocks = resolved_annotation_blocks(content, relative_path)
        if not blocks:
            continue

        keep_in_file = False
        for start, end, source_name, locale in reversed(blocks):
            keep = locale == language and source_name not in base_source_names
            if keep:
                if source_name in added_names:
                    fail(
                        "Gekkoushi contains duplicate runtime source names for this language: "
                        + source_name
                    )
                added_names.add(source_name)
                keep_in_file = True
                continue

            if locale == language and source_name in base_source_names:
                skipped_existing.add(source_name)
            content = blank_span(content, start, end)

        file.write_text(content, encoding="utf-8")
        if keep_in_file:
            kept_parser_files.append(relative)

    return added_names, skipped_existing, kept_parser_files


def namespace_uma_content(content: str) -> str:
    """Move only UMA-owned internal packages below miyorare.uma.

    Tsuki API packages such as tsuki.model, tsuki.core, tsuki.network and tsuki.MangaLoaderContext
    remain untouched. This prevents UMA helpers/parser classes from colliding with Gekkoushi's own
    internal helpers while still producing one physical plugin JAR.
    """
    for prefix in UMA_INTERNAL_PREFIXES:
        content = re.sub(
            rf"\btsuki\.{re.escape(prefix)}\b",
            f"{UMA_NAMESPACE}.{prefix}",
            content,
        )
    return content


def copy_namespaced_uma(uma_upstream: Path, gekkoushi_upstream: Path) -> list[str]:
    """Copy the prepared UMA Kotlin tree into an isolated package namespace."""
    source_root = uma_upstream / "src/main/kotlin/tsuki"
    if not source_root.is_dir():
        fail(f"Prepared UMA Kotlin root missing: {source_root}")

    destination_root = gekkoushi_upstream / "src/main/kotlin/miyorare/uma"
    if destination_root.exists():
        fail(f"Unexpected UMA merge destination already exists: {destination_root}")
    destination_root.mkdir(parents=True, exist_ok=False)

    copied: list[str] = []
    for source in sorted(source_root.rglob("*.kt")):
        relative = source.relative_to(source_root)
        destination = destination_root / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        content = namespace_uma_content(source.read_text(encoding="utf-8"))
        destination.write_text(content, encoding="utf-8")
        copied.append(relative.as_posix())

    if not copied:
        fail("Prepared UMA tree contains no Kotlin files")
    return copied


def collect_registered_sources(kotlin_root: Path) -> tuple[list[str], list[str]]:
    names: list[str] = []
    files: list[str] = []
    for file in sorted(kotlin_root.rglob("*.kt")):
        content = file.read_text(encoding="utf-8")
        blocks = annotation_blocks(content, file.relative_to(kotlin_root).as_posix())
        if not blocks:
            continue
        files.append(file.relative_to(kotlin_root).as_posix())
        for _, _, arguments in blocks:
            source_name, _ = parsed_annotation(arguments, file.relative_to(kotlin_root).as_posix())
            names.append(source_name)
    return names, files


def load_manifest(path: Path, pack_name: str) -> tuple[dict, dict, dict]:
    root = json.loads(path.read_text(encoding="utf-8"))
    if root.get("schema") != 1:
        fail("Unsupported packs.json schema")
    pack = (root.get("packs") or {}).get(pack_name)
    if not isinstance(pack, dict):
        fail(f"Unknown pack: {pack_name}")
    if "gekkoushi" not in (pack.get("includeAllFrom") or []):
        fail(f"Pack {pack_name} does not opt in to the Gekkoushi all-source merge")
    gekkoushi_meta = (root.get("additionalUpstreams") or {}).get("gekkoushi")
    if not isinstance(gekkoushi_meta, dict):
        fail("packs.json is missing additionalUpstreams.gekkoushi")
    return root, pack, gekkoushi_meta


def prepare(
    manifest: Path,
    uma_upstream: Path,
    gekkoushi_upstream: Path,
    pack_name: str,
) -> None:
    root, pack, gekkoushi_meta = load_manifest(manifest, pack_name)
    language = pack["language"]

    expected_uma = root["upstream"]["commit"]
    actual_uma = git_head(uma_upstream)
    if actual_uma != expected_uma:
        fail(f"UMA HEAD mismatch: expected {expected_uma}, got {actual_uma}")

    expected_gekkoushi = gekkoushi_meta["commit"]
    actual_gekkoushi = git_head(gekkoushi_upstream)
    if actual_gekkoushi != expected_gekkoushi:
        fail(f"Gekkoushi HEAD mismatch: expected {expected_gekkoushi}, got {actual_gekkoushi}")

    base_metadata_file = uma_upstream / "miyorare-pack.json"
    if not base_metadata_file.is_file():
        fail("Curated UMA metadata missing; run prepare_pack.py first")
    base_metadata = json.loads(base_metadata_file.read_text(encoding="utf-8"))
    if base_metadata.get("language") != language:
        fail("Curated UMA metadata language does not match requested pack")

    base_source_names = set(base_metadata.get("sourceNames") or [])
    if not base_source_names:
        fail("Curated UMA metadata contains no runtime source names")

    site_root = gekkoushi_upstream / "src/main/kotlin/tsuki/site"
    kotlin_root = gekkoushi_upstream / "src/main/kotlin"
    if not site_root.is_dir():
        fail(f"Gekkoushi site directory not found: {site_root}")

    added_names, skipped_existing, kept_parser_files = filter_gekkoushi_annotations(
        site_root,
        language,
        base_source_names,
    )
    if not added_names:
        fail(f"No new Gekkoushi {language} sources remain after duplicate filtering")

    copied_uma_files = copy_namespaced_uma(uma_upstream, gekkoushi_upstream)

    # Never let an old local build contaminate KSP source discovery/provenance.
    shutil.rmtree(gekkoushi_upstream / "build", ignore_errors=True)
    summary = gekkoushi_upstream / ".github/summary.yaml"
    if summary.exists():
        summary.unlink()

    final_names, parser_files = collect_registered_sources(kotlin_root)
    final_set = set(final_names)
    expected_set = base_source_names | added_names

    if len(final_names) != len(final_set):
        duplicates = sorted({name for name in final_names if final_names.count(name) > 1})
        fail("Merged pack contains duplicate runtime source names: " + ", ".join(duplicates))
    missing = sorted(expected_set - final_set)
    unexpected = sorted(final_set - expected_set)
    if missing or unexpected:
        details: list[str] = []
        if missing:
            details.append("missing: " + ", ".join(missing))
        if unexpected:
            details.append("unexpected: " + ", ".join(unexpected))
        fail("Merged parser registration set is inconsistent (" + "; ".join(details) + ")")

    metadata = {
        "schema": 2,
        "pluginId": pack["pluginId"],
        "displayName": pack["displayName"],
        "language": language,
        "assetName": pack["assetName"],
        "tsukiApi": root["tsukiApi"],
        "buildUpstream": gekkoushi_meta,
        "upstreams": [root["upstream"], gekkoushi_meta],
        "sourceFilesCount": len(parser_files),
        "sourceCount": len(final_names),
        "sourceNames": sorted(final_names),
        "curatedUmaSourceCount": len(base_source_names),
        "curatedUmaSourceNames": sorted(base_source_names),
        "gekkoushiAddedSourceCount": len(added_names),
        "gekkoushiAddedSourceNames": sorted(added_names),
        "gekkoushiSkippedExistingCount": len(skipped_existing),
        "gekkoushiSkippedExistingSourceNames": sorted(skipped_existing),
        "gekkoushiParserFiles": sorted(kept_parser_files),
        "umaNamespacedKotlinFiles": copied_uma_files,
        "mergeStrategy": "single-jar-namespaced-uma",
        "buildArtifact": "build/libs/gekkoushi.jar",
    }
    (gekkoushi_upstream / "miyorare-pack.json").write_text(
        json.dumps(metadata, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )

    print(
        f"Prepared {pack['displayName']}: {len(base_source_names)} curated UMA sources + "
        f"{len(added_names)} new Gekkoushi sources = {len(final_names)} runtime sources; "
        f"skipped {len(skipped_existing)} Gekkoushi duplicates; "
        f"UMA isolated under {UMA_NAMESPACE}"
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
