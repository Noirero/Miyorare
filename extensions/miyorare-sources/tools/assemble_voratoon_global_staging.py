#!/usr/bin/env python3
"""Assemble a two-shard Global staging bundle, never a release manifest."""

import argparse
import hashlib
import json
import shutil
from pathlib import Path

from bind_voratoon_global import bind_voratoon_global


def binding(root, asset):
    jar = root / asset
    digest = hashlib.sha256(jar.read_bytes()).hexdigest()
    if (root / (asset + ".sha256")).read_text(encoding="utf-8").split() != [digest, asset]:
        raise SystemExit(f"Checksum mismatch: {asset}")
    return {"assetName": asset, "sha256": digest, "size": jar.stat().st_size}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for arg in ("global", "voratoon", "output"):
        parser.add_argument("--" + arg, type=Path, required=True, dest=arg + "_dir")
    parser.add_argument("--revision", required=True)
    args = parser.parse_args()
    logical = json.loads((args.global_dir / "miyorare-global-pack.json").read_text(encoding="utf-8"))
    (gekkoushi,) = logical["shards"]
    names = gekkoushi["sourceNames"]
    if (logical["packId"] != "miyorare-global" or gekkoushi["provider"] != "GEKKOUSHI"
            or gekkoushi["pluginId"] != "miyorare-global"
            or gekkoushi["assetName"] != "miyorare-global-gekkoushi.jar"
            or logical["sourceNames"] != sorted(set(names))
            or len(names) != len(set(names)) or "VORATOON" in names):
        raise SystemExit("Unexpected or duplicate Global source ownership")
    existing = binding(args.global_dir, gekkoushi["assetName"])
    output = args.output_dir
    output.mkdir(parents=True, exist_ok=False)
    asset = existing["assetName"]
    shutil.copyfile(args.global_dir / asset, output / asset)
    shutil.copyfile(args.global_dir / (asset + ".sha256"), output / (asset + ".sha256"))
    manifest = {
        **logical, "stagingOnly": True, "revision": args.revision,
        "shards": [{**gekkoushi, **existing}],
    }
    bind_voratoon_global(manifest, args.voratoon_dir, output)
    (output / "miyorare-global-staging.json").write_text(
        json.dumps(manifest, indent=2) + "\n", encoding="utf-8"
    )


if __name__ == "__main__":
    main()
