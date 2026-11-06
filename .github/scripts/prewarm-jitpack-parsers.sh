#!/usr/bin/env bash
# Shared, behavior-preserving parser JitPack readiness check for CI workflows.
# Existing workflows intentionally use different retry budgets and cache policies.
set -euo pipefail

if [[ "$#" -ne 1 ]]; then
  echo "Usage: $0 {preview|acceptance}" >&2
  exit 2
fi

case "$1" in
  preview)
    attempts=10
    connect_timeout=5
    request_timeout=10
    retry_delay=30
    ;;
  acceptance)
    attempts=12
    connect_timeout=10
    request_timeout=30
    retry_delay=20
    ;;
  *)
    echo "Unknown JitPack pre-warm profile: $1" >&2
    exit 2
    ;;
esac

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

# Preserve the preview-build cache short circuit. Acceptance always probes JitPack.
if [[ "$1" == "preview" ]]; then
  CACHE_DIR="$HOME/.gradle/caches/modules-2/files-2.1/com.github.Gekkoushi/plugin-source/${PARSERS_VERSION}"
  CACHED_JAR="$(find "$CACHE_DIR" -type f -name '*.jar' -print -quit 2>/dev/null || true)"
  CACHED_METADATA="$(find "$CACHE_DIR" -type f \( -name '*.pom' -o -name '*.module' \) -print -quit 2>/dev/null || true)"
  if [[ -n "$CACHED_JAR" && -n "$CACHED_METADATA" ]]; then
    echo "kotatsu-parsers:${PARSERS_VERSION} artifact and metadata are restored in the Gradle cache; skipping JitPack pre-warm."
    exit 0
  fi
  echo "Pre-warming JitPack for kotatsu-parsers:${PARSERS_VERSION}"
fi

POM_URL="https://jitpack.io/com/github/Gekkoushi/plugin-source/${PARSERS_VERSION}/plugin-source-${PARSERS_VERSION}.pom"
for i in $(seq 1 "$attempts"); do
  HTTP_STATUS="$(curl -sS -o /dev/null -w "%{http_code}" --connect-timeout "$connect_timeout" --max-time "$request_timeout" "$POM_URL" || true)"
  if [[ "$HTTP_STATUS" == "200" ]]; then
    if [[ "$1" == "preview" ]]; then
      echo "JitPack artifact is ready (HTTP 200)"
    else
      echo "Parser artifact is ready (HTTP 200)"
    fi
    exit 0
  fi
  if [[ "$1" == "preview" ]]; then
    echo "Attempt $i: JitPack returned HTTP ${HTTP_STATUS:-000}, waiting 30s..."
  else
    echo "Attempt $i: parser artifact returned HTTP ${HTTP_STATUS:-000}"
  fi
  sleep "$retry_delay"
done

if [[ "$1" == "preview" ]]; then
  echo "::error::JitPack artifact for kotatsu-parsers:${PARSERS_VERSION} not available after 10 attempts"
else
  echo "::error::Parser artifact ${PARSERS_VERSION} is not ready on JitPack"
fi
exit 1
