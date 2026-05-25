package com.whisperonnx;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Verifies that the Zip Slip path-traversal protection logic is sound.
 *
 * The guard implemented in SetupActivity.zipExtract() is:
 *
 *   String canonicalTarget = targetDir.getCanonicalPath() + File.separator;
 *   String canonicalEntry  = new File(targetDir, entryName).getCanonicalPath();
 *   if (!canonicalEntry.startsWith(canonicalTarget)) { ... skip ... }
 *
 * These tests exercise that logic using standard java.io.File (no Android SDK needed).
 */
public class ZipSlipTest {

    private File targetDir;

    @Before
    public void setUp() throws IOException {
        targetDir = Files.createTempDirectory("zip-slip-test-").toFile();
    }

    @After
    public void tearDown() {
        deleteRecursively(targetDir);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /**
     * Replicates the guard used in SetupActivity. Returns true if the entry is safe.
     *
     * Guard order matches the production code:
     * 1. Reject entries with absolute paths or Windows-style separators.
     * 2. Reject entries whose canonical path escapes targetDir.
     */
    private boolean isSafeEntry(String entryName) throws IOException {
        // Mirror the explicit name-based checks added to SetupActivity
        if (entryName.startsWith("/") || entryName.startsWith("\\")
                || entryName.contains("\\")) {
            return false;
        }
        String canonicalTarget = targetDir.getCanonicalPath() + File.separator;
        String canonicalEntry  = new File(targetDir, entryName).getCanonicalPath();
        return canonicalEntry.startsWith(canonicalTarget);
    }

    private void deleteRecursively(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursively(child);
            }
        }
        f.delete();
    }

    // ── safe entries ─────────────────────────────────────────────────────────

    @Test
    public void flatFileInTargetDir_isAllowed() throws IOException {
        assertTrue(isSafeEntry("model.onnx"));
    }

    @Test
    public void nestedFileInSubdir_isAllowed() throws IOException {
        assertTrue(isSafeEntry("subdir/model.onnx"));
    }

    @Test
    public void deeplyNestedFile_isAllowed() throws IOException {
        assertTrue(isSafeEntry("a/b/c/model.onnx"));
    }

    // ── Zip Slip attacks ────────────────────────────────────────────────────

    @Test
    public void classicPathTraversal_isBlocked() throws IOException {
        assertFalse(isSafeEntry("../../etc/passwd"));
    }

    @Test
    public void absolutePathEntry_isBlocked() throws IOException {
        // Some zip implementations produce entries like /etc/passwd
        assertFalse(isSafeEntry("/etc/passwd"));
    }

    @Test
    public void traversalWithFilename_isBlocked() throws IOException {
        assertFalse(isSafeEntry("../sibling-dir/evil.sh"));
    }

    @Test
    public void embeddedTraversal_isBlocked() throws IOException {
        assertFalse(isSafeEntry("subdir/../../etc/passwd"));
    }

    @Test
    public void windowsStyleTraversal_isBlocked() throws IOException {
        // File.getCanonicalPath() normalises separators on all platforms
        assertFalse(isSafeEntry("..\\..\\etc\\passwd"));
    }
}
