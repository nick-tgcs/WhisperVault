package com.whisperonnx;

import com.whisperonnx.asr.RecordBuffer;

import org.junit.After;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * Unit tests for RecordBuffer.
 *
 * RecordBuffer holds the raw 16-bit PCM bytes from the microphone and exposes
 * them as normalised float samples via getSamples(). These tests verify both
 * the byte storage round-trip and the PCM→float conversion.
 *
 * Note: RecordBuffer uses static state, so each test resets the buffer in @After.
 */
public class RecordBufferTest {

    @After
    public void resetBuffer() {
        RecordBuffer.setOutputBuffer(null);
    }

    // ── storage ──────────────────────────────────────────────────────────────

    @Test
    public void setAndGet_roundTrip() {
        byte[] data = {0x01, 0x02, 0x03, 0x04};
        RecordBuffer.setOutputBuffer(data);
        assertArrayEquals(data, RecordBuffer.getOutputBuffer());
    }

    @Test
    public void setNull_clearsBuffer() {
        RecordBuffer.setOutputBuffer(new byte[]{1, 2});
        RecordBuffer.setOutputBuffer(null);
        assertEquals(null, RecordBuffer.getOutputBuffer());
    }

    // ── PCM → float conversion ────────────────────────────────────────────────

    /**
     * A buffer of all zeros (silence) should produce float samples all equal to 0.0
     * because when maxAbsValue == 0 the normalisation step is skipped.
     */
    @Test
    public void silentBuffer_producesZeroSamples() {
        byte[] silence = new byte[4]; // two 16-bit samples, both 0
        RecordBuffer.setOutputBuffer(silence);

        float[] samples = RecordBuffer.getSamples();

        assertNotNull(samples);
        assertEquals(2, samples.length);
        assertEquals(0.0f, samples[0], 0.0001f);
        assertEquals(0.0f, samples[1], 0.0001f);
    }

    /**
     * A single positive sample (32767 / 0x7FFF) should normalise to 1.0.
     */
    @Test
    public void maxPositiveSample_normalisesToOne() {
        short maxVal = Short.MAX_VALUE; // 32767
        byte[] buf = shortArrayToBytes(new short[]{maxVal});
        RecordBuffer.setOutputBuffer(buf);

        float[] samples = RecordBuffer.getSamples();

        assertEquals(1, samples.length);
        assertEquals(1.0f, samples[0], 0.001f);
    }

    /**
     * A single negative sample (−32768 / 0x8000) should normalise to exactly −1.0
     * once normalised by maxAbsValue = 1.0 (since 32768/32768 = 1.0 in float).
     */
    @Test
    public void maxNegativeSample_normalisesToNegativeOne() {
        short minVal = Short.MIN_VALUE; // −32768
        byte[] buf = shortArrayToBytes(new short[]{minVal});
        RecordBuffer.setOutputBuffer(buf);

        float[] samples = RecordBuffer.getSamples();

        assertEquals(1, samples.length);
        // −32768 / 32768 = −1.0, maxAbsValue = 1.0, so normalised = −1.0
        assertEquals(-1.0f, samples[0], 0.001f);
    }

    /**
     * Two samples of opposite amplitude should normalise such that the larger
     * absolute value becomes ±1.0 and the other is proportionally smaller.
     */
    @Test
    public void twoSamples_largerOneNormalisesToOne() {
        short big   = 16384;  // half of max
        short small = 8192;   // quarter of max
        byte[] buf = shortArrayToBytes(new short[]{big, small});
        RecordBuffer.setOutputBuffer(buf);

        float[] samples = RecordBuffer.getSamples();

        assertEquals(2, samples.length);
        // After normalisation: big/big = 1.0, small/big = 0.5
        assertEquals(1.0f, samples[0], 0.001f);
        assertEquals(0.5f, samples[1], 0.001f);
    }

    // ── consume-once and empty-slot behaviour ────────────────────────────────

    /**
     * getSamples() must atomically clear the buffer slot as part of retrieval.
     * After the call, getOutputBuffer() must return null, confirming the audio
     * block has been consumed and cannot be processed a second time — for example
     * if the inference thread is signalled again before the next recording arrives.
     */
    @Test
    public void getSamples_consumesBuffer() {
        RecordBuffer.setOutputBuffer(shortArrayToBytes(new short[]{1000}));
        RecordBuffer.getSamples();
        assertNull(
            "getSamples() must clear the slot so the same audio block cannot be processed twice",
            RecordBuffer.getOutputBuffer()
        );
    }

    /**
     * When the slot is empty, getSamples() must return an empty array rather than
     * throwing NullPointerException.  Returning an empty array lets callers use a
     * simple length check instead of a separate null-check call to getOutputBuffer(),
     * which would re-introduce the time-of-check / time-of-use race that the
     * atomic retrieve-and-erase in getSamples() is designed to eliminate.
     */
    @Test
    public void getSamples_whenEmpty_returnsEmptyArray() {
        // Slot is null at this point (set by @After on the previous test, or
        // initially null for the first test in the class).
        float[] samples = RecordBuffer.getSamples();
        assertNotNull(samples);
        assertEquals(0, samples.length);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private byte[] shortArrayToBytes(short[] shorts) {
        ByteBuffer bb = ByteBuffer.allocate(shorts.length * 2);
        bb.order(ByteOrder.nativeOrder());
        for (short s : shorts) bb.putShort(s);
        return bb.array();
    }
}
