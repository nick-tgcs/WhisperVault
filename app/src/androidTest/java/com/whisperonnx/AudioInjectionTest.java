package com.whisperonnx;

import android.Manifest;
import android.os.SystemClock;

import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.GrantPermissionRule;

import com.whisperonnx.asr.RecordBuffer;
import com.whisperonnx.asr.Recorder;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotNull;

/**
 * POC: App-level audio file injection.
 *
 * Instead of using the real microphone (or the broken gRPC injectAudio API),
 * we pre-load raw s16le 16 kHz mono PCM bytes into {@link Recorder#sTestAudioBytes}.
 * When the record button is pressed, {@link Recorder#recordAudio()} detects the
 * flag, skips AudioRecord entirely, and feeds the bytes directly into RecordBuffer —
 * exactly as if the mic had captured them.
 *
 * Pipeline: sTestAudioBytes → RecordBuffer → (Whisper transcription, if model present)
 *
 * Run with:
 *   ./gradlew connectedDebugAndroidTest --tests '*.AudioInjectionTest'
 */
@RunWith(AndroidJUnit4.class)
public class AudioInjectionTest {

    @ClassRule
    public static GrantPermissionRule grantPermissions = GrantPermissionRule.grant(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS
    );

    @Rule
    public ActivityScenarioRule<MainActivity> activityRule =
            new ActivityScenarioRule<>(MainActivity.class);

    @BeforeClass
    public static void setUpEnvironment() {
        E2ETestHelper.setUp();
    }

    @After
    public void clearTestAudio() {
        // Always clear after each test so we don't leak into subsequent tests.
        Recorder.sTestAudioBytes = null;
        RecordBuffer.sLastSetBuffer = null;
    }

    @Before
    public void dismissDialogs() {
        // ActivityScenarioRule auto-launches MainActivity before @Before runs,
        // so the dialogs are already on screen by the time this fires.
        E2ETestHelper.dismissStartupDialogs();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Generates {@code durationSeconds} of a 440 Hz sine wave at 16 kHz mono s16le.
     * This is recognisable non-silence and well above the 6 400-byte minimum that
     * Recorder uses to distinguish a valid recording from an error.
     */
    private static byte[] generateSineWavePcm(float durationSeconds) {
        int sampleRate = 16000;
        int numSamples = (int) (sampleRate * durationSeconds);
        byte[] pcm = new byte[numSamples * 2]; // 2 bytes per s16 sample
        double freq = 440.0;
        for (int i = 0; i < numSamples; i++) {
            short sample = (short) (Short.MAX_VALUE * Math.sin(2 * Math.PI * freq * i / sampleRate));
            pcm[i * 2]     = (byte) (sample & 0xFF);
            pcm[i * 2 + 1] = (byte) ((sample >> 8) & 0xFF);
        }
        return pcm;
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    /**
     * Core POC test.
     *
     * Sets {@code sTestAudioBytes} to 1 second of 440 Hz sine-wave PCM,
     * presses the record button (which triggers Recorder.start()), waits
     * for the background worker thread to complete the bypass, then asserts
     * that RecordBuffer holds exactly the bytes we injected.
     */
    @Test
    public void recordButton_withInjectedPcm_populatesRecordBuffer() throws Exception {
        byte[] testPcm = generateSineWavePcm(1.0f); // 32 000 bytes, ~1 s

        // Arm the injection hook BEFORE pressing the button.
        Recorder.sTestAudioBytes = testPcm;

        // Press the record button.  The touch-listener fires ACTION_DOWN which
        // calls Recorder.start().  The worker wakes, hits the bypass, sets
        // RecordBuffer, and calls fileSavedLock.notify() almost instantly.
        // ACTION_UP fires shortly after; if mInProgress is already false the
        // stop() path is skipped; otherwise stop() unblocks via the notify.
        onView(withId(R.id.btnRecord)).perform(click());

        // Give the background worker thread time to complete (should be <5 ms,
        // but 300 ms is a comfortable margin for a slow emulator).
        SystemClock.sleep(300);

        // --- Assert ---
        // Use sLastSetBuffer directly: getOutputBuffer() is a non-destructive peek of
        // sLastSetBuffer, but sLastSetBuffer is never cleared by getSamples() — both
        // return the same value here.  Accessing the field directly is the clearest signal
        // that we're asserting on the raw injected bytes, not a queue element.
        byte[] captured = RecordBuffer.sLastSetBuffer;
        assertNotNull("RecordBuffer should not be null after injected recording", captured);
        assertArrayEquals("RecordBuffer must contain the exact injected PCM bytes", testPcm, captured);
    }

    /**
     * Boundary test: bytes below the 6 400-byte threshold trigger MSG_RECORDING_ERROR,
     * but the buffer is still populated correctly.
     */
    @Test
    public void recordButton_withShortPcm_stillPopulatesRecordBuffer() throws Exception {
        byte[] shortPcm = new byte[1000]; // below 6 400 threshold → MSG_RECORDING_ERROR

        Recorder.sTestAudioBytes = shortPcm;
        onView(withId(R.id.btnRecord)).perform(click());
        SystemClock.sleep(300);

        byte[] captured = RecordBuffer.getOutputBuffer();
        assertNotNull("RecordBuffer should not be null even for short audio", captured);
        assertArrayEquals("RecordBuffer must match the injected short PCM", shortPcm, captured);
    }
}
