#!/usr/bin/env python3
"""Verify, attribute and finalize a curated Miyorare source-pack JAR."""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import zipfile
from pathlib import Path


def fail(message: str) -> None:
    raise SystemExit(message)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--upstream", type=Path, required=True)
    parser.add_argument("--pack", choices=("id", "en"), required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    pack = manifest["packs"][args.pack]
    expected_count = len(pack["sources"])

    summary = args.upstream / ".github/summary.yaml"
    if not summary.is_file():
        fail("KSP summary.yaml was not generated")
    summary_line = summary.read_text(encoding="utf-8").strip()
    try:
        actual_count = int(summary_line.split(":", 1)[1].strip())
    except (IndexError, ValueError):
        fail(f"Unexpected KSP summary: {summary_line!r}")
    if actual_count != expected_count:
        fail(f"KSP exposed {actual_count} sources; manifest expects {expected_count}")

    built = args.upstream / "build/libs/uma.jar"
    if not built.is_file() or built.stat().st_size == 0:
        fail("UMA build did not produce build/libs/uma.jar")

    with zipfile.ZipFile(built, "r") as archive:
        names = set(archive.namelist())
    if "classes.dex" not in names:
        fail("Built plugin is not a dexed Tsuki JAR (classes.dex missing)")

    license_file = args.upstream / "LICENSE"
    provenance_file = args.upstream / "miyorare-pack.json"
    if not license_file.is_file() or not provenance_file.is_file():
        fail("Required license/provenance metadata is missing")

    # JAR is a ZIP container. Add provenance resources without modifying classes.dex.
    with zipfile.ZipFile(built, "a", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.writestr(
            "META-INF/licenses/UMA-GPL-3.0.txt",
            license_file.read_text(encoding="utf-8"),
        )
        archive.writestr(
            "META-INF/miyorare-pack.json",
            provenance_file.read_text(encoding="utf-8"),
        )

    args.output.mkdir(parents=True, exist_ok=True)
    final_jar = args.output / pack["assetName"]
    shutil.copy2(built, final_jar)
    digest = hashlib.sha256(final_jar.read_bytes()).hexdigest()
    (args.output / f"{pack['assetName']}.sha256").write_text(
        f"{digest}  {pack['assetName']}\n",
        encoding="utf-8",
    )
    shutil.copy2(provenance_file, args.output / f"{args.pack}-pack.json")
    print(f"Finalized {final_jar} ({expected_count} sources, sha256={digest})")


if __name__ == "__main__":
    main()
