---
name: android-emulator-screenshots
description: 'Capture all 7 WhisperVault Play Store / README screenshots on the emulator. USE FOR: taking or retaking screenshots after UI changes; screenshots showing wrong UI state, permission dialogs, or SetupActivity; ∞ button not visible; IME not appearing. Requires the emulator to be set up first (see android-emulator-setup). Uses dummy model files — transcription does not work in screenshot mode (see android-manual-testing for real testing).'
argument-hint: 'Optional: which screenshot(s) to retake, e.g. "IME auto mode" or "05 and 06"'
---

# Android Emulator Screenshots — WhisperVault

**Two commands. Read the expected output. Done.**

---

## Step 0 — Export Android SDK path (run once per terminal session)

```bash
export ANDROID_HOME=~/Android/Sdk && export PATH="$PATH:$ANDROID_HOME/platform-tools"
```

Expected: no output. If `adb version` prints a version number afterwards, you are ready.

---

## Step 1 — Verify emulator is running

```bash
adb devices
```

Expected output contains: `emulator-5554   device`

**STOP if you do not see `emulator-5554   device`.** Follow the `android-emulator-setup` skill first, then come back.

---

## Step 2 — Install and configure the app

```bash
bash .github/skills/android-emulator-setup/scripts/setup_emulator.sh
```

Expected last line: `==> Setup complete.`

**STOP if you do not see `==> Setup complete.`** Report the last line of output and do not continue.

---

## Step 3 — Take all 7 screenshots

```bash
bash .github/skills/android-emulator-screenshots/scripts/take_screenshots.sh
```

### Expected output (every line must appear)

```
==> [0/6] Gaining adb root...
  Root active.
==> [1/6] Setting default IME...
  IME confirmed: io.github.nick_tgcs.whispervault/com.whisperonnx.WhisperInputMethodService
==> [2/6] Creating dummy model files...
  6 .onnx files on device.
==> [3/6] Taking activity screenshots (01-04)...
── 01: MainActivity
  SAVED: fastlane/metadata/android/en-US/images/phoneScreenshots/01.png
── 02: RecognizeActivity (MANUAL)
  SAVED: fastlane/metadata/android/en-US/images/phoneScreenshots/02.png
── 03: RecognizeActivity (AUTO)
  SAVED: fastlane/metadata/android/en-US/images/phoneScreenshots/03.png
── 04: Settings screen
  SAVED: fastlane/metadata/android/en-US/images/phoneScreenshots/04.png
==> [4/6] Re-asserting IME before keyboard screenshots...
  IME confirmed: io.github.nick_tgcs.whispervault/com.whisperonnx.WhisperInputMethodService
==> [5/6] Opening Contacts and summoning IME keyboard...
  IME switched and field cleared.
==> [6/6] Taking IME screenshots (05-07)...
── 05: IME keyboard (MANUAL)
  SAVED: fastlane/metadata/android/en-US/images/phoneScreenshots/05.png
── 06: IME keyboard (AUTO) — tapping A button at (163,1291)
  SAVED: fastlane/metadata/android/en-US/images/phoneScreenshots/06.png
── 07: IME keyboard (CONTINUOUS) — tapping ∞ button at (406,1291)
  SAVED: fastlane/metadata/android/en-US/images/phoneScreenshots/07.png
=== DONE ===
PASS: All 7 screenshots captured.

==> Shutting down emulator...
  Emulator stopped.
```

---

## Step 4 — Verify files exist and are not tiny

```bash
ls -lh fastlane/metadata/android/en-US/images/phoneScreenshots/
```

Expected: 7 files, each **at least 80K**:

```
01.png   ~82K   MainActivity
02.png   ~86K   RecognizeActivity MANUAL
03.png   ~92K   RecognizeActivity AUTO
04.png  ~115K   Settings
05.png   ~90K   IME MANUAL
06.png   ~95K   IME AUTO
07.png   ~93K   IME CONTINUOUS
```

If any file is missing or smaller than 10K, the screenshot failed — see Error table below.

---

## What each screenshot should show

| File | What to look for |
|------|-----------------|
| `01.png` | WhisperVault title, blue mic button, Append/Translate toggles |
| `02.png` | Floating overlay, blue mic, A button off, ∞ button off |
| `03.png` | Floating overlay, red mic, "Recording…" text, A button on (filled blue), ∞ outline |
| `04.png` | Settings screen: language picker dropdown, VAD silence slider, Bluetooth toggle |
| `05.png` | Black IME keyboard strip, blue mic, A button off, ∞ button off, Contacts form above |
| `06.png` | Black IME keyboard strip, red mic, "Recording…", A button on (filled), ∞ outline |
| `07.png` | Black IME keyboard strip, red mic, "Recording…", A button on (filled), ∞ filled blue |

---

## Error table

| Error message or symptom | Fix |
|---|---|
| Script exits before `PASS` with `ERROR: IME set failed` | Run `bash .github/skills/android-emulator-setup/scripts/setup_emulator.sh`, then retry. |
| `ERROR: Expected 6 .onnx files, got 0` | App not installed. Run setup script, then retry. |
| `ERROR: ... is only N bytes` | Screencap returned blank. Force-stop the app manually (`adb shell am force-stop io.github.nick_tgcs.whispervault`) and retry. |
| 05/06/07 show GBoard (QWERTY keys) instead of WhisperVault (mic button) | Run setup script, retry. If persists, run `adb shell ime set io.github.nick_tgcs.whispervault/com.whisperonnx.WhisperInputMethodService` manually. |
| 02/03 show SetupActivity ("Select Model" screen) | Dummy model files missing. Delete old files and re-run: `adb shell rm -rf /sdcard/Android/data/io.github.nick_tgcs.whispervault/files/` then retry the screenshot script. |
| 02/03 show an integrity check dialog | `model_name` pref not set correctly. The script injects `whisper_tiny_int8` automatically — re-run the screenshot script. |

---

## Notes (do not affect the procedure)

- **Dummy model files**: The script creates 1-byte placeholder `.onnx` files. WhisperVault's file-count check passes, so SetupActivity is suppressed. ONNX Runtime cannot load them — the mic button works but inference produces no output. This is intentional for screenshots.
- **∞ button is always visible**: In both MANUAL and AUTO/CONTINUOUS modes, the ∞ button is shown. In MANUAL mode it is just not highlighted.
- **RecognizeActivity CONTINUOUS mode**: Cannot be screenshotted (the ∞ button calls `finish()` immediately). Settings screen is used for slot 04 instead.
- **IME coordinates**: Calibrated for 1080×1920 @ 420dpi with IME top at y=1202. If the emulator resolution changes, recalibrate by running `adb shell dumpsys window windows | grep -A5 InputMethod` and looking for the `Frames:` line.


