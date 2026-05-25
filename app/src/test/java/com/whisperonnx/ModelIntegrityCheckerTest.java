package com.whisperonnx;

import com.whisperonnx.utils.ModelIntegrityChecker;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * JVM unit tests for {@link ModelIntegrityChecker}.
 *
 * Uses a package-private overload of {@code getMismatches(File, Map)} so tests can
 * inject a small synthetic hash map without requiring the real 242 MB model files.
 */
public class ModelIntegrityCheckerTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    // ---------------------------------------------------------------------------
    // sha256Hex
    // ---------------------------------------------------------------------------

    @Test
    public void sha256Hex_emptyFile_givesKnownHash() throws IOException, NoSuchAlgorithmException {
        File f = tmp.newFile("empty.bin");
        // SHA-256 of zero bytes is a well-known constant
        String expected = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        assertEquals(expected, ModelIntegrityChecker.sha256Hex(f));
    }

    @Test
    public void sha256Hex_knownContent_givesCorrectHash() throws IOException, NoSuchAlgorithmException {
        File f = writeFile("abc.bin", "abc");
        // SHA-256("abc") — verified with `echo -n "abc" | sha256sum`
        String expected = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
        assertEquals(expected, ModelIntegrityChecker.sha256Hex(f));
    }

    @Test
    public void sha256Hex_result_is64LowercaseHexChars() throws IOException, NoSuchAlgorithmException {
        File f = writeFile("data.bin", "some test data");
        String hash = ModelIntegrityChecker.sha256Hex(f);
        assertEquals(64, hash.length());
        assertTrue("hash should be lowercase hex", hash.matches("[0-9a-f]{64}"));
    }

    // ---------------------------------------------------------------------------
    // getMismatches — happy path
    // ---------------------------------------------------------------------------

    @Test
    public void getMismatches_allMatch_returnsEmpty() throws IOException, NoSuchAlgorithmException {
        File dir = tmp.newFolder("model");
        String content = "fake onnx data";
        File f = writeFile(dir, "Whisper_encoder.onnx", content);
        String hash = ModelIntegrityChecker.sha256Hex(f);

        Map<String, String> hashes = mapOf("Whisper_encoder.onnx", hash);
        List<String> mismatches = ModelIntegrityChecker.getMismatches(dir, hashes);

        assertTrue("No mismatches expected for matching file", mismatches.isEmpty());
    }

    @Test
    public void getMismatches_multipleFilesAllMatch_returnsEmpty()
            throws IOException, NoSuchAlgorithmException {
        File dir = tmp.newFolder("model2");
        String[] names = {"Whisper_encoder.onnx", "Whisper_decoder.onnx"};
        Map<String, String> hashes = new LinkedHashMap<>();
        for (String name : names) {
            File f = writeFile(dir, name, "content-" + name);
            hashes.put(name, ModelIntegrityChecker.sha256Hex(f));
        }

        assertTrue(ModelIntegrityChecker.getMismatches(dir, hashes).isEmpty());
    }

    // ---------------------------------------------------------------------------
    // getMismatches — mismatch cases
    // ---------------------------------------------------------------------------

    @Test
    public void getMismatches_wrongContent_flagsFilename() throws IOException {
        File dir = tmp.newFolder("model3");
        writeFile(dir, "Whisper_encoder.onnx", "corrupted data");

        Map<String, String> hashes = mapOf("Whisper_encoder.onnx",
                "0000000000000000000000000000000000000000000000000000000000000000");
        List<String> mismatches = ModelIntegrityChecker.getMismatches(dir, hashes);

        assertEquals(1, mismatches.size());
        assertEquals("Whisper_encoder.onnx", mismatches.get(0));
    }

    @Test
    public void getMismatches_missingFile_flagsWithMissingSuffix() throws IOException {
        File dir = tmp.newFolder("model4");
        // Do not create the file — it is absent

        Map<String, String> hashes = mapOf("Whisper_encoder.onnx",
                "0000000000000000000000000000000000000000000000000000000000000000");
        List<String> mismatches = ModelIntegrityChecker.getMismatches(dir, hashes);

        assertEquals(1, mismatches.size());
        assertTrue(mismatches.get(0).contains("(missing)"));
    }

    // ---------------------------------------------------------------------------
    // getMismatches — backwards-compatibility guarantee
    // ---------------------------------------------------------------------------

    @Test
    public void getMismatches_fileNotInKnownHashes_isNotFlagged() throws IOException {
        File dir = tmp.newFolder("model5");
        // Write a file whose name is NOT in the hash map
        writeFile(dir, "Whisper_encoder.onnx", "whatever content");

        // Hash map mentions a different file name → on-disk file is silently skipped
        Map<String, String> hashes = mapOf("Whisper_decoder.onnx",
                "0000000000000000000000000000000000000000000000000000000000000000");
        // Whisper_decoder.onnx is missing → flagged; Whisper_encoder.onnx is not in map → ignored
        List<String> mismatches = ModelIntegrityChecker.getMismatches(dir, hashes);

        assertEquals(1, mismatches.size());
        assertTrue(mismatches.get(0).contains("Whisper_decoder.onnx"));
        assertTrue("Extra on-disk file should not be flagged",
                mismatches.stream().noneMatch(s -> s.contains("Whisper_encoder.onnx")));
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private File writeFile(String name, String content) throws IOException {
        File f = tmp.newFile(name);
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
        }
        return f;
    }

    private File writeFile(File dir, String name, String content) throws IOException {
        File f = new File(dir, name);
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
        }
        return f;
    }

    private static Map<String, String> mapOf(String key, String value) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(key, value);
        return m;
    }
}
