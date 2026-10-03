#!/usr/bin/env python3
"""Classify changes that require the Android runtime acceptance gate."""

from __future__ import annotations

import argparse
from pathlib import Path

OWNER_REQUEST = "ci:owner-request"
USER_ISSUE = "ci:user-issue"
THEME = "ci:theme"
FORCE_RUNTIME = "ci:runtime-required"

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
    "app/src/main/kotlin/org/koitharu/kotatsu/reader/ui/",
    "app/src/main/kotlin/org/koitharu/kotatsu/tracker/work/",
    "app/src/main/kotlin/org/koitharu/kotatsu/core/network/",
    "app/src/main/kotlin/org/koitharu/kotatsu/core/prefs/",
    "app/src/main/kotlin/org/koitharu/kotatsu/favourites/ui/list/",
    "app/src/main/kotlin/org/koitharu/kotatsu/list/ui/",
    "app/src/main/kotlin/org/koitharu/kotatsu/settings/override/",
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

# Owner-request normally skips the emulator, but these changes can invalidate the
# runtime gate itself or persisted user data and therefore fail closed.
CRITICAL_EXACT = {
    "app/build.gradle",
    "gradle.properties",
    "settings.gradle",
    "app/src/main/AndroidManifest.xml",
    ".github/workflows/android-runtime.yml",
    ".github/scripts/android_runtime_paths.py",
    ".github/scripts/test_android_runtime_paths.py",
}
CRITICAL_PREFIXES = (
    "app/src/main/kotlin/org/koitharu/kotatsu/core/db/",
    "app/src/main/kotlin/org/koitharu/kotatsu/backup/",
    "app/src/main/kotlin/org/koitharu/kotatsu/settings/backup/",
    "app/src/main/kotlin/org/koitharu/kotatsu/kotatsumigration/",
    "app/src/androidTest/kotlin/org/koitharu/kotatsu/core/db/",
    "app/src/androidTest/kotlin/org/koitharu/kotatsu/backup/",
    "app/src/androidTest/kotlin/org/koitharu/kotatsu/settings/backup/",
    "gradle/",
)

THEME_EXACT = {
    "app/src/main/kotlin/org/koitharu/kotatsu/main/ui/nav/ExclusiveBottomNavigation.kt",
    "app/src/main/kotlin/org/koitharu/kotatsu/main/ui/nav/ComposeThemeBridge.kt",
}
THEME_PREFIXES = (
    "app/src/main/assets/navigation/themes/",
    "app/src/main/kotlin/org/koitharu/kotatsu/readerjourney/theme/",
    "app/src/androidTest/kotlin/org/koitharu/kotatsu/readerjourney/theme/",
    "app/src/test/kotlin/org/koitharu/kotatsu/readerjourney/theme/",
)


def normalize_paths(paths: list[str]) -> list[str]:
    return [p.strip().replace("\\", "/") for p in paths if p.strip()]


def matches(paths: list[str], exact: set[str], prefixes: tuple[str, ...]) -> bool:
    return any(p in exact or p.startswith(prefixes) for p in normalize_paths(paths))


def requires_android_runtime(paths: list[str]) -> bool:
    normalized = normalize_paths(paths)
    if not normalized:
        return True
    return matches(normalized, RUNTIME_EXACT, RUNTIME_PREFIXES)


def is_theme_change(paths: list[str]) -> bool:
    return matches(paths, THEME_EXACT, THEME_PREFIXES)


def is_critical_change(paths: list[str]) -> bool:
    return matches(paths, CRITICAL_EXACT, CRITICAL_PREFIXES)


def parse_labels(raw: str) -> set[str]:
    return {label.strip().lower() for label in raw.split(",") if label.strip()}


def policy_requires_android_runtime(paths: list[str], labels: set[str]) -> tuple[bool, str]:
    # Strong safety signals always win, regardless of origin.
    if FORCE_RUNTIME in labels:
        return True, "explicit runtime-required override"
    if THEME in labels or is_theme_change(paths):
        return True, "theme changes always require emulator"
    if is_critical_change(paths):
        return True, "critical runtime/persistence safety override"

    # Conflicting origin metadata fails closed instead of guessing.
    if OWNER_REQUEST in labels and USER_ISSUE in labels:
        return True, "conflicting origin labels; fail closed"
    if USER_ISSUE in labels:
        return True, "GitHub user issue/feature requires emulator"
    if OWNER_REQUEST in labels:
        return False, "owner-request non-theme change skips emulator"

    # Unclassified PRs retain the previous path-based behavior.
    required = requires_android_runtime(paths)
    return required, "fallback legacy runtime path classifier"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--paths-file", required=True, type=Path)
    parser.add_argument("--labels", default="")
    parser.add_argument("--explain", action="store_true")
    args = parser.parse_args()

    paths = args.paths_file.read_text(encoding="utf-8").splitlines()
    required, reason = policy_requires_android_runtime(paths, parse_labels(args.labels))
    print("true" if required else "false")
    if args.explain:
        print(reason, file=__import__("sys").stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
