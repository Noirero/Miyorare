#!/usr/bin/env python3
"""Prepare the curated UMA shard for one logical Miyorare ID/EN source pack.

The upstream UMA checkout is modified in place using UMA's own source tree and build system. No
Gekkoushi code is copied into this checkout. CI must provide the exact pinned upstream revision.
"""

from __future__ import annotations

import argparse
import json
import re
import shutil
import subprocess
from pathlib import Path
from typing import NoReturn

from source_icon_metadata import extract_source_icon_urls


ANNOTATION_RE = re.compile(r"@MangaSourceParser\s*\((.*?)\)", re.DOTALL)
STRING_RE = re.compile(r'"((?:\\.|[^"\\])*)"')
SEMANTIC_ADAPTER_FILE = "miyorare-semantic-adapter.json"


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
    """Remove parser declarations not selected by the UMA shard, including nested folders."""
    requested_paths = {(language_dir / name).resolve() for name in requested}
    for file in sorted(language_dir.rglob("*.kt")):
        if file.resolve() in requested_paths:
            continue
        content = file.read_text(encoding="utf-8")
        if parser_annotations(content, file.relative_to(language_dir).as_posix()):
            file.unlink()

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
        fail("Unselected parser files remain after pruning: " + ", ".join(unexpected_files))


def validate_requested_paths(language_dir: Path, requested: list[str], pack_name: str) -> None:
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


def clean_semantic_change(change: dict) -> dict:
    """Keep auditable adapter facts without embedding arbitrary upstream source text."""
    allowed = (
        "changeClass",
        "mode",
        "kind",
        "oldHost",
        "newHost",
        "oldLiteral",
        "newLiteral",
        "replacementCount",
        "keiyoushiFile",
    )
    return {key: change.get(key) for key in allowed if key in change}


def semantic_adapter_provenance(upstream: Path, language: str, requested: list[str]) -> list[dict]:
    report_path = upstream / SEMANTIC_ADAPTER_FILE
    if not report_path.is_file():
        return []
    try:
        report = json.loads(report_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        fail(f"Could not read semantic adapter provenance: {exc}")

    schema = report.get("schema")
    if schema not in (1, 2):
        fail("Unsupported semantic adapter provenance schema")
    if report.get("state") == "blocked" or report.get("blocked"):
        fail("Blocked semantic adapter state must not enter a Source Pack build")

    adapter_name = report.get("adapter")
    if not isinstance(adapter_name, str) or not adapter_name:
        fail("Semantic adapter report is missing adapter identity")

    allowed_files = {
        (Path("src/main/kotlin/tsuki/site") / language / name).as_posix()
        for name in requested
    }
    applied: list[dict] = []
    for item in report.get("applied") or []:
        if not isinstance(item, dict):
            continue
        uma_file = item.get("umaFile")
        if uma_file not in allowed_files:
            continue

        if schema == 1:
            change = clean_semantic_change(item)
            change_classes = [item.get("changeClass")] if isinstance(item.get("changeClass"), str) else []
            changes = [change] if change else []
        else:
            raw_classes = item.get("changeClasses") or []
            change_classes = sorted({value for value in raw_classes if isinstance(value, str) and value})
            changes = [
                clean_semantic_change(change)
                for change in (item.get("changes") or [])
                if isinstance(change, dict)
            ]
            changes = [change for change in changes if change]

        applied.append(
            {
                "canonicalId": item.get("canonicalId"),
                "module": item.get("module"),
                "umaFile": uma_file,
                "changeClasses": change_classes,
                "changes": changes,
            }
        )

    if not applied:
        return []

    result = {
        "adapter": adapter_name,
        "reportSchema": schema,
        "appliedCount": len(applied),
        "applied": applied,
    }
    capabilities = report.get("capabilities")
    if isinstance(capabilities, list):
        result["capabilities"] = sorted({value for value in capabilities if isinstance(value, str) and value})
    if isinstance(report.get("base"), str):
        result["semanticBase"] = report["base"]
    if isinstance(report.get("candidate"), str):
        result["candidate"] = report["candidate"]
    return [result]


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
    semantic_adapters = semantic_adapter_provenance(upstream, language, requested)

    available = {
        file.relative_to(language_dir).as_posix()
        for file in language_dir.rglob("*.kt")
        if file.is_file()
    }
    missing = sorted(set(requested) - available)
    if missing:
        fail(f"Pack {pack_name} references missing upstream sources: {', '.join(missing)}")

    # UMA remains a compact curated shard: other languages and unselected source declarations are
    # removed only inside the disposable pinned UMA checkout used by CI.
    for child in list(site_root.iterdir()):
        if child.name == language:
            continue
        if child.is_dir():
            shutil.rmtree(child)
        else:
            child.unlink()

    prune_unselected_parser_files(language_dir, requested)
    assert_only_requested_parsers_remain(language_dir, requested)

    source_names: list[str] = []
    source_icons: dict[str, str] = {}
    for name in requested:
        content = (language_dir / name).read_text(encoding="utf-8")
        annotations = parser_annotations(content, name)
        if not annotations:
            fail(f"{name} has no parseable @MangaSourceParser annotation")
        file_source_names: list[str] = []
        for source_name, locale in annotations:
            if locale != language:
                fail(f"{name} declares {source_name} with locale {locale!r}, expected {language!r}")
            source_names.append(source_name)
            file_source_names.append(source_name)
        source_icons.update(extract_source_icon_urls(content, file_source_names))

    if len(source_names) != len(set(source_names)):
        duplicates = sorted({name for name in source_names if source_names.count(name) > 1})
        fail(f"Pack {pack_name} contains duplicate runtime source names: {', '.join(duplicates)}")

    unknown_icon_sources = set(source_icons) - set(source_names)
    if unknown_icon_sources:
        fail("Icon metadata references unknown UMA sources: " + ", ".join(sorted(unknown_icon_sources)))

    # The UMA shard keeps the logical plugin id. This lets a shard release replace an older
    # one-JAR Miyorare-ID/EN install in place while preserving enabled-source choices and stored
    # Tsuki source identities. Only the Gekkoushi shard needs a separate physical plugin id.
    plugin_id = pack["pluginId"]
    asset_name = f"miyorare-{pack_name}-uma.jar"
    metadata = {
        "schema": 2,
        "logicalPackId": pack["pluginId"],
        "logicalDisplayName": pack["displayName"],
        "shard": "uma",
        "pluginId": plugin_id,
        "displayName": f"{pack['displayName']} / UMA",
        "language": language,
        "assetName": asset_name,
        "tsukiApi": root["tsukiApi"],
        "buildUpstream": upstream_meta,
        "upstreams": [upstream_meta],
        "semanticAdapters": semantic_adapters,
        "sourceFilesCount": len(requested),
        "sourceCount": len(source_names),
        "sourceFiles": requested,
        "sourceNames": sorted(source_names),
        "sourceIcons": dict(sorted(source_icons.items())),
        "compiledSourceCount": len(source_names),
        "compiledSourceNames": sorted(source_names),
        "hiddenSupportSourceCount": 0,
        "hiddenSupportSourceNames": [],
        "buildArtifact": "build/libs/uma.jar",
    }
    (upstream / "miyorare-pack.json").write_text(
        json.dumps(metadata, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )
    print(
        f"Prepared {pack['displayName']} UMA shard "
        f"({len(requested)} files / {len(source_names)} runtime sources / {len(source_icons)} icons / "
        f"{sum(item['appliedCount'] for item in semantic_adapters)} semantic adaptations) from "
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
