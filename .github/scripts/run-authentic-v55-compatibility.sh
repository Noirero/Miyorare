#!/usr/bin/env bash
set -euo pipefail
# connectedDebugAndroidTest removes the historical app after execution, so
# install and instrument explicitly to retain its real database for the upgrade.
(
  cd historical-v55
  ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon --stacktrace
)
mapfile -t app_apks < <(find historical-v55/app/build/outputs/apk/debug -maxdepth 1 -type f -name '*.apk' -print)
mapfile -t test_apks < <(find historical-v55/app/build/outputs/apk/androidTest/debug -maxdepth 1 -type f -name '*.apk' -print)
test "${#app_apks[@]}" -eq 1
test "${#test_apks[@]}" -eq 1
adb install -r -t "${app_apks[0]}"
adb install -r -t "${test_apks[0]}"
adb shell pm path org.noirero.miyorare.debug
adb shell pm list instrumentation | grep -F 'org.noirero.miyorare.debug.test/org.koitharu.kotatsu.HiltTestRunner'
mkdir -p app/build/historical-v55-evidence
adb shell am instrument -w -r \
  -e class org.koitharu.kotatsu.core.db.HistoricalV55SeedTest \
  org.noirero.miyorare.debug.test/org.koitharu.kotatsu.HiltTestRunner \
  | tee app/build/historical-v55-evidence/v55-seed-instrumentation.log
python3 - <<'PY'
from pathlib import Path
out = Path("app/build/historical-v55-evidence/v55-seed-instrumentation.log").read_text()
assert "INSTRUMENTATION_STATUS: test=createAuthenticRoomV55DatabaseWithGroups" in out, out
assert "INSTRUMENTATION_STATUS_CODE: 0" in out, out
assert "OK (1 test)" in out, out
assert "INSTRUMENTATION_CODE: -1" in out, out
assert "FAILURES!!!" not in out and "Error in" not in out, out
print("Verified exactly one authentic Room v55 instrumentation test passed")
PY
test -f historical-v55/app/schemas/org.koitharu.kotatsu.core.db.MangaDatabase/55.json
mkdir -p app/build/historical-v55-evidence
cp historical-v55/app/schemas/org.koitharu.kotatsu.core.db.MangaDatabase/55.json app/build/historical-v55-evidence/55.json
adb shell run-as org.noirero.miyorare.debug ls databases/historical-v55-library-groups
adb exec-out run-as org.noirero.miyorare.debug cat databases/historical-v55-library-groups > app/build/historical-v55-evidence/v55-runtime.sqlite
python3 - <<'PY'
import sqlite3
db = sqlite3.connect("app/build/historical-v55-evidence/v55-runtime.sqlite")
assert db.execute("PRAGMA user_version").fetchone()[0] == 55
assert db.execute("SELECT COUNT(*) FROM library_group_members").fetchone()[0] == 2
assert db.execute("PRAGMA foreign_key_check").fetchall() == []
print("Verified real historical Room v55 runtime database, seeded with Group children")
db.close()
PY
./gradlew :app:connectedDebugAndroidTest --no-daemon --stacktrace \
  -Pandroid.testInstrumentationRunnerArguments.class=org.koitharu.kotatsu.core.db.HistoricalV55CompatibilityTest
