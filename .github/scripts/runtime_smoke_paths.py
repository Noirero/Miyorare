#!/usr/bin/env python3
"""Fail-safe classifier for the beta -> main Android runtime smoke gate."""

from __future__ import annotations

import argparse
from pathlib import Path

SAFE_EXACT_PATHS = frozenset(
    {
        ".github/release-notes.md",
        "README.md",
        "SECURITY.md",
        "LICENSE",
        "LICENSE.md",
        "LICENSE.txt",
        "NOTICE",
        "NOTICE.md",
        "NOTICE.txt",
    }
)

SAFE_PREFIXES = (
    "docs/",
)


def is_explicitly_non_runtime(path: str) -> bool:
    normalized = path.strip().replace("\\", "/")
    if not normalized:
        return False
    if normalized in SAFE_EXACT_PATHS:
        return True
    return any(normalized.startswith(prefix) for prefix in SAFE_PREFIXES)


def requires_runtime_smoke(paths: list[str]) -> bool:
    """Return True unless every changed path is explicitly known to be non-runtime."""
    normalized_paths = [path.strip() for path in paths if path.strip()]
    if not normalized_paths:
        # Empty/unresolved diffs fail closed.
        return True
    return any(not is_explicitly_non_runtime(path) for path in normalized_paths)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--paths-file", required=True, type=Path)
    args = parser.parse_args()

    paths = args.paths_file.read_text(encoding="utf-8").splitlines()
    print("true" if requires_runtime_smoke(paths) else "false")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
