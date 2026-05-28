---
name: android-emulator-setup
description: 'Start the Android emulator, build the APK, install it, grant permissions, and configure the IME — everything needed before running the app or taking screenshots. USE FOR: first-time setup; after a fresh clone; after an AVD reset; when adb devices shows nothing; when WhisperVault IME is not showing. This skill covers only device/app configuration — it does NOT install real model files (see android-manual-testing) or take screenshots (see android-emulator-screenshots).'
---

# Android Emulator Setup — WhisperVault

**Run every command below in order. Do not skip any step.**

---

## Check 1 — Java 17

```bash
source ~/.sdkman/bin/sdkman-init.sh && sdk use java 17.0.15-tem
java -version 2>&1 | head -1
```

Expected output contains: `openjdk version "17.`

If you see `17.` → continue. Any other version → the `sdk use` command failed.

---

## Check 2 — Android SDK

```bash
export ANDROID_HOME=~/Android/Sdk
export PATH=$PATH:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator
ls $ANDROID_HOME/emulator/emulator
```

Expected: a file path printed, no error.

---

## Check 3 — AVD exists

```bash
emulator -list-avds
```

Expected output contains: `test33`

If `test33` is missing, the AVD has not been created. Create it in Android Studio → Virtual Device Manager using a **non-Google-Play** API 33 x86_64 image.

---

## Step 1 — Start the emulator

```bash
adb devices
```

If output contains `emulator-5554   device` → **skip this step**, the emulator is already running.

Otherwise:

```bash
nohup emulator -avd test33 -no-snapshot -no-boot-anim \
  -gpu swiftshader_indirect -memory 4096 -no-metrics \
  > /tmp/emu.log 2>&1 & disown $!
```

---

## Step 2 — Wait for boot

Run the following command and keep running it until it prints `1`. This takes 30–90 seconds.

```bash
adb shell getprop sys.boot_completed
```

Expected output: exactly `1`

Do not proceed until you see `1`.

---

## Step 3 — Build the APK (skip if already built)

```bash
ls app/build/outputs/apk/debug/app-debug.apk
```

If the file exists → skip to Step 4.

If it does not exist:

```bash
./gradlew assembleDebug
```

Expected last line: `BUILD SUCCESSFUL`

If you see `BUILD FAILED`, fix the compilation errors before continuing.

---

## Step 4 — Install and configure

```bash
bash .github/skills/android-emulator-setup/scripts/setup_emulator.sh
```

Expected output (all of these lines must appear):

```
==> Waiting for device...
  Device ready.
==> Granting adb root...
==> Installing APK...
Success
==> Granting permissions...
  Granted: android.permission.RECORD_AUDIO
==> Configuring IME...
  IME set to: io.github.nick_tgcs.whispervault/com.whisperonnx.WhisperInputMethodService
==> Disabling animations...
==> Force-stopping app...

==> Setup complete.
```

---

## Step 5 — Verify

```bash
adb shell settings get secure default_input_method
```

Expected output (exact):

```
io.github.nick_tgcs.whispervault/com.whisperonnx.WhisperInputMethodService
```

If output is different → re-run Step 4.

---

## Error table

| What you see | What to do |
|---|---|
| `adb root` fails: "adbd cannot run as root in production builds" | Wrong AVD image. The AVD must use the **non-Google-Play** system image. Recreate the AVD in Android Studio using "Google APIs" or plain AOSP image. |
| `BUILD FAILED` | Fix the compilation error shown above `BUILD FAILED`, then re-run `./gradlew assembleDebug`. |
| Step 5 shows `null` or GBoard package | Re-run `bash .github/skills/android-emulator-setup/scripts/setup_emulator.sh`. |
| `adb devices` shows `unauthorized` | Run `adb kill-server && adb start-server`, then accept the RSA key on the emulator screen. |
| `emulator -list-avds` is empty | Create AVD in Android Studio → Virtual Device Manager. Use API 33, x86_64, non-Google-Play image. |

