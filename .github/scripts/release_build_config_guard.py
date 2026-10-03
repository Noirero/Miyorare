#!/usr/bin/env python3
from pathlib import Path
import sys

def extract_block(text, marker_index):
    start = text.index("{", marker_index)
    depth = 0
    for i in range(start, len(text)):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                return text[start + 1:i]
    raise ValueError("Unterminated Gradle block")

def verify_release_config(text):
    build_types = extract_block(text, text.index("buildTypes {"))
    release = extract_block(build_types, build_types.index("release {"))
    required = (
        "buildConfigField 'boolean', 'READER_JOURNEY_UNLOCK_ALL_REWARDS', 'false'",
        "buildConfigField 'boolean', 'EXCLUSIVE_THEME_QA_ENABLED', 'false'",
    )
    missing = [x for x in required if x not in release]
    if missing:
        raise ValueError("Stable/Main release flags are unsafe: " + ", ".join(missing))

if __name__ == "__main__":
    try:
        verify_release_config(Path(sys.argv[1] if len(sys.argv) > 1 else "app/build.gradle").read_text(encoding="utf-8"))
    except (ValueError, OSError) as error:
        raise SystemExit(str(error))
    print("Verified stable release-only Reader Journey and Exclusive Theme flags")
