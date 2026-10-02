#!/usr/bin/env python3
"""Validates the Miyorare Audio Pack catalog consumed by Hiraukan.

Schema v1 rules:
- Pack level: schemaVersion == 1, packId == "miyorare-audio",
  mediaType == "audio", consumer.repository == "Noirero/Hiraukan",
  consumer.extensionSchemaVersion == 1, at least one extension.
- Per extension: unique non-empty id, type == "audio", non-empty name,
  auth in {none, optional, required}, version in MAJOR.MINOR.PATCH,
  non-empty languages list of language codes, capabilities subset of the
  known set, delivery.kind == "builtin" (v1 forbids downloading or
  executing unsigned external code), delivery.runtimeId == id and prefixed
  with "miyorare.audio.".
- Optional Hiraukan cross-check (--hiraukan-dir): every runtimeId must
  resolve to a runtime actually bundled in Hiraukan. The "miyorare.audio."
  prefix is stripped and the remainder must match a registered
  UnifiedSourceKind id in lib/src/sources/unified_source_models.dart.
  This is fail-closed: a declared-but-unbundled runtime fails validation
  instead of shipping a dead catalog entry.

Usage:
    validate_pack.py <pack.json> [--hiraukan-dir <path-to-hiraukan-checkout>]
"""
import argparse
import json
import re
import sys
from pathlib import Path

ALLOWED_AUTH = {"none", "optional", "required"}
ALLOWED_CAPABILITIES = {
    "catalog",
    "search",
    "detail",
    "playback",
    "download",
    "subtitles",
}
VERSION_RE = re.compile(r"^\d+\.\d+\.\d+$")
LANG_RE = re.compile(r"^[a-z]{2,8}$")
RUNTIME_ID_PREFIX = "miyorare.audio."
# Matches the arms inside `String get id => switch (this) { ... };` in
# lib/src/sources/unified_source_models.dart, e.g.
#   UnifiedSourceKind.asmrOne => 'asmr_one',
# Scoped to the id getter so the label getter's arms ('ASMR.one', ...)
# are not picked up.
SOURCE_ID_BLOCK_RE = re.compile(
    r"String get id\s*=>\s*switch\s*\(this\)\s*\{(.*?)\}\s*;", re.DOTALL
)
SOURCE_ID_ARM_RE = re.compile(r"UnifiedSourceKind\.\w+\s*=>\s*'([^']+)'")


def fail(message: str) -> None:
    raise SystemExit(message)


def load_hiraukan_source_ids(hiraukan_dir: Path) -> set[str]:
    """Returns the set of source ids Hiraukan actually bundles."""
    models = (
        hiraukan_dir / "lib" / "src" / "sources" / "unified_source_models.dart"
    )
    if not models.is_file():
        fail(f"hiraukan cross-check: not found: {models}")
    text = models.read_text(encoding="utf-8")
    block = SOURCE_ID_BLOCK_RE.search(text)
    if not block:
        fail(f"hiraukan cross-check: id getter not found in {models}")
    ids = set(SOURCE_ID_ARM_RE.findall(block.group(1)))
    if not ids:
        fail(f"hiraukan cross-check: no source ids parsed from {models}")
    return ids


def validate(path: Path, hiraukan_dir: Path | None = None) -> None:
    data = json.loads(path.read_text(encoding="utf-8"))
    if data.get("schemaVersion") != 1:
        fail("unsupported audio pack schemaVersion")
    if data.get("packId") != "miyorare-audio":
        fail("unexpected audio pack id")
    if data.get("mediaType") != "audio":
        fail("audio pack mediaType must be audio")

    consumer = data.get("consumer")
    if not isinstance(consumer, dict):
        fail("consumer object is required")
    if consumer.get("repository") != "Noirero/Hiraukan":
        fail("audio pack consumer must be Noirero/Hiraukan")
    if consumer.get("extensionSchemaVersion") != 1:
        fail("unsupported Hiraukan extension schema version")

    extensions = data.get("extensions")
    if not isinstance(extensions, list) or not extensions:
        fail("audio pack must contain at least one extension")

    hiraukan_ids = (
        load_hiraukan_source_ids(hiraukan_dir) if hiraukan_dir is not None else None
    )

    seen: set[str] = set()
    for item in extensions:
        if not isinstance(item, dict):
            fail("extension entries must be objects")
        extension_id = item.get("id")
        if not isinstance(extension_id, str) or not extension_id:
            fail("extension id is required")
        if extension_id in seen:
            fail(f"duplicate extension id: {extension_id}")
        seen.add(extension_id)
        if item.get("type") != "audio":
            fail(f"{extension_id}: type must be audio")

        name = item.get("name")
        if not isinstance(name, str) or not name.strip():
            fail(f"{extension_id}: name is required")

        version = item.get("version")
        if not isinstance(version, str) or not VERSION_RE.match(version):
            fail(
                f"{extension_id}: version must be MAJOR.MINOR.PATCH "
                f"(got {version!r})"
            )

        if item.get("auth") not in ALLOWED_AUTH:
            fail(f"{extension_id}: unsupported auth mode")

        languages = item.get("languages")
        if (
            not isinstance(languages, list)
            or not languages
            or not all(isinstance(l, str) and LANG_RE.match(l) for l in languages)
        ):
            fail(
                f"{extension_id}: languages must be a non-empty list "
                f"of language codes (got {languages!r})"
            )

        capabilities = item.get("capabilities")
        if not isinstance(capabilities, list) or not capabilities:
            fail(f"{extension_id}: capabilities are required")
        unknown = set(capabilities) - ALLOWED_CAPABILITIES
        if unknown:
            fail(f"{extension_id}: unsupported capabilities: {sorted(unknown)}")

        delivery = item.get("delivery")
        if not isinstance(delivery, dict):
            fail(f"{extension_id}: delivery is required")
        if delivery.get("kind") != "builtin":
            fail(f"{extension_id}: schema v1 only allows builtin delivery")
        runtime_id = delivery.get("runtimeId")
        if runtime_id != extension_id:
            fail(f"{extension_id}: runtimeId must equal extension id")
        if not runtime_id.startswith(RUNTIME_ID_PREFIX):
            fail(
                f"{extension_id}: runtimeId must start with "
                f"{RUNTIME_ID_PREFIX!r}"
            )
        if hiraukan_ids is not None:
            suffix = runtime_id[len(RUNTIME_ID_PREFIX) :]
            if suffix not in hiraukan_ids:
                fail(
                    f"{extension_id}: runtime {suffix!r} is not bundled in "
                    f"Hiraukan (bundled: {sorted(hiraukan_ids)}). "
                    f"Implement the runtime or remove the entry."
                )


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(
        description="Validate the Miyorare Audio Pack catalog."
    )
    parser.add_argument("pack", help="path to pack.json")
    parser.add_argument(
        "--hiraukan-dir",
        default=None,
        help="path to a Noirero/Hiraukan checkout; enables the "
        "fail-closed check that every runtimeId is actually bundled",
    )
    args = parser.parse_args(argv)
    hiraukan_dir = Path(args.hiraukan_dir) if args.hiraukan_dir else None
    validate(Path(args.pack), hiraukan_dir)


if __name__ == "__main__":
    main()
