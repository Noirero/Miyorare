#!/usr/bin/env python3
import argparse
import hashlib
import json
import shutil
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", required=True)
    parser.add_argument("--output-dir", required=True)
    parser.add_argument("--revision", required=True)
    args = parser.parse_args()

    apk = Path(args.apk)
    output_dir = Path(args.output_dir)
    revision = args.revision.strip()

    if not apk.is_file():
        raise SystemExit(f"Kotatsu runtime APK was not produced: {apk}")
    if len(revision) != 40 or any(ch not in "0123456789abcdef" for ch in revision):
        raise SystemExit("Kotatsu runtime upstream revision must be a full lowercase SHA")

    asset_dir = output_dir / "kotatsu-runtime"
    if asset_dir.exists():
        shutil.rmtree(asset_dir)
    asset_dir.mkdir(parents=True, exist_ok=True)

    bundled_apk = asset_dir / "bundled.apk"
    shutil.copyfile(apk, bundled_apk)
    sha256 = hashlib.sha256(bundled_apk.read_bytes()).hexdigest()
    manifest = {
        "protocolVersion": 1,
        "packVersion": f"1-{revision[:10]}-bundled",
        "upstreamRevision": revision,
        "sourceCount": 0,
        "sha256": sha256,
        "downloadUrl": "https://github.com/Noirero/Miyorare/releases/download/kotatsu-runtime-latest/kotatsu-runtime.apk",
    }
    (asset_dir / "manifest.json").write_text(
        json.dumps(manifest, separators=(",", ":")),
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()
