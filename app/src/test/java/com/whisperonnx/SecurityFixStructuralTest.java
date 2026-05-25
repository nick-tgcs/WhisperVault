package com.whisperonnx;

import com.whisperonnx.asr.Recorder;
import com.whisperonnx.asr.Whisper;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Structural tests that verify security-related field/method properties using
 * reflection.  These tests are intentionally written BEFORE the corresponding
 * fixes are applied (TDD red → green), and remain as regression guards.
 *
 * None of these tests depend on the Android SDK — they run on the JVM.
 */
public class SecurityFixStructuralTest {

    // ── Thread safety ─────────────────────────────────────────────────────────

    /**
     * WhisperRecognitionService.recognitionCancelled is written from the binder
     * thread (onCancel) and read from the transcription background thread.
     * It MUST be volatile to guarantee visibility.
     */
    @Test
    public void recognitionCancelled_mustBeVolatile() throws Exception {
        Field field = WhisperRecognitionService.class.getDeclaredField("recognitionCancelled");
        assertTrue(
            "recognitionCancelled must be declared volatile for safe cross-thread visibility",
            Modifier.isVolatile(field.getModifiers())
        );
    }

    // ── Instance vs. static state ─────────────────────────────────────────────

    /**
     * WhisperInputMethodService.translate controls translate-mode per keyboard
     * session.  Static state would be shared across all instances/sessions,
     * leaking one user's mode into the next.
     */
    @Test
    public void translate_mustBeInstanceField() throws Exception {
        Field field = WhisperInputMethodService.class.getDeclaredField("translate");
        assertFalse(
            "translate must NOT be static — each IME session is independent",
            Modifier.isStatic(field.getModifiers())
        );
    }

    // ── Thread lifecycle ──────────────────────────────────────────────────────

    /**
     * Whisper.threadProcessRecordBuffer must be stored as an instance field
     * (not a local variable) so that unloadModel() can interrupt it on cleanup.
     */
    @Test
    public void whisperProcessingThread_mustBeInstanceField() throws Exception {
        Field field = Whisper.class.getDeclaredField("threadProcessRecordBuffer");
        assertFalse(
            "threadProcessRecordBuffer must be an instance field so it can be interrupted",
            Modifier.isStatic(field.getModifiers())
        );
        assertEquals(Thread.class, field.getType());
    }

    /**
     * Recorder.destroy() must exist and be public so callers (IME service,
     * recognition service) can interrupt the worker thread on shutdown.
     */
    @Test
    public void recorder_mustHavePublicDestroyMethod() throws Exception {
        Method method = Recorder.class.getDeclaredMethod("destroy");
        assertNotNull("Recorder must have a destroy() method", method);
        assertTrue(
            "Recorder.destroy() must be public",
            Modifier.isPublic(method.getModifiers())
        );
    }

    // ── Path traversal guard ──────────────────────────────────────────────────

    /**
     * SetupActivity must declare TAG so the Zip Slip log statements compile.
     * If TAG is missing the whole fix collapses silently.
     */
    @Test
    public void setupActivity_mustHaveTagConstant() throws Exception {
        Field field = SetupActivity.class.getDeclaredField("TAG");
        assertTrue("TAG must be static", Modifier.isStatic(field.getModifiers()));
        assertTrue("TAG must be final",  Modifier.isFinal(field.getModifiers()));
        field.setAccessible(true);
        assertEquals("SetupActivity", field.get(null));
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private static void assertEquals(Object expected, Object actual) {
        org.junit.Assert.assertEquals(expected, actual);
    }
}
