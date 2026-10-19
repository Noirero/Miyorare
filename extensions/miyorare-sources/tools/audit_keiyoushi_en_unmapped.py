#!/usr/bin/env python3
"""Produce review-only evidence for Miyorare EN sources not yet mapped to Keiyoushi.

Unlike discover_keiyoushi_en_aliases.py this script never proposes an alias. It helps a human review
possible renames/domain migrations by showing literal-host matches and the closest source names.
"""

from __future__ import annotations

import argparse
import difflib
import json
from pathlib import Path

from discover_keiyoushi_en_aliases import (
    comparable_host,
    load_json,
    normalize_name,
    read_keiyoushi_en,
    read_miyorare_en,
)


def audit(manifest: dict, packs: dict, uma_root: Path, keiyoushi_root: Path) -> dict:
    official = read_miyorare_en(packs, uma_root)
    keiyoushi = read_keiyoushi_en(keiyoushi_root)
    mapped = {
        alias["official"]["sourceName"]
        for alias in manifest.get("aliases") or []
        if alias.get("language") == "en" and alias.get("official", {}).get("pack") == "en"
    }

    reviews: list[dict] = []
    for uma in official:
        if uma.get("error") or uma.get("sourceName") in mapped:
            continue
        literal_keys = {comparable_host(value) for value in uma.get("literalHosts") or []}
        host_matches = [source for source in keiyoushi if comparable_host(source["domain"]) in literal_keys]

        uma_keys = set(uma.get("nameKeys") or [])
        scored: list[tuple[float, dict]] = []
        for source in keiyoushi:
            source_keys = set(source.get("nameKeys") or [])
            score = max(
                (
                    difflib.SequenceMatcher(None, left, right).ratio()
                    for left in uma_keys
                    for right in source_keys
                ),
                default=0.0,
            )
            if score >= 0.45:
                scored.append((score, source))
        scored.sort(key=lambda item: (-item[0], item[1]["module"], item[1]["sourceName"]))

        reviews.append(
            {
                "miyorare": uma,
                "literalHostMatches": host_matches,
                "closestNames": [
                    {
                        "score": round(score, 4),
                        "module": source["module"],
                        "sourceName": source["sourceName"],
                        "domain": source["domain"],
                        "sourceId": source["sourceId"],
                    }
                    for score, source in scored[:5]
                ],
            }
        )

    return {
        "schema": 1,
        "summary": {
            "mappedEnAliases": len(mapped),
            "unmappedMiyorareEn": len(reviews),
            "uniqueLiteralHostCandidates": sum(len(item["literalHostMatches"]) == 1 for item in reviews),
            "ambiguousLiteralHostCandidates": sum(len(item["literalHostMatches"]) > 1 for item in reviews),
        },
        "reviews": reviews,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--packs", type=Path, required=True)
    parser.add_argument("--uma", type=Path, required=True)
    parser.add_argument("--keiyoushi", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    report = audit(
        load_json(args.manifest.resolve()),
        load_json(args.packs.resolve()),
        args.uma.resolve(),
        args.keiyoushi.resolve(),
    )
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    summary = report["summary"]
    print(
        f"Unmapped EN audit: {summary['unmappedMiyorareEn']} remaining; "
        f"{summary['uniqueLiteralHostCandidates']} unique host candidates; "
        f"{summary['ambiguousLiteralHostCandidates']} ambiguous host candidates"
    )


if __name__ == "__main__":
    main()
