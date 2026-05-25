#!/usr/bin/env bash
# =============================================================================
# setup_e2e_tests.sh — One-time setup for FullTranscriptionTest
#
# What this script does:
#   1. Checks that an Android emulator is running via ADB.
#   2. Downloads the whisper_small_int8 model (6 ONNX files) from HuggingFace
#      and pushes them to the emulator's app-private external storage so that
#      Whisper.java finds exactly 6 files and skips SetupActivity.
#   3. Generates a short speech WAV ("one two three four five") using espeak-ng
#      and converts it to 16 kHz mono s16le via ffmpeg.
#      The resulting file is written to:
#        app/src/androidTest/assets/speech_test.wav
#      (packed into the test APK so FullTranscriptionTest can open it as an asset).
#
# Prerequisites on the HOST (not the emulator):
#   - ADB in PATH (or ANDROID_HOME set)
#   - espeak-ng   (sudo apt-get install -y espeak-ng)
#   - ffmpeg      (sudo apt-get install -y ffmpeg)
#   - curl + unzip
#
# Run from the project root:
#   ./scripts/setup_e2e_tests.sh
#
# After this script succeeds, run the test with:
#   source ~/.sdkman/bin/sdkman-init.sh && sdk use java 17.0.15-tem
#   export ANDROID_HOME=~/Android/Sdk
#   export PATH=$PATH:$ANDROID_HOME/platform-tools
#   ./gradlew connectedDebugAndroidTest \
#     -Pandroid.testInstrumentationRunnerArguments.class=com.whisperonnx.FullTranscriptionTest
# =============================================================================
set -euo pipefail

# ---------------------------------------------------------------------------
# Paths
# ---------------------------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
ASSETS_DIR="$PROJECT_DIR/app/src/androidTest/assets"
SPEECH_WAV="$ASSETS_DIR/speech_test.wav"

# Path on the device where model files are staged.
# IMPORTANT: This must NOT be inside the app's external files dir
# (/sdcard/Android/data/<pkg>/files/) because the Android Gradle Plugin
# uninstalls the app — and thereby deletes that dir — after every test run.
# /data/local/tmp/whisper_model survives app install/uninstall cycles.
#
# The stageModelFilesOnDevice Gradle task reads from this path via a host-side
# `adb shell cp` command (which runs as u:r:shell:s0 with full filesystem
# access, bypassing Android 13 scoped-storage restrictions).
DEVICE_MODEL_DIR="/data/local/tmp/whisper_model"

# HuggingFace direct-download URL (resolve, not blob)
ZIP_URL="https://huggingface.co/DocWolle/whisperOnnx/resolve/main/whisper_small_int8.zip"

# Exact file names that Recognizer.java expects (in any order inside the dir)
MODEL_FILES=(
    "Whisper_initializer.onnx"
    "Whisper_encoder.onnx"
    "Whisper_decoder.onnx"
    "Whisper_cache_initializer.onnx"
    "Whisper_cache_initializer_batch.onnx"
    "Whisper_detokenizer.onnx"
)

# Text spoken in the test WAV.  Must match FullTranscriptionTest.SPEECH_ASSET logic.
SPEECH_TEXT="one two three four five"

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------
info()  { echo "[INFO]  $*"; }
ok()    { echo "[OK]    $*"; }
warn()  { echo "[WARN]  $*"; }
die()   { echo "[ERROR] $*" >&2; exit 1; }

require_cmd() {
    command -v "$1" &>/dev/null || die "'$1' is not installed. $2"
}

# ---------------------------------------------------------------------------
# Locate ADB
# ---------------------------------------------------------------------------
if [ -n "${ANDROID_HOME:-}" ] && [ -x "$ANDROID_HOME/platform-tools/adb" ]; then
    ADB="$ANDROID_HOME/platform-tools/adb"
elif command -v adb &>/dev/null; then
    ADB="adb"
else
    die "adb not found. Set ANDROID_HOME or add platform-tools to PATH."
fi

# ---------------------------------------------------------------------------
# Step 0: Check emulator
# ---------------------------------------------------------------------------
echo ""
echo "=== WhisperIME+ E2E Test Setup ==="
echo ""
info "Checking ADB connection..."
if ! "$ADB" devices 2>/dev/null | grep -qE "emulator|device$"; then
    die "No running emulator/device detected.\n" \
        "Start the emulator with:\n" \
        "  source ~/.sdkman/bin/sdkman-init.sh\n" \
        "  export ANDROID_HOME=~/Android/Sdk\n" \
        "  nohup \$ANDROID_HOME/emulator/emulator -avd test33 \\\n" \
        "    -no-snapshot -no-boot-anim -gpu swiftshader_indirect \\\n" \
        "    -memory 4096 -no-metrics > /tmp/emu.log 2>&1 & disown \$!\n" \
        "Then wait for it to boot and re-run this script."
fi
ok "Emulator is online."

# ---------------------------------------------------------------------------
# Step 1: Push model files
# ---------------------------------------------------------------------------
echo ""
info "Checking model files on device..."

already_installed=true
for f in "${MODEL_FILES[@]}"; do
    if ! "$ADB" shell "test -f '$DEVICE_MODEL_DIR/$f'" 2>/dev/null; then
        already_installed=false
        break
    fi
done

if $already_installed; then
    ok "All 6 model files already staged at $DEVICE_MODEL_DIR — skipping download."
else
    require_cmd curl  "Install with: sudo apt-get install -y curl"
    require_cmd unzip "Install with: sudo apt-get install -y unzip"

    TMP_DIR=$(mktemp -d)
    trap 'rm -rf "$TMP_DIR"' EXIT

    ZIP_FILE="$TMP_DIR/whisper_model.zip"
    info "Downloading Whisper model from HuggingFace (this may take several minutes)..."
    info "URL: $ZIP_URL"
    curl -L --progress-bar --fail "$ZIP_URL" -o "$ZIP_FILE" \
        || die "Download failed. Check your internet connection."

    info "Extracting..."
    unzip -q "$ZIP_FILE" -d "$TMP_DIR/model" \
        || die "Failed to extract $ZIP_FILE"

    info "Creating device staging directory $DEVICE_MODEL_DIR ..."
    "$ADB" shell "mkdir -p '$DEVICE_MODEL_DIR'"

    info "Pushing model files..."
    for f in "${MODEL_FILES[@]}"; do
        src=$(find "$TMP_DIR/model" -name "$f" 2>/dev/null | head -1)
        if [ -z "$src" ]; then
            die "File '$f' not found in the downloaded zip. " \
                "The archive may have changed — check $ZIP_URL"
        fi
        echo "    → $f"
        "$ADB" push "$src" "$DEVICE_MODEL_DIR/$f" >/dev/null
    done

    # Verify
    count=$("$ADB" shell "ls '$DEVICE_MODEL_DIR' 2>/dev/null | wc -l" | tr -d '[:space:]')
    if [ "${count:-0}" -lt 6 ]; then
        die "Expected 6 model files on device but found $count. Push may have failed."
    fi
    ok "Model staged successfully ($count files in $DEVICE_MODEL_DIR)."
    ok "  Note: stored at $DEVICE_MODEL_DIR on public external storage; survives app reinstalls."
fi

# ---------------------------------------------------------------------------
# Step 2: Generate test speech WAV
# ---------------------------------------------------------------------------
echo ""
mkdir -p "$ASSETS_DIR"

if [ -f "$SPEECH_WAV" ]; then
    ok "speech_test.wav already exists — skipping generation."
    ok "  Path : $SPEECH_WAV"
    ok "  Size : $(wc -c < "$SPEECH_WAV") bytes"
else
    require_cmd ffmpeg "Install with: sudo apt-get install -y ffmpeg"

    TMP_DIR=${TMP_DIR:-$(mktemp -d)}
    trap 'rm -rf "$TMP_DIR"' EXIT

    info "Synthesising speech: \"$SPEECH_TEXT\""

    if command -v espeak-ng &>/dev/null; then
        # Preferred: local offline TTS (no network required)
        TMP_RAW="$TMP_DIR/speech_raw.wav"
        espeak-ng -w "$TMP_RAW" -s 130 "$SPEECH_TEXT" \
            || die "espeak-ng failed to generate audio."
        info "Converting to 16 kHz mono s16le PCM WAV..."
        ffmpeg -y -loglevel error \
            -i "$TMP_RAW" \
            -ar 16000 -ac 1 -f wav -acodec pcm_s16le \
            "$SPEECH_WAV" \
            || die "ffmpeg conversion failed."
    elif command -v python3 &>/dev/null && python3 -c "import gtts" 2>/dev/null; then
        # Fallback: Google TTS via gTTS Python package (requires internet)
        info "espeak-ng not found; using gTTS (Google TTS) — requires internet."
        TMP_MP3="$TMP_DIR/speech.mp3"
        python3 - <<EOF
from gtts import gTTS
gTTS(text="$SPEECH_TEXT", lang="en", slow=False).save("$TMP_MP3")
EOF
        ffmpeg -y -loglevel error \
            -i "$TMP_MP3" \
            -ar 16000 -ac 1 -f wav -acodec pcm_s16le \
            "$SPEECH_WAV" \
            || die "ffmpeg conversion from gTTS mp3 failed."
    else
        die "No TTS engine found. Install espeak-ng (sudo apt-get install -y espeak-ng) " \
            "or gTTS (pip3 install --break-system-packages gTTS) and retry."
    fi

    ok "Created: $SPEECH_WAV"
    ok "  Size : $(wc -c < "$SPEECH_WAV") bytes"
fi

# ---------------------------------------------------------------------------
# Done
# ---------------------------------------------------------------------------
echo ""
echo "=== Setup complete! ==="
echo ""
echo "Run the E2E transcription test with:"
echo ""
echo "  source ~/.sdkman/bin/sdkman-init.sh && sdk use java 17.0.15-tem"
echo "  export ANDROID_HOME=~/Android/Sdk"
echo "  export PATH=\$PATH:\$ANDROID_HOME/platform-tools"
echo "  cd '$PROJECT_DIR'"
echo "  ./gradlew connectedDebugAndroidTest \\"
echo "    -Pandroid.testInstrumentationRunnerArguments.class=com.whisperonnx.FullTranscriptionTest"
echo ""
