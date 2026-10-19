#!/usr/bin/env python3
"""Verify Miyorare multi-upstream aliases against pinned UMA and Keiyoushi trees.

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
import sys
from pathlib import Path
from typing import NoReturn
from urllib.parse import urlparse

UMA_ANNOTATION_RE = re.compile(
    r'@MangaSourceParser\s*\(\s*"([^"]+)"\s*,\s*"([^"]+)"\s*,\s*"([^"]+)"',
    re.DOTALL,
)
QUOTED_STRING_RE = re.compile(r'"([^"\r\n]+)"')
PLAIN_HOST_RE = re.compile(r'^(?:[a-zA-Z0-9-]+\.)+[a-zA-Z]{2,}$')
EXTENSION_NAME_RE = re.compile(r'(?m)^\s*name\s*=\s*"([^"]+)"')
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


def comparable_host(value: str) -> str:
    value = host(value)
    return value[4:] if value.startswith("www.") else value


def literal_hosts(text: str) -> set[str]:
    """Return concrete host literals from the parser; interpolation is deliberately ignored."""
    result: set[str] = set()
    for raw in QUOTED_STRING_RE.findall(text):
        value = raw.strip()
        if "$" in value or "{" in value or "}" in value:
            continue
        try:
            if value.startswith(("https://", "http://")):
                result.add(host(value))
            elif PLAIN_HOST_RE.fullmatch(value):
                result.add(host(value))
        except SystemExit:
            continue
    return result


def extract_blocks(text: str, keyword: str) -> list[str]:
    """Return brace-balanced Kotlin blocks introduced by `keyword {`."""
    result: list[str] = []
    pattern = re.compile(rf"\b{re.escape(keyword)}\s*\{{")
    for match in pattern.finditer(text):
        start = match.end()
        depth = 1
        i = start
        in_string = False
        escaped = False
        while i < len(text) and depth:
            ch = text[i]
            if in_string:
                if escaped:
                    escaped = False
                elif ch == "\\":
                    escaped = True
                elif ch == '"':
                    in_string = False
            else:
                if ch == '"':
                    in_string = True
                elif ch == "{":
                    depth += 1
                elif ch == "}":
                    depth -= 1
            i += 1
        if depth == 0:
            result.append(text[start : i - 1])
    return result


def parse_keiyoushi_sources(module: Path) -> list[dict]:
    build_file = module / "build.gradle.kts"
    if not build_file.is_file():
        fail(f"Keiyoushi module build file missing: {build_file}")
    text = build_file.read_text(encoding="utf-8")

    extension_name_match = EXTENSION_NAME_RE.search(text)
    if extension_name_match is None:
        fail(f"Could not parse extension name from {build_file}")
    extension_name = extension_name_match.group(1)

    sources: list[dict] = []
    for block in extract_blocks(text, "source"):
        lang_match = FIELD_STRING_RE["lang"].search(block)
        base_url_match = BASE_URL_RE.search(block)
        if lang_match is None or base_url_match is None:
            continue
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
        sources.append(
            {
                "name": source_name,
                "lang": lang_match.group(1),
                "baseUrl": base_url_match.group(1),
                "sourceId": source_id,
                "sourceIdKind": "explicit" if explicit_id_match else "computed",
                "versionId": version_id,
            }
        )
    if not sources:
        fail(f"Could not parse any source blocks from {build_file}")
    return sources


def parse_keiyoushi_source(module: Path, expected_name: str, expected_domain: str) -> dict:
    expected_host = comparable_host(expected_domain)
    candidates = [
        source
        for source in parse_keiyoushi_sources(module)
        if source["name"] == expected_name and comparable_host(source["baseUrl"]) == expected_host
    ]
    if len(candidates) != 1:
        fail(
            f"Expected exactly one Keiyoushi source {expected_name!r} at {expected_domain} "
            f"in {module}, found {len(candidates)}"
        )
    return candidates[0]


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


def selected_uma_path(uma_file: str, language: str) -> str:
    path = Path(uma_file)
    site_root = Path("src/main/kotlin/tsuki/site") / language
    try:
        return path.relative_to(site_root).as_posix()
    except ValueError:
        fail(f"UMA file {uma_file} is outside the expected {site_root} source tree")


def apply_structural_adapter_if_available(manifest_path: Path, uma_root: Path, keiyoushi_root: Path) -> None:
    """Apply deterministic Source-Pack structural profiles before verifying/building the UMA shard."""
    script = Path("source-packs/tools/keiyoushi_structural_adapter.py").resolve()
    report = Path("build/keiyoushi-semantic-adapter.json").resolve()
    if not script.is_file() or not report.is_file():
        return
    try:
        subprocess.run(
            [
                sys.executable,
                str(script),
                "--aliases",
                str(manifest_path),
                "--keiyoushi-root",
                str(keiyoushi_root),
                "--uma-root",
                str(uma_root),
                "--output",
                str(report),
                "--strict",
            ],
            check=True,
            timeout=120,
        )
    except (OSError, subprocess.CalledProcessError, subprocess.TimeoutExpired) as exc:
        fail(f"Structural source adaptation failed: {exc}")


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

    # Structural profiles modify only the disposable UMA checkout. Verification below therefore
    # validates the exact adapted source that will subsequently be compiled by the pack workflow.
    apply_structural_adapter_if_available(manifest_path, uma_root, keiyoushi_root)

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
        verified_host = comparable_host(verified_domain)
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
        selected_path = selected_uma_path(uma["file"], language)
        if selected_path not in set(pack.get("sources") or []):
            fail(f"{canonical}: UMA file {selected_path} is not selected by official pack {pack_name}")
        uma_source = parse_uma_source(uma_file)
        if uma_source["sourceName"] != uma["sourceName"] or uma_source["sourceName"] != official["sourceName"]:
            fail(f"{canonical}: UMA source identity mismatch")
        if uma_source["lang"] != language:
            fail(f"{canonical}: UMA language mismatch")
        uma_hosts = {comparable_host(value) for value in literal_hosts(uma_source["text"])}
        if verified_host not in uma_hosts:
            fail(f"{canonical}: verified domain {verified_domain} not found as a concrete UMA host literal")

        kei_module = keiyoushi_root / kei["module"]
        kei_source = parse_keiyoushi_source(kei_module, kei["sourceName"], verified_domain)
        if kei_source["lang"] != language:
            fail(f"{canonical}: Keiyoushi language mismatch")
        if comparable_host(kei_source["baseUrl"]) != verified_host:
            fail(f"{canonical}: Keiyoushi host no longer matches verified host {verified_domain}")
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
