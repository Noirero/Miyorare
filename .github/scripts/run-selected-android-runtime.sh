#!/usr/bin/env bash
set -euo pipefail

if [[ "${RUN_RUNTIME_ISOLATION:-false}" == "true" ]]; then
  bash .github/scripts/run-kotatsu-runtime-isolation-test.sh
fi

if [[ "${RUN_MIGRATION_PERSISTENCE:-false}" == "true" ]]; then
  bash .github/scripts/check-migration-process-persistence.sh
fi

# Android 15 emulator preflight: an ANR dialog from Pixel Launcher can retain
# input focus while the app activity is RESUMED. Capture evidence before any
# recovery; never dismiss an unknown system dialog.
preflight_dir=app/build/reports/android-runtime-diagnostics
mkdir -p "$preflight_dir"
adb shell dumpsys window windows > "$preflight_dir/window-before.txt" 2>&1
adb logcat -d -b events -v time > "$preflight_dir/events-before.txt" 2>&1
focused="$(grep -E 'mCurrentFocus=|mFocusedWindow=' "$preflight_dir/window-before.txt" | tail -1 || true)"
printf 'Preflight window focus: %s\n' "$focused"
launcher_anr=false
if grep -Eiq 'am_anr.*com\\.google\\.android\\.apps\\.nexuslauncher' "$preflight_dir/events-before.txt"; then
  launcher_anr=true
fi
if [[ "$launcher_anr" == true ]] && grep -Eiq 'Application Not Responding|Application Error|AppNotRespondingDialog|isn.t responding' <<< "$focused"; then
  echo 'Pixel Launcher ANR dialog has focus; recovering disposable emulator launcher.'
  adb shell am force-stop com.google.android.apps.nexuslauncher
  sleep 5
  adb shell dumpsys window windows > "$preflight_dir/window-after.txt" 2>&1
  recovered_focus="$(grep -E 'mCurrentFocus=|mFocusedWindow=' "$preflight_dir/window-after.txt" | tail -1 || true)"
  printf 'Recovered window focus: %s\n' "$recovered_focus"
  if grep -Eiq 'Application Not Responding|Application Error|AppNotRespondingDialog|isn.t responding' <<< "$recovered_focus"; then
    echo 'Launcher ANR dialog still owns focus; refusing to run instrumentation.' >&2
    exit 1
  fi
  # Validate the affected class before running the selected full suite.
  echo 'Running CategorySetsUiTest targeted probe after launcher recovery.'
  bash .github/scripts/collect-android-runtime-diagnostics.sh \
    ./gradlew connectedDebugAndroidTest --max-workers=4 --no-daemon --stacktrace \
    '-Pandroid.testInstrumentationRunnerArguments.class=org.koitharu.kotatsu.favourites.ui.CategorySetsUiTest'
elif [[ "$launcher_anr" == true ]]; then
  echo 'Launcher ANR event found, but no ANR dialog currently owns focus; preserving emulator state.'
fi

if [[ -n "${RUNTIME_CLASSES:-}" ]]; then
  bash .github/scripts/collect-android-runtime-diagnostics.sh \
    ./gradlew connectedDebugAndroidTest \
    --max-workers=4 \
    --no-daemon \
    --stacktrace \
    "-Pandroid.testInstrumentationRunnerArguments.class=${RUNTIME_CLASSES}"
fi
