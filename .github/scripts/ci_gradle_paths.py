#!/usr/bin/env python3
"""Classify whether a PR needs Gradle-backed validation."""

from __future__ import annotations
import argparse
from pathlib import Path

LOW_RISK_PREFIXES = ("docs/",)
LOW_RISK_EXACT = {"README.md", "LICENSE", "LICENSE.txt", ".gitignore", ".gitattributes"}
FORCE_GRADLE_EXACT = {
    ".github/workflows/ci-fast.yml",
    ".github/workflows/p0-p1-acceptance.yml",
    ".github/scripts/ci_gradle_paths.py",
    ".github/scripts/test_ci_gradle_paths.py",
}

def requires_gradle(paths: list[str]) -> bool:
    normalized = [p.strip().replace("\\", "/") for p in paths if p.strip()]
    if not normalized:
        return True
    for path in normalized:
        if path in FORCE_GRADLE_EXACT:
            return True
        if path in LOW_RISK_EXACT or path.startswith(LOW_RISK_PREFIXES):
            continue
        if path.endswith((".md", ".mdx")) and "/" not in path:
            continue
        return True
    return False

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--paths-file", required=True, type=Path)
    args = parser.parse_args()
    print("true" if requires_gradle(args.paths_file.read_text(encoding="utf-8").splitlines()) else "false")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
