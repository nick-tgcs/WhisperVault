---
name: android-manual-testing
description: 'Install real Whisper ONNX model files on the emulator and manually test WhisperVault — speech is recognised and text actually appears. USE FOR: verifying that transcription works end-to-end; testing a new feature or bug fix manually; confirming recording modes (Manual / Auto / Continuous) behave correctly; testing the IME in a real text field; testing RecognizeActivity as a voice input overlay. Requires the emulator to already be set up (see android-emulator-setup). This skill is NOT for screenshots (dummy model files are used there instead).'
---

# Android Manual Testing — WhisperVault

**Prerequisite: complete `android-emulator-setup` first.**

---

## Step 1 — Install the real Whisper model

```bash
./scripts/setup_e2e_tests.sh
```

This downloads `whisper_small_int8` (~242 MB) from HuggingFace and stages it on the device. It is skipped automatically if the files are already present.

**Host prerequisites (install once):**
```bash
sudo apt-get install -y curl unzip ffmpeg espeak-ng
```

### Expected output (key lines):

```
[OK]    Emulator is online.
[INFO]  Downloading Whisper model from HuggingFace ...
[OK]    Model staged successfully (6 files in /data/local/tmp/whisper_model).
```

OR if already downloaded:

```
[OK]    All 6 model files already staged at /data/local/tmp/whisper_model — skipping download.
```

After this, the 6 ONNX files live at `/data/local/tmp/whisper_model/` and survive `adb install -r` cycles.

---

## Step 2 — Copy model files into the app's storage

```bash
adb root
adb shell mkdir -p /sdcard/Android/data/io.github.nick_tgcs.whispervault/files/
adb shell cp /data/local/tmp/whisper_model/*.onnx \
  /sdcard/Android/data/io.github.nick_tgcs.whispervault/files/
```

Verify:

```bash
adb shell ls /sdcard/Android/data/io.github.nick_tgcs.whispervault/files/ | wc -l
```

Expected output: `6`

---

## Step 3 — Set the model name preference to the real model

The screenshot skill uses `whisper_tiny_int8` to bypass the integrity check. For real transcription, reset it to `whisper_small_int8`:

```bash
PREFS="/data/data/io.github.nick_tgcs.whispervault/shared_prefs/io.github.nick_tgcs.whispervault_preferences.xml"

cat > /tmp/real_model_prefs.xml << 'EOF'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <int name="versionCode" value="17" />
    <string name="model_name">whisper_small_int8</string>
</map>
EOF

adb push /tmp/real_model_prefs.xml "$PREFS"
rm /tmp/real_model_prefs.xml
```

---

## Step 4 — Launch the app

```bash
adb shell am start -n io.github.nick_tgcs.whispervault/com.whisperonnx.MainActivity
```

On first launch, the app verifies SHA-256 hashes of all 6 model files. A progress dialog will appear and disappear (takes ~5 seconds). This is expected — it only happens once.

---

## Step 5 — Test transcription

### Standalone app (MainActivity)

- The blue mic button is in the centre of the screen.
- **Manual mode**: press and hold the mic button while speaking → release → text appears below.
- **Auto mode**: tap the **A** button → mic opens automatically → stops when silence is detected → text appears.
- **Continuous mode**: tap **A** then **∞** → recording restarts automatically after each utterance → text accumulates.

### IME keyboard (dictate into any text field)

```bash
# Open Contacts "Create contact" as a text field host
adb shell am start -a android.intent.action.INSERT -t vnd.android.cursor.dir/contact
```

Tap the First name field → WhisperVault keyboard appears.

- **Manual**: press and hold mic → speak → release → text typed into the field.
- **Auto**: tap **A** → speak → silence detected → text typed.
- **Continuous**: tap **A** → tap **∞** → keep talking → tap **A** again to stop.

### Voice overlay (RecognizeActivity)

```bash
adb shell am start \
  -a android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH \
  -n io.github.nick_tgcs.whispervault/com.whisperonnx.WhisperRecognizeActivity
```

A floating overlay appears. Tap the mic button and speak. The result is discarded when tested manually (no caller to receive it).

---

## Recording mode reference

| Mode | Activate | Mic stops |
|------|----------|-----------|
| Manual | Hold mic button | On release |
| Auto | Tap **A** | Silence detected |
| Continuous | Tap **A** then **∞** | Tap **A** again |

**Mic colour guide:** blue = idle · amber = listening · red = recording · light blue = processing

---

## Error table

| Symptom | Fix |
|---|---|
| App shows "Select Model" (SetupActivity) | Fewer than 6 ONNX files in `/sdcard/Android/data/.../files/`. Re-run Step 2. |
| Mic records but no text appears | Wrong model name in prefs (`whisper_tiny_int8` is set). Re-run Step 3. |
| Integrity mismatch dialog on launch | Files are corrupted or were replaced with dummies. Re-run Steps 2–3. |
| `[ERROR] No running emulator/device detected` in setup_e2e_tests.sh | Emulator is not booted. Run `adb devices` — must show `emulator-5554   device`. |
| App shows "Initializing…" forever | ONNX Runtime takes 10–20 s on first load. Wait. If it never finishes: `adb logcat -s OnnxRuntime:E` |
| IME keyboard shows GBoard instead of WhisperVault | Re-run `bash .github/skills/android-emulator-setup/scripts/setup_emulator.sh` |

