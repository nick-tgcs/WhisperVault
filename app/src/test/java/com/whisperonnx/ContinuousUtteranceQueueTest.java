package com.whisperonnx;

import com.whisperonnx.asr.RecordBuffer;

import org.junit.After;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for the {@link RecordBuffer} queue semantics that underpin the
 * continuous-mode utterance-drain fix (Issue 1).
 *
 * <p>Problem being fixed: in continuous mode the Recorder enqueues utterances
 * via {@link RecordBuffer#enqueueUtterance(byte[])} while Whisper is still
 * processing the previous one. {@link com.whisperonnx.asr.Whisper#start()}
 * silently no-ops when in-progress, so without a drain step after each result,
 * any utterances that arrived while transcription was running are permanently
 * lost.
 *
 * <p>Fix: after every {@code onResultReceived} in CONTINUOUS mode both
 * {@link WhisperInputMethodService} and {@link WhisperRecognizeActivity} now
 * call {@link RecordBuffer#hasUtterances()} and re-trigger transcription if
 * the queue is non-empty.
 *
 * <p>These tests verify the RecordBuffer queue contract that the drain logic
 * depends on.  They are pure JVM tests — no Android SDK required.
 */
public class ContinuousUtteranceQueueTest {

    @After
    public void clearQueue() {
        RecordBuffer.setOutputBuffer(null);
    }

    // ── hasUtterances() ───────────────────────────────────────────────────────

    @Test
    public void emptyQueue_hasUtterancesFalse() {
        assertFalse(RecordBuffer.hasUtterances());
    }

    @Test
    public void afterEnqueue_hasUtterancesTrue() {
        RecordBuffer.enqueueUtterance(makeSilentPcm(4));
        assertTrue(RecordBuffer.hasUtterances());
    }

    @Test
    public void afterDrain_hasUtterancesFalse() {
        RecordBuffer.enqueueUtterance(makeSilentPcm(4));
        RecordBuffer.dequeueSamples();
        assertFalse("Queue must be empty after draining the only utterance",
                RecordBuffer.hasUtterances());
    }

    @Test
    public void reEnqueueAfterDrain_hasUtterancesTrue() {
        RecordBuffer.enqueueUtterance(makeSilentPcm(4));
        RecordBuffer.dequeueSamples();
        RecordBuffer.enqueueUtterance(makeSilentPcm(4));
        assertTrue("Re-enqueuing after drain must make the queue non-empty again",
                RecordBuffer.hasUtterances());
    }

    // ── FIFO ordering ─────────────────────────────────────────────────────────

    /**
     * Three utterances with distinct peak sample values are enqueued while
     * "transcription" is busy. After each dequeue the queue still has entries
     * (mirrors the drain loop firing startTranscription()), until the last one
     * is consumed.
     */
    @Test
    public void multipleUtterances_drainedInFifoOrder() {
        // Utterance 1: max positive sample (32767) → normalises to ≈ 1.0
        RecordBuffer.enqueueUtterance(encodeSample(Short.MAX_VALUE));
        // Utterance 2: half-scale (16384) → normalises to ≈ 0.5
        RecordBuffer.enqueueUtterance(encodeSample((short) 16384));
        // Utterance 3: silence (0) → normalises to 0.0
        RecordBuffer.enqueueUtterance(makeSilentPcm(2));

        assertTrue(RecordBuffer.hasUtterances());
        float[] s1 = RecordBuffer.dequeueSamples();
        assertTrue(RecordBuffer.hasUtterances());
        float[] s2 = RecordBuffer.dequeueSamples();
        assertTrue(RecordBuffer.hasUtterances());
        float[] s3 = RecordBuffer.dequeueSamples();
        assertFalse("Queue must be empty after draining all three utterances",
                RecordBuffer.hasUtterances());

        // FIFO: utterance 1 had the highest peak
        assertNotNull(s1);
        assertNotNull(s2);
        assertNotNull(s3);

        // Utterance 1 peak > utterance 2 peak (both normalised to their own max)
        // Both are positive; utterance 3 is silence → all zeros after normalisation
        assertTrue("First utterance must have a positive sample", s1[0] > 0.0f);
        // Utterance 3 is silence (all zeros); after normalisation (max==0 guard) it stays zero
        assertEquals(0.0f, s3[0], 0.0001f);
    }

    // ── Empty dequeue ─────────────────────────────────────────────────────────

    @Test
    public void dequeueOnEmptyQueue_returnsEmptyArray() {
        float[] samples = RecordBuffer.dequeueSamples();
        assertNotNull(samples);
        assertEquals("Dequeue on empty queue must return empty float[]", 0, samples.length);
    }

    // ── setOutputBuffer path (manual/auto) also feeds the queue ───────────────

    @Test
    public void setOutputBuffer_feedsQueue() {
        assertFalse(RecordBuffer.hasUtterances());
        RecordBuffer.setOutputBuffer(makeSilentPcm(4));
        assertTrue("setOutputBuffer must enqueue the buffer so hasUtterances() is true",
                RecordBuffer.hasUtterances());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Two bytes of silence (one s16le sample == 0). */
    private static byte[] makeSilentPcm(int byteCount) {
        return new byte[byteCount];
    }

    /** Encodes a single s16le sample in native byte order. */
    private static byte[] encodeSample(short value) {
        ByteBuffer buf = ByteBuffer.allocate(2).order(ByteOrder.nativeOrder());
        buf.putShort(value);
        return buf.array();
    }
}
