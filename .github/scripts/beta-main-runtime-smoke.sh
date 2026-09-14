#!/usr/bin/env bash
set -euo pipefail

PKG='org.noirero.miyorare.debug'
SETTINGS_ACTIVITY='org.koitharu.kotatsu.settings.SettingsActivity'
PREF_FILE="${PKG}_preferences.xml"
OUT="$GITHUB_WORKSPACE/release-gate-runtime"
mkdir -p "$OUT"

fail() {
  echo "::error::$*"
  adb logcat -d > "$OUT/failure-logcat.txt" || true
  tail -n 300 "$OUT/failure-logcat.txt" || true
  exit 1
}

assert_alive_no_crash() {
  local label="$1"
  sleep 2
  adb shell pidof "$PKG" >/dev/null 2>&1 || fail "$label: app process is not alive"
  adb logcat -d > "$OUT/${label}.log" || true
  if grep -q 'FATAL EXCEPTION' "$OUT/${label}.log" && grep -q "Process: $PKG" "$OUT/${label}.log"; then
    fail "$label: fatal exception detected"
  fi
  adb exec-out screencap -p > "$OUT/${label}.png" || true
  adb shell uiautomator dump /sdcard/window.xml >/dev/null 2>&1 || true
  adb pull /sdcard/window.xml "$OUT/${label}.xml" >/dev/null 2>&1 || true
}

start_settings() {
  local label="$1"
  local action="${2:-}"
  adb shell am force-stop "$PKG" || true
  adb logcat -c
  if [[ -n "$action" ]]; then
    adb shell am start -W -a "$action" -n "$PKG/$SETTINGS_ACTIVITY" >/tmp/start.txt || fail "$label: unable to launch Settings action $action"
  else
    adb shell am start -W -n "$PKG/$SETTINGS_ACTIVITY" >/tmp/start.txt || fail "$label: unable to launch Settings"
  fi
  cat /tmp/start.txt
  assert_alive_no_crash "$label"
}

echo '=== Install main baseline and seed upgrade-sensitive settings ==='
adb install -r /tmp/miyorare-main-debug.apk
start_settings main_baseline
adb shell am force-stop "$PKG"
adb shell run-as "$PKG" mkdir -p shared_prefs files
printf 'miyorare-release-gate-marker\n' | adb shell run-as "$PKG" sh -c 'cat > files/release_gate_marker.txt'
cat > /tmp/seedprefs.xml <<'EOF'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
  <string name="miyorare_design_style">CLASSIC</string>
  <string name="miyorare_theme_preset">VIOLET</string>
  <string name="miyorare_custom_accent">#3A7AFE</string>
  <string name="theme">2</string>
  <boolean name="amoled_theme" value="true" />
  <boolean name="title_tap_to_read" value="true" />
  <boolean name="check_duplicates" value="false" />
  <boolean name="reader_volume_buttons" value="true" />
  <boolean name="reader_taps_ltr" value="true" />
  <boolean name="reader_navigation_inverted" value="true" />
  <boolean name="feed_swipe_gestures" value="false" />
  <boolean name="feed_counter_dot" value="true" />
  <boolean name="nav_legacy" value="true" />
  <boolean name="haptic_feedback" value="false" />
  <boolean name="tracker_smart_update" value="false" />
  <boolean name="no_offline" value="false" />
  <boolean name="ssl_bypass" value="false" />
</map>
EOF
cat /tmp/seedprefs.xml | adb shell run-as "$PKG" sh -c "cat > shared_prefs/$PREF_FILE"

echo '=== Upgrade in place to beta ==='
adb install -r -d /tmp/miyorare-beta-debug.apk
test "$(adb shell run-as "$PKG" cat files/release_gate_marker.txt | tr -d '\r')" = 'miyorare-release-gate-marker' || fail 'Upgrade wiped app-private files'
start_settings beta_after_upgrade
adb shell am force-stop "$PKG"
adb shell run-as "$PKG" cat "shared_prefs/$PREF_FILE" > "$OUT/post_upgrade_preferences.xml"

python3 - "$OUT/post_upgrade_preferences.xml" <<'PY'
import sys
import xml.etree.ElementTree as ET

root = ET.parse(sys.argv[1]).getroot()
vals = {}
for node in root:
    name = node.attrib.get('name')
    if node.tag == 'string':
        vals[name] = node.text or ''
    elif node.tag == 'boolean':
        vals[name] = node.attrib.get('value') == 'true'

expected = {
    'miyorare_design_style': 'CLASSIC',
    'miyorare_theme_preset': 'VIOLET',
    'miyorare_custom_accent': '#3A7AFE',
    'theme': '2',
    'amoled_theme': True,
    'title_tap_to_read': True,
    'check_duplicates': False,
    'reader_volume_buttons': True,
    'reader_taps_ltr': True,
    'reader_navigation_inverted': True,
    'feed_swipe_gestures': False,
    'feed_counter_dot': True,
    'nav_legacy': True,
    'haptic_feedback': False,
    'tracker_smart_update': False,
    'no_offline': False,
    'ssl_bypass': False,
}
bad = {k: (vals.get(k), v) for k, v in expected.items() if vals.get(k) != v}
if bad:
    raise SystemExit(f'Upgrade preference regression: {bad}')
print(f'PASS: {len(expected)} sensitive preferences survived the main -> beta upgrade')
PY

echo '=== Smoke critical Settings destinations ==='
for action in \
  "$PKG.action.MANAGE_PRIVATE_FAVOURITES" \
  "$PKG.action.MANAGE_READER_SETTINGS" \
  "$PKG.action.MANAGE_DOWNLOADS" \
  "$PKG.action.MANAGE_TRACKER" \
  "$PKG.action.MANAGE_SOURCES" \
  "$PKG.action.MANAGE_PROXY"; do
  label="route_${action##*.}"
  start_settings "$label" "$action"
done

echo '=== Verify clean install defaults to Modern ==='
adb uninstall "$PKG"
adb install /tmp/miyorare-beta-debug.apk
start_settings fresh_beta
adb shell am force-stop "$PKG"
adb shell run-as "$PKG" cat "shared_prefs/$PREF_FILE" > "$OUT/fresh_preferences.xml"
grep -q '<string name="miyorare_design_style">MODERN</string>' "$OUT/fresh_preferences.xml" || fail 'Fresh install did not default to Modern'

echo 'RELEASE GATE RUNTIME SMOKE PASSED'
