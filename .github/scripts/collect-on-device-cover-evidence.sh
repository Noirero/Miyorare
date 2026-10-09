#!/usr/bin/env bash
set -euo pipefail

# Wrap connectedDebugAndroidTest inside the existing Android 15 emulator session.
# AGP uninstalls test packages afterward, so capture synthetic reports on the host
# while instrumentation executes rather than reading the app's private files later.
if (( $# == 0 )); then
    echo "Usage: $0 <connectedDebugAndroidTest command and arguments>" >&2
    exit 2
fi
report_dir=app/build/reports/on-device-cover
mkdir -p "$report_dir"
adb logcat -c
adb logcat -v raw SmartLocalCoverReport:I '*:S' > "$report_dir/instrumentation-reports.log" &
logcat_pid=$!
stop_report_stream() {
    kill "$logcat_pid" 2>/dev/null || true
    wait "$logcat_pid" 2>/dev/null || true
}
trap stop_report_stream EXIT
"$@"
stop_report_stream
trap - EXIT

python3 - <<'PY'
import json
from pathlib import Path

directory = Path("app/build/reports/on-device-cover")
expected = {"recipe", "mixed350", "pdf", "archives", "format-heavy"}
reports = {}
for line in (directory / "instrumentation-reports.log").read_text().splitlines():
    if not line.startswith("REPORT "):
        continue
    _, name, payload = line.split(" ", 2)
    if name not in expected:
        raise SystemExit(f"Unexpected cover survey: {name}")
    report = json.loads(payload)
    if not isinstance(report, dict) or report.get("fixture") != "synthetic" or not report.get("recipe"):
        raise SystemExit(f"Invalid synthetic cover survey: {name}")
    if name in reports:
        raise SystemExit(f"Duplicate cover survey: {name}")
    reports[name] = report
if set(reports) != expected:
    raise SystemExit(f"Missing cover surveys: {sorted(expected - set(reports))}")
if len({report["recipe"] for report in reports.values()}) != 1:
    raise SystemExit("Inconsistent cover recipe in fixture reports")
for name in sorted(reports):
    formatted = json.dumps(reports[name], indent=2, sort_keys=True)
    (directory / f"{name}.json").write_text(formatted + "\n")
    print(f"SYNTHETIC SURVEY {name}\n{formatted}")
PY
git rev-parse HEAD > "$report_dir/commit.txt"
sha256sum app/build/outputs/apk/debug/*.apk > "$report_dir/apk-sha256.txt"
cat "$report_dir/commit.txt" "$report_dir/apk-sha256.txt"
