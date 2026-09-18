#!/usr/bin/env python3
"""Resolve the next stable Miyorare release version monotonically."""

from __future__ import annotations

import argparse
import re
from pathlib import Path

SEMVER_RE = re.compile(r"^(\d+)\.(\d+)\.(\d+)$")
TAG_RE = re.compile(r"^v(\d+)\.(\d+)\.(\d+)$")


def parse_version(value: str) -> tuple[int, int, int]:
    match = SEMVER_RE.fullmatch(value.strip())
    if not match:
        raise ValueError(f"invalid semantic version: {value!r}")
    return tuple(int(part) for part in match.groups())


def stable_tag_versions(tags: list[str]) -> list[tuple[int, int, int]]:
    out: list[tuple[int, int, int]] = []
    for raw in tags:
        match = TAG_RE.fullmatch(raw.strip())
        if match:
            out.append(tuple(int(part) for part in match.groups()))
    return out


def format_version(version: tuple[int, int, int]) -> str:
    return ".".join(str(part) for part in version)


def resolve_version(base_version: str, tags: list[str], requested: str = "") -> str:
    base = parse_version(base_version)
    published = stable_tag_versions(tags)
    latest = max(published) if published else None

    if requested.strip():
        candidate = parse_version(requested.strip())
        if latest is not None and candidate <= latest:
            raise ValueError(
                f"requested stable version {format_version(candidate)} must be greater than "
                f"latest published stable version {format_version(latest)}"
            )
        return format_version(candidate)

    if latest is None:
        return format_version(base)

    if base > latest:
        return format_version(base)

    return format_version((latest[0], latest[1], latest[2] + 1))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-version", required=True)
    parser.add_argument("--tags-file", required=True, type=Path)
    parser.add_argument("--requested", default="")
    args = parser.parse_args()

    tags = args.tags_file.read_text(encoding="utf-8").splitlines()
    print(resolve_version(args.base_version, tags, args.requested))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
