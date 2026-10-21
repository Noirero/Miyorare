#!/usr/bin/env bash
set -euo pipefail

./gradlew :kotatsu-runtime-fixture:assembleDebug :kotatsu-runtime:assembleDebug --max-workers=2 --no-daemon --stacktrace

adb install -r kotatsu-runtime-fixture/build/outputs/apk/debug/kotatsu-runtime-fixture-debug.apk
adb install -r kotatsu-runtime/build/outputs/apk/debug/kotatsu-runtime-debug.apk

fixture_apk="$(adb shell pm path org.noirero.miyorare.kotatsu.runtimefixture | sed -n 's/^package://p' | head -n 1 | tr -d '\r')"
if [[ -z "$fixture_apk" ]]; then
  echo "Kotatsu runtime fixture APK path was not reported by package manager" >&2
  exit 1
fi

runtime_apk="$(adb shell pm path org.noirero.miyorare.kotatsu.runtime | sed -n 's/^package://p' | head -n 1 | tr -d '\r')"
if [[ -z "$runtime_apk" ]]; then
  echo "Production Kotatsu runtime APK path was not reported by package manager" >&2
  exit 1
fi

upstream_revision="$(sed -n 's/^revision=//p' kotatsu-runtime/upstream.properties | tr -d '\r' | head -n 1)"
if [[ ! "$upstream_revision" =~ ^[0-9a-f]{40}$ ]]; then
  echo "Invalid Kotatsu upstream revision in kotatsu-runtime/upstream.properties" >&2
  exit 1
fi

runtime_catalog_args=()
if [[ -n "${KOTATSU_EXPECTED_SOURCE_COUNT:-}" ]]; then
  if [[ ! "$KOTATSU_EXPECTED_SOURCE_COUNT" =~ ^[1-9][0-9]*$ ]]; then
    echo "Invalid KOTATSU_EXPECTED_SOURCE_COUNT: $KOTATSU_EXPECTED_SOURCE_COUNT" >&2
    exit 1
  fi
  runtime_catalog_args+=("-Pandroid.testInstrumentationRunnerArguments.runtimeExpectedSourceCount=$KOTATSU_EXPECTED_SOURCE_COUNT")
fi

# The host currently has a pre-existing Tsuki JAR repack task that is not configuration-cache safe.
# Keep this Android acceptance invocation isolated from that unrelated Gradle limitation; the Kotatsu
# runtime build itself remains configuration-cache compatible and is validated by the normal compile job.
bash .github/scripts/collect-on-device-cover-evidence.sh \
  ./gradlew connectedDebugAndroidTest --max-workers=4 --no-daemon --no-configuration-cache --stacktrace \
  -Pandroid.testInstrumentationRunnerArguments.class=org.koitharu.kotatsu.local.library.SmartLocalCoverPipelineTest,org.koitharu.kotatsu.core.image.MiyorareImageDiskCacheTest,org.koitharu.kotatsu.kotatsu.runtime.KotatsuRuntimeIsolationTest,org.koitharu.kotatsu.kotatsu.runtime.KotatsuRuntimeRealSourceTest \
  -Pandroid.testInstrumentationRunnerArguments.runtimeFixtureApk="$fixture_apk" \
  -Pandroid.testInstrumentationRunnerArguments.runtimePackApk="$runtime_apk" \
  -Pandroid.testInstrumentationRunnerArguments.runtimeUpstreamRevision="$upstream_revision" \
  "${runtime_catalog_args[@]}"
