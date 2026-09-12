#!/usr/bin/env python3
"""Prepare one curated Miyorare Tsuki source pack from a pinned UMA checkout.

The upstream checkout is modified in place. The script never downloads anything itself: CI or the
maintainer must provide the exact upstream checkout declared in packs.json.
"""

from __future__ import annotations

import argparse
import json
import re
import shutil
import subprocess
from pathlib import Path
from typing import NoReturn


ANNOTATION_RE = re.compile(r"@MangaSourceParser\s*\((.*?)\)", re.DOTALL)
STRING_RE = re.compile(r'"((?:\\.|[^"\\])*)"')


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
        fail(f"Could not read upstream git HEAD: {exc}")


def load_manifest(path: Path, pack_name: str) -> tuple[dict, dict]:
    root = json.loads(path.read_text(encoding="utf-8"))
    if root.get("schema") != 1:
        fail("Unsupported packs.json schema")
    packs = root.get("packs") or {}
    pack = packs.get(pack_name)
    if not isinstance(pack, dict):
        fail(f"Unknown pack: {pack_name}")
    return root, pack


def parser_annotations(content: str, file_name: str) -> list[tuple[str, str]]:
    """Return (runtime source name, locale) pairs declared by one curated Kotlin file."""
    result: list[tuple[str, str]] = []
    for match in ANNOTATION_RE.finditer(content):
        strings = STRING_RE.findall(match.group(1))
        if len(strings) < 3:
            fail(f"Could not parse @MangaSourceParser arguments in {file_name}")
        result.append((strings[0], strings[2]))
    return result


def prune_unselected_parser_files(language_dir: Path, requested: list[str]) -> None:
    """Remove parser declarations not selected by the pack, including nested source folders.

    UMA keeps some sources in nested locale subdirectories. Removing only top-level ``*.kt`` files
    leaves those parsers visible to KSP. Keep unannotated helper files so selected parsers can retain
    locale-specific support code, but no unselected runtime source declaration may survive.
    """
    requested_paths = {(language_dir / name).resolve() for name in requested}
    for file in sorted(language_dir.rglob("*.kt")):
        if file.resolve() in requested_paths:
            continue
        content = file.read_text(encoding="utf-8")
        if parser_annotations(content, file.relative_to(language_dir).as_posix()):
            file.unlink()

    # Remove directories made empty by parser pruning without touching directories that still contain
    # helper code/resources used by the curated sources.
    directories = sorted(
        (path for path in language_dir.rglob("*") if path.is_dir()),
        key=lambda path: len(path.parts),
        reverse=True,
    )
    for directory in directories:
        try:
            directory.rmdir()
        except OSError:
            pass


def assert_only_requested_parsers_remain(language_dir: Path, requested: list[str]) -> None:
    requested_paths = {(language_dir / name).resolve() for name in requested}
    unexpected_files: list[str] = []
    for file in sorted(language_dir.rglob("*.kt")):
        annotations = parser_annotations(
            file.read_text(encoding="utf-8"),
            file.relative_to(language_dir).as_posix(),
        )
        if annotations and file.resolve() not in requested_paths:
            unexpected_files.append(file.relative_to(language_dir).as_posix())
    if unexpected_files:
        fail(
            "Unselected parser files remain after pruning: "
            + ", ".join(unexpected_files)
        )


def validate_requested_paths(language_dir: Path, requested: list[str], pack_name: str) -> None:
    """Allow safe relative Kotlin paths, including nested nsfw/mangabox source directories."""
    root = language_dir.resolve()
    for name in requested:
        if not isinstance(name, str) or not name or "\\" in name:
            fail(f"Pack {pack_name} contains an invalid source path")
        relative = Path(name)
        if relative.is_absolute() or relative.suffix != ".kt" or any(part in ("", ".", "..") for part in relative.parts):
            fail(f"Pack {pack_name} contains an invalid source path: {name}")
        try:
            (language_dir / relative).resolve().relative_to(root)
        except ValueError:
            fail(f"Pack {pack_name} source escapes the language directory: {name}")


def prepare(manifest: Path, upstream: Path, pack_name: str) -> None:
    root, pack = load_manifest(manifest, pack_name)
    upstream_meta = root["upstream"]
    expected_commit = upstream_meta["commit"]
    actual_commit = git_head(upstream)
    if actual_commit != expected_commit:
        fail(f"Upstream HEAD mismatch: expected {expected_commit}, got {actual_commit}")

    site_root = upstream / "src/main/kotlin/tsuki/site"
    if not site_root.is_dir():
        fail(f"UMA site directory not found: {site_root}")

    language = pack["language"]
    language_dir = site_root / language
    if not language_dir.is_dir():
        fail(f"Language directory does not exist upstream: {language}")

    requested = pack.get("sources") or []
    if not requested or len(requested) != len(set(requested)):
        fail(f"Pack {pack_name} must contain a non-empty unique source list")
    validate_requested_paths(language_dir, requested, pack_name)

    available = {
        file.relative_to(language_dir).as_posix()
        for file in language_dir.rglob("*.kt")
        if file.is_file()
    }
    missing = sorted(set(requested) - available)
    if missing:
        fail(f"Pack {pack_name} references missing upstream sources: {', '.join(missing)}")

    # Remove every other language. Shared parser/util code remains untouched.
    for child in list(site_root.iterdir()):
        if child.name == language:
            continue
        if child.is_dir():
            shutil.rmtree(child)
        else:
            child.unlink()

    prune_unselected_parser_files(language_dir, requested)
    assert_only_requested_parsers_remain(language_dir, requested)

    # One Kotlin file can declare several runtime sources. Record the actual annotation names so the
    # finalizer can compare KSP output exactly instead of incorrectly equating file count to source count.
    source_names: list[str] = []
    for name in requested:
        content = (language_dir / name).read_text(encoding="utf-8")
        annotations = parser_annotations(content, name)
        if not annotations:
            fail(f"{name} has no parseable @MangaSourceParser annotation")
        for source_name, locale in annotations:
            if locale != language:
                fail(f"{name} declares {source_name} with locale {locale!r}, expected {language!r}")
            source_names.append(source_name)

    if len(source_names) != len(set(source_names)):
        duplicates = sorted({name for name in source_names if source_names.count(name) > 1})
        fail(f"Pack {pack_name} contains duplicate runtime source names: {', '.join(duplicates)}")

    metadata = {
        "schema": 1,
        "pluginId": pack["pluginId"],
        "displayName": pack["displayName"],
        "language": language,
        "assetName": pack["assetName"],
        "tsukiApi": root["tsukiApi"],
        "upstream": upstream_meta,
        "sourceFilesCount": len(requested),
        "sourceCount": len(source_names),
        "sourceFiles": requested,
        "sourceNames": sorted(source_names),
    }
    (upstream / "miyorare-pack.json").write_text(
        json.dumps(metadata, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )
    print(
        f"Prepared {pack['displayName']} ({len(requested)} files / {len(source_names)} runtime sources) from "
        f"{upstream_meta['repository']}@{expected_commit[:12]}"
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--upstream", type=Path, required=True)
    parser.add_argument("--pack", choices=("id", "en"), required=True)
    args = parser.parse_args()
    prepare(args.manifest.resolve(), args.upstream.resolve(), args.pack)


if __name__ == "__main__":
    main()
