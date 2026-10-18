#!/usr/bin/env bash
# P5: preserve evidence from two actual instrumentation processes using the same installed app data.
set -euo pipefail

migration_test_class=org.koitharu.kotatsu.alternatives.domain.MigrationEndToEndRuntimeTest
migration_evidence_dir=app/build/reports/migration-process
target_package=org.noirero.miyorare.debug
mkdir -p "$migration_evidence_dir"

# The preceding P4 WorkManager instrumentation invocation owns useful durable state. A second
# Gradle connectedDebugAndroidTest invocation reinstalls/clears the target boundary, so run the
# already-built instrumentation component directly for both P5 processes instead.
cp -R app/build/outputs/androidTest-results/connected "$migration_evidence_dir/batch-work-results"

find_instrumentation_component() {
  { adb shell pm list instrumentation || true; } | tr -d '\r' | awk -v target="(target=$target_package)" '
    index($0, target) {
      sub(/^instrumentation:/, "", $1)
      print $1
      exit
    }
  '
}

# connectedDebugAndroidTest may remove only the instrumentation package after a successful run.
# The target app (and its P4 WorkManager/private data) must still exist; never reinstall/clear it here.
if ! adb shell pm path "$target_package" | tr -d '\r' | grep -q '^package:'; then
  echo "P5 runtime harness lost target app/data after the P4 runtime test; refusing to recreate state" >&2
  exit 1
fi

instrumentation_component="$(find_instrumentation_component)"
if [[ -z "$instrumentation_component" ]]; then
  test_apk="$(find app/build/outputs/apk/androidTest -type f -name '*.apk' -print 2>/dev/null | sort | head -n 1)"
  if [[ -z "$test_apk" || ! -f "$test_apk" ]]; then
    echo "P5 runtime harness cannot find the built AndroidTest APK" >&2
    exit 1
  fi
  # Reinstall only the test package. This restores the runner without touching target app data.
  adb install -r -t "$test_apk"
  instrumentation_component="$(find_instrumentation_component)"
fi
if [[ -z "$instrumentation_component" ]]; then
  echo "P5 runtime harness cannot find installed instrumentation for $target_package after test-APK reinstall" >&2
  exit 1
fi

run_case() {
  local label="$1"
  local method="$2"
  local output="$migration_evidence_dir/${label}-output.txt"

  adb shell am instrument -w -r \
    -e class "$migration_test_class#$method" \
    "$instrumentation_component" | tee "$output"

  if grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED' "$output" || \
      ! grep -Eq '^OK \(1 test\)' "$output"; then
    echo "P5 runtime case $method did not complete successfully" >&2
    exit 1
  fi
}

run_case prepare prepareCommittedAndInterruptedCheckpoints

# Prove the checkpoint exists in the target app's private storage before forcing a new app process.
adb shell run-as "$target_package" test -f files/migration-p5-fixture/checkpoint.json
adb shell am force-stop "$target_package"
run_case reopen reopenCheckpointsRetryWithoutLossAndClean
