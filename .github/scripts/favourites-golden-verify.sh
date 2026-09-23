#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -ne 3 ]; then
  echo "Usage: $0 <reference.png> <implementation.png> <output-dir>" >&2
  exit 2
fi

REFERENCE="$1"
IMPLEMENTATION="$2"
OUT_DIR="$3"
mkdir -p "$OUT_DIR"

if command -v magick >/dev/null 2>&1; then
  IM=(magick)
elif command -v convert >/dev/null 2>&1 && command -v identify >/dev/null 2>&1; then
  IM=()
else
  echo "ImageMagick is required (magick, or convert + identify)." >&2
  exit 3
fi

identify_image() {
  if [ "${#IM[@]}" -gt 0 ]; then
    "${IM[@]}" identify -format '%wx%h' "$1"
  else
    identify -format '%wx%h' "$1"
  fi
}

convert_image() {
  if [ "${#IM[@]}" -gt 0 ]; then
    "${IM[@]}" "$@"
  else
    convert "$@"
  fi
}

reference_size="$(identify_image "$REFERENCE")"
implementation_size="$(identify_image "$IMPLEMENTATION")"
if [ "$reference_size" != "$implementation_size" ]; then
  echo "Viewport mismatch: reference=$reference_size implementation=$implementation_size" >&2
  echo "Capture both at the same canonical viewport before comparing; this script does not stretch screenshots." >&2
  exit 4
fi

cp "$REFERENCE" "$OUT_DIR/reference.png"
cp "$IMPLEMENTATION" "$OUT_DIR/implementation.png"

# Equal 50/50 blend: doubled major edges are immediately visible.
convert_image "$REFERENCE" "$IMPLEMENTATION" -compose blend -define compose:args=50,50 -composite "$OUT_DIR/overlay_50.png"

# Absolute per-pixel difference. With a fixed wallpaper + data fixture this becomes deterministic
# evidence; without that fixture it is still useful for locating geometry drift.
convert_image "$REFERENCE" "$IMPLEMENTATION" -compose difference -composite "$OUT_DIR/difference.png"

# Edge-only overlay suppresses much of the wallpaper/cover noise and is the primary geometry check.
convert_image "$REFERENCE" -colorspace Gray -edge 1 "$OUT_DIR/reference_edges.png"
convert_image "$IMPLEMENTATION" -colorspace Gray -edge 1 "$OUT_DIR/implementation_edges.png"
convert_image "$OUT_DIR/reference_edges.png" "$OUT_DIR/implementation_edges.png" \
  -compose blend -define compose:args=50,50 -composite "$OUT_DIR/edge_overlay_50.png"

echo "Golden verification artifacts:"
printf '  %s\n' \
  "$OUT_DIR/reference.png" \
  "$OUT_DIR/implementation.png" \
  "$OUT_DIR/overlay_50.png" \
  "$OUT_DIR/difference.png" \
  "$OUT_DIR/edge_overlay_50.png"
