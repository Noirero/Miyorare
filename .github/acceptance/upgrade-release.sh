#!/usr/bin/env bash
set -euo pipefail

: "${PACKAGE:?}" "${STABLE_SHA256:?}" "${EXPECTED_CANDIDATE_APK_SHA256:?}" "${ACCEPTANCE_EVIDENCE:?}"
mkdir -p "$ACCEPTANCE_EVIDENCE"
finish() {
    status=$?
    trap - EXIT
    printf 'exit_code=%s\n' "$status" > "$ACCEPTANCE_EVIDENCE/exit-status.txt"
    adb logcat -d > "$ACCEPTANCE_EVIDENCE/release-logcat.txt" 2>&1 || true
    adb exec-out screencap -p > "$ACCEPTANCE_EVIDENCE/final-screen.png" 2>/dev/null || true
    exit "$status"
}
trap finish EXIT
verify_apks() {
    printf '%s  %s\n' "$STABLE_SHA256" /tmp/stable.apk "$EXPECTED_CANDIDATE_APK_SHA256" /tmp/candidate.apk | sha256sum -c -
}
verify_apks
adb shell settings put system screen_off_timeout 1800000
adb shell settings put system accelerometer_rotation 0

echo '=== stable v1.4.4 install and representative state seed ==='
adb uninstall "$PACKAGE" >/dev/null 2>&1 || true
adb install /tmp/stable.apk | tee "$ACCEPTANCE_EVIDENCE/stable-install.txt"
adb install /tmp/stable-test.apk | tee "$ACCEPTANCE_EVIDENCE/stable-probe-install.txt"
adb shell am instrument -w -r \
    -e class org.koitharu.kotatsu.acceptance.SignedUpgradeAcceptanceHarnessTest#seedStableRepresentativeState \
    "$PACKAGE.test/org.koitharu.kotatsu.HiltTestRunner" | tee "$ACCEPTANCE_EVIDENCE/stable-seed.txt"
grep -q 'OK (1 test)' "$ACCEPTANCE_EVIDENCE/stable-seed.txt"
adb pull "/sdcard/Android/data/$PACKAGE/files/runtime-acceptance-baseline.txt" "$ACCEPTANCE_EVIDENCE/"

echo '=== immutable signed candidate in-place update: no uninstall, no clear, no downgrade ==='
adb uninstall "$PACKAGE.test" >/dev/null 2>&1 || true
adb install -r /tmp/candidate.apk | tee "$ACCEPTANCE_EVIDENCE/update-install.txt"
grep -q 'Success' "$ACCEPTANCE_EVIDENCE/update-install.txt"
adb install /tmp/candidate-test.apk | tee "$ACCEPTANCE_EVIDENCE/candidate-probe-install.txt"
adb shell am instrument -w -r \
    -e class org.koitharu.kotatsu.acceptance.SignedUpgradeAcceptanceHarnessTest#verifyCandidateMigrationAndPreservation \
    "$PACKAGE.test/org.koitharu.kotatsu.HiltTestRunner" | tee "$ACCEPTANCE_EVIDENCE/candidate-verify.txt"
grep -q 'OK (1 test)' "$ACCEPTANCE_EVIDENCE/candidate-verify.txt"
adb pull "/sdcard/Android/data/$PACKAGE/files/runtime-acceptance-candidate.txt" "$ACCEPTANCE_EVIDENCE/"

echo '=== signed Release cold-start / force-stop / reopen / offline smoke ==='
adb logcat -c
adb shell monkey -p "$PACKAGE" -c android.intent.category.LAUNCHER 1 | tee "$ACCEPTANCE_EVIDENCE/launch.txt"
sleep 5
adb shell pidof "$PACKAGE"
adb exec-out screencap -p > "$ACCEPTANCE_EVIDENCE/release-launch.png"
adb shell am force-stop "$PACKAGE"
adb shell monkey -p "$PACKAGE" -c android.intent.category.LAUNCHER 1
sleep 5
adb shell pidof "$PACKAGE"
adb shell svc wifi disable || true
adb shell svc data disable || true
adb shell am force-stop "$PACKAGE"
adb shell monkey -p "$PACKAGE" -c android.intent.category.LAUNCHER 1
sleep 5
adb shell pidof "$PACKAGE"
adb exec-out screencap -p > "$ACCEPTANCE_EVIDENCE/release-offline.png"
adb logcat -d > "$ACCEPTANCE_EVIDENCE/release-logcat.txt"
python3 - "$ACCEPTANCE_EVIDENCE/release-logcat.txt" "$PACKAGE" <<'PY'
import pathlib, re, sys
text = pathlib.Path(sys.argv[1]).read_text(errors='replace')
if re.search(r'FATAL EXCEPTION[^\n]*\n(?:[^\n]*\n){0,4}[^\n]*Process: ' + re.escape(sys.argv[2]) + r'(?:,|\s)', text):
    raise SystemExit('Signed Release crashed during smoke')
PY
verify_apks
