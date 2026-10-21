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

bash .github/scripts/collect-on-device-cover-evidence.sh \
  ./gradlew connectedDebugAndroidTest --max-workers=4 --no-daemon --stacktrace \
  -Pandroid.testInstrumentationRunnerArguments.class=org.koitharu.kotatsu.local.library.SmartLocalCoverPipelineTest,org.koitharu.kotatsu.core.image.MiyorareImageDiskCacheTest,org.koitharu.kotatsu.kotatsu.runtime.KotatsuRuntimeIsolationTest,org.koitharu.kotatsu.kotatsu.runtime.KotatsuRuntimeRealSourceTest \
  -Pandroid.testInstrumentationRunnerArguments.runtimeFixtureApk="$fixture_apk" \
  -Pandroid.testInstrumentationRunnerArguments.runtimePackApk="$runtime_apk"
