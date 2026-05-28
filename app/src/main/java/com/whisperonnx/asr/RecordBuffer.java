package com.whisperonnx.asr;

import androidx.annotation.VisibleForTesting;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Intermediate buffer that carries a single block of recorded PCM audio from the
 * capture thread (Recorder) to the inference thread (Whisper).
 *
 * <p>Design notes:
 * <ul>
 *   <li>This is a single-slot channel: if a new recording completes before the
 *       previous one has been consumed, the previous block is silently replaced.
 *       In normal use there is exactly one producer (Recorder) and one consumer
 *       (Whisper), so no data is lost.</li>
 *   <li>The slot is backed by an {@link AtomicReference} so that
 *       {@link #getSamples()} can atomically retrieve and erase the buffer in a
 *       single operation.  This eliminates two hazards that exist with a plain
 *       field: (a) the same audio block being processed twice if the inference
 *       thread is signalled again before a new recording arrives, and (b) a
 *       time-of-check / time-of-use race where the buffer reference could be
 *       replaced between the length read and the data read inside
 *       {@code getSamples()}.</li>
 * </ul>
 */
public class RecordBuffer {

    /**
     * The raw PCM bytes of the most recently completed recording, or {@code null}
     * when no unprocessed audio is waiting.
     */
    private static final AtomicReference<byte[]> outputBuffer = new AtomicReference<>();

    /**
     * Persists the last value passed to {@link #setOutputBuffer}. Unlike the main
     * {@code outputBuffer} slot, this reference is never erased by {@link #getSamples}.
     * Intended for instrumented tests that need to verify the bytes stored even after
     * the inference pipeline has already consumed them via {@link #getSamples}.
     */
    @VisibleForTesting
    public static volatile byte[] sLastSetBuffer = null;

    /**
     * Stores a completed block of raw PCM audio so it can be retrieved by
     * {@link #getSamples()}.  Replaces any unprocessed buffer that was already
     * waiting.
     *
     * @param buffer s16le (signed 16-bit, native byte order) PCM bytes at 16 kHz
     *               mono, or {@code null} to mark the slot as empty.
     */
    public static void setOutputBuffer(byte[] buffer) {
        sLastSetBuffer = buffer;
        outputBuffer.set(buffer);
    }

    /**
     * Returns the raw PCM bytes currently waiting to be processed, or {@code null}
     * if the slot is empty.
     *
     * <p>This is a non-destructive peek: the buffer is not removed from the slot.
     * Callers that intend to process the audio should use {@link #getSamples()},
     * which atomically retrieves and erases the buffer so it cannot be processed
     * a second time.
     */
    public static byte[] getOutputBuffer() {
        return outputBuffer.get();
    }

    /**
     * Atomically retrieves and erases the stored PCM buffer, then converts it to
     * peak-normalised floating-point samples in the range [−1, 1].
     *
     * <p>The retrieve-and-erase is performed as a single {@code getAndSet(null)}
     * call on the underlying {@link AtomicReference}.  This provides two
     * guarantees:
     * <ul>
     *   <li><b>Consume-once</b>: once this method returns, the slot is empty.  A
     *       subsequent call will return an empty array rather than re-processing
     *       the same audio.</li>
     *   <li><b>No TOCTOU race</b>: the buffer reference is captured once and used
     *       throughout; it cannot be replaced by another thread between the length
     *       check and the data read.</li>
     * </ul>
     *
     * <p>Audio format: two bytes per sample, signed 16-bit, byte order follows the
     * platform native order (little-endian on all current Android devices).
     *
     * @return peak-normalised {@code float[]} ready for the ONNX inference
     *         pipeline, or an empty array if no recording was waiting.
     */
    public static float[] getSamples() {
        // Atomically take the buffer out of the slot and leave it empty, so that
        // no other caller can process the same recording.
        byte[] buf = outputBuffer.getAndSet(null);
        if (buf == null) {
            return new float[0];
        }

        int numSamples = buf.length / 2;
        ByteBuffer byteBuffer = ByteBuffer.wrap(buf);
        byteBuffer.order(ByteOrder.nativeOrder());

        // Convert audio data to PCM_FLOAT format
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
