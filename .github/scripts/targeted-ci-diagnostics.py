#!/usr/bin/env python3
"""Manual, read-only GitHub CI diagnostics for exact-head JVM/Android tests.

Not a replacement for mandatory PR compile/JVM/Android checks.
Never interprets a passing targeted rerun as a passing full CI gate.
"""
from __future__ import annotations

import argparse
import json
import re
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Sequence

TEST_RE = re.compile(r"[A-Za-z_][A-Za-z_0-9]*(?:\.[A-Za-z_][A-Za-z_0-9]*)+(?:#[A-Za-z_][A-Za-z_0-9]*)?")
SHA_RE = re.compile(r"[a-fA-F0-9]{40}")
ARTIFACT = Path("diagnostics-evidence")
JVM_RESULTS = Path("app/build/test-results/testDebugUnitTest")
ANDROID_RESULTS = Path("app/build/outputs/androidTest-results/connected")
ANDROID_REPORTS = Path("app/build/reports/androidTests/connected")


def parse_targets(raw: str, mode: str, repeats: int) -> list[str]:
    targets = [target.strip() for target in raw.split(",")]
    if not 1 <= len(targets) <= 8 or any(not TEST_RE.fullmatch(t) for t in targets):
        raise ValueError("Provide 1–8 fully-qualified classes or class#method targets, comma-separated")
    if len(set(targets)) != len(targets):
        raise ValueError("Repeated test targets are not allowed")
    if mode == "isolated" and len(targets) * repeats > 15:
        raise ValueError("Isolated mode is limited to 15 total test invocations")
    return targets


def build_command(platform: str, targets: Sequence[str]) -> list[str]:
    base = ["./gradlew"]
    if platform == "jvm":
        for target in targets:
            base.extend(["--tests", target.replace("#", ".")])
        # --rerun-tasks ensures that a repeated JVM test actually executes each time.
        return ["./gradlew", ":app:testDebugUnitTest", "--rerun-tasks",
                "--max-workers=2", "--no-daemon", "--stacktrace"] + base[1:]
    return ["./gradlew", ":app:connectedDebugAndroidTest", "--max-workers=2",
            "--no-daemon", "--stacktrace",
            "-Pandroid.testInstrumentationRunnerArguments.class=" + ",".join(targets)]


def self_test() -> None:
    assert parse_targets("com.example.ATest#case,com.example.BTest", "grouped", 5) == [
        "com.example.ATest#case", "com.example.BTest"
    ]
    assert build_command("jvm", ["com.example.A#one"])[-2:] == ["--tests", "com.example.A.one"]
    assert build_command("android", ["com.example.A#one"])[-1].endswith("com.example.A#one")
    for bad in ["com.example.Test;rm -rf .", "com.example.Test\nother", "Test",
                "com.example.Test,,com.example.Other", "com.example.Test,com.example.Test"]:
        try:
            parse_targets(bad, "isolated", 1)
        except ValueError:
            pass
        else:
            raise AssertionError("Unsafe/invalid test target accepted")
    try:
        parse_targets(",".join(f"com.example.T{i}" for i in range(8)), "isolated", 5)
    except ValueError:
        pass
    else:
        raise AssertionError("Missing invocation budget")
    print("Diagnostic target parser and command builder: PASS")


def run_command(cmd: Sequence[str], log_path: Path) -> int:
    print("Executing: " + " ".join(cmd), flush=True)
    with log_path.open("w", encoding="utf-8") as log:
        with subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                              text=True, errors="replace", bufsize=1) as proc:
            assert proc.stdout is not None
            for line in proc.stdout:
                print(line, end="", flush=True)
                log.write(line)
            return proc.wait()


def collect_results(platform: str, directory: Path) -> tuple[int, list[str]]:
    roots = [JVM_RESULTS] if platform == "jvm" else [ANDROID_RESULTS, ANDROID_REPORTS]
    count = 0
    failures: list[str] = []
    for root in roots:
        if not root.exists():
            continue
        for file in root.rglob("*.xml"):
            relative = file.relative_to(root)
            output = directory / "test-xml" / root.name / relative
            output.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(file, output)
            try:
                doc = ET.parse(file).getroot()
                for case in doc.iter("testcase"):
                    count += 1
                    for error in list(case):
                        if error.tag in ("failure", "error"):
                            failures.append(
                                f"{case.attrib.get('classname', '')}.{case.attrib.get('name', '')}: "
                                f"{error.attrib.get('message', '')[:300]}"
                            )
            except ET.ParseError:
                failures.append(f"Cannot parse test XML: {file}")
    return count, failures[:20]


def android_failure_evidence(directory: Path) -> None:
    # Best-effort only; failure to capture must not mask the real test result.
    capture = {
        "logcat.txt": ["adb", "logcat", "-d", "-t", "2500"],
        "window.txt": ["adb", "shell", "dumpsys", "window"],
    }
    for name, cmd in capture.items():
        try:
            result = subprocess.run(cmd, capture_output=True, text=True, timeout=25, check=False)
            (directory / name).write_text(result.stdout + result.stderr, encoding="utf-8")
        except (OSError, subprocess.TimeoutExpired) as exc:
            (directory / name).write_text(str(exc), encoding="utf-8")
    try:
        with (directory / "screenshot.png").open("wb") as output:
            subprocess.run(["adb", "exec-out", "screencap", "-p"],
                           stdout=output, stderr=subprocess.DEVNULL, timeout=20, check=False)
    except (OSError, subprocess.TimeoutExpired):
        pass
    # Android UIAutomator hierarchy is NOT a Compose semantics dump.
    try:
        subprocess.run(["adb", "shell", "uiautomator", "dump", "/sdcard/diagnostic-ui.xml"],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=30, check=False)
        with (directory / "android-view-hierarchy.xml").open("wb") as output:
            subprocess.run(["adb", "exec-out", "cat", "/sdcard/diagnostic-ui.xml"],
                           stdout=output, stderr=subprocess.DEVNULL, timeout=15, check=False)
    except (OSError, subprocess.TimeoutExpired):
        pass


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--platform", choices=["android", "jvm"])
    parser.add_argument("--targets")
    parser.add_argument("--mode", choices=["isolated", "grouped"])
    parser.add_argument("--repeats", choices=[1, 3, 5], type=int)
    parser.add_argument("--expected-sha")
    args = parser.parse_args()

    if args.self_test:
        self_test()
        return 0
    if None in (args.platform, args.targets, args.mode, args.repeats, args.expected_sha):
        parser.error("--platform, --targets, --mode, --repeats and --expected-sha are required")
    try:
        targets = parse_targets(args.targets, args.mode, args.repeats)
    except ValueError as exc:
        parser.error(str(exc))
    if not SHA_RE.fullmatch(args.expected_sha):
        parser.error("expected SHA must be a 40-character Git commit hash")
    actual = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
    if actual.lower() != args.expected_sha.lower():
        parser.error("Checked-out HEAD differs from expected SHA")

    ARTIFACT.mkdir(exist_ok=True)
    batches = [[target] for target in targets] if args.mode == "isolated" else [targets]
    runs = []
    for repeat in range(1, args.repeats + 1):
        for group in batches:
            index = len(runs) + 1
            directory = ARTIFACT / f"invocation-{index:02d}"
            directory.mkdir(parents=True, exist_ok=True)
            roots = [JVM_RESULTS] if args.platform == "jvm" else [ANDROID_RESULTS, ANDROID_REPORTS]
            # The runner is ephemeral; prevent XML from a previous repetition being counted twice.
            for root in roots:
                if root.exists():
                    shutil.rmtree(root)
            started = time.monotonic()
            code = run_command(build_command(args.platform, group), directory / "gradle.log")
            count, failures = collect_results(args.platform, directory)
            if code != 0 and args.platform == "android":
                android_failure_evidence(directory)
            runs.append({
                "index": index, "repeat": repeat, "targets": group,
                "exit_code": code, "duration_seconds": round(time.monotonic() - started, 1),
                "reported_tests": count, "failure_details": failures,
            })
            print(f"Diagnostic invocation {index}: {'PASS' if code == 0 else 'FAIL'}", flush=True)

    passes = sum(r["exit_code"] == 0 for r in runs)
    summary = [
        "## Manual targeted CI diagnostic (non-gating)",
        f"- Exact HEAD: \`{actual}\`",
        f"- Platform: **{args.platform}**; mode: **{args.mode}**; repeats: **{args.repeats}**",
        f"- Targets: \`{', '.join(targets)}\`",
        f"- Result: **{passes}/{len(runs)} invocations passed**",
        "- A passing diagnostic does not replace mandatory PR CI or authorize merge.",
        "",
        "| Invocation | Repeat | Targets | Result | Duration |",
        "|---|---:|---|---|---:|",
    ]
    for row in runs:
        summary.append(f"| {row['index']} | {row['repeat']} | "
                       f"\`{', '.join(row['targets'])}\` | "
                       f"{'PASS' if row['exit_code'] == 0 else 'FAIL'} | "
                       f"{row['duration_seconds']}s |")
        for failure in row["failure_details"][:3]:
            summary.append(f"  - Failure: {failure.replace(chr(10), ' ')[:300]}")
    message = "\n".join(summary) + "\n"
    (ARTIFACT / "summary.md").write_text(message, encoding="utf-8")
    (ARTIFACT / "results.json").write_text(json.dumps({
        "sha": actual, "platform": args.platform, "mode": args.mode,
        "targets": targets, "runs": runs,
    }, indent=2), encoding="utf-8")
    with Path(__import__("os").environ.get("GITHUB_STEP_SUMMARY", "/dev/null")).open("a", encoding="utf-8") as out:
        out.write(message)
    return 0 if passes == len(runs) else 1


if __name__ == "__main__":
    sys.exit(main())
