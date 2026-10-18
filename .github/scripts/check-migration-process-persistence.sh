#!/usr/bin/env bash
# P5: preserve evidence from two actual instrumentation processes using the same installed data.
set -euo pipefail
migration_test_class=org.koitharu.kotatsu.alternatives.domain.MigrationEndToEndRuntimeTest
migration_evidence_dir=app/build/reports/migration-process
mkdir -p "$migration_evidence_dir"
# The preceding P4 worker process has finished. Retain its report before Gradle replaces it.
cp -R app/build/outputs/androidTest-results/connected "$migration_evidence_dir/batch-work-results"
./gradlew connectedDebugAndroidTest --max-workers=4 --no-daemon --stacktrace \
  "-Pandroid.testInstrumentationRunnerArguments.class=$migration_test_class#prepareCommittedAndInterruptedCheckpoints"
cp -R app/build/outputs/androidTest-results/connected "$migration_evidence_dir/prepare-results"
cp -R app/build/reports/androidTests/connected "$migration_evidence_dir/prepare-report"
adb shell am force-stop org.noirero.miyorare.debug
./gradlew connectedDebugAndroidTest --max-workers=4 --no-daemon --stacktrace \
  "-Pandroid.testInstrumentationRunnerArguments.class=$migration_test_class#reopenCheckpointsRetryWithoutLossAndClean"
cp -R app/build/outputs/androidTest-results/connected "$migration_evidence_dir/reopen-results"
cp -R app/build/reports/androidTests/connected "$migration_evidence_dir/reopen-report"
