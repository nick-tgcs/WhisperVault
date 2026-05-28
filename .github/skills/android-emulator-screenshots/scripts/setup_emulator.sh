#!/usr/bin/env bash
# setup_emulator.sh — Configure emulator for WhisperVault screenshot sessions.
# NOTE: Prefer .github/skills/android-emulator-setup/scripts/setup_emulator.sh
# for general setup. This script is a thin wrapper used by take_screenshots.sh.
# Usage: bash .github/skills/android-emulator-screenshots/scripts/setup_emulator.sh [package]
# Default package: io.github.nick_tgcs.whispervault

set -euo pipefail

PACKAGE="${1:-io.github.nick_tgcs.whispervault}"
MAIN_ACTIVITY="${PACKAGE}/com.whisperonnx.MainActivity"
RECOGNIZE_ACTIVITY="${PACKAGE}/com.whisperonnx.WhisperRecognizeActivity"
IME_SERVICE="${PACKAGE}/com.whisperonnx.WhisperInputMethodService"
PREFS_FILE="/data/data/${PACKAGE}/shared_prefs/${PACKAGE}_preferences.xml"
SCREENSHOT_DIR="fastlane/metadata/android/en-US/images/phoneScreenshots"
TEMP_DIR="ai/temp"

# ── 1. Wait for device ───────────────────────────────────────────────────────
echo "==> Waiting for device..."
adb wait-for-device
adb shell getprop sys.boot_completed | grep -q "^1$" || {
    echo "  Device not fully booted, waiting up to 60s..."
    for i in $(seq 1 60); do
        sleep 1
        adb shell getprop sys.boot_completed 2>/dev/null | grep -q "^1$" && break
        [ "$i" -eq 60 ] && { echo "ERROR: device never finished booting"; exit 1; }
    done
}
echo "  Device ready."

# ── 2. Install APK ───────────────────────────────────────────────────────────
APK="app/build/outputs/apk/debug/app-debug.apk"
if [ ! -f "$APK" ]; then
    echo "ERROR: APK not found at $APK — run ./gradlew assembleDebug first"
    exit 1
fi
echo "==> Installing APK..."
adb install -r "$APK"

# ── 3. Grant all required permissions ────────────────────────────────────────
echo "==> Granting permissions..."
for perm in \
    android.permission.RECORD_AUDIO \
    android.permission.POST_NOTIFICATIONS
do
    adb shell pm grant "$PACKAGE" "$perm" 2>/dev/null && echo "  Granted: $perm" || echo "  Skipped (not requestable): $perm"
done

# ── 4. Enable and set IME ────────────────────────────────────────────────────
echo "==> Enabling WhisperVault IME..."
adb shell ime enable "$IME_SERVICE"
adb shell ime set "$IME_SERVICE"
adb shell settings put secure default_input_method "$IME_SERVICE"
echo "  IME set."

# ── 5. Dismiss any existing dialogs (send BACK twice) ───────────────────────
echo "==> Dismissing dialogs..."
adb shell input keyevent KEYCODE_BACK
sleep 0.5
adb shell input keyevent KEYCODE_BACK
sleep 0.5

# ── 6. Reset app data / preferences to clean state ──────────────────────────
echo "==> Resetting app preferences to clean state..."
adb shell am force-stop "$PACKAGE"
sleep 1

mkdir -p "$SCREENSHOT_DIR" "$TEMP_DIR"
echo "  Done."

echo ""
echo "==> Setup complete. Device is ready for screenshots."
echo "    Run: bash scripts/take_screenshots.sh"
