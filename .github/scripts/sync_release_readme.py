#!/usr/bin/env python3
"""Normalize stable release notes and refresh README release verification."""

from __future__ import annotations

import argparse
import re
from pathlib import Path

START = "<!-- RELEASE_README_START -->"
END = "<!-- RELEASE_README_END -->"
PROVENANCE = re.compile(r"^<!-- MIYORARE_RELEASE_(?:RUN_ID|SOURCE_SHA):.*? -->$", re.MULTILINE)


def normalize_release_body(body: str, version: str) -> str:
    heading = f"## Miyorare v{version}"
    body = PROVENANCE.sub("", body).strip()
    lines = body.splitlines()
    while lines and not lines[0].strip():
        lines.pop(0)

    # The stable release pipeline owns the version heading. Changelog files may
    # still contain it for readability, so collapse repeated leading headings.
    while lines and lines[0].strip() == heading:
        lines.pop(0)
        while lines and not lines[0].strip():
            lines.pop(0)

    payload = "\n".join(lines).rstrip()
    return f"{heading}\n\n{payload}\n" if payload else f"{heading}\n"


def update_readme(
    readme: str,
    *,
    version: str,
    source_sha: str,
    run_id: str,
    apk_name: str,
    apk_sha256: str,
    repository: str,
) -> str:
    block = "\n".join(
        [
            START,
            f"- **Versi:** [v{version}](https://github.com/{repository}/releases/tag/v{version})",
            f"- **Commit:** [`{source_sha}`](https://github.com/{repository}/commit/{source_sha})",
            f"- **GitHub Actions:** [run {run_id}](https://github.com/{repository}/actions/runs/{run_id})",
            "- **APK dan SHA-256:**",
            f"  - `{apk_name}` — `{apk_sha256}`",
            END,
        ]
    )
    pattern = re.compile(re.escape(START) + r".*?" + re.escape(END), re.DOTALL)
    updated, count = pattern.subn(block, readme, count=1)
    if count != 1:
        raise SystemExit("README release verification markers are missing or duplicated")
    return updated


def main() -> None:
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)

    normalize = sub.add_parser("normalize-release-body")
    normalize.add_argument("--body", required=True)
    normalize.add_argument("--version", required=True)
    normalize.add_argument("--output", required=True)

    readme = sub.add_parser("update-readme")
    readme.add_argument("--readme", required=True)
    readme.add_argument("--version", required=True)
    readme.add_argument("--source-sha", required=True)
    readme.add_argument("--run-id", required=True)
    readme.add_argument("--apk-name", required=True)
    readme.add_argument("--apk-sha256", required=True)
    readme.add_argument("--repository", required=True)

    args = parser.parse_args()
    if args.command == "normalize-release-body":
        body = Path(args.body).read_text(encoding="utf-8")
        Path(args.output).write_text(normalize_release_body(body, args.version), encoding="utf-8")
        return

    path = Path(args.readme)
    current = path.read_text(encoding="utf-8")
    updated = update_readme(
        current,
        version=args.version,
        source_sha=args.source_sha,
        run_id=args.run_id,
        apk_name=args.apk_name,
        apk_sha256=args.apk_sha256,
        repository=args.repository,
    )
    path.write_text(updated, encoding="utf-8")


if __name__ == "__main__":
    main()
