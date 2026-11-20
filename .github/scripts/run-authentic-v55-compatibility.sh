#!/usr/bin/env bash
set -euo pipefail
(
  cd historical-v55
  ./gradlew :app:connectedDebugAndroidTest --no-daemon --stacktrace \
    -Pandroid.testInstrumentationRunnerArguments.class=org.koitharu.kotatsu.core.db.HistoricalV55SeedTest
)
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
