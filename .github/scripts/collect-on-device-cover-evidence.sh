#!/usr/bin/env bash
set -euo pipefail

# Run inside the existing Android 15 emulator session, after connectedDebugAndroidTest.
# The fixture reports contain synthetic numeric measurements only.
report_dir=app/build/reports/on-device-cover
mkdir -p "$report_dir"
for survey in recipe mixed350; do
    adb shell run-as org.noirero.miyorare.debug cat "files/on-device-cover-survey/$survey.json" > "$report_dir/$survey.json"
    python3 -m json.tool "$report_dir/$survey.json"
done
git rev-parse HEAD > "$report_dir/commit.txt"
sha256sum app/build/outputs/apk/debug/*.apk > "$report_dir/apk-sha256.txt"
cat "$report_dir/commit.txt" "$report_dir/apk-sha256.txt"
