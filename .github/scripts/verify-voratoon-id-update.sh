#!/usr/bin/env bash
set -euo pipefail
pkg=org.noirero.miyorare.debug.globalvalidation
proof="VORATOON_ID_UPDATE_OK revision=$(git rev-parse HEAD)"
adb install app/build/outputs/apk/debug/app-debug.apk
adb logcat -c
adb shell am start -W -n "$pkg/org.koitharu.kotatsu.tsuki.GlobalValidationActivity" --ez verify_id_update true
for ((attempt=0; attempt<45; attempt++)); do
  logs=$(adb logcat -d -s GlobalValidation:I)
  if grep -Fq "$proof" <<<"$logs"; then
    mkdir -p build
    printf '%s\n' "$proof" | tee build/voratoon-id-update-proof.txt
    exit 0
  fi
  if grep -Fq 'Global validation setup failed' <<<"$logs"; then break; fi
  sleep 2
done
adb logcat -d -s GlobalValidation:I AndroidRuntime:E
exit 1
