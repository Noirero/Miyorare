#!/usr/bin/env python3
"""Merge all Gekkoushi sources for one pack language into the curated Miyorare pack.

The existing curated UMA sources remain authoritative. Gekkoushi parser declarations with a
runtime source name already present in the curated UMA pack are removed before build, so the final
plugin contains exactly one implementation for every runtime source name.

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
    """Infer only the locale buckets relevant to this intake from a parser's path."""
    matches = [part.lower() for part in relative.parts[:-1] if part.lower() in PATH_LOCALES]
    if not matches:
        return None
    unique = set(matches)
    if len(unique) != 1:
        fail(f"Ambiguous locale path for {relative.as_posix()}: {sorted(unique)}")
    return matches[-1]


def annotation_argument_lists(content: str, file_name: str) -> list[str]:
    """Extract balanced MangaSourceParser argument lists while respecting quoted strings.

    A plain regex is unsafe here because a display name can contain parentheses, for example
    `ComicK (Unofficial)`. Parentheses inside quoted strings must not close the annotation.
    """
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


def parser_annotations(content: str, file_name: str) -> list[tuple[str, str | None]]:
    """Return runtime source key and optional explicit locale from parser annotations."""
    result: list[tuple[str, str | None]] = []
    for arguments in annotation_argument_lists(content, file_name):
        strings = STRING_RE.findall(arguments)
        if len(strings) < 2:
            fail(f"Could not parse {ANNOTATION_MARKER} arguments in {file_name}")
        source_name = strings[0]
        explicit_locale = strings[2].lower() if len(strings) >= 3 else None
        result.append((source_name, explicit_locale))
    return result


def resolved_annotations(file: Path, site_root: Path) -> list[tuple[str, str]]:
    relative_path = file.relative_to(site_root)
    relative = relative_path.as_posix()
    annotations = parser_annotations(file.read_text(encoding="utf-8"), relative)
    if not annotations:
        return []

    path_locale = infer_path_locale(relative_path)
    result: list[tuple[str, str]] = []
    for source_name, explicit_locale in annotations:
        locale = explicit_locale or path_locale
        if locale is None:
            fail(
                f"Cannot determine locale for {ANNOTATION_MARKER} {source_name} in {relative}; "
                "add an explicit locale or place it under a locale directory"
            )
        if explicit_locale is not None and path_locale in {"id", "en"} and explicit_locale != path_locale:
            fail(
                f"Locale mismatch for {source_name} in {relative}: "
                f"annotation={explicit_locale}, path={path_locale}"
            )
        result.append((source_name, locale))
    return result


def remove_empty_dirs(root: Path) -> None:
    directories = sorted(
        (path for path in root.rglob("*") if path.is_dir()),
        key=lambda path: len(path.parts),
        reverse=True,
    )
    for directory in directories:
        try:
            directory.rmdir()
        except OSError:
            pass


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
        fail(
            f"Gekkoushi HEAD mismatch: expected {expected_gekkoushi}, got {actual_gekkoushi}"
        )

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
    if not site_root.is_dir():
        fail(f"Gekkoushi site directory not found: {site_root}")

    added_names: set[str] = set()
    skipped_existing: set[str] = set()
    kept_parser_files: list[str] = []

    # Gekkoushi groups sources by parser family, then locale. Keep unannotated helpers because
    # selected parser declarations may depend on them, but remove every parser declaration that is
    # not for this pack language or that duplicates a runtime source already curated from UMA.
    for file in sorted(site_root.rglob("*.kt")):
        relative = file.relative_to(site_root).as_posix()
        annotations = resolved_annotations(file, site_root)
        if not annotations:
            continue

        locales = {locale for _, locale in annotations}
        if len(locales) != 1:
            fail(f"{relative} declares parsers with mixed locales: {sorted(locales)}")
        locale = next(iter(locales))

        names = {source_name for source_name, _ in annotations}
        if len(names) != len(annotations):
            fail(f"{relative} declares duplicate runtime source names")

        if locale != language:
            file.unlink()
            continue

        duplicate_names = names & base_source_names
        new_names = names - base_source_names
        if duplicate_names and new_names:
            fail(
                f"{relative} mixes already-curated and new Gekkoushi sources; "
                f"duplicates={sorted(duplicate_names)}, new={sorted(new_names)}"
            )
        if duplicate_names:
            skipped_existing.update(duplicate_names)
            file.unlink()
            continue

        collisions = added_names & names
        if collisions:
            fail(
                "Gekkoushi contains duplicate runtime source names for this language: "
                + ", ".join(sorted(collisions))
            )
        added_names.update(names)
        kept_parser_files.append(relative)

    remove_empty_dirs(site_root)

    if not added_names:
        fail(f"No new Gekkoushi {language} sources remain after duplicate filtering")

    # Copy the already-curated UMA language tree into an isolated source-root subdirectory. Kotlin
    # package declarations remain unchanged; the physical path only prevents file overwrites.
    uma_language_dir = uma_upstream / "src/main/kotlin/tsuki/site" / language
    if not uma_language_dir.is_dir():
        fail(f"Prepared UMA language directory missing: {uma_language_dir}")
    merged_base_dir = site_root / "_miyorare_base" / language
    if merged_base_dir.exists():
        fail(f"Unexpected merge destination already exists: {merged_base_dir}")
    merged_base_dir.parent.mkdir(parents=True, exist_ok=True)
    shutil.copytree(uma_language_dir, merged_base_dir)

    final_names: list[str] = []
    parser_files_count = 0
    wrong_locale: list[str] = []
    for file in sorted(site_root.rglob("*.kt")):
        relative = file.relative_to(site_root).as_posix()
        annotations = resolved_annotations(file, site_root)
        if not annotations:
            continue
        parser_files_count += 1
        for source_name, locale in annotations:
            if locale != language:
                wrong_locale.append(f"{source_name}:{locale}@{relative}")
            final_names.append(source_name)

    if wrong_locale:
        fail(
            "Non-target parser declarations remain after merge: "
            + ", ".join(wrong_locale[:20])
        )
    if len(final_names) != len(set(final_names)):
        duplicates = sorted({name for name in final_names if final_names.count(name) > 1})
        fail("Merged pack contains duplicate runtime source names: " + ", ".join(duplicates))
    if not base_source_names.issubset(final_names):
        missing = sorted(base_source_names - set(final_names))
        fail("Merged pack lost curated UMA runtime sources: " + ", ".join(missing))

    metadata = {
        "schema": 2,
        "pluginId": pack["pluginId"],
        "displayName": pack["displayName"],
        "language": language,
        "assetName": pack["assetName"],
        "tsukiApi": root["tsukiApi"],
        "buildUpstream": gekkoushi_meta,
        "upstreams": [root["upstream"], gekkoushi_meta],
        "sourceFilesCount": parser_files_count,
        "sourceCount": len(final_names),
        "sourceNames": sorted(final_names),
        "curatedUmaSourceCount": len(base_source_names),
        "curatedUmaSourceNames": sorted(base_source_names),
        "gekkoushiAddedSourceCount": len(added_names),
        "gekkoushiAddedSourceNames": sorted(added_names),
        "gekkoushiSkippedExistingCount": len(skipped_existing),
        "gekkoushiSkippedExistingSourceNames": sorted(skipped_existing),
        "gekkoushiParserFiles": sorted(kept_parser_files),
        "buildArtifact": "build/libs/gekkoushi.jar",
    }
    (gekkoushi_upstream / "miyorare-pack.json").write_text(
        json.dumps(metadata, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )

    print(
        f"Prepared {pack['displayName']}: {len(base_source_names)} curated UMA sources + "
        f"{len(added_names)} new Gekkoushi sources = {len(final_names)} runtime sources; "
        f"skipped {len(skipped_existing)} Gekkoushi duplicates"
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
