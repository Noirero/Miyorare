#!/usr/bin/env python3
"""Discover provable EN overlap between the curated Miyorare/UMA shard and Keiyoushi.

This tool is intentionally read-only. It proposes a canonical alias only when one selected
Miyorare EN source and one Keiyoushi EN source resolve to the same unique website host.
Name-only or ambiguous matches are reported for manual review and are never auto-promoted.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from collections import defaultdict
from pathlib import Path
from urllib.parse import urlparse

UMA_ANNOTATION_RE = re.compile(
    r'@MangaSourceParser\s*\(\s*"([^"]+)"\s*,\s*"([^"]+)"\s*,\s*"([^"]+)"',
    re.DOTALL,
)
UMA_DOMAIN_RE = re.compile(r'ConfigKey\.Domain\s*\(\s*"([^"]+)"')
EXTENSION_NAME_RE = re.compile(r'(?m)^\s*name\s*=\s*"([^"]+)"')
FIELD_STRING_RE = {
    "name": re.compile(r'(?m)^\s*name\s*=\s*"([^"]+)"'),
    "lang": re.compile(r'(?m)^\s*lang\s*=\s*"([^"]+)"'),
}
BASE_URL_RE = re.compile(r'(?m)^\s*baseUrl\s*=\s*"([^"]+)"')
SOURCE_ID_RE = re.compile(r'(?m)^\s*id\s*=\s*(\d+)L?')
VERSION_ID_RE = re.compile(r'(?m)^\s*versionId\s*=\s*(\d+)')


def load_json(path: Path) -> dict:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise SystemExit(f"{path} must contain a JSON object")
    return value


def host(value: str) -> str:
    parsed = urlparse(value if "://" in value else f"https://{value}")
    hostname = (parsed.hostname or "").lower().rstrip(".")
    if not hostname:
        raise ValueError(f"Invalid URL/domain: {value!r}")
    return hostname


def normalize_name(value: str) -> str:
    return re.sub(r"[^a-z0-9]+", "", value.lower())


def compute_source_id(name: str, lang: str, version_id: int = 1) -> int:
    digest = hashlib.md5(f"{name.lower()}/{lang}/{version_id}".encode()).digest()
    return int.from_bytes(digest[:8], byteorder="big", signed=False) & ((1 << 63) - 1)


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


def read_miyorare_en(packs: dict, uma_root: Path) -> list[dict]:
    pack = (packs.get("packs") or {}).get("en") or {}
    result: list[dict] = []
    root = uma_root / "src/main/kotlin/tsuki/site/en"
    for selected in pack.get("sources") or []:
        path = root / selected
        if not path.is_file():
            result.append({"selected": selected, "error": "missing UMA file"})
            continue
        text = path.read_text(encoding="utf-8")
        annotation = UMA_ANNOTATION_RE.search(text)
        if annotation is None:
            result.append({"selected": selected, "error": "missing @MangaSourceParser"})
            continue
        source_name, display_name, language = annotation.groups()
        domains = sorted({host(value) for value in UMA_DOMAIN_RE.findall(text)})
        result.append(
            {
                "selected": selected,
                "file": f"src/main/kotlin/tsuki/site/en/{selected}",
                "sourceName": source_name,
                "displayName": display_name,
                "language": language,
                "domains": domains,
                "nameKeys": sorted(
                    {
                        normalize_name(source_name),
                        normalize_name(display_name),
                        normalize_name(Path(selected).stem),
                    }
                    - {""}
                ),
            }
        )
    return result


def read_keiyoushi_en(keiyoushi_root: Path) -> list[dict]:
    result: list[dict] = []
    root = keiyoushi_root / "src/en"
    for module in sorted(path for path in root.iterdir() if path.is_dir()):
        build_file = module / "build.gradle.kts"
        if not build_file.is_file():
            continue
        text = build_file.read_text(encoding="utf-8")
        extension_match = EXTENSION_NAME_RE.search(text)
        if extension_match is None:
            continue
        extension_name = extension_match.group(1)
        for index, block in enumerate(extract_blocks(text, "source")):
            lang_match = FIELD_STRING_RE["lang"].search(block)
            base_url_match = BASE_URL_RE.search(block)
            if lang_match is None or base_url_match is None or lang_match.group(1) != "en":
                continue
            source_name_match = FIELD_STRING_RE["name"].search(block)
            source_name = source_name_match.group(1) if source_name_match else extension_name
            version_match = VERSION_ID_RE.search(block)
            version_id = int(version_match.group(1)) if version_match else 1
            explicit_id = SOURCE_ID_RE.search(block)
            source_id = int(explicit_id.group(1)) if explicit_id else compute_source_id(source_name, "en", version_id)
            base_url = base_url_match.group(1)
            result.append(
                {
                    "module": f"src/en/{module.name}",
                    "moduleName": module.name,
                    "sourceIndex": index,
                    "extensionName": extension_name,
                    "sourceName": source_name,
                    "sourceId": source_id,
                    "sourceIdKind": "explicit" if explicit_id else "computed",
                    "versionId": version_id,
                    "baseUrl": base_url,
                    "domain": host(base_url),
                    "nameKeys": sorted(
                        {
                            normalize_name(extension_name),
                            normalize_name(source_name),
                            normalize_name(module.name),
                        }
                        - {""}
                    ),
                }
            )
    return result


def discover(manifest: dict, packs: dict, uma_root: Path, keiyoushi_root: Path) -> dict:
    official = read_miyorare_en(packs, uma_root)
    kei = read_keiyoushi_en(keiyoushi_root)
    existing = {
        alias["official"]["sourceName"]: alias
        for alias in manifest.get("aliases") or []
        if alias.get("language") == "en" and alias.get("official", {}).get("pack") == "en"
    }

    uma_by_domain: dict[str, list[dict]] = defaultdict(list)
    for item in official:
        for domain in item.get("domains") or []:
            uma_by_domain[domain].append(item)
    kei_by_domain: dict[str, list[dict]] = defaultdict(list)
    for item in kei:
        kei_by_domain[item["domain"]].append(item)

    exact: list[dict] = []
    ambiguous: list[dict] = []
    for domain in sorted(set(uma_by_domain) & set(kei_by_domain)):
        uma_items = uma_by_domain[domain]
        kei_items = kei_by_domain[domain]
        if len(uma_items) == 1 and len(kei_items) == 1:
            uma = uma_items[0]
            source = kei_items[0]
            alias = {
                "canonicalId": f"miyorare:miyorare-en:{uma['sourceName']}",
                "language": "en",
                "verifiedDomain": domain,
                "official": {
                    "pack": "en",
                    "pluginId": "miyorare-en",
                    "sourceName": uma["sourceName"],
                },
                "uma": {
                    "pluginId": "uma",
                    "sourceName": uma["sourceName"],
                    "file": uma["file"],
                },
                "keiyoushi": {
                    "module": source["module"],
                    "sourceName": source["sourceName"],
                    "sourceId": source["sourceId"],
                },
            }
            exact.append(
                {
                    "domain": domain,
                    "miyorare": uma,
                    "keiyoushi": source,
                    "alreadyMapped": uma["sourceName"] in existing,
                    "proposedAlias": alias,
                }
            )
        else:
            ambiguous.append({"domain": domain, "miyorare": uma_items, "keiyoushi": kei_items})

    exact_source_names = {item["miyorare"]["sourceName"] for item in exact}
    name_review: list[dict] = []
    for uma in official:
        if uma.get("sourceName") in exact_source_names or uma.get("error"):
            continue
        matches = [
            source
            for source in kei
            if set(uma.get("nameKeys") or []) & set(source.get("nameKeys") or [])
        ]
        if matches:
            name_review.append({"miyorare": uma, "keiyoushi": matches})

    mapped_names = set(existing)
    return {
        "schema": 1,
        "pins": manifest.get("upstreams") or {},
        "summary": {
            "miyorareSelectedEn": len(official),
            "keiyoushiEnSources": len(kei),
            "existingEnAliases": len(existing),
            "exactUniqueDomainMatches": len(exact),
            "missingExactAliases": sum(not item["alreadyMapped"] for item in exact),
            "ambiguousSharedDomains": len(ambiguous),
            "nameOnlyReviewCandidates": len(name_review),
        },
        "missingExactAliases": [item["proposedAlias"] for item in exact if not item["alreadyMapped"]],
        "exactMatches": exact,
        "ambiguousDomains": ambiguous,
        "nameOnlyReview": name_review,
        "unmappedMiyorareSourceNames": sorted(
            item["sourceName"]
            for item in official
            if item.get("sourceName") and item["sourceName"] not in mapped_names
        ),
        "miyorareParseProblems": [item for item in official if item.get("error")],
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--packs", type=Path, required=True)
    parser.add_argument("--uma", type=Path, required=True)
    parser.add_argument("--keiyoushi", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    report = discover(
        load_json(args.manifest.resolve()),
        load_json(args.packs.resolve()),
        args.uma.resolve(),
        args.keiyoushi.resolve(),
    )
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    summary = report["summary"]
    print(
        "EN overlap inventory: "
        f"{summary['miyorareSelectedEn']} Miyorare / {summary['keiyoushiEnSources']} Keiyoushi; "
        f"{summary['exactUniqueDomainMatches']} exact-domain matches, "
        f"{summary['missingExactAliases']} missing aliases, "
        f"{summary['ambiguousSharedDomains']} ambiguous domains, "
        f"{summary['nameOnlyReviewCandidates']} name-only review candidates"
    )


if __name__ == "__main__":
    main()
