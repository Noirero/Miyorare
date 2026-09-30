#!/usr/bin/env python3
"""Classify changes that require the core Android runtime acceptance gate."""

from __future__ import annotations
import argparse
from pathlib import Path

RUNTIME_EXACT = {
    "app/build.gradle",
    "gradle.properties",
    "settings.gradle",
    "app/src/main/AndroidManifest.xml",
    ".github/workflows/android-runtime.yml",
    ".github/scripts/android_runtime_paths.py",
    ".github/scripts/test_android_runtime_paths.py",
}
RUNTIME_PREFIXES = (
    "app/src/main/kotlin/org/koitharu/kotatsu/core/db/",
    "app/src/main/kotlin/org/koitharu/kotatsu/backup/",
    "app/src/main/kotlin/org/koitharu/kotatsu/settings/backup/",
    "app/src/main/kotlin/org/koitharu/kotatsu/alternatives/",
    "app/src/main/kotlin/org/koitharu/kotatsu/kotatsumigration/",
    "app/src/main/kotlin/org/koitharu/kotatsu/sync/",
    "app/src/main/kotlin/org/koitharu/kotatsu/core/parser/",
    "app/src/main/kotlin/org/koitharu/kotatsu/details/",
    "app/src/main/kotlin/org/koitharu/kotatsu/local/",
    "app/src/main/kotlin/org/koitharu/kotatsu/reader/domain/",
    "app/src/main/kotlin/org/koitharu/kotatsu/history/data/",
    "app/src/main/kotlin/org/koitharu/kotatsu/favourites/data/",
    "app/src/main/kotlin/org/koitharu/kotatsu/bookmarks/data/",
    "app/src/main/kotlin/org/koitharu/kotatsu/scrobbling/common/data/",
    "app/src/main/kotlin/org/koitharu/kotatsu/stats/data/",
    "app/src/androidTest/kotlin/org/koitharu/kotatsu/core/db/",
    "app/src/androidTest/kotlin/org/koitharu/kotatsu/backup/",
    "app/src/androidTest/kotlin/org/koitharu/kotatsu/settings/backup/",
    "app/src/androidTest/kotlin/org/koitharu/kotatsu/alternatives/",
    "gradle/",
)

def requires_android_runtime(paths: list[str]) -> bool:
    normalized = [p.strip().replace("\\", "/") for p in paths if p.strip()]
    if not normalized:
        return True
    return any(p in RUNTIME_EXACT or p.startswith(RUNTIME_PREFIXES) for p in normalized)

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--paths-file", required=True, type=Path)
    args = parser.parse_args()
    print("true" if requires_android_runtime(args.paths_file.read_text(encoding="utf-8").splitlines()) else "false")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
