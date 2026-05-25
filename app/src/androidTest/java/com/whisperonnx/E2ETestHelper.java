package com.whisperonnx;

import android.app.UiAutomation;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import org.junit.Assume;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/**
 * Shared setup utilities for all E2E (instrumented) tests.
 *
 * <p>Replaces the former production-code bypass flags
 * ({@code sSkipImeCheckForTesting}, {@code sSkipModelCheckForTesting},
 * {@code sSkipDialogsForTesting}) with proper environment setup that exercises
 * the same production code paths a real user would hit:
 *
 * <ul>
 *   <li>{@link #stageModelFiles()} — copies the 6 ONNX model files from the
 *       persistent on-device staging directory to the app's external files dir
 *       so that {@code Whisper.java} finds exactly 6 files on launch, and the
 *       SHA-256 integrity check passes without a dialog.
 *   <li>{@link #enableWhisperIme()} — enables and activates
 *       {@code WhisperInputMethodService} via shell commands so that
 *       {@code MainActivity.checkInputMethodEnabled()} does not redirect to the
 *       system Settings screen.
 *   <li>{@link #dismissStartupDialogs()} — uses UIAutomator to click through
 *       any one-time informational dialogs (e.g. FreeDroidWarn, GithubStar)
 *       that may appear on first launch after a fresh install.
 * </ul>
 *
 * <p><b>Usage pattern:</b>
 * <pre>
 *   {@literal @}BeforeClass
 *   public static void setUp() {
 *       E2ETestHelper.setUp();
 *   }
 *
 *   {@literal @}Test
 *   public void myTest() {
 *       try (ActivityScenario&lt;MainActivity&gt; s = ActivityScenario.launch(MainActivity.class)) {
 *           E2ETestHelper.dismissStartupDialogs();
 *           // ... Espresso assertions ...
 *       }
 *   }
 * </pre>
 */
public final class E2ETestHelper {

    private static final String TAG = "E2ETestHelper";



    private static final String[] MODEL_FILE_NAMES = {
            "Whisper_initializer.onnx",
            "Whisper_encoder.onnx",
            "Whisper_decoder.onnx",
            "Whisper_cache_initializer.onnx",
            "Whisper_cache_initializer_batch.onnx",
            "Whisper_detokenizer.onnx",
    };

    private static final String IME_COMPONENT =
            "org.woheller69.whisperplus/com.whisperonnx.WhisperInputMethodService";

    private E2ETestHelper() {}

    /**
     * Full environment setup. Call from {@code @BeforeClass}.
     * Verifies model files are present then enables WhisperIME.
     */
    public static void setUp() {
        stageModelFiles();
        enableWhisperIme();
    }

    /**
     * Verifies that the 6 ONNX model files are present in the app's external
     * files directory.  They are staged there by the {@code stageModelFilesOnDevice}
     * Gradle task, which runs a host-side {@code adb shell cp} command before
     * instrumentation starts — bypassing Android 13 scoped-storage restrictions
     * that prevent {@code UiAutomation.executeShellCommand()} from reading
     * outside the app's own package directory.
     *
     * <p>Skips the enclosing test class via {@link Assume} if any file is absent,
     * so failures show up as "assumption failed" rather than a misleading
     * {@code NoMatchingViewException}.  To fix: run
     * {@code scripts/setup_e2e_tests.sh} then
     * {@code ./gradlew connectedDebugAndroidTest}.
     */
    public static void stageModelFiles() {
        File dir = InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getExternalFilesDir(null);
        Assume.assumeTrue("External storage unavailable — cannot run E2E tests", dir != null);
        for (String name : MODEL_FILE_NAMES) {
            File f = new File(dir, name);
            if (!f.exists()) {
                Log.w(TAG, "Missing model file: " + f.getAbsolutePath()
                        + " — run scripts/setup_e2e_tests.sh then ./gradlew connectedDebugAndroidTest");
            }
            Assume.assumeTrue("Model file not staged: " + name
                    + " (run scripts/setup_e2e_tests.sh first)", f.exists());
        }
        Log.i(TAG, "All 6 model files confirmed in " + dir.getAbsolutePath());
    }

    /**
     * Enables and activates {@code WhisperInputMethodService} via {@code ime}
     * shell commands.
     *
     * <p>After this call, {@code MainActivity.checkInputMethodEnabled()} will
     * find the IME in the system's enabled-methods list and will not redirect
     * to the system Settings screen.
     */
    public static void enableWhisperIme() {
        UiAutomation ua = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        shellCmd(ua, "ime enable " + IME_COMPONENT);
        shellCmd(ua, "ime set " + IME_COMPONENT);
    }

    /**
     * Dismisses any blocking one-time dialog (e.g. FreeDroidWarn upgrade notice,
     * GitHub star prompt) by clicking the positive button.
     *
     * <p>Call immediately after {@code ActivityScenario.launch()} and before
     * any Espresso view interactions. If no dialog appears within the wait
     * window, this method returns quickly without any action.
     *
     * <p>Handles up to 3 sequential dialogs.
     */
    public static void dismissStartupDialogs() {
        UiDevice device = UiDevice.getInstance(
                InstrumentationRegistry.getInstrumentation());
        for (int i = 0; i < 3; i++) {
            UiObject2 btn = device.wait(
                    Until.findObject(By.res("android:id/button1")), 1500L);
            if (btn == null) break;
            btn.click();
        }
    }

    private static void shellCmd(UiAutomation ua, String cmd) {
        try (ParcelFileDescriptor pfd = ua.executeShellCommand(cmd)) {
            // Drain stdout to ensure the command completes before we return.
            byte[] buf = new byte[512];
            try (FileInputStream fis = new FileInputStream(pfd.getFileDescriptor())) {
                //noinspection StatementWithEmptyBody
                while (fis.read(buf) != -1) { /* drain */ }
            }
        } catch (IOException e) {
            Log.e(TAG, "Shell command failed: " + cmd, e);
        }
    }
}
