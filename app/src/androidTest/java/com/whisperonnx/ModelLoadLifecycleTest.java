package com.whisperonnx;

import android.Manifest;
import android.content.Intent;
import android.os.Looper;
import android.speech.RecognizerIntent;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.GrantPermissionRule;

import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.fail;

/**
 * Tests that {@link WhisperRecognizeActivity} and {@link MainActivity} do not
 * crash when the Activity is destroyed before the model-load
 * {@code runOnUiThread} lambda executes.
 *
 * <p><b>Bug</b>: {@code initModel()} starts a background thread that posts
 * {@code runOnUiThread(() -> mWhisper.loadModel())}. If the Activity is
 * destroyed before that lambda runs, {@code deinitModel()} sets
 * {@code mWhisper = null}, and the pending lambda throws
 * {@link NullPointerException}.
 *
 * <p>This test reproduces the race by launching the Activity, waiting just
 * long enough for the background integrity-check thread to start, then
 * immediately closing the Activity. The pending {@code runOnUiThread} lambda
 * fires after {@code onDestroy()} has nulled {@code mWhisper}.
 *
 * <p>Run in isolation:
 * <pre>
 *   ./gradlew connectedDebugAndroidTest \
 *       -Pandroid.testInstrumentationRunnerArguments.class=com.whisperonnx.ModelLoadLifecycleTest
 * </pre>
 */
@RunWith(AndroidJUnit4.class)
public class ModelLoadLifecycleTest {

    @ClassRule
    public static final GrantPermissionRule grantPermissions = GrantPermissionRule.grant(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS
    );

    @BeforeClass
    public static void setUpEnvironment() {
        E2ETestHelper.setUp();
    }

    /**
     * Launches {@link WhisperRecognizeActivity}, waits for the integrity-check
     * thread to start, then immediately closes the Activity. The pending
     * {@code runOnUiThread(() -> mWhisper.loadModel())} fires after
     * {@code onDestroy()} has nulled {@code mWhisper}, causing NPE.
     *
     * <p>If the null-guard is missing, this test crashes the process.
     */
    @Test
    public void recognizeActivity_modelLoadAfterDestroy_doesNotCrash()
            throws Exception {
        for (int i = 0; i < 5; i++) {
            Intent intent = new Intent(
                    androidx.test.platform.app.InstrumentationRegistry
                            .getInstrumentation().getTargetContext(),
                    WhisperRecognizeActivity.class);
            intent.setAction(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);

            try (ActivityScenario<WhisperRecognizeActivity> scenario =
                         ActivityScenario.launch(intent)) {
                // Wait for onCreate → initModel() to start the integrity-check
                // thread.  200 ms is enough for the thread to start and post
                // runOnUiThread, but short enough that the Activity may be
                // destroyed before the UI thread processes the lambda.
                Thread.sleep(200);
            }
            // Activity is now destroyed.  deinitModel() has set mWhisper = null.
            // The pending runOnUiThread lambda may still be in the message queue.
        }

        // Drain all pending messages on the main looper so any queued
        // runOnUiThread lambdas from destroyed activities get executed.
        // If any of them NPE on mWhisper.loadModel(), the process crashes.
        CountDownLatch drainLatch = new CountDownLatch(1);
        new android.os.Handler(Looper.getMainLooper()).post(() -> drainLatch.countDown());
        drainLatch.await(5, TimeUnit.SECONDS);

        // If we reach this line, no NPE was thrown from the pending lambdas.
        // A crash would have killed the process and this line would never execute.
    }

    /**
     * Same test for {@link MainActivity} — rapidly create/destroy to race
     * {@code checkIntegrityThenLoad()}'s {@code runOnUiThread} lambda against
     * {@code deinitModel()}.
     */
    @Test
    public void mainActivity_modelLoadAfterDestroy_doesNotCrash()
            throws Exception {
        for (int i = 0; i < 5; i++) {
            try (ActivityScenario<MainActivity> scenario =
                         ActivityScenario.launch(MainActivity.class)) {
                E2ETestHelper.dismissStartupDialogs();
                // Wait for the integrity-check thread to start
                Thread.sleep(200);
            }
        }

        // Drain all pending messages on the main looper
        CountDownLatch drainLatch = new CountDownLatch(1);
        new android.os.Handler(Looper.getMainLooper()).post(() -> drainLatch.countDown());
        drainLatch.await(5, TimeUnit.SECONDS);
    }
}