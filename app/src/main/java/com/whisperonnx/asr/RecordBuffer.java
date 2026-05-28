package com.whisperonnx.asr;

import androidx.annotation.VisibleForTesting;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Intermediate buffer that carries recorded PCM audio from the capture thread
 * (Recorder) to the inference thread (Whisper).
 *
 * <p>All audio — whether from manual, auto, or continuous recording — is enqueued
 * via a thread-safe FIFO queue.  The single-slot {@link AtomicReference} that
 * previously backed this class has been replaced by the queue, which provides
 * the same consume-once semantics for single-utterance recordings while also
 * supporting multiple in-flight utterances in continuous mode.
 *
 * <ul>
 *   <li>{@link #setOutputBuffer(byte[])} — enqueues a single completed recording
 *       (manual/auto mode).  For backward compatibility it also stores the buffer
 *       in {@link #sLastSetBuffer} for tests.</li>
 *   <li>{@link #enqueueUtterance(byte[])} — enqueues an utterance for continuous
 *       mode.</li>
 *   <li>{@link #dequeueSamples()} — dequeues the next utterance and converts it
 *       to peak-normalised floating-point samples.</li>
 *   <li>{@link #hasUtterances()} — returns true if the queue is non-empty.</li>
 * </ul>
 */
public class RecordBuffer {

    /**
     * FIFO queue of PCM byte arrays.  Each entry is one utterance handed off by
     * the Recorder.  In manual/auto mode the queue contains at most one entry;
     * in continuous mode it may contain several.
     */
    private static final ConcurrentLinkedQueue<byte[]> utteranceQueue = new ConcurrentLinkedQueue<>();

    /**
     * Persists the last value passed to {@link #setOutputBuffer}.  Unlike the
     * queue, this reference is never erased by {@link #dequeueSamples()}.
     * Intended for instrumented tests that need to verify the bytes stored even
     * after the inference pipeline has already consumed them.
     */
    @VisibleForTesting
    public static volatile byte[] sLastSetBuffer = null;

    // ── Enqueue API ──────────────────────────────────────────────────────────

    /**
     * Enqueues a completed block of raw PCM audio (manual/auto mode).
     * Also stores the buffer in {@link #sLastSetBuffer} for test verification.
     * Passing {@code null} clears the queue (used for test teardown).
     *
     * @param buffer s16le (signed 16-bit, native byte order) PCM bytes at 16 kHz
     *               mono, or {@code null} to clear the queue.
     */
    public static void setOutputBuffer(byte[] buffer) {
        if (buffer == null) {
            utteranceQueue.clear();
            sLastSetBuffer = null;
            return;
        }
        sLastSetBuffer = buffer;
        utteranceQueue.offer(buffer);
    }

    /**
     * Enqueues a completed utterance for continuous mode.
     *
     * @param utterance s16le PCM bytes at 16 kHz mono representing one utterance.
     */
    public static void enqueueUtterance(byte[] utterance) {
        sLastSetBuffer = utterance;
        utteranceQueue.offer(utterance);
    }

    // ── Dequeue API ──────────────────────────────────────────────────────────

    /**
     * Returns {@code true} if there are utterances waiting in the queue.
     */
    public static boolean hasUtterances() {
        return !utteranceQueue.isEmpty();
    }

    /**
     * Dequeues the next utterance from the queue and converts it to
     * peak-normalised floating-point samples in the range [−1, 1].
     *
     * <p>This is a consume-once operation: once an utterance is dequeued it
     * cannot be retrieved again from the queue.
     *
     * @return peak-normalised {@code float[]} ready for the ONNX inference
     *         pipeline, or an empty array if no utterance was waiting.
     */
    public static float[] dequeueSamples() {
        byte[] buf = utteranceQueue.poll();
        if (buf == null) {
            return new float[0];
        }
        return convertToSamples(buf);
    }

    // ── Legacy compatibility ────────────────────────────────────────────────

    /**
     * Returns the raw PCM bytes of the most recently enqueued buffer, or
     * {@code null} if none.  This is a non-destructive peek for test use only.
     * Production code should use {@link #dequeueSamples()}.
     */
    public static byte[] getOutputBuffer() {
        return sLastSetBuffer;
    }

    /**
     * Atomically retrieves and converts the next utterance from the queue.
     * Equivalent to {@link #dequeueSamples()}; kept for backward compatibility.
     *
     * @return peak-normalised {@code float[]} ready for the ONNX inference
     *         pipeline, or an empty array if no utterance was waiting.
     */
    public static float[] getSamples() {
        return dequeueSamples();
    }

    // ── Shared conversion ────────────────────────────────────────────────────

    /**
     * Converts raw s16le PCM bytes to peak-normalised floating-point samples
     * in the range [−1, 1].
     */
    private static float[] convertToSamples(byte[] buf) {
        int numSamples = buf.length / 2;
        ByteBuffer byteBuffer = ByteBuffer.wrap(buf);
        byteBuffer.order(ByteOrder.nativeOrder());

        float[] samples = new float[numSamples];
        float maxAbsValue = 0.0f;

        for (int i = 0; i < numSamples; i++) {
            samples[i] = (float) (byteBuffer.getShort() / 32768.0);
            if (Math.abs(samples[i]) > maxAbsValue) {
                maxAbsValue = Math.abs(samples[i]);
            }
        }

        // Normalize the samples
        if (maxAbsValue > 0.0f) {
            for (int i = 0; i < numSamples; i++) {
                samples[i] /= maxAbsValue;
            }
        }

        return samples;
    }
}
