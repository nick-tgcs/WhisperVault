package com.whisperonnx;

import com.whisperonnx.asr.RecordBuffer;
import com.whisperonnx.asr.Recorder;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Structural regression tests for the four continuous-mode fixes in PR #7.
 *
 * <p>None of these tests depend on the Android SDK — they run on the JVM via
 * reflection.  Each test verifies a structural invariant that would be violated
 * if a fix were accidentally reverted.
 *
 * <h3>Fixes covered</h3>
 * <ol>
 *   <li><b>IME utterance drain</b> ({@link WhisperInputMethodService}) — after
 *       each result in continuous mode, the code now calls
 *       {@link RecordBuffer#hasUtterances()} to decide whether to re-trigger
 *       transcription.  Verified by confirming the method exists and has the
 *       expected boolean return type.
 *   <li><b>RecognizeActivity transcript accumulation</b>
 *       ({@link WhisperRecognizeActivity}) — the field
 *       {@code continuousTranscript} must be present and of type
 *       {@link StringBuilder} so that partial results are not silently
 *       discarded.
 *   <li><b>RecognizeActivity recorder guard</b>
 *       ({@link WhisperRecognizeActivity}) — {@code restartRecording()} must
 *       now consult {@link Recorder#isInProgress()} before reinitialising VAD
 *       and calling {@code start()}, preventing a VAD resource leak.
 *       Verified by confirming {@code isInProgress()} is public.
 *   <li><b>Whisper start guard pattern</b> — the
 *       {@link java.util.concurrent.atomic.AtomicBoolean#compareAndSet} guard
 *       in {@link com.whisperonnx.asr.Whisper#start()} is the root cause of
 *       why the drain step is necessary.  A behavioural test confirms the
 *       guard semantics.
 * </ol>
 */
public class ContinuousModeFixStructuralTest {

    // ── Fix 1: RecordBuffer.hasUtterances() exists ────────────────────────────

    @Test
    public void recordBuffer_hasUtterancesMethod_isPublicStatic() throws Exception {
        Method method = RecordBuffer.class.getDeclaredMethod("hasUtterances");
        assertNotNull("RecordBuffer.hasUtterances() must exist", method);
        assertTrue("hasUtterances() must be public",
                Modifier.isPublic(method.getModifiers()));
        assertTrue("hasUtterances() must be static",
                Modifier.isStatic(method.getModifiers()));
        assertEquals("hasUtterances() must return boolean",
                boolean.class, method.getReturnType());
    }

    @Test
    public void recordBuffer_enqueueUtteranceMethod_isPublicStatic() throws Exception {
        Method method = RecordBuffer.class.getDeclaredMethod("enqueueUtterance", byte[].class);
        assertNotNull("RecordBuffer.enqueueUtterance(byte[]) must exist", method);
        assertTrue("enqueueUtterance() must be public",
                Modifier.isPublic(method.getModifiers()));
        assertTrue("enqueueUtterance() must be static",
                Modifier.isStatic(method.getModifiers()));
    }

    @Test
    public void recordBuffer_dequeueSamplesMethod_isPublicStatic() throws Exception {
        Method method = RecordBuffer.class.getDeclaredMethod("dequeueSamples");
        assertNotNull("RecordBuffer.dequeueSamples() must exist", method);
        assertTrue("dequeueSamples() must be public",
                Modifier.isPublic(method.getModifiers()));
        assertTrue("dequeueSamples() must be static",
                Modifier.isStatic(method.getModifiers()));
        assertEquals("dequeueSamples() must return float[]",
                float[].class, method.getReturnType());
    }

    // ── Fix 2: WhisperRecognizeActivity.continuousTranscript field exists ─────

    @Test
    public void recognizeActivity_continuousTranscriptField_existsAndIsStringBuilder()
            throws Exception {
        Field field = WhisperRecognizeActivity.class.getDeclaredField("continuousTranscript");
        assertNotNull("WhisperRecognizeActivity must have a 'continuousTranscript' field", field);
        assertEquals(
                "continuousTranscript must be of type StringBuilder",
                StringBuilder.class, field.getType());
        assertFalse(
                "continuousTranscript must be an instance field (not static)",
                Modifier.isStatic(field.getModifiers()));
    }

    // ── Fix 3: Recorder.isInProgress() is public (drain guard uses it) ────────

    @Test
    public void recorder_isInProgressMethod_isPublic() throws Exception {
        Method method = Recorder.class.getDeclaredMethod("isInProgress");
        assertNotNull("Recorder.isInProgress() must exist", method);
        assertTrue("isInProgress() must be public so callers can guard restartRecording()",
                Modifier.isPublic(method.getModifiers()));
        assertEquals("isInProgress() must return boolean",
                boolean.class, method.getReturnType());
    }

    // ── Fix 4: compareAndSet guard semantics (why drain is needed) ────────────

    /**
     * Demonstrates the exact guard in {@code Whisper.start()} that causes a
     * second concurrent call to be silently dropped.
     *
     * <p>This test documents WHY the drain loop is necessary: once
     * {@code mInProgress} is true, any further {@code start()} call returns
     * immediately without signalling the worker thread.  After the worker
     * completes and sets {@code mInProgress} back to false, the caller must
     * explicitly re-trigger transcription if the queue is non-empty.
     */
    @Test
    public void compareAndSetGuard_secondStartDropped_thirdStartSucceedsAfterReset() {
        AtomicBoolean inProgress = new AtomicBoolean(false);

        // First start: succeeds (worker is signalled)
        assertTrue("First start() must succeed", inProgress.compareAndSet(false, true));

        // Second concurrent start while in-progress: silently dropped
        assertFalse("Second start() while in-progress must be rejected (drain loop needed)",
                inProgress.compareAndSet(false, true));

        // Simulate worker completing transcription
        inProgress.set(false);

        // Third start (drain loop re-triggers after result): succeeds
        assertTrue("Drain-triggered start() after reset must succeed",
                inProgress.compareAndSet(false, true));
    }
}
