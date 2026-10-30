#!/usr/bin/env bash
set -euo pipefail

if [[ "${RUN_RUNTIME_ISOLATION:-false}" == "true" ]]; then
  bash .github/scripts/run-kotatsu-runtime-isolation-test.sh
fi

if [[ "${RUN_MIGRATION_PERSISTENCE:-false}" == "true" ]]; then
  bash .github/scripts/check-migration-process-persistence.sh
fi

if [[ -n "${RUNTIME_CLASSES:-}" ]]; then
  # TEMPORARY DIAGNOSTIC: one previously interrupted instrumentation test.
  # Restore the original selection before final PR validation.
  diagnostic_test="org.koitharu.kotatsu.favourites.ui.CategorySetsUiTest#longAndEmptySetRemainNavigableAcrossContentTypesAndWidths"
  report_dir=app/build/reports/android-runtime-diagnostics
  mkdir -p "$report_dir"
  (
    while true; do
      printf '%s ' "$(date -u +%FT%TZ)"
      adb devices -l | tail -n +2 || true
      adb shell pidof org.koitharu.kotatsu 2>/dev/null || true
      adb shell pidof org.koitharu.kotatsu.test 2>/dev/null || true
      sleep 2
    done
  ) > "$report_dir/adb-process-heartbeat.txt" 2>&1 &
  heartbeat_pid=$!
  trap 'kill "$heartbeat_pid" 2>/dev/null || true' EXIT
  bash .github/scripts/collect-android-runtime-diagnostics.sh \
    ./gradlew connectedDebugAndroidTest \
    --max-workers=4 \
    --no-daemon \
    --stacktrace \
    "-Pandroid.testInstrumentationRunnerArguments.class=$diagnostic_test"
fi
