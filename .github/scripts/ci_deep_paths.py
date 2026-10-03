#!/usr/bin/env python3
"""Classify full JVM regression and compile-only Android test validation."""

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

# Repository metadata cannot change the Android test compile classpath. Keep
# the existing JVM routing intact; only the new compile work uses this allowlist.
ANDROID_COMPILE_METADATA_EXACT = {
    ".github/CODEOWNERS",
    ".github/PULL_REQUEST_TEMPLATE.md",
    ".github/release-notes.md",
    ".github/BUILD_NOW",
    ".github/PF5_BUILD_NOW",
}
ANDROID_COMPILE_METADATA_PREFIXES = (
    ".github/ISSUE_TEMPLATE/",
    ".github/PULL_REQUEST_TEMPLATE/",
)

# These inputs are already compiled against Debug by the combined JVM/Android
# compile invocation. Variant-only sources, build inputs and unknown paths also
# need Preview; its testBuildType cannot coexist with testDebugUnitTest in AGP.
DEBUG_COMPILED_PREFIXES = (
    "app/src/main/",
    "app/src/debug/",
    "app/src/test/",
    "app/src/testDebug/",
    "app/src/androidTest/",
    "app/src/androidTestDebug/",
)

def requires_android_test_compile(paths: list[str]) -> bool:
    normalized = [p.strip().replace("\\", "/") for p in paths if p.strip()]
    if not normalized:
        return True
    compile_paths = [
        p for p in normalized
        if p not in ANDROID_COMPILE_METADATA_EXACT
        and not p.startswith(ANDROID_COMPILE_METADATA_PREFIXES)
    ]
    return bool(compile_paths) and requires_deep(compile_paths)

def requires_preview_android_test_compile(paths: list[str]) -> bool:
    normalized = [p.strip().replace("\\", "/") for p in paths if p.strip()]
    if not normalized:
        return True
    return any(
        requires_android_test_compile([p]) and not p.startswith(DEBUG_COMPILED_PREFIXES)
        for p in normalized
    )

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
    parser.add_argument("--gate", choices=("deep", "android-test-compile", "preview-android-test-compile"), default="deep")
    args = parser.parse_args()
    paths = args.paths_file.read_text(encoding="utf-8").splitlines()
    classifier = {
        "deep": requires_deep,
        "android-test-compile": requires_android_test_compile,
        "preview-android-test-compile": requires_preview_android_test_compile,
    }[args.gate]
    print("true" if classifier(paths) else "false")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
