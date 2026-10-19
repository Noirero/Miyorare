#!/usr/bin/env python3
"""Apply Miyorare-owned overlays to a disposable pinned Gekkoushi checkout."""

from __future__ import annotations

import hashlib
import json
import shutil
from pathlib import Path
from typing import NoReturn


REPLACEMENT_MANIFEST = "_replacements.json"
REPLACEMENT_DIR = "_replacements"


def fail(message: str) -> NoReturn:
    raise SystemExit(message)


def git_blob_sha(path: Path) -> str:
    data = path.read_bytes()
    header = f"blob {len(data)}\0".encode("ascii")
    return hashlib.sha1(header + data).hexdigest()


def safe_relative(value: object, *, label: str) -> Path:
    if not isinstance(value, str) or not value.strip():
        fail(f"{label} must be a non-empty relative path")
    path = Path(value)
    if path.is_absolute() or ".." in path.parts:
        fail(f"{label} must stay inside the overlay/upstream root: {value}")
    return path


def replacement_entries(source_root: Path) -> list[dict[str, str]]:
    manifest = source_root / REPLACEMENT_MANIFEST
    replacement_root = source_root / REPLACEMENT_DIR
    if not manifest.is_file():
        if replacement_root.is_dir() and any(replacement_root.rglob("*.kt")):
            fail(f"{replacement_root} contains replacement files but {manifest.name} is missing")
        return []

    data = json.loads(manifest.read_text(encoding="utf-8"))
    if data.get("schema") != 1:
        fail(f"Unsupported Gekkoushi replacement overlay schema: {data.get('schema')!r}")
    rows = data.get("replacements")
    if not isinstance(rows, list) or not rows:
        fail("Gekkoushi replacement manifest must contain a non-empty replacements list")

    entries: list[dict[str, str]] = []
    declared_sources: set[str] = set()
    declared_targets: set[str] = set()
    for index, row in enumerate(rows):
        if not isinstance(row, dict):
            fail(f"replacement[{index}] must be an object")
        source = safe_relative(row.get("source"), label=f"replacement[{index}].source")
        target = safe_relative(row.get("target"), label=f"replacement[{index}].target")
        expected = row.get("expectedBlobSha")
        if not isinstance(expected, str) or len(expected) != 40 or any(c not in "0123456789abcdef" for c in expected):
            fail(f"replacement[{index}].expectedBlobSha must be a lowercase 40-character Git blob SHA")
        if not source.parts or source.parts[0] != REPLACEMENT_DIR or source.suffix != ".kt":
            fail(f"replacement[{index}].source must be a Kotlin file under {REPLACEMENT_DIR}/")
        if target.suffix != ".kt":
            fail(f"replacement[{index}].target must be a Kotlin source path")
        source_key = source.as_posix()
        target_key = target.as_posix()
        if source_key in declared_sources:
            fail(f"duplicate replacement source: {source_key}")
        if target_key in declared_targets:
            fail(f"duplicate replacement target: {target_key}")
        declared_sources.add(source_key)
        declared_targets.add(target_key)
        entries.append({"source": source_key, "target": target_key, "expectedBlobSha": expected})

    actual_sources = {
        path.relative_to(source_root).as_posix()
        for path in replacement_root.rglob("*.kt")
        if path.is_file()
    } if replacement_root.is_dir() else set()
    if actual_sources != declared_sources:
        missing = sorted(declared_sources - actual_sources)
        orphaned = sorted(actual_sources - declared_sources)
        fail(f"replacement manifest/source mismatch: missing={missing}, orphaned={orphaned}")
    return entries


def apply_miyorare_overlays(miyorare_sources_root: Path, gekkoushi_upstream: Path, pack_name: str) -> list[str]:
    """Copy additive overlays and exact-blob replacement overlays into a disposable checkout."""
    source_root = miyorare_sources_root / "overlays" / "gekkoushi" / pack_name
    if not source_root.is_dir():
        return []

    site_root = gekkoushi_upstream / "src/main/kotlin/tsuki/site"
    direct_target_root = site_root / pack_name
    applied: list[str] = []

    replacements = replacement_entries(source_root)
    for source in sorted(source_root.rglob("*.kt")):
        relative = source.relative_to(source_root)
        if relative.parts and relative.parts[0] == REPLACEMENT_DIR:
            continue
        target = direct_target_root / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)
        applied.append(relative.as_posix())

    for row in replacements:
        source = source_root / row["source"]
        target = site_root / row["target"]
        if not target.is_file():
            fail(f"Gekkoushi replacement target is missing: {row['target']}")
        actual_blob = git_blob_sha(target)
        if actual_blob != row["expectedBlobSha"]:
            fail(
                "Gekkoushi replacement target drifted: "
                f"{row['target']} expected {row['expectedBlobSha']} got {actual_blob}"
            )
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)
        applied.append(f"replace:{row['target']}")

    return applied
