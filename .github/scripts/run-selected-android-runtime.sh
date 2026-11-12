#!/usr/bin/env bash
set -euo pipefail

# The History A/B workload is the last, GPU-heavy class in the full suite.
# Two observed CI runs lost the emulator during it after 390+ tests. Execute
# exactly that class in a separate, fresh emulator action while keeping every
# selected test mandatory. The planner and reusable proof still include it.
HISTORY_SCROLL_CLASS=org.koitharu.kotatsu.history.ui.HistoryScrollBenchmarkTest

strip_fresh_emulator_class() {
  local class_list="$1"
  local item
  local selected=()
  local matched=false
  IFS=',' read -r -a requested <<< "$class_list"
  for item in "${requested[@]}"; do
    [[ -n "$item" ]] || { echo 'Empty runtime class token' >&2; return 1; }
    if [[ "$item" == "$HISTORY_SCROLL_CLASS" ]]; then
      matched=true
    else
      selected+=("$item")
    fi
  done
  [[ "$matched" == true && ${#selected[@]} -gt 0 ]] || {
    echo 'Cannot isolate History benchmark: exact full-suite class is absent or no other class remains' >&2
    return 1
  }
  ( IFS=','; printf '%s\n' "${selected[*]}" )
}

if [[ "${1:-}" == --self-test ]]; then
  expected="test.A,test.B"
  result="$(strip_fresh_emulator_class "test.A,$HISTORY_SCROLL_CLASS,test.B")"
  [[ "$result" == "$expected" ]] || exit 1
  if strip_fresh_emulator_class "test.A,test.B" 2>/dev/null; then exit 1; fi
  if strip_fresh_emulator_class "$HISTORY_SCROLL_CLASS" 2>/dev/null; then exit 1; fi
  echo 'Fresh-emulator History class split: PASS'
  exit 0
fi

if [[ "${RUN_HISTORY_BENCHMARK_IN_FRESH_EMULATOR:-false}" == true ]]; then
  RUNTIME_CLASSES="$(strip_fresh_emulator_class "${RUNTIME_CLASSES:-}")"
  printf 'History scroll benchmark reserved for a separate mandatory emulator session.\n'
fi

if [[ "${RUN_RUNTIME_ISOLATION:-false}" == "true" ]]; then
  bash .github/scripts/run-kotatsu-runtime-isolation-test.sh
fi

if [[ "${RUN_MIGRATION_PERSISTENCE:-false}" == "true" ]]; then
  bash .github/scripts/check-migration-process-persistence.sh
fi

# Capture transport and window state independently. "dumpsys window windows" does
# not reliably expose mCurrentFocus on Android 15; query the full service.
preflight_dir=app/build/reports/android-runtime-diagnostics
mkdir -p "$preflight_dir"
printf '%s\n' "Preflight UTC: $(date -u +%FT%TZ)" | tee "$preflight_dir/preflight-status.txt"
adb devices -l | tee "$preflight_dir/adb-before.txt"
if ! adb shell true > "$preflight_dir/adb-shell-before.txt" 2>&1; then
  echo 'Preflight failed: ADB shell is unavailable.' >&2
  exit 1
fi
if ! adb shell dumpsys window > "$preflight_dir/window-before.txt" 2> "$preflight_dir/window-before.err"; then
  echo 'Preflight failed: dumpsys window returned nonzero.' >&2
  cat "$preflight_dir/window-before.err" >&2
  exit 1
fi
if ! adb logcat -d -b events -v time > "$preflight_dir/events-before.txt" 2> "$preflight_dir/events-before.err"; then
  echo 'Preflight failed: event logcat unavailable.' >&2
  cat "$preflight_dir/events-before.err" >&2
  exit 1
fi
focus_lines="$(grep -E 'mCurrentFocus=|mFocusedWindow=|mInputMethodTarget=' "$preflight_dir/window-before.txt" || true)"
printf 'Preflight focus records: %s\n' "${focus_lines:-<none>}" | tee -a "$preflight_dir/preflight-status.txt"
launcher_anr=false
if grep -Eiq 'am_anr.*com[.]google[.]android[.]apps[.]nexuslauncher' "$preflight_dir/events-before.txt"; then
  launcher_anr=true
fi
# Focus can be null during a legitimate idle/boot transition; do not call it
# healthy when an ANR event is recorded and focus cannot be determined.
anr_window_pattern='Application Not Responding|Application Error|AppNotRespondingDialog|isn.t responding'
if [[ "$launcher_anr" == true ]]; then
  echo 'Preflight: Pixel Launcher ANR event found.' | tee -a "$preflight_dir/preflight-status.txt"
  if grep -Eiq "$anr_window_pattern" <<< "$focus_lines"; then
    echo 'Preflight: ANR dialog owns focus; attempting launcher recovery.'
    adb shell am force-stop com.google.android.apps.nexuslauncher
    sleep 5
    adb shell dumpsys window > "$preflight_dir/window-after.txt" 2> "$preflight_dir/window-after.err"
    after_focus="$(grep -E 'mCurrentFocus=|mFocusedWindow=' "$preflight_dir/window-after.txt" || true)"
    printf 'Post-recovery focus records: %s\n' "${after_focus:-<none>}" | tee -a "$preflight_dir/preflight-status.txt"
    if [[ -z "$after_focus" ]] || grep -Eiq "$anr_window_pattern" <<< "$after_focus"; then
      echo 'Preflight failed: cannot confirm ANR dialog focus cleared.' >&2
      exit 1
    fi
    echo 'Running targeted CategorySetsUiTest after launcher recovery.'
    bash .github/scripts/collect-android-runtime-diagnostics.sh \
      ./gradlew connectedDebugAndroidTest --max-workers=4 --no-daemon --stacktrace \
      '-Pandroid.testInstrumentationRunnerArguments.class=org.koitharu.kotatsu.favourites.ui.CategorySetsUiTest'
  elif [[ -z "$focus_lines" ]]; then
    echo 'Preflight failed: launcher ANR event present but window focus is undiagnosable.' >&2
    exit 1
  else
    echo 'Preflight: historical Launcher ANR, no ANR dialog currently focused.'
  fi
elif [[ -z "$focus_lines" ]]; then
  echo 'Preflight: focus record unavailable; no Launcher ANR event found (not proof of healthy focus).' | tee -a "$preflight_dir/preflight-status.txt"
fi

if [[ -n "${RUNTIME_CLASSES:-}" ]]; then
  runtime_exit=0
  bash .github/scripts/collect-android-runtime-diagnostics.sh \
    ./gradlew connectedDebugAndroidTest \
    --max-workers=4 \
    --no-daemon \
    --stacktrace \
    "-Pandroid.testInstrumentationRunnerArguments.class=${RUNTIME_CLASSES}" || runtime_exit=$?

  # Diagnostic only; one aggregate line per A/B phase, not per-frame logging.
  # Capture even on test failure, without masking the original Gradle exit code.
  if [[ "$RUNTIME_CLASSES" == *"org.koitharu.kotatsu.history.ui.HistoryScrollBenchmarkTest"* ]]; then
    adb logcat -d -v brief -s HistoryScrollBench:I \
      | tee "$preflight_dir/history-scroll-benchmark.txt" || true
  fi
  exit "$runtime_exit"
fi
