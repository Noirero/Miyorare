#!/usr/bin/env python3
"""Synchronize packable ACTIVE Compatibility Farm sources into Miyorare packs.json.

This tool is intentionally additive. It never removes existing UMA source files and never
pretends Keiyoushi-only sources are publishable before Miyorare has a Keiyoushi runtime shard.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

LANGUAGES = ("id", "en")
UMA_PREFIX = {
    "id": "src/main/kotlin/tsuki/site/id/",
    "en": "src/main/kotlin/tsuki/site/en/",
}


def load(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"{path} must contain a JSON object")
    return value


def save(path: Path, value: Any) -> None:
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def valid_sha(value: Any) -> bool:
    return isinstance(value, str) and len(value) == 40 and all(c in "0123456789abcdef" for c in value)


def relative_uma_path(language: str, file_path: Any) -> str | None:
    if not isinstance(file_path, str):
        return None
    prefix = UMA_PREFIX[language]
    if not file_path.startswith(prefix):
        return None
    relative = file_path[len(prefix):]
    path = Path(relative)
    if (
        not relative
        or path.is_absolute()
        or path.suffix != ".kt"
        or any(part in ("", ".", "..") for part in path.parts)
    ):
        return None
    return path.as_posix()


def sync(registry: dict[str, Any], packs_root: dict[str, Any]) -> tuple[dict[str, Any], dict[str, Any]]:
    if registry.get("schemaVersion") != 1:
        raise ValueError("unsupported Compatibility Farm registry schema")
    if packs_root.get("schema") != 1:
        raise ValueError("unsupported Miyorare packs schema")

    baselines = registry.get("providerBaselines")
    if not isinstance(baselines, dict):
        raise ValueError("registry.providerBaselines missing")
    for provider in ("uma", "gekkoushi"):
        if not valid_sha(baselines.get(provider)):
            raise ValueError(f"registry provider baseline invalid: {provider}")

    packs = packs_root.get("packs")
    if not isinstance(packs, dict):
        raise ValueError("packs root missing packs")
    for language in LANGUAGES:
        pack = packs.get(language)
        if not isinstance(pack, dict):
            raise ValueError(f"missing {language} pack")
        if pack.get("language") != language:
            raise ValueError(f"{language} pack language mismatch")
        include_all = pack.get("includeAllFrom")
        if not isinstance(include_all, list) or "gekkoushi" not in include_all:
            raise ValueError(f"{language} pack must retain includeAllFrom gekkoushi")
        sources = pack.get("sources")
        if not isinstance(sources, list) or any(not isinstance(item, str) for item in sources):
            raise ValueError(f"{language} pack sources invalid")
        if len(sources) != len(set(sources)):
            raise ValueError(f"{language} pack sources contain duplicates")

    upstream = packs_root.get("upstream")
    additional = packs_root.get("additionalUpstreams")
    if not isinstance(upstream, dict) or not isinstance(additional, dict):
        raise ValueError("packs upstream metadata missing")
    gekkoushi_meta = additional.get("gekkoushi")
    if not isinstance(gekkoushi_meta, dict):
        raise ValueError("packs Gekkoushi metadata missing")

    previous_uma = upstream.get("commit")
    previous_gekkoushi = gekkoushi_meta.get("commit")
    upstream["commit"] = baselines["uma"]
    gekkoushi_meta["commit"] = baselines["gekkoushi"]

    existing = {
        language: set(packs[language]["sources"])
        for language in LANGUAGES
    }
    additions = {language: set() for language in LANGUAGES}
    gekkoushi_covered: list[str] = []
    keiyoushi_only: list[str] = []
    uma_unpublishable: list[dict[str, str]] = []
    active_count = 0

    rows = registry.get("sources")
    if not isinstance(rows, list):
        raise ValueError("registry.sources must be a list")

    for source in rows:
        if not isinstance(source, dict):
            raise ValueError("registry source entry must be an object")
        enrollment = source.get("compatibilityEnrollment")
        if not isinstance(enrollment, dict) or enrollment.get("state") != "ACTIVE":
            continue
        language = source.get("language")
        if language not in LANGUAGES:
            continue
        active_count += 1

        canonical_id = source.get("canonicalId")
        if not isinstance(canonical_id, str) or not canonical_id:
            raise ValueError("ACTIVE source missing canonicalId")
        providers = source.get("providers")
        identities = source.get("upstreamIdentities")
        if not isinstance(providers, list) or not isinstance(identities, dict):
            raise ValueError(f"{canonical_id} provider metadata invalid")

        if "gekkoushi" in providers:
            gekkoushi_covered.append(canonical_id)

        if "uma" in providers:
            uma_identity = identities.get("uma")
            if not isinstance(uma_identity, dict):
                raise ValueError(f"{canonical_id} UMA identity missing")
            raw_file = uma_identity.get("file")
            relative = relative_uma_path(language, raw_file)
            if relative is None:
                uma_unpublishable.append(
                    {
                        "canonicalId": canonical_id,
                        "language": language,
                        "file": str(raw_file),
                        "reason": "UMA_FILE_OUTSIDE_LANGUAGE_PACK_ROOT",
                    }
                )
            else:
                additions[language].add(relative)

        if set(providers) == {"keiyoushi"}:
            keiyoushi_only.append(canonical_id)

    added = {}
    already_present = {}
    for language in LANGUAGES:
        new_items = additions[language] - existing[language]
        known_items = additions[language] & existing[language]
        packs[language]["sources"] = sorted(existing[language] | additions[language])
        added[language] = sorted(new_items)
        already_present[language] = sorted(known_items)

    report = {
        "schemaVersion": 1,
        "action": "SYNC_ACTIVE_FARM_MEMBERSHIP",
        "activeSourceCount": active_count,
        "providerBaselines": {
            "uma": baselines["uma"],
            "gekkoushi": baselines["gekkoushi"],
        },
        "pinChanges": {
            "uma": {"before": previous_uma, "after": baselines["uma"]},
            "gekkoushi": {"before": previous_gekkoushi, "after": baselines["gekkoushi"]},
        },
        "addedUmaSources": added,
        "alreadyPresentUmaSources": already_present,
        "gekkoushiCoveredCanonicalIds": sorted(set(gekkoushi_covered)),
        "validatedNotPackable": {
            "keiyoushiOnlyCanonicalIds": sorted(set(keiyoushi_only)),
            "umaOutsideLanguagePackRoot": sorted(
                uma_unpublishable,
                key=lambda item: (item["language"], item["canonicalId"]),
            ),
        },
        "changed": (
            bool(added["id"])
            or bool(added["en"])
            or previous_uma != baselines["uma"]
            or previous_gekkoushi != baselines["gekkoushi"]
        ),
    }
    return packs_root, report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--registry", type=Path, required=True)
    parser.add_argument("--packs", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()

    registry = load(args.registry)
    packs = load(args.packs)
    updated, report = sync(registry, packs)
    save(args.packs, updated)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    save(args.report, report)
    print(json.dumps(report, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
