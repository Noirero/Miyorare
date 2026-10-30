#!/usr/bin/env bash
set -euo pipefail

if [[ "${RUN_RUNTIME_ISOLATION:-false}" == "true" ]]; then
  bash .github/scripts/run-kotatsu-runtime-isolation-test.sh
fi

if [[ "${RUN_MIGRATION_PERSISTENCE:-false}" == "true" ]]; then
  bash .github/scripts/check-migration-process-persistence.sh
fi

if [[ -n "${RUNTIME_CLASSES:-}" ]]; then
  bash .github/scripts/collect-android-runtime-diagnostics.sh \
    ./gradlew connectedDebugAndroidTest \
    --max-workers=4 \
    --no-daemon \
    --stacktrace \
    "-Pandroid.testInstrumentationRunnerArguments.class=${RUNTIME_CLASSES}"
fi
