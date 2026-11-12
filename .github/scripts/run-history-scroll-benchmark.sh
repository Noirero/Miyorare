#!/usr/bin/env bash
set -euo pipefail

# Mandatory full-size History A/B workload, isolated on a *fresh* Android 15 VM.
# This command must fail closed on a missing, skipped or failed testcase.
benchmark_class=org.koitharu.kotatsu.history.ui.HistoryScrollBenchmarkTest
bash .github/scripts/collect-android-runtime-diagnostics.sh \
  ./gradlew connectedDebugAndroidTest --max-workers=2 --no-daemon --stacktrace \
  "-Pandroid.testInstrumentationRunnerArguments.class=$benchmark_class"

python3 - <<'PY'
from pathlib import Path
from xml.etree import ElementTree

paths = list(Path("app/build/outputs/androidTest-results/connected").rglob("TEST-*.xml"))
expected = "org.koitharu.kotatsu.history.ui.HistoryScrollBenchmarkTest"
matches = []
for path in paths:
    for case in ElementTree.parse(path).getroot().iter("testcase"):
        if case.attrib.get("classname") == expected:
            matches.append(case)
assert len(matches) == 1, f"Expected exactly one History benchmark execution, got {len(matches)}"
assert all(
    not any(child.tag in ("failure", "error", "skipped") for child in case)
    for case in matches
), "History A/B benchmark did not pass"
print("History full A/B benchmark executed and passed on fresh Android 15.")
PY
