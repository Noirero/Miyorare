#!/usr/bin/env python3
"""Create the logical Miyorare Global manifest from its single Gekkoushi shard."""

from __future__ import annotations

import argparse
import json
from pathlib import Path


def fail(message: str) -> None:
    raise SystemExit(message)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--pack", choices=("global",), default="global")
    parser.add_argument("--gekkoushi-metadata", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    root = json.loads(args.manifest.read_text(encoding="utf-8"))
    pack = root["packs"][args.pack]
    data = json.loads(args.gekkoushi_metadata.read_text(encoding="utf-8"))
    if data.get("schema") != 2 or data.get("shard") != "gekkoushi":
        fail("Invalid Gekkoushi shard metadata")
    if data.get("logicalPackId") != pack["pluginId"]:
        fail("Shard belongs to a different logical pack")
    if data.get("language") != "all":
        fail("Global shard must use language 'all'")
    names = data.get("sourceNames") or []
    if not names or len(names) != len(set(names)):
        fail("Global shard has an invalid source list")

    logical = {
        "schema": 1,
        "packId": pack["pluginId"],
        "displayName": pack["displayName"],
        "language": "all",
        "tsukiApi": root["tsukiApi"],
        "sourceCount": len(names),
        "sourceNames": sorted(names),
        "deduplication": {
            "priority": ["GEKKOUSHI"],
            "runtimeKeyBased": True,
            "globalSingleOwner": True,
        },
        "shards": [
            {
                "provider": "GEKKOUSHI",
                "pluginId": data["pluginId"],
                "displayName": data["displayName"],
                "assetName": data["assetName"],
                "sourceCount": data["sourceCount"],
                "sourceNames": data["sourceNames"],
                "upstreams": data.get("upstreams") or [],
            }
        ],
    }

    args.output.mkdir(parents=True, exist_ok=True)
    output = args.output / "miyorare-global-pack.json"
    output.write_text(json.dumps(logical, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"Finalized {pack['displayName']}: {len(names)} unique global source(s)")


if __name__ == "__main__":
    main()
