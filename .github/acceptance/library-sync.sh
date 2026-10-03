#!/usr/bin/env bash
set -euo pipefail

: "${CANDIDATE_SHA:?}" "${HARNESS_TOOLS:?}" "${ACCEPTANCE_EVIDENCE:?}"
mkdir -p "$ACCEPTANCE_EVIDENCE"
finish() {
    status=$?
    trap - EXIT
    python3 "$HARNESS_TOOLS/runtime_acceptance_evidence.py" junit \
        --results app/build/outputs/androidTest-results/connected \
        --class-name org.koitharu.kotatsu.sync.library.LibrarySyncPersistenceTest \
        --expected-count 3 --output "$ACCEPTANCE_EVIDENCE/test-summary.json" || {
        if [[ "$status" -eq 0 ]]; then status=1; fi
    }
    printf 'exit_code=%s\n' "$status" > "$ACCEPTANCE_EVIDENCE/exit-status.txt"
    adb logcat -d > "$ACCEPTANCE_EVIDENCE/logcat.txt" 2>&1 || true
    for path in app/build/outputs/androidTest-results/connected app/build/reports/androidTests/connected; do
        if [[ -d "$path" ]]; then
            cp -R "$path" "$ACCEPTANCE_EVIDENCE/$(basename "$(dirname "$path")")" || true
        fi
    done
    exit "$status"
}
trap finish EXIT
test "$(git rev-parse HEAD)" = "$CANDIDATE_SHA"
./gradlew :app:connectedDebugAndroidTest --no-daemon --stacktrace \
    -Pandroid.testInstrumentationRunnerArguments.class=org.koitharu.kotatsu.sync.library.LibrarySyncPersistenceTest \
    2>&1 | tee "$ACCEPTANCE_EVIDENCE/gradle-instrumentation.log"
