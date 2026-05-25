package com.whisperonnx.utils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Verifies SHA-256 hashes of the Whisper ONNX model files against the known-good
 * fingerprint for whisper-small-int8 (DocWolle/whisperOnnx on HuggingFace, May 2026).
 *
 * <p>Only files listed in {@link #KNOWN_HASHES} are checked. Files on disk that are
 * <em>not</em> in the map — e.g., from a different model variant — are silently skipped,
 * preserving backwards compatibility. When a mismatch is detected the caller is expected
 * to present a warning dialog and let the user decide whether to continue or abort.
 *
 * <p>This class has no Android dependencies and is fully unit-testable on the JVM.
 */
public final class ModelIntegrityChecker {

    /**
     * SHA-256 hashes for the 6 files in whisper_small_int8
     * (DocWolle/whisperOnnx, retrieved May 2026).
     * Update this map whenever the shipped model is intentionally replaced.
     */
    static final Map<String, String> KNOWN_HASHES = new LinkedHashMap<>();

    static {
        KNOWN_HASHES.put("Whisper_cache_initializer_batch.onnx",
                "6da66ede8430347e314a7341fa05481778ac945d5b028106a8340361fdc99986");
        KNOWN_HASHES.put("Whisper_cache_initializer.onnx",
                "6e303b22e38ea5a2f6c1e4c29ddbf11b047f77d8e7a99841182e709ee6b2d9d1");
        KNOWN_HASHES.put("Whisper_decoder.onnx",
                "704de24c96a812779f3d0d7ed2a47a37b070f44abb0d92ca5e19d31c727718df");
        KNOWN_HASHES.put("Whisper_detokenizer.onnx",
                "713447d18dc0f4cae0759f616aba98a909100185941f80844bf954ef12888e12");
        KNOWN_HASHES.put("Whisper_encoder.onnx",
                "0171be36c908738326f70c8a7f58049ba4786a07d7ea79c0e0f9e26550cdfb50");
        KNOWN_HASHES.put("Whisper_initializer.onnx",
                "dbf3281f2399b46527afbf0401a91174596222b79fa4e6646b0f2d49fb397d85");
    }

    private ModelIntegrityChecker() {}

    /**
     * Checks each file in {@link #KNOWN_HASHES} against its SHA-256.
     * Returns the names of files that are missing, unreadable, or whose hash differs.
     * An empty list means all known files verified successfully.
     */
    public static List<String> getMismatches(File modelDir) {
        return getMismatches(modelDir, KNOWN_HASHES);
    }

    /**
     * Same as {@link #getMismatches(File)} but uses a caller-supplied hash map.
     * Exposed as {@code public} to allow injection in unit tests without touching
     * production state.
     */
    public static List<String> getMismatches(File modelDir, Map<String, String> expectedHashes) {
        List<String> mismatches = new ArrayList<>();
        for (Map.Entry<String, String> entry : expectedHashes.entrySet()) {
            File f = new File(modelDir, entry.getKey());
            if (!f.exists()) {
                mismatches.add(entry.getKey() + " (missing)");
                continue;
            }
            try {
                String actual = sha256Hex(f);
                if (!actual.equalsIgnoreCase(entry.getValue())) {
                    mismatches.add(entry.getKey());
                }
            } catch (IOException | NoSuchAlgorithmException e) {
                mismatches.add(entry.getKey() + " (unreadable)");
            }
        }
        return mismatches;
    }

    /** Computes the SHA-256 hex digest of {@code file}. */
    public static String sha256Hex(File file) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream is = Files.newInputStream(file.toPath())) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = is.read(buf)) != -1) {
                digest.update(buf, 0, n);
            }
        }
        byte[] hash = digest.digest();
        StringBuilder sb = new StringBuilder(64);
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
