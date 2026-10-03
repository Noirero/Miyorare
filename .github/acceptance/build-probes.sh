#!/usr/bin/env bash
set -euo pipefail

: "${HARNESS_TOOLS:?}" "${ACCEPTANCE_EVIDENCE:?}" "${STABLE_COMMIT:?}" "${CANDIDATE_SHA:?}"
: "${RELEASE_STORE_FILE:?}" "${RELEASE_STORE_PASSWORD:?}" "${RELEASE_KEY_ALIAS:?}" "${RELEASE_KEY_PASSWORD:?}"
mkdir -p "$ACCEPTANCE_EVIDENCE"
finish() {
    status=$?
    trap - EXIT
    printf 'exit_code=%s\n' "$status" > "$ACCEPTANCE_EVIDENCE/probe-build-exit-status.txt"
    # Actions masks console output, not raw files uploaded as artifacts.
    python3 - "$ACCEPTANCE_EVIDENCE" <<'PY'
import os, pathlib, sys
for path in pathlib.Path(sys.argv[1]).rglob('*.log'):
    text = path.read_text(errors='replace')
    for key in ('RELEASE_STORE_PASSWORD', 'RELEASE_KEY_ALIAS', 'RELEASE_KEY_PASSWORD'):
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
    mkdir -p "$evidence"
    printf 'source_sha=%s\n' "$source_sha" > "$evidence/source.txt"
    git checkout --force "$source_sha"
    test "$(git rev-parse HEAD)" = "$source_sha"
    # The two sources must not reuse each other's generated test APK or Room/Hilt outputs.
    rm -rf app/build build
    mkdir -p app/src/androidTest/kotlin/org/koitharu/kotatsu/acceptance
    cp "$HARNESS_TOOLS/SignedUpgradeAcceptanceHarnessTest.kt" app/src/androidTest/kotlin/org/koitharu/kotatsu/acceptance/
    chmod +x gradlew
    ./gradlew -I "$HARNESS_TOOLS/runtime-probe.init.gradle" :app:recordRuntimeProbeProductionGraph \
        --no-configuration-cache --no-daemon --stacktrace \
        -PMIYORARE_ANDROID_TEST_BUILD_TYPE=release -PruntimeProbeBaseline=true \
        -PruntimeProbeEvidenceDir="$evidence" 2>&1 | tee "$evidence/production-baseline.log"
    ./gradlew -I "$HARNESS_TOOLS/runtime-probe.init.gradle" :app:dependencyInsight \
        --configuration releaseAndroidTestRuntimeClasspath --dependency concurrent-futures \
        --no-configuration-cache --no-daemon --stacktrace \
        -PMIYORARE_ANDROID_TEST_BUILD_TYPE=release -PruntimeProbeEvidenceDir="$evidence" \
        2>&1 | tee "$evidence/dependency-insight.log"
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
    mapfile -t apks < <(find app/build/outputs/apk/androidTest/release -type f -name '*.apk')
    test "${#apks[@]}" -eq 1
    cp "${apks[0]}" "$output_apk"
    sha256sum "$output_apk" > "$evidence/probe-sha256.txt"
}
build_probe "$STABLE_COMMIT" stable /tmp/stable-test.apk
build_probe "$CANDIDATE_SHA" candidate /tmp/candidate-test.apk
git checkout --force "$CANDIDATE_SHA"
test "$(git rev-parse HEAD)" = "$CANDIDATE_SHA"
