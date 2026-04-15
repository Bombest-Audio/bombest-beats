#!/usr/bin/env bash
# Bombest Beats — Play Store asset generation pipeline.
#
# Produces:
#   icon-512.png                      (Play Store app icon, 512x512, opaque)
#   feature-graphic-1024x500.png      (Play Store feature graphic)
#   screenshot-01-library.png         (Library screen)
#   screenshot-02-player.png          (Player screen)
#   screenshot-03-visualizer.png      (Visualizer screen)
#   screenshot-04-playlists.png       (Playlists screen)
#
# Usage: ./generate.sh [all|icon|graphic|screenshots]
#
# Prerequisites: see README.md next to this script.

set -euo pipefail

REPO_ROOT="$(git rev-parse --show-toplevel)"
cd "$REPO_ROOT"

OUT_DIR="android-app/store-assets"
FG="android-app/app/src/main/res/drawable/ic_launcher_foreground_img.png"
BG_COLOR="#0B0E23"
ACCENT_COLOR="#6F42C1"

# -----------------------------------------------------------------------------
# Helpers
# -----------------------------------------------------------------------------

log()   { printf '[generate] %s\n' "$*"; }
die()   { printf 'error: %s\n' "$*" >&2; exit 1; }

resolve_magick() {
  if command -v magick >/dev/null 2>&1; then
    echo magick
  elif command -v convert >/dev/null 2>&1; then
    echo convert
  else
    die "ImageMagick not found. Install with: brew install imagemagick"
  fi
}

resolve_adb() {
  if command -v adb >/dev/null 2>&1; then
    echo adb
  elif [ -x "$HOME/Library/Android/sdk/platform-tools/adb" ]; then
    echo "$HOME/Library/Android/sdk/platform-tools/adb"
  else
    die "adb not found. Install Android platform-tools or add them to PATH."
  fi
}

pick_font() {
  local m="$1"
  for f in Helvetica-Bold Arial-Bold DejaVu-Sans-Bold Helvetica Arial; do
    if "$m" -list font 2>/dev/null | grep -q " Font: ${f}$"; then
      echo "$f"
      return
    fi
  done
  echo Helvetica
}

# -----------------------------------------------------------------------------
# Task 1 — 512x512 icon
# -----------------------------------------------------------------------------

build_icon() {
  local m; m="$(resolve_magick)"
  [ -f "$FG" ] || die "foreground image not found: $FG"
  local out="$OUT_DIR/icon-512.png"
  log "rendering $out (background $BG_COLOR, 66% foreground)"
  "$m" -size 512x512 "xc:${BG_COLOR}" \
    \( "$FG" -resize 338x338 \) \
    -gravity center -composite \
    -alpha off -background "$BG_COLOR" -flatten \
    "$out"
  local dims; dims="$("$m" identify -format '%wx%h' "$out")"
  [ "$dims" = "512x512" ] || die "icon dimensions wrong: $dims"
  local bytes; bytes=$(stat -f %z "$out" 2>/dev/null || stat -c %s "$out")
  [ "$bytes" -le 1048576 ] || die "icon exceeds 1MB: $bytes bytes"
  log "ok: $out ($dims, ${bytes} bytes)"
}

# -----------------------------------------------------------------------------
# Task 2 — 1024x500 feature graphic
# -----------------------------------------------------------------------------

build_feature_graphic() {
  local m; m="$(resolve_magick)"
  local icon="$OUT_DIR/icon-512.png"
  [ -f "$icon" ] || build_icon
  local font; font="$(pick_font "$m")"
  local out="$OUT_DIR/feature-graphic-1024x500.png"
  log "rendering $out (font $font)"
  "$m" -size 1024x500 "xc:${BG_COLOR}" \
    \( "$icon" -resize 380x380 \) -geometry +60+60 -composite \
    -font "$font" -pointsize 96 -fill "#FFFFFF" \
      -annotate +500+270 "Bombest Beats" \
    -font "$font" -pointsize 36 -fill "#CCCCCC" \
      -annotate +500+330 "Your personal library, streamed." \
    -fill "$ACCENT_COLOR" -draw "rectangle 0,496 1024,500" \
    -alpha off -background "$BG_COLOR" -flatten \
    "$out"
  local dims; dims="$("$m" identify -format '%wx%h' "$out")"
  [ "$dims" = "1024x500" ] || die "feature graphic dimensions wrong: $dims"
  log "ok: $out ($dims)"
}

# -----------------------------------------------------------------------------
# Task 3 — screenshots via adb
# -----------------------------------------------------------------------------

capture_screenshots() {
  local adb; adb="$(resolve_adb)"
  local devices
  devices="$("$adb" devices | tail -n +2 | awk '$2=="device"{print $1}')"
  local count; count="$(printf '%s\n' "$devices" | sed '/^$/d' | wc -l | tr -d ' ')"
  if [ "$count" -eq 0 ]; then
    die "no device/emulator attached. Start one with:
  \$HOME/Library/Android/sdk/emulator/emulator -avd verses_pixel &
then wait for boot and re-run: $0 screenshots"
  fi
  if [ "$count" -gt 1 ] && [ -z "${ANDROID_SERIAL:-}" ]; then
    die "multiple devices attached — set ANDROID_SERIAL to pick one"
  fi
  local screens=( "library:01" "player:02" "visualizer:03" "playlists:04" )
  log "launching app: com.bombest.music/.MainActivity"
  "$adb" shell am start -n com.bombest.music/.MainActivity >/dev/null
  sleep 4
  for entry in "${screens[@]}"; do
    local name="${entry%%:*}"
    local num="${entry##*:}"
    local out="$OUT_DIR/screenshot-${num}-${name}.png"
    log "capture $out — navigate the app to the ${name} screen, then press Enter"
    # Interactive pause: user navigates manually; reliable across UI changes.
    read -r _ </dev/tty || true
    "$adb" shell screencap -p /sdcard/_bombest_ss.png
    "$adb" pull /sdcard/_bombest_ss.png "$out" >/dev/null
    "$adb" shell rm /sdcard/_bombest_ss.png >/dev/null 2>&1 || true
    # Validate
    local m; m="$(resolve_magick)"
    local d; d="$("$m" identify -format '%w %h' "$out")"
    local w="${d%% *}"; local h="${d##* }"
    if [ "$w" -lt 320 ]; then die "screenshot too narrow: ${w}px"; fi
    if [ "$h" -le "$w" ]; then die "screenshot not portrait: ${w}x${h}"; fi
    local bytes; bytes=$(stat -f %z "$out" 2>/dev/null || stat -c %s "$out")
    if [ "$bytes" -gt 8388608 ]; then
      log "shrinking oversized screenshot ($bytes bytes)"
      "$m" "$out" -strip -quality 90 "$out"
    fi
    log "ok: $out (${w}x${h})"
  done
}

# -----------------------------------------------------------------------------
# Dispatch
# -----------------------------------------------------------------------------

main() {
  local cmd="${1:-all}"
  mkdir -p "$OUT_DIR"
  case "$cmd" in
    icon)        build_icon ;;
    graphic)     build_feature_graphic ;;
    screenshots) capture_screenshots ;;
    all)
      build_icon
      build_feature_graphic
      capture_screenshots
      ;;
    -h|--help|help)
      cat <<EOF
Usage: $0 [all|icon|graphic|screenshots]

  all          Run icon + graphic + screenshots (default).
  icon         Render 512x512 Play Store icon.
  graphic      Render 1024x500 feature graphic.
  screenshots  Capture 4 portrait phone screenshots via adb.

Outputs land in android-app/store-assets/. See README.md for prerequisites.
EOF
      ;;
    *) die "unknown command: $cmd (try: $0 --help)" ;;
  esac
}

main "$@"
