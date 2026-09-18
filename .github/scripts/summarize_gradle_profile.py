#!/usr/bin/env python3
"""Summarize the slowest tasks from a Gradle --profile HTML report."""

from __future__ import annotations

import argparse
import re
from html.parser import HTMLParser
from pathlib import Path

DURATION_PART_RE = re.compile(r"([0-9]+(?:\.[0-9]+)?)\s*(ms|s|m|h)")


def parse_duration_seconds(value: str) -> float | None:
    text = " ".join(value.split()).lower()
    if not text:
        return None
    parts = DURATION_PART_RE.findall(text)
    if not parts:
        return None
    consumed = "".join(f"{number}{unit}" for number, unit in parts)
    compact = re.sub(r"\s+", "", text)
    if consumed != compact:
        return None

    scale = {"ms": 0.001, "s": 1.0, "m": 60.0, "h": 3600.0}
    return sum(float(number) * scale[unit] for number, unit in parts)


class ProfileParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__()
        self._capture_heading = False
        self._heading_parts: list[str] = []
        self.current_heading = ""
        self.in_table = False
        self.table_heading = ""
        self.in_row = False
        self.in_cell = False
        self.cell_parts: list[str] = []
        self.row: list[str] = []
        self.rows: list[tuple[str, list[str]]] = []

    def handle_starttag(self, tag: str, attrs) -> None:
        if tag in {"h1", "h2", "h3", "h4"}:
            self._capture_heading = True
            self._heading_parts = []
        elif tag == "table":
            self.in_table = True
            self.table_heading = self.current_heading
        elif tag == "tr" and self.in_table:
            self.in_row = True
            self.row = []
        elif tag in {"td", "th"} and self.in_row:
            self.in_cell = True
            self.cell_parts = []

    def handle_endtag(self, tag: str) -> None:
        if tag in {"h1", "h2", "h3", "h4"} and self._capture_heading:
            self.current_heading = " ".join("".join(self._heading_parts).split())
            self._capture_heading = False
        elif tag in {"td", "th"} and self.in_cell:
            self.row.append(" ".join("".join(self.cell_parts).split()))
            self.in_cell = False
        elif tag == "tr" and self.in_row:
            if self.row:
                self.rows.append((self.table_heading, self.row[:]))
            self.in_row = False
        elif tag == "table":
            self.in_table = False
            self.table_heading = ""

    def handle_data(self, data: str) -> None:
        if self._capture_heading:
            self._heading_parts.append(data)
        if self.in_cell:
            self.cell_parts.append(data)


def extract_tasks(html: str) -> list[tuple[float, str, str]]:
    parser = ProfileParser()
    parser.feed(html)
    tasks: list[tuple[float, str, str]] = []

    for heading, row in parser.rows:
        task = next((cell for cell in row if cell.startswith(":")), None)
        if task is None:
            continue
        duration_cell = next(
            (cell for cell in row if parse_duration_seconds(cell) is not None),
            None,
        )
        if duration_cell is None:
            continue
        duration = parse_duration_seconds(duration_cell)
        assert duration is not None
        tasks.append((duration, task, heading))

    tasks.sort(reverse=True)
    return tasks


def find_profile(profile_dir: Path) -> Path:
    candidates = sorted(
        profile_dir.rglob("profile-*.html"),
        key=lambda path: path.stat().st_mtime,
        reverse=True,
    )
    if not candidates:
        raise FileNotFoundError(f"No Gradle profile HTML found under {profile_dir}")
    return candidates[0]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--profile-dir", type=Path, required=True)
    parser.add_argument("--top", type=int, default=15)
    args = parser.parse_args()

    report = find_profile(args.profile_dir)
    tasks = extract_tasks(report.read_text(encoding="utf-8", errors="replace"))
    if not tasks:
        raise SystemExit(f"No task durations could be parsed from {report}")

    print(f"Gradle profile: {report}")
    print(f"Top {min(args.top, len(tasks))} task durations:")
    for duration, task, heading in tasks[: args.top]:
        suffix = f" [{heading}]" if heading else ""
        print(f"{duration:9.3f}s  {task}{suffix}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
