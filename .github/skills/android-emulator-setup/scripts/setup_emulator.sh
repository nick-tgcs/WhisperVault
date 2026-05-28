#!/usr/bin/env bash
# setup_emulator.sh — Install and configure WhisperVault on a running emulator.
#
# Prerequisite: emulator is booted (adb devices shows a device).
# Usage: bash .github/skills/android-emulator-setup/scripts/setup_emulator.sh
#
# What it does:
#   1. Waits for full boot
#   2. Grants adb root
#   3. Installs the debug APK
#   4. Grants RECORD_AUDIO and POST_NOTIFICATIONS silently
#   5. Enables + sets WhisperVault as the default IME (both mechanisms)
#   6. Disables animations (needed for Espresso test stability)
#   7. Force-stops the app

set -euo pipefail

# ── Ensure adb is on PATH ────────────────────────────────────────────────────
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export PATH="$PATH:$ANDROID_HOME/platform-tools"

PACKAGE="io.github.nick_tgcs.whispervault"
IME_SERVICE="${PACKAGE}/com.whisperonnx.WhisperInputMethodService"
APK="app/build/outputs/apk/debug/app-debug.apk"

# ── 1. Wait for device ───────────────────────────────────────────────────────
echo "==> Waiting for device..."
adb wait-for-device
for i in $(seq 1 60); do
    booted=$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
    [ "$booted" = "1" ] && break
    [ "$i" -eq 60 ] && { echo "ERROR: device never finished booting"; exit 1; }
    sleep 1
done
echo "  Device ready."

# ── 2. Grant root ────────────────────────────────────────────────────────────
echo "==> Granting adb root..."
adb root
sleep 1

# ── 3. Install APK ───────────────────────────────────────────────────────────
if [ ! -f "$APK" ]; then
    echo "ERROR: APK not found at $APK — run ./gradlew assembleDebug first"
    exit 1
fi
echo "==> Installing APK..."
adb install -r "$APK"

# ── 4. Grant permissions ─────────────────────────────────────────────────────
echo "==> Granting permissions..."
for perm in android.permission.RECORD_AUDIO android.permission.POST_NOTIFICATIONS; do
    adb shell pm grant "$PACKAGE" "$perm" 2>/dev/null \
        && echo "  Granted: $perm" \
        || echo "  Skipped (not runtime-requestable): $perm"
done

# ── 5. Set WhisperVault as default IME ───────────────────────────────────────
echo "==> Configuring IME..."
adb shell ime enable "$IME_SERVICE"
adb shell ime set "$IME_SERVICE"
adb shell settings put secure default_input_method "$IME_SERVICE"
echo "  IME set to: $IME_SERVICE"

# ── 6. Disable animations (Espresso timing) ──────────────────────────────────
echo "==> Disabling animations..."
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0

# ── 7. Force-stop ────────────────────────────────────────────────────────────
echo "==> Force-stopping app..."
adb shell am force-stop "$PACKAGE"

echo ""
echo "==> Setup complete."
echo "    For manual testing with real transcription: see android-manual-testing skill"
echo "    For screenshots only:                       see android-emulator-screenshots skill"
