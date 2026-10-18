#!/usr/bin/env bash
# P5: run P4 durable-state setup and the two P5 process checks on one installed app data boundary.
set -euo pipefail

batch_test_class=org.koitharu.kotatsu.alternatives.work.MigrationBatchWorkRuntimeTest
batch_test_method=actualWorkersPersistResultsRejectOverlappingBatchesAndRecoverCancellationAndReplay
migration_test_class=org.koitharu.kotatsu.alternatives.domain.MigrationEndToEndRuntimeTest
migration_evidence_dir=app/build/reports/migration-process
target_package=org.noirero.miyorare.debug
mkdir -p "$migration_evidence_dir"

find_instrumentation_component() {
  { adb shell pm list instrumentation || true; } | tr -d '\r' | awk -v target="(target=$target_package)" '
    index($0, target) {
      sub(/^instrumentation:/, "", $1)
      print $1
      exit
    }
  '
}

find_first_apk() {
  local root="$1"
  find "$root" -type f -name '*.apk' -print -quit 2>/dev/null || true
}

target_apk="$(find_first_apk app/build/outputs/apk/debug)"
test_apk="$(find_first_apk app/build/outputs/apk/androidTest)"
if [[ -z "$target_apk" || ! -f "$target_apk" ]]; then
  echo "P5 runtime harness cannot find the built debug target APK" >&2
  exit 1
fi
if [[ -z "$test_apk" || ! -f "$test_apk" ]]; then
  echo "P5 runtime harness cannot find the built AndroidTest APK" >&2
  exit 1
fi

# Gradle connectedDebugAndroidTest removes the target/test packages after each invocation. P4 state
# therefore cannot be handed to P5 across two Gradle invocations. Start a deliberate clean boundary
# here, then keep this one installation intact while P4 and both P5 processes run via am instrument.
adb uninstall "$target_package" >/dev/null 2>&1 || true
adb install -r -t "$target_apk"
adb install -r -t "$test_apk"

instrumentation_component="$(find_instrumentation_component)"
if [[ -z "$instrumentation_component" ]]; then
  echo "P5 runtime harness cannot find installed instrumentation for $target_package" >&2
  exit 1
fi

run_case() {
  local label="$1"
  local test_class="$2"
  local method="$3"
  local output="$migration_evidence_dir/${label}-output.txt"

  adb shell am instrument -w -r \
    -e class "$test_class#$method" \
    "$instrumentation_component" | tee "$output"

  if grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED' "$output" || \
      ! grep -Eq '^OK \(1 test\)' "$output"; then
    echo "P5 runtime case $test_class#$method did not complete successfully" >&2
    exit 1
  fi
}

# P4 must create its durable WorkManager evidence inside the exact same target installation that P5
# later reopens. Manual instrumentation exits its own process without uninstalling the app or data.
run_case batch "$batch_test_class" "$batch_test_method"
if ! adb shell pm path "$target_package" | tr -d '\r' | grep -q '^package:'; then
  echo "P5 runtime harness lost target app/data after manual P4 instrumentation" >&2
  exit 1
fi

run_case prepare "$migration_test_class" prepareCommittedAndInterruptedCheckpoints

# Prove the checkpoint exists in target-private storage, then force a genuinely fresh app process.
adb shell run-as "$target_package" test -f files/migration-p5-fixture/checkpoint.json
adb shell am force-stop "$target_package"
run_case reopen "$migration_test_class" reopenCheckpointsRetryWithoutLossAndClean
