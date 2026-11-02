#!/usr/bin/env python3
"""Strict parser for owner-only Miyori commands."""

from __future__ import annotations

import argparse
import sys

COMMANDS = {
    "/miyori help": "help",
    "/miyori status": "status",
    "/miyori checks": "checks",
    "/miyori explain": "explain",
    "/miyori labels": "labels",
    "/miyori readiness": "readiness",
    "/miyori head": "head",
    "/miyori tests": "tests",
    "/miyori require-runtime": "require-runtime",
}


def parse_command(text: str) -> str:
    lines = [line.strip() for line in text.splitlines() if line.strip()]
    if len(lines) != 1:
        return "unknown"
    return COMMANDS.get(lines[0], "unknown")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("text")
    args = parser.parse_args()
    print(parse_command(args.text))
    return 0


if __name__ == "__main__":
    sys.exit(main())
