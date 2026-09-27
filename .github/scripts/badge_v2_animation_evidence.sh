#!/usr/bin/env bash
set -euo pipefail

adb shell wm size 864x1536
adb shell wm density 320
adb shell settings put system font_scale 1.0
adb shell cmd uimode night yes
adb shell cmd power set-mode 0 || true
adb shell rm -rf /sdcard/Download/miyorare-badge-v2-animation

mkdir -p badge-v2-animation-videos badge-v2-animation-files

record_connected_test() {
  local method="$1"
  local out="$2"
  local limit="$3"
  local log="$4"

  adb shell rm -f "/sdcard/$out"

  ./gradlew :app:connectedPreviewAndroidTest --no-daemon --stacktrace \
    -PMIYORARE_ANDROID_TEST_BUILD_TYPE=preview \
    -PMIYORARE_VISUAL_TEST_SIGNING=true \
    -Pandroid.testInstrumentationRunnerArguments.class="org.koitharu.kotatsu.stats.ui.BadgeV2AnimationEvidenceTest#$method" \
    > "$log" 2>&1 &
  local gradle_pid=$!

  local found=0
  local attempt=0
  while [[ "$attempt" -lt 1200 ]]; do
    if adb shell dumpsys activity activities 2>/dev/null | tr -d '\r' | grep -q "StatsActivity"; then
      found=1
      break
    fi
    if ! kill -0 "$gradle_pid" 2>/dev/null; then
      break
    fi
    attempt=$((attempt + 1))
    sleep 0.5
  done

  if [[ "$found" -ne 1 ]]; then
    wait "$gradle_pid" || true
    cat "$log"
    echo "::error::StatsActivity never became visible for $method"
    return 1
  fi

  adb shell screenrecord \
    --size 720x1280 \
    --bit-rate 6000000 \
    --time-limit "$limit" \
    "/sdcard/$out" || true

  if ! wait "$gradle_pid"; then
    cat "$log"
    return 1
  fi
  grep -q "BUILD SUCCESSFUL" "$log"

  adb pull "/sdcard/$out" "badge-v2-animation-videos/$out"
  test -s "badge-v2-animation-videos/$out"
}

record_connected_test largePreviewSignatureSequence \
  badge-v2-large-preview-01-12.mp4 78 large-preview-instrumentation.txt

adb shell cmd power set-mode 0 || true
record_connected_test equippedProfileIdleEvidence \
  badge-v2-profile-equipped.mp4 18 profile-instrumentation.txt

adb shell cmd power set-mode 0 || true
record_connected_test reduceMotionEvidence \
  badge-v2-reduce-motion.mp4 12 reduce-motion-instrumentation.txt

adb shell cmd power set-mode 1
sleep 1
adb shell dumpsys power | grep -i -E 'mIsPowered|mBatteryLevel|mLowPower|Power Save|battery saver' > battery-saver-state.txt || true
record_connected_test batterySaverEvidence \
  badge-v2-battery-saver.mp4 12 battery-saver-instrumentation.txt
adb shell cmd power set-mode 0 || true

adb pull /sdcard/Download/miyorare-badge-v2-animation/. badge-v2-animation-files/

test -s badge-v2-animation-videos/badge-v2-large-preview-01-12.mp4
test -s badge-v2-animation-videos/badge-v2-profile-equipped.mp4
test -s badge-v2-animation-videos/badge-v2-reduce-motion.mp4
test -s badge-v2-animation-videos/badge-v2-battery-saver.mp4
test -s badge-v2-animation-files/large-preview-sequence.json
test -s badge-v2-animation-files/profile-equipped.json
test -s badge-v2-animation-files/reduce-motion-metrics.json
test -s badge-v2-animation-files/battery-saver-metrics.json
