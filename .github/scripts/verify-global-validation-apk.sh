#!/usr/bin/env bash
set -euo pipefail
dir=build/global-validation
pkg=org.noirero.miyorare.debug.globalvalidation
proof="GLOBAL_VALIDATION_OK revision=$(cat "$dir/revision") sha256=$(cat "$dir/sha256")"
adb install "$dir/miyorare-global-validation.apk"
adb logcat -c
adb shell am start -W -n "$pkg/org.koitharu.kotatsu.tsuki.GlobalValidationActivity" --ez verify_title_fixture true
for ((attempt=0; attempt<45; attempt++)); do
  logs=$(adb logcat -d -s GlobalValidation:I)
  if grep -Fq "$proof" <<<"$logs"; then
    grep -F 'TITLE_TRACE' <<<"$logs" | tee "$dir/title-trace.txt"
    grep -F 'TITLE_FIXTURE_OK' <<<"$logs"
    printf '%s\n' "$proof" | tee "$dir/android-15-proof.txt"
    exit 0
  fi
  if grep -Fq 'Global validation setup failed' <<<"$logs"; then
    break
  fi
  sleep 2
done
adb logcat -d -s GlobalValidation:I AndroidRuntime:E
exit 1
