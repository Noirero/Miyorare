#!/usr/bin/env python3
import argparse
import re
from pathlib import Path

VERSION_NAME_RE = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+$")
VERSION_CODE_RE = re.compile(r"^[0-9]+$")


def apply_version(path: Path, version_name: str, version_code_text: str, stable_version_name: str, stable_version_code: int) -> None:
    if not VERSION_NAME_RE.fullmatch(version_name):
        raise SystemExit("version_name must be a three-part numeric version")
    if not VERSION_CODE_RE.fullmatch(version_code_text):
        raise SystemExit("version_code must be numeric")
    if tuple(map(int, version_name.split("."))) <= tuple(map(int, stable_version_name.split("."))):
        raise SystemExit("candidate versionName must be greater than stable versionName")
    version_code = int(version_code_text)
    if version_code <= stable_version_code:
        raise SystemExit("candidate versionCode must be greater than stable versionCode")
    if version_code > 2_100_000_000:
        raise SystemExit("candidate versionCode is too large")

    text = path.read_text(encoding="utf-8")
    text, count_name = re.subn(r"versionName = '[^']+'", f"versionName = '{version_name}'", text, count=1)
    text, count_code = re.subn(r"versionCode = \d+", f"versionCode = {version_code}", text, count=1)
    if count_name != 1 or count_code != 1:
        raise SystemExit("could not update versionName/versionCode exactly once")
    path.write_text(text, encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--build-gradle", type=Path, required=True)
    parser.add_argument("--version-name", required=True)
    parser.add_argument("--version-code", required=True)
    parser.add_argument("--stable-version-name", required=True)
    parser.add_argument("--stable-version-code", type=int, required=True)
    args = parser.parse_args()
    apply_version(args.build_gradle, args.version_name, args.version_code, args.stable_version_name, args.stable_version_code)


if __name__ == "__main__":
    main()
