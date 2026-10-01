#!/usr/bin/env bash
set -euo pipefail

ATTEMPTS="${MIYORARE_JITPACK_ATTEMPTS:-10}"
DELAY_SECONDS="${MIYORARE_JITPACK_DELAY_SECONDS:-30}"

PARSERS_VERSION="$(python3 - <<'PY'
import tomllib
from pathlib import Path
data = tomllib.loads(Path("gradle/libs.versions.toml").read_text(encoding="utf-8"))
value = str(data.get("versions", {}).get("parsers", "")).strip()
if not value:
    raise SystemExit("Missing versions.parsers in gradle/libs.versions.toml")
print(value)
PY
)"

CACHE_DIR="$HOME/.gradle/caches/modules-2/files-2.1/com.github.Gekkoushi/plugin-source/${PARSERS_VERSION}"
CACHED_JAR="$(find "$CACHE_DIR" -type f -name '*.jar' -print -quit 2>/dev/null || true)"
CACHED_METADATA="$(find "$CACHE_DIR" -type f \( -name '*.pom' -o -name '*.module' \) -print -quit 2>/dev/null || true)"
if [[ -n "$CACHED_JAR" && -n "$CACHED_METADATA" ]]; then
  echo "plugin-source:${PARSERS_VERSION} restored from Gradle cache; skipping JitPack pre-warm."
  exit 0
fi

POM_URL="https://jitpack.io/com/github/Gekkoushi/plugin-source/${PARSERS_VERSION}/plugin-source-${PARSERS_VERSION}.pom"
for i in $(seq 1 "$ATTEMPTS"); do
  HTTP_STATUS="$(curl -sS -o /dev/null -w "%{http_code}" --connect-timeout 5 --max-time 15 "$POM_URL" || true)"
  if [[ "$HTTP_STATUS" == "200" ]]; then
    echo "JitPack artifact is ready (HTTP 200)"
    exit 0
  fi
  echo "Attempt $i/$ATTEMPTS: JitPack returned HTTP ${HTTP_STATUS:-000}"
  if [[ "$i" -lt "$ATTEMPTS" ]]; then sleep "$DELAY_SECONDS"; fi
done

echo "::error::JitPack artifact plugin-source:${PARSERS_VERSION} is not ready"
exit 1
