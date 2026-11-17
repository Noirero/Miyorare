#!/usr/bin/env bash
set -euo pipefail
pkg=org.noirero.miyorare.debug.globalvalidation
revision=$(git rev-parse HEAD)
proof="VORATOON_ROUTING_OK revision=$revision"
stored="VORATOON_STORED_DATA_OK revision=$revision"
cutover="VORATOON_ID_CUTOVER_OK revision=$revision"
adb install app/build/outputs/apk/debug/app-debug.apk
adb logcat -c
adb shell am start -W -n "$pkg/org.koitharu.kotatsu.tsuki.GlobalValidationActivity"
for ((attempt=0; attempt<45; attempt++)); do
  logs=$(adb logcat -d -s GlobalValidation:I)
  if grep -Fq "$proof" <<<"$logs" && grep -Fq "$stored" <<<"$logs" && grep -Fq "$cutover" <<<"$logs"; then
    mkdir -p build/voratoon-device-validation
    printf '%s\n' "$cutover" "$stored" "$proof" | tee build/voratoon-device-validation/android-15-proof.txt
    exit 0
  fi
  if grep -Fq 'Global validation setup failed' <<<"$logs"; then
    break
  fi
  sleep 2
done
adb logcat -d -s GlobalValidation:I AndroidRuntime:E
exit 1
