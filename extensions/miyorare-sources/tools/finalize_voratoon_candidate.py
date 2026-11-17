#!/usr/bin/env python3
"""Build-only staging for one Miyorare-owned Global candidate, not a release manifest."""

import argparse
import hashlib
import json
import shutil
import zipfile
from pathlib import Path

from finalize_global_shard import GENERATED_SOURCE_RE
from prepare_pack import git_head


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--upstream", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    manifest = json.loads((root / "packs.json").read_text(encoding="utf-8"))
    if git_head(args.upstream) != manifest["upstream"]["commit"]:
        raise SystemExit("Unexpected build baseline")
    build = args.upstream / "build"
    generated = list((build / "generated/ksp").rglob("MangaSource.kt"))
    if len(generated) != 1:
        raise SystemExit("Expected exactly one generated MangaSource.kt")
    names = GENERATED_SOURCE_RE.findall(generated[0].read_text(encoding="utf-8"))
    if names != ["VORATOON"]:
        raise SystemExit(f"Unexpected generated runtime sources: {names!r}")
    jar = build / "libs/uma.jar"
    args.output.mkdir(parents=True, exist_ok=True)
    target = args.output / "miyorare-global-voratoon-candidate.jar"
    with zipfile.ZipFile(jar) as archive:
        if "classes.dex" not in archive.namelist():
            raise SystemExit("Candidate is missing classes.dex")
        if archive.testzip() is not None:
            raise SystemExit("Candidate contains a corrupt entry")
    shutil.copyfile(jar, target)
    with zipfile.ZipFile(target, "a", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.write(root / "first-party/voratoon/LICENSE", "META-INF/licenses/VoraToon-GPL-3.0.txt")
    digest = hashlib.sha256(target.read_bytes()).hexdigest()
    (args.output / (target.name + ".sha256")).write_text(
        f"{digest}  {target.name}\n", encoding="utf-8"
    )
    print(f"Verified build-only candidate: {target} ({digest})")


if __name__ == "__main__":
    main()
