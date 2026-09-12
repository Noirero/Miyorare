#!/usr/bin/env python3
"""Verify Miyorare's first multi-upstream source aliases against pinned UMA and Keiyoushi trees.

This is an intake/compatibility adapter, not a source-code transpiler. It proves that an alias points
at the same selected Miyorare pack source, the expected UMA parser identity, and a Keiyoushi source
whose deterministic source id and website host match the pinned manifest.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
from pathlib import Path
from typing import NoReturn
from urllib.parse import urlparse

UMA_ANNOTATION_RE = re.compile(
    r'@MangaSourceParser\s*\(\s*"([^"]+)"\s*,\s*"([^"]+)"\s*,\s*"([^"]+)"',
    re.DOTALL,
)
EXTENSION_NAME_RE = re.compile(r'(?m)^\s*name\s*=\s*"([^"]+)"')
SOURCE_BLOCK_RE = re.compile(r'\bsource\s*\{(.*?)\}', re.DOTALL)
FIELD_STRING_RE = {
    "name": re.compile(r'(?m)^\s*name\s*=\s*"([^"]+)"'),
    "lang": re.compile(r'(?m)^\s*lang\s*=\s*"([^"]+)"'),
}
BASE_URL_RE = re.compile(r'(?m)^\s*baseUrl\s*=\s*"([^"]+)"')
SOURCE_ID_RE = re.compile(r'(?m)^\s*id\s*=\s*(\d+)L?')
VERSION_ID_RE = re.compile(r'(?m)^\s*versionId\s*=\s*(\d+)')


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
        fail(f"Could not read git HEAD for {repo}: {exc}")


def load_json(path: Path) -> dict:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        fail(f"Could not read {path}: {exc}")
    if not isinstance(value, dict):
        fail(f"{path} must contain a JSON object")
    return value


def compute_keiyoushi_source_id(name: str, lang: str, version_id: int = 1) -> int:
    # Mirrors keiyoushi/extensions-source gradle/build-logic ExtensionPlugin.computeSourceId.
    digest = hashlib.md5(f"{name.lower()}/{lang}/{version_id}".encode()).digest()
    return int.from_bytes(digest[:8], byteorder="big", signed=False) & ((1 << 63) - 1)


def host(value: str) -> str:
    parsed = urlparse(value if "://" in value else f"https://{value}")
    hostname = (parsed.hostname or "").lower().rstrip(".")
    if not hostname:
        fail(f"Invalid URL/domain: {value!r}")
    return hostname


def parse_keiyoushi_source(module: Path) -> dict:
    build_file = module / "build.gradle.kts"
    if not build_file.is_file():
        fail(f"Keiyoushi module build file missing: {build_file}")
    text = build_file.read_text(encoding="utf-8")

    extension_name_match = EXTENSION_NAME_RE.search(text)
    if extension_name_match is None:
        fail(f"Could not parse extension name from {build_file}")
    extension_name = extension_name_match.group(1)

    blocks = SOURCE_BLOCK_RE.findall(text)
    if len(blocks) != 1:
        fail(f"M2 POC expects exactly one source block in {build_file}, found {len(blocks)}")
    block = blocks[0]

    lang_match = FIELD_STRING_RE["lang"].search(block)
    base_url_match = BASE_URL_RE.search(block)
    if lang_match is None or base_url_match is None:
        fail(f"Could not parse lang/baseUrl from {build_file}")

    source_name_match = FIELD_STRING_RE["name"].search(block)
    source_name = source_name_match.group(1) if source_name_match else extension_name
    version_id_match = VERSION_ID_RE.search(block)
    version_id = int(version_id_match.group(1)) if version_id_match else 1
    explicit_id_match = SOURCE_ID_RE.search(block)
    source_id = (
        int(explicit_id_match.group(1))
        if explicit_id_match
        else compute_keiyoushi_source_id(source_name, lang_match.group(1), version_id)
    )

    return {
        "name": source_name,
        "lang": lang_match.group(1),
        "baseUrl": base_url_match.group(1),
        "sourceId": source_id,
        "sourceIdKind": "explicit" if explicit_id_match else "computed",
        "versionId": version_id,
    }


def parse_uma_source(path: Path) -> dict:
    if not path.is_file():
        fail(f"UMA source file missing: {path}")
    text = path.read_text(encoding="utf-8")
    match = UMA_ANNOTATION_RE.search(text)
    if match is None:
        fail(f"Could not parse @MangaSourceParser from {path}")
    return {
        "sourceName": match.group(1),
        "displayName": match.group(2),
        "lang": match.group(3),
        "text": text,
    }


def verify(manifest_path: Path, packs_path: Path, uma_root: Path, keiyoushi_root: Path, output: Path) -> None:
    manifest = load_json(manifest_path)
    packs = load_json(packs_path)
    if manifest.get("schema") != 1:
        fail("Unsupported multi-upstream manifest schema")
    if packs.get("schema") != 1:
        fail("Unsupported packs.json schema")

    upstreams = manifest.get("upstreams") or {}
    uma_meta = upstreams.get("uma") or {}
    kei_meta = upstreams.get("keiyoushi") or {}
    for label, meta, root in (
        ("UMA", uma_meta, uma_root),
        ("Keiyoushi", kei_meta, keiyoushi_root),
    ):
        expected = meta.get("commit")
        if not expected:
            fail(f"{label} commit is not pinned")
        actual = git_head(root)
        if actual != expected:
            fail(f"{label} HEAD mismatch: expected {expected}, got {actual}")

    pack_upstream = packs.get("upstream") or {}
    if pack_upstream.get("repository") != uma_meta.get("repository") or pack_upstream.get("commit") != uma_meta.get("commit"):
        fail("packs.json UMA pin does not match multi-upstream manifest")

    aliases = manifest.get("aliases")
    if not isinstance(aliases, list) or not aliases:
        fail("multi-upstream manifest must contain aliases")

    seen_canonical: set[str] = set()
    seen_source_ids: set[int] = set()
    normalized: list[dict] = []
    pack_defs = packs.get("packs") or {}

    for raw in aliases:
        canonical = raw["canonicalId"]
        language = raw["language"]
        verified_domain = host(raw["verifiedDomain"])
        official = raw["official"]
        uma = raw["uma"]
        kei = raw["keiyoushi"]

        if canonical in seen_canonical:
            fail(f"Duplicate canonical alias: {canonical}")
        seen_canonical.add(canonical)

        expected_canonical = f"miyorare:{official['pluginId']}:{official['sourceName']}"
        if canonical != expected_canonical:
            fail(f"Canonical id mismatch for {canonical}: expected {expected_canonical}")

        pack_name = official["pack"]
        pack = pack_defs.get(pack_name)
        if not isinstance(pack, dict):
            fail(f"Unknown official pack {pack_name} for {canonical}")
        if pack.get("pluginId") != official["pluginId"] or pack.get("language") != language:
            fail(f"Official pack metadata mismatch for {canonical}")

        uma_file = uma_root / uma["file"]
        if Path(uma["file"]).name not in set(pack.get("sources") or []):
            fail(f"{canonical}: UMA file is not selected by official pack {pack_name}")
        uma_source = parse_uma_source(uma_file)
        if uma_source["sourceName"] != uma["sourceName"] or uma_source["sourceName"] != official["sourceName"]:
            fail(f"{canonical}: UMA source identity mismatch")
        if uma_source["lang"] != language:
            fail(f"{canonical}: UMA language mismatch")
        if f'"{verified_domain}"' not in uma_source["text"]:
            fail(f"{canonical}: verified domain {verified_domain} not found in UMA source")

        kei_module = keiyoushi_root / kei["module"]
        kei_source = parse_keiyoushi_source(kei_module)
        if kei_source["name"] != kei["sourceName"]:
            fail(f"{canonical}: Keiyoushi source name mismatch: {kei_source['name']!r}")
        if kei_source["lang"] != language:
            fail(f"{canonical}: Keiyoushi language mismatch")
        if host(kei_source["baseUrl"]) != verified_domain:
            fail(
                f"{canonical}: Keiyoushi host {host(kei_source['baseUrl'])} "
                f"does not match verified host {verified_domain}"
            )
        if kei_source["sourceId"] != int(kei["sourceId"]):
            fail(
                f"{canonical}: Keiyoushi source id mismatch: "
                f"expected {kei['sourceId']}, got {kei_source['sourceId']}"
            )
        if kei_source["sourceId"] in seen_source_ids:
            fail(f"Duplicate Keiyoushi source id: {kei_source['sourceId']}")
        seen_source_ids.add(kei_source["sourceId"])

        normalized.append(
            {
                "canonicalId": canonical,
                "language": language,
                "domain": verified_domain,
                "officialStoredName": f"TSUKI:MIYORARE:{official['pluginId']}:{official['sourceName']}",
                "umaStoredName": f"TSUKI:UMA:{uma['pluginId']}:{uma['sourceName']}",
                "mihonStoredPrefix": f"MIHON_{kei_source['sourceId']}:",
                "keiyoushi": {
                    "module": kei["module"],
                    "name": kei_source["name"],
                    "sourceId": kei_source["sourceId"],
                    "sourceIdKind": kei_source["sourceIdKind"],
                    "baseUrl": kei_source["baseUrl"],
                },
                "uma": {
                    "file": uma["file"],
                    "displayName": uma_source["displayName"],
                },
            }
        )

    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(
        json.dumps(
            {
                "schema": 1,
                "aliases": sorted(normalized, key=lambda item: item["canonicalId"]),
            },
            indent=2,
            ensure_ascii=False,
        )
        + "\n",
        encoding="utf-8",
    )
    print(
        f"Verified {len(normalized)} multi-upstream aliases "
        f"({sum(item['language'] == 'id' for item in normalized)} ID / "
        f"{sum(item['language'] == 'en' for item in normalized)} EN)"
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--packs", type=Path, required=True)
    parser.add_argument("--uma", type=Path, required=True)
    parser.add_argument("--keiyoushi", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    verify(
        args.manifest.resolve(),
        args.packs.resolve(),
        args.uma.resolve(),
        args.keiyoushi.resolve(),
        args.output.resolve(),
    )


if __name__ == "__main__":
    main()
