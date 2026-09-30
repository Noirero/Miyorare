#!/usr/bin/env python3
"""Classify changes that require the full JVM regression gate."""

from __future__ import annotations
import argparse
from pathlib import Path

LOW_RISK_PREFIXES = (
    "docs/",
)
LOW_RISK_EXACT = {
    "README.md",
    "LICENSE",
    "LICENSE.txt",
    ".gitignore",
    ".gitattributes",
}

FORCE_DEEP_EXACT = {
    ".github/workflows/ci-deep.yml",
    ".github/scripts/ci_deep_paths.py",
    ".github/scripts/test_ci_deep_paths.py",
}

def requires_deep(paths: list[str]) -> bool:
    normalized = [p.strip().replace("\\", "/") for p in paths if p.strip()]
    if not normalized:
        return True

    for path in normalized:
        if path in FORCE_DEEP_EXACT:
            return True
        if path in LOW_RISK_EXACT:
            continue
        if path.startswith(LOW_RISK_PREFIXES):
            continue
        if path.endswith((".md", ".mdx")) and "/" not in path:
            continue
        return True
    return False

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--paths-file", required=True, type=Path)
    args = parser.parse_args()
    paths = args.paths_file.read_text(encoding="utf-8").splitlines()
    print("true" if requires_deep(paths) else "false")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
