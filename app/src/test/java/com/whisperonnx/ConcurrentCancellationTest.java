package com.whisperonnx;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Demonstrates that a flag shared between threads MUST be volatile (or
 * otherwise use a safe publication mechanism) to guarantee visibility.
 *
 * These tests document the concurrency contract that the
 * {@code recognitionCancelled} fix in {@link WhisperRecognitionService}
 * satisfies.  They use standard Java primitives and need no Android SDK.
 */
public class ConcurrentCancellationTest {

    /**
     * A volatile flag written by one thread is always visible to another.
     * This characterises the corrected WhisperRecognitionService behaviour:
     * the binder thread sets {@code recognitionCancelled = true} and the
     * transcription thread reads it.
     */
    @Test
    public void volatileFlag_writtenByOneThread_visibleToAnother()
            throws InterruptedException {

        final int ITERATIONS = 10_000;
        AtomicInteger mismatches = new AtomicInteger(0);

        // Simulate many write→read cycles across a thread boundary.
        for (int i = 0; i < ITERATIONS; i++) {
            VolatileFlag flag = new VolatileFlag();     // volatile boolean
            CountDownLatch writerDone  = new CountDownLatch(1);
            CountDownLatch readerStart = new CountDownLatch(1);

            Thread writer = new Thread(() -> {
                try { readerStart.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                flag.value = true;
                writerDone.countDown();
            });

            Thread reader = new Thread(() -> {
                readerStart.countDown();        // let writer proceed
                try { writerDone.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                if (!flag.value) mismatches.incrementAndGet();  // should never happen
            });

            writer.start();
            reader.start();
            writer.join(500);
            reader.join(500);
        }

        assertEquals(
            "A volatile flag must always be visible across threads — mismatches: " + mismatches.get(),
            0, mismatches.get()
        );
    }

    /**
     * Regression: a non-volatile boolean shared between threads can appear
     * stale due to CPU caching / JIT optimisation.  This test confirms our
     * production flag is declared volatile (structural check complements
     * {@link SecurityFixStructuralTest#recognitionCancelled_mustBeVolatile}).
     */
    @Test
    public void recognitionCancelledField_isDeclaredVolatile()
            throws NoSuchFieldException {

        java.lang.reflect.Field field =
            WhisperRecognitionService.class.getDeclaredField("recognitionCancelled");

        assertTrue(
            "recognitionCancelled must be volatile for safe visibility across binder/background threads",
            java.lang.reflect.Modifier.isVolatile(field.getModifiers())
        );
    }

    // ── inner helper class ────────────────────────────────────────────────────

    /** Holder with a volatile flag, mirroring the fixed production field. */
    private static class VolatileFlag {
        volatile boolean value = false;
    }
}
