#!/usr/bin/env bash
set -euo pipefail

: "${HARNESS_TOOLS:?}" "${ACCEPTANCE_EVIDENCE:?}" "${STABLE_COMMIT:?}" "${CANDIDATE_SHA:?}"
: "${RELEASE_STORE_FILE:?}" "${RELEASE_STORE_PASSWORD:?}" "${RELEASE_KEY_ALIAS:?}" "${RELEASE_KEY_PASSWORD:?}"
mkdir -p "$ACCEPTANCE_EVIDENCE"
active_probe_evidence=''
capture_r8_diagnostics() {
    local diagnostic relative
    if [[ -n "$active_probe_evidence" && -d app/build/outputs/mapping ]]; then
        mkdir -p "$active_probe_evidence/r8" || return "$?"
        # Allowlist text diagnostics only: never copy the build tree or signing inputs.
        while IFS= read -r -d '' diagnostic; do
            relative="${diagnostic#app/build/outputs/mapping/}"
            mkdir -p "$active_probe_evidence/r8/$(dirname "$relative")" || return "$?"
            cp "$diagnostic" "$active_probe_evidence/r8/$relative" || return "$?"
        done < <(find app/build/outputs/mapping -type f \
            \( -name missing_rules.txt -o -name configuration.txt \) -print0)
    fi
}
finish() {
    status=$?
    trap - EXIT
    if ! capture_r8_diagnostics; then
        printf 'Failed to retain R8 diagnostics\n' >&2
        if [[ "$status" -eq 0 ]]; then status=1; fi
    fi
    printf 'exit_code=%s\n' "$status" > "$ACCEPTANCE_EVIDENCE/probe-build-exit-status.txt"
    # Actions masks console output, not raw files uploaded as artifacts.
    python3 - "$ACCEPTANCE_EVIDENCE" <<'PY'
import os, pathlib, sys
for path in pathlib.Path(sys.argv[1]).rglob('*'):
    if not path.is_file() or path.suffix not in ('.log', '.txt'):
        continue
    text = path.read_text(errors='replace')
    for key in ('RELEASE_STORE_FILE', 'RELEASE_STORE_PASSWORD', 'RELEASE_KEY_ALIAS', 'RELEASE_KEY_PASSWORD'):
        value = os.environ.get(key)
        if value:
            text = text.replace(value, '***')
    path.write_text(text)
PY
    exit "$status"
}
trap finish EXIT

build_probe() {
    local source_sha="$1" label="$2" output_apk="$3"
    local evidence="$ACCEPTANCE_EVIDENCE/probe-$label"
    active_probe_evidence=''
    mkdir -p "$evidence"
    printf 'source_sha=%s\n' "$source_sha" > "$evidence/source.txt"
    git checkout --force "$source_sha"
    test "$(git rev-parse HEAD)" = "$source_sha"
    # The two sources must not reuse each other's generated test APK or Room/Hilt outputs.
    rm -rf app/build build
    active_probe_evidence="$evidence"
    mkdir -p app/src/androidTest/kotlin/org/koitharu/kotatsu/acceptance
    cp "$HARNESS_TOOLS/SignedUpgradeAcceptanceHarnessTest.kt" app/src/androidTest/kotlin/org/koitharu/kotatsu/acceptance/
    chmod +x gradlew
    ./gradlew -I "$HARNESS_TOOLS/runtime-probe.init.gradle" :app:recordRuntimeProbeProductionGraph \
        --no-configuration-cache --no-daemon --stacktrace \
        -PMIYORARE_ANDROID_TEST_BUILD_TYPE=release -PruntimeProbeBaseline=true \
        -PruntimeProbeEvidenceDir="$evidence" 2>&1 | tee "$evidence/production-baseline.log"
    for classpath in releaseAndroidTestCompileClasspath releaseAndroidTestRuntimeClasspath; do
        # One report per classpath covers core, concurrent-futures and shared AndroidX modules.
        ./gradlew -I "$HARNESS_TOOLS/runtime-probe.init.gradle" :app:dependencyInsight \
            --configuration "$classpath" --dependency androidx \
            --no-configuration-cache --no-daemon --stacktrace \
            -PMIYORARE_ANDROID_TEST_BUILD_TYPE=release -PruntimeProbeEvidenceDir="$evidence" \
            2>&1 | tee "$evidence/dependency-insight-$classpath.log"
    done
    ./gradlew -I "$HARNESS_TOOLS/runtime-probe.init.gradle" :app:verifyRuntimeProbeClasspaths \
        --no-configuration-cache --no-daemon --stacktrace \
        -PMIYORARE_ANDROID_TEST_BUILD_TYPE=release -PruntimeProbeEvidenceDir="$evidence" \
        2>&1 | tee "$evidence/classpath-verification.log"
    ./gradlew -I "$HARNESS_TOOLS/runtime-probe.init.gradle" :app:assembleReleaseAndroidTest \
        --no-configuration-cache --no-daemon --stacktrace \
        -PMIYORARE_ANDROID_TEST_BUILD_TYPE=release -PruntimeProbeEvidenceDir="$evidence" \
        -PRELEASE_STORE_FILE="$RELEASE_STORE_FILE" \
        -PRELEASE_STORE_PASSWORD="$RELEASE_STORE_PASSWORD" \
        -PRELEASE_KEY_ALIAS="$RELEASE_KEY_ALIAS" \
        -PRELEASE_KEY_PASSWORD="$RELEASE_KEY_PASSWORD" \
        2>&1 | tee "$evidence/assemble-probe.log"
    capture_r8_diagnostics
    mapfile -t apks < <(find app/build/outputs/apk/androidTest/release -type f -name '*.apk')
    test "${#apks[@]}" -eq 1
    cp "${apks[0]}" "$output_apk"
    sha256sum "$output_apk" > "$evidence/probe-sha256.txt"
}
build_probe "$STABLE_COMMIT" stable /tmp/stable-test.apk
build_probe "$CANDIDATE_SHA" candidate /tmp/candidate-test.apk
git checkout --force "$CANDIDATE_SHA"
test "$(git rev-parse HEAD)" = "$CANDIDATE_SHA"
