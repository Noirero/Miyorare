#!/usr/bin/env bash
set -euo pipefail

if [[ "${RUN_RUNTIME_ISOLATION:-false}" == "true" ]]; then
  bash .github/scripts/run-kotatsu-runtime-isolation-test.sh
fi

if [[ "${RUN_MIGRATION_PERSISTENCE:-false}" == "true" ]]; then
  bash .github/scripts/check-migration-process-persistence.sh
fi

if [[ -n "${RUNTIME_CLASSES:-}" ]]; then
  # Discovery's Activity/Compose instrumentation is isolated from the rest of
  # the suite so a device transport failure has a smaller diagnostic surface.
  discovery_class=org.koitharu.kotatsu.discovery.DiscoveryNavigationRuntimeTest
  remaining=()
  discovery_selected=false
  IFS=',' read -r -a selected <<< "$RUNTIME_CLASSES"
  for class in "${selected[@]}"; do
    if [[ "$class" == "$discovery_class" ]]; then
      discovery_selected=true
    else
      remaining+=("$class")
    fi
  done

  run_classes() {
    local label="$1" classes="$2"
    [[ -n "$classes" ]] || return 0
    echo "Android runtime partition: $label"
    bash .github/scripts/collect-android-runtime-diagnostics.sh \
      ./gradlew connectedDebugAndroidTest \
      --max-workers=2 \
      --no-daemon \
      --stacktrace \
      "-Pandroid.testInstrumentationRunnerArguments.class=$classes"
  }

  if (( ${#remaining[@]} > 0 )); then
    remaining_csv="$(IFS=,; echo "${remaining[*]}")"
    run_classes core "$remaining_csv"
  fi
  if [[ "$discovery_selected" == true ]]; then
    run_classes discovery "$discovery_class"
  fi
fi
