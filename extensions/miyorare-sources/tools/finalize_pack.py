#!/usr/bin/env python3
"""Verify, attribute and finalize a Miyorare source-pack JAR."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import zipfile
from pathlib import Path


GENERATED_SOURCE_RE = re.compile(
    r'^\s*(?:@Deprecated\("(?:\\.|[^"\\])*"\)\s*)?([A-Z_][A-Z0-9_]{3,})\(',
    re.MULTILINE,
)


def fail(message: str) -> None:
    raise SystemExit(message)


def generated_source_file(upstream: Path) -> Path:
    generated_root = upstream / "build/generated/ksp"
    candidates = list(generated_root.rglob("MangaSource.kt")) if generated_root.is_dir() else []
    if len(candidates) != 1:
        fail(f"Expected exactly one generated MangaSource.kt, found {len(candidates)}")
    return candidates[0]


def add_license(archive: zipfile.ZipFile, source: Path, archive_name: str) -> None:
    license_file = source / "LICENSE"
    if not license_file.is_file():
        fail(f"Required upstream license is missing: {license_file}")
    archive.writestr(
        f"META-INF/licenses/{archive_name}",
        license_file.read_text(encoding="utf-8"),
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--upstream", type=Path, required=True)
    parser.add_argument("--base-upstream", type=Path)
    parser.add_argument("--pack", choices=("id", "en"), required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    pack = manifest["packs"][args.pack]
    provenance_file = args.upstream / "miyorare-pack.json"
    if not provenance_file.is_file():
        fail("Prepared pack provenance metadata is missing")
    provenance = json.loads(provenance_file.read_text(encoding="utf-8"))
    expected_names = provenance.get("sourceNames") or []
    expected_count = provenance.get("sourceCount")
    if not expected_names or expected_count != len(expected_names):
        fail("Prepared pack provenance has an invalid runtime source list")

    generated = generated_source_file(args.upstream)
    generated_names = GENERATED_SOURCE_RE.findall(generated.read_text(encoding="utf-8"))
    if len(generated_names) != len(set(generated_names)):
        fail("KSP generated duplicate runtime source names")
    missing = sorted(set(expected_names) - set(generated_names))
    unexpected = sorted(set(generated_names) - set(expected_names))
    if missing or unexpected:
        details = []
        if missing:
            details.append(f"missing: {', '.join(missing)}")
        if unexpected:
            details.append(f"unexpected: {', '.join(unexpected)}")
        fail(
            "Generated KSP source set differs from curated provenance ("
            + "; ".join(details)
            + ")"
        )

    summary = args.upstream / ".github/summary.yaml"
    if not summary.is_file():
        fail("KSP summary.yaml was not generated")
    summary_line = summary.read_text(encoding="utf-8").strip()
    try:
        summary_count = int(summary_line.split(":", 1)[1].strip())
    except (IndexError, ValueError):
        fail(f"Unexpected KSP summary: {summary_line!r}")
    if summary_count != len(generated_names):
        fail(
            f"KSP summary reports {summary_count}, "
            f"generated enum contains {len(generated_names)}"
        )

    built_relative = provenance.get("buildArtifact") or "build/libs/uma.jar"
    built = args.upstream / built_relative
    if not built.is_file() or built.stat().st_size == 0:
        fail(f"Source-pack build did not produce {built_relative}")

    with zipfile.ZipFile(built, "r") as archive:
        names = set(archive.namelist())
    if "classes.dex" not in names:
        fail("Built plugin is not a dexed Tsuki JAR (classes.dex missing)")

    # JAR is a ZIP container. Add all source-code licenses plus exact build provenance without
    # modifying classes.dex.
    with zipfile.ZipFile(built, "a", compression=zipfile.ZIP_DEFLATED) as archive:
        if args.base_upstream is not None:
            add_license(archive, args.base_upstream, "UMA-GPL-3.0.txt")
        build_name = (provenance.get("buildUpstream") or {}).get("name", "UPSTREAM")
        safe_name = re.sub(r"[^A-Za-z0-9._-]+", "-", build_name).strip("-") or "UPSTREAM"
        add_license(archive, args.upstream, f"{safe_name}-GPL-3.0.txt")
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
    print(
        f"Finalized {final_jar} "
        f"({provenance['sourceFilesCount']} parser files / "
        f"{len(generated_names)} runtime sources, sha256={digest})"
    )


if __name__ == "__main__":
    main()
