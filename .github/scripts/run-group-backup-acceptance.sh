#!/usr/bin/env bash
set -Eeuo pipefail

# Bound a stuck Gradle/instrumentation process so the workflow can upload
# logs and XML instead of exhausting the overall job timeout.
diagnostics="app/build/group-backup-ci-diagnostics"
mkdir -p "$diagnostics"
on_exit() {
  status=$?
  printf 'exit_code=%s\n' "$status" > "$diagnostics/gradle-exit.txt"
  adb devices -l > "$diagnostics/adb-devices.txt" 2>&1 || true
  timeout 25s adb logcat -d -t 5000 > "$diagnostics/logcat-tail.txt" 2>&1 || true
}
trap on_exit EXIT

printf 'started_utc=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" > "$diagnostics/timing.txt"
# 35 minutes is longer than historical targeted Android 15 runs (~10 min)
# and shorter than the 60-minute job budget, allowing post-failure evidence.
timeout --signal=TERM --kill-after=30s 35m \
  ./gradlew :app:connectedDebugAndroidTest --no-daemon --stacktrace --console=plain \
  -Pandroid.testInstrumentationRunnerArguments.class=org.koitharu.kotatsu.backup.local.LibraryGroupSetBackupRegressionTest \
  2>&1 | tee "$diagnostics/gradle.log"
