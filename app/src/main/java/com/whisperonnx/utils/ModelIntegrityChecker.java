package com.whisperonnx.utils;

import android.content.Context;

import androidx.preference.PreferenceManager;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
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

    /** SharedPreferences key for the selected model name. */
    public static final String PREF_KEY = "model_name";

    /** Default model when no preference has been set. */
    public static final String DEFAULT_MODEL = "whisper_small_int8";

    /**
     * Known SHA-256 hashes indexed by model name then file name.
     * Only whisper_small_int8 has verified hashes; other variants return an empty
     * mismatch list (unverified) until their zips are verified and uploaded.
     */
    private static final Map<String, Map<String, String>> HASHES_BY_MODEL = new HashMap<>();

    /**
     * SHA-256 hashes for the 6 files in whisper_small_int8
     * (DocWolle/whisperOnnx / huggingface0ddg0/whisperOnnx, May 2026).
     * Package-visible to allow direct access in unit tests.
     */
    static final Map<String, String> KNOWN_HASHES;

    static {
        Map<String, String> small = new LinkedHashMap<>();
        small.put("Whisper_cache_initializer_batch.onnx",
                "6da66ede8430347e314a7341fa05481778ac945d5b028106a8340361fdc99986");
        small.put("Whisper_cache_initializer.onnx",
                "6e303b22e38ea5a2f6c1e4c29ddbf11b047f77d8e7a99841182e709ee6b2d9d1");
        small.put("Whisper_decoder.onnx",
                "704de24c96a812779f3d0d7ed2a47a37b070f44abb0d92ca5e19d31c727718df");
        small.put("Whisper_detokenizer.onnx",
                "713447d18dc0f4cae0759f616aba98a909100185941f80844bf954ef12888e12");
        small.put("Whisper_encoder.onnx",
                "0171be36c908738326f70c8a7f58049ba4786a07d7ea79c0e0f9e26550cdfb50");
        small.put("Whisper_initializer.onnx",
                "dbf3281f2399b46527afbf0401a91174596222b79fa4e6646b0f2d49fb397d85");
        HASHES_BY_MODEL.put("whisper_small_int8", Collections.unmodifiableMap(small));
        // whisper_tiny_int8, whisper_base_int8, whisper_medium_int8:
        // hashes will be added once the model zips are verified and uploaded to HuggingFace.
        KNOWN_HASHES = HASHES_BY_MODEL.get("whisper_small_int8");
    }

    private ModelIntegrityChecker() {}

    /**
     * Returns the model name stored in SharedPreferences,
     * falling back to {@link #DEFAULT_MODEL}.
     */
    public static String getSelectedModel(Context ctx) {
        return PreferenceManager.getDefaultSharedPreferences(ctx)
                .getString(PREF_KEY, DEFAULT_MODEL);
    }

    /**
     * Checks the model files in {@code modelDir} against the known hashes for
     * {@code modelName}. If the variant has no registered hashes (e.g. a
     * user-sideloaded model not yet in {@link #HASHES_BY_MODEL}), returns an
     * empty list — unverified but not an active failure.
     */
    public static List<String> getMismatches(File modelDir, String modelName) {
        Map<String, String> hashes = HASHES_BY_MODEL.get(modelName);
        if (hashes == null || hashes.isEmpty()) {
            return Collections.emptyList();
        }
        return getMismatches(modelDir, hashes);
    }

    /**
     * Convenience overload that uses the default model (whisper_small_int8).
     * Retained for backward compatibility with existing tests.
     */
    public static List<String> getMismatches(File modelDir) {
        return getMismatches(modelDir, DEFAULT_MODEL);
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
