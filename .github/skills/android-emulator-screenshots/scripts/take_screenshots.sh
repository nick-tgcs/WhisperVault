#!/usr/bin/env bash
# take_screenshots.sh — Capture all 7 WhisperVault screenshots.
#
# Outputs (in fastlane/metadata/android/en-US/images/phoneScreenshots/):
#   01.png  MainActivity       — idle, blue mic, Append/Translate toggles
#   02.png  RecognizeActivity  — MANUAL mode (blue mic, A off, ∞ off)
#   03.png  RecognizeActivity  — AUTO mode   (red mic recording, A on, ∞ outline)
#   04.png  SettingsActivity   — language picker, VAD slider, Bluetooth toggle
#   05.png  IME keyboard       — MANUAL mode (blue mic, A off, ∞ off)
#   06.png  IME keyboard       — AUTO mode   (red mic, A on, ∞ outline)
#   07.png  IME keyboard       — CONTINUOUS  (red mic, A on, ∞ filled blue)
#
# Usage: bash .github/skills/android-emulator-screenshots/scripts/take_screenshots.sh
# Prerequisite: run setup_emulator.sh first (app must be installed).

set -euo pipefail

# ── Ensure adb is on PATH ────────────────────────────────────────────────────
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export PATH="$PATH:$ANDROID_HOME/platform-tools"

PACKAGE="io.github.nick_tgcs.whispervault"
MAIN_ACTIVITY="${PACKAGE}/com.whisperonnx.MainActivity"
RECOGNIZE_ACTIVITY="${PACKAGE}/com.whisperonnx.WhisperRecognizeActivity"
SETTINGS_ACTIVITY="${PACKAGE}/com.whisperonnx.SettingsActivity"
IME_SERVICE="${PACKAGE}/com.whisperonnx.WhisperInputMethodService"
PREFS_DIR="/data/data/${PACKAGE}/shared_prefs"
PREFS_FILE="${PREFS_DIR}/${PACKAGE}_preferences.xml"
EXTERNAL_FILES="/sdcard/Android/data/${PACKAGE}/files"
OUT="fastlane/metadata/android/en-US/images/phoneScreenshots"

mkdir -p "$OUT"

# ── Step 0: gain root FIRST ───────────────────────────────────────────────────
# IMPORTANT: adb root restarts adbd, which resets IME settings on some images.
# Always root first, then set the IME — never the other way around.
echo ""
echo "==> [0/6] Gaining adb root..."
adb root
sleep 2
adb wait-for-device
echo "  Root active."

# ── assert_ime: set and verify WhisperVault as default IME ───────────────────
# Must be called after adb root is stable.
assert_ime() {
    adb shell ime enable "${IME_SERVICE}" >/dev/null 2>&1
    adb shell ime set "${IME_SERVICE}" >/dev/null 2>&1
    adb shell settings put secure default_input_method "${IME_SERVICE}"
    local actual
    actual=$(adb shell settings get secure default_input_method | tr -d '\r\n')
    if [ "$actual" != "$IME_SERVICE" ]; then
        echo "  ERROR: IME set failed."
        echo "  Got:      '$actual'"
        echo "  Expected: '$IME_SERVICE'"
        exit 1
    fi
    echo "  IME confirmed: $actual"
}

# ── Step 1: set IME ───────────────────────────────────────────────────────────
echo ""
echo "==> [1/6] Setting default IME..."
assert_ime

# ── Step 2: create dummy model files ─────────────────────────────────────────
# Whisper.java requires exactly 6 files in getExternalFilesDir().
# Dummy files (1 byte each) satisfy the count check and suppress SetupActivity.
# ONNX Runtime cannot load them — the mic works but inference produces no output.
# That is intentional: we are taking screenshots, not testing transcription.
echo ""
echo "==> [2/6] Creating dummy model files..."
adb shell mkdir -p "$EXTERNAL_FILES"
for f in \
    Whisper_cache_initializer_batch.onnx \
    Whisper_cache_initializer.onnx \
    Whisper_decoder.onnx \
    Whisper_detokenizer.onnx \
    Whisper_encoder.onnx \
    Whisper_initializer.onnx; do
    adb shell "test -s ${EXTERNAL_FILES}/${f} || printf 'x' > ${EXTERNAL_FILES}/${f}"
done
file_count=$(adb shell "ls ${EXTERNAL_FILES}/*.onnx 2>/dev/null | wc -l" | tr -d ' \r\n')
echo "  ${file_count} .onnx files on device."
if [ "${file_count:-0}" -lt 6 ]; then
    echo "  ERROR: Expected 6 .onnx files, got ${file_count}. Cannot continue."
    exit 1
fi

# ── write_prefs: write SharedPreferences via host temp file + adb push ────────
# adb push is used to avoid quoting issues with heredocs inside adb shell.
# model_name=whisper_tiny_int8 bypasses the integrity check (no hashes registered).
write_prefs() {
    local auto="${1:-false}"
    local continuous="${2:-false}"
    local tmp
    tmp=$(mktemp /tmp/whisper_prefs_XXXXXX.xml)
    cat > "$tmp" << ENDOFPREFS
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <int name="versionCode" value="17" />
    <string name="model_name">whisper_tiny_int8</string>
    <boolean name="imeModeAuto" value="${auto}" />
    <boolean name="imeContinuous" value="${continuous}" />
</map>
ENDOFPREFS
    adb shell mkdir -p "$PREFS_DIR"
    adb push "$tmp" "$PREFS_FILE" >/dev/null
    rm -f "$tmp"
}

# ── shoot: screencap and pull ─────────────────────────────────────────────────
shoot() {
    local tag="$1"
    local dest="$2"
    adb shell screencap -p "/sdcard/${tag}.png"
    adb pull "/sdcard/${tag}.png" "$dest" >/dev/null
    adb shell rm -f "/sdcard/${tag}.png"
    local size
    size=$(wc -c < "$dest")
    if [ "$size" -lt 10000 ]; then
        echo "  ERROR: ${dest} is only ${size} bytes — screencap likely failed."
        exit 1
    fi
    echo "  SAVED: $dest (${size} bytes)"
}

stop_app() {
    adb shell am force-stop "$PACKAGE" 2>/dev/null || true
    sleep 1
}

echo ""
echo "==> [3/6] Taking activity screenshots (01-04)..."

# ── 01: MainActivity ─────────────────────────────────────────────────────────
echo ""
echo "── 01: MainActivity"
stop_app
write_prefs false false
adb shell am start -n "$MAIN_ACTIVITY" || true
sleep 3
shoot ss01 "$OUT/01.png"

# ── 02: RecognizeActivity — MANUAL mode ──────────────────────────────────────
# MANUAL mode: imeModeAuto=false. Blue mic, A button off, ∞ button off.
echo ""
echo "── 02: RecognizeActivity (MANUAL)"
stop_app
write_prefs false false
# Launch Contacts as background so the floating overlay has a real app behind it.
adb shell am start -a android.intent.action.INSERT -t vnd.android.cursor.dir/contact || true
sleep 2
adb shell am start -n "$RECOGNIZE_ACTIVITY" || true
sleep 3
shoot ss02 "$OUT/02.png"

# ── 03: RecognizeActivity — AUTO mode ────────────────────────────────────────
# AUTO mode: imeModeAuto=true. Recording starts immediately on launch.
# Red mic, A button on (filled), ∞ button outline.
echo ""
echo "── 03: RecognizeActivity (AUTO)"
stop_app
write_prefs true false
adb shell am start -a android.intent.action.INSERT -t vnd.android.cursor.dir/contact || true
sleep 2
adb shell am start -n "$RECOGNIZE_ACTIVITY" || true
sleep 2
shoot ss03 "$OUT/03.png"

# ── 04: Settings screen ──────────────────────────────────────────────────────
# NOTE: CONTINUOUS mode for RecognizeActivity cannot be screenshotted —
# the ∞ button calls finish() immediately, closing the activity before screencap.
# Settings screen is used for slot 04 instead.
echo ""
echo "── 04: Settings screen"
stop_app
write_prefs false false
adb shell am start -n "$SETTINGS_ACTIVITY" || true
sleep 2
shoot ss04 "$OUT/04.png"

# ── 05-07: IME keyboard screenshots ──────────────────────────────────────────
# The IME keyboard only appears when a text field in another app is focused.
# We use Contacts "Create contact" as the host app.
#
# uiautomator CANNOT reach IME views — they run in a separate Android window.
# Button taps use pre-measured pixel coordinates:
#   Display: 1080×1920 @ 420dpi
#   IME window top: y=1202
#   Mode strip centre y: 1202 + 26px(10dp margin) + 63px(half of 48dp strip) = 1291
#   Strip usable width: 1080 - 2×42px(16dp margins) = 996px
#   4 equal buttons in 996px → each button 249px wide
#   A button (button 1) centre x: 42 + 124 = 166  (rounded to 163)
#   ∞ button (button 2) centre x: 42 + 373 = 415  (rounded to 406)
#
# Re-assert IME here because multiple am start calls can drift the default IME.
echo ""
echo "==> [4/6] Re-asserting IME before keyboard screenshots..."
assert_ime

echo ""
echo "==> [5/6] Opening Contacts and summoning IME keyboard..."
# Force-stop both Contacts apps so it launches FRESH (avoids stale task state).
adb shell am force-stop com.android.contacts 2>/dev/null || true
adb shell am force-stop com.google.android.contacts 2>/dev/null || true
# Press HOME to clear any overlay windows left by RecognizeActivity.
adb shell input keyevent KEYCODE_HOME
sleep 1

stop_app
write_prefs false false
# Start Contacts "Create contact" — fresh launch since we force-stopped it above.
adb shell am start -a android.intent.action.INSERT -t vnd.android.cursor.dir/contact || true
sleep 3

# Tap the "First name" field to give it focus. GBoard may appear at this point.
# Bounds confirmed via uiautomator dump: [137,793][933,942] → centre (540,867)
adb shell input tap 540 867
sleep 1

# Switch IME WHILE the text field is already focused. Calling ime set now sends
# the switch command to the live InputMethodManagerService session, immediately
# replacing GBoard with WhisperVault. Calling it before focus doesn't work.
adb shell ime set "${IME_SERVICE}"
adb shell settings put secure default_input_method "${IME_SERVICE}"
sleep 2

# Clear any stray text typed in previous runs (CTRL+A then BACKSPACE).
adb shell input keyevent KEYCODE_CTRL_A
adb shell input keyevent KEYCODE_DEL
sleep 0.5

echo "  IME switched and field cleared."

echo ""
echo "==> [6/6] Taking IME screenshots (05-07)..."

# ── 05: IME MANUAL mode (default — A off, ∞ off, both always visible)
echo ""
echo "── 05: IME keyboard (MANUAL)"
shoot ss05 "$OUT/05.png"

# ── 06: IME AUTO mode — tap A button at (163, 1291)
echo ""
echo "── 06: IME keyboard (AUTO) — tapping A button at (163,1291)"
adb shell input tap 163 1291
sleep 1
shoot ss06 "$OUT/06.png"

# ── 07: IME CONTINUOUS mode — tap ∞ button at (406, 1291)
echo ""
echo "── 07: IME keyboard (CONTINUOUS) — tapping ∞ button at (406,1291)"
adb shell input tap 406 1291
sleep 1
shoot ss07 "$OUT/07.png"

# ── Cleanup ──────────────────────────────────────────────────────────────────
adb shell input keyevent KEYCODE_HOME
stop_app

echo ""
echo "=== DONE ==="
ls -lh "$OUT/"
echo ""
echo "PASS: All 7 screenshots captured."

# ── Shut down emulator ────────────────────────────────────────────────────────
echo ""
echo "==> Shutting down emulator..."
adb emu kill 2>/dev/null || true
echo "  Emulator stopped."
