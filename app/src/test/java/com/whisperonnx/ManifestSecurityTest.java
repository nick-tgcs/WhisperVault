package com.whisperonnx;

import android.Manifest;
import android.content.ComponentName;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;

/**
 * Robolectric integration tests that validate AndroidManifest.xml security
 * properties without needing a physical device.
 *
 * Robolectric reads the merged manifest and exposes it via PackageManager,
 * giving us the same component attributes the framework would see at runtime.
 *
 * Run with: ./gradlew test  (needs no Android SDK on the host machine)
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class ManifestSecurityTest {

    private PackageManager pm;
    private static final String PKG = "io.github.nick_tgcs.whispervault";

    @Before
    public void setUp() {
        pm = ApplicationProvider.getApplicationContext().getPackageManager();
    }

    // ── exported activity checks ──────────────────────────────────────────────

    /**
     * SetupActivity is only ever launched explicitly from within the same app.
     * It must NOT be exported so external apps cannot invoke the zip-extraction
     * code path.
     */
    @Test
    public void setupActivity_mustNotBeExported() throws Exception {
        ActivityInfo info = pm.getActivityInfo(
            new ComponentName(PKG, "com.whisperonnx.SetupActivity"),
            0
        );
        assertFalse(
            "SetupActivity must have android:exported=\"false\" — it is started explicitly within the app",
            info.exported
        );
    }

    /**
     * SettingsActivity is only ever started explicitly from MainActivity.
     * Exporting it unnecessarily widens the attack surface.
     */
    @Test
    public void settingsActivity_mustNotBeExported() throws Exception {
        ActivityInfo info = pm.getActivityInfo(
            new ComponentName(PKG, "com.whisperonnx.SettingsActivity"),
            0
        );
        assertFalse(
            "SettingsActivity must have android:exported=\"false\" — it is started explicitly within the app",
            info.exported
        );
    }

    /**
     * WhisperRecognizeActivity handles an implicit RECOGNIZE_SPEECH intent from
     * external apps, so it must stay exported.  However it must NOT declare
     * visibleToInstantApps=true — instant apps are not a supported use-case.
     */
    @Test
    public void whisperRecognizeActivity_mustNotBeVisibleToInstantApps() throws Exception {
        ActivityInfo info = pm.getActivityInfo(
            new ComponentName(PKG, "com.whisperonnx.WhisperRecognizeActivity"),
            0
        );
        int FLAG_VISIBLE_TO_INSTANT_APP = 0x00100000; // ActivityInfo.FLAG_VISIBLE_TO_INSTANT_APP (API 26+)
        assertFalse(
            "WhisperRecognizeActivity must not have android:visibleToInstantApps=\"true\"",
            (info.flags & FLAG_VISIBLE_TO_INSTANT_APP) != 0
        );
    }

    // ── dangerous permission check ────────────────────────────────────────────

    /**
     * QUERY_ALL_PACKAGES is a sensitive permission that was incorrectly added
     * as a workaround.  After the fix a <queries> element is used instead,
     * which requires no extra permission.
     */
    @Test
    public void queryAllPackages_mustNotBeDeclared() throws Exception {
        PackageInfo info = pm.getPackageInfo(PKG, PackageManager.GET_PERMISSIONS);
        if (info.requestedPermissions == null) return; // no permissions at all → pass

        for (String perm : info.requestedPermissions) {
            assertNotEquals(
                "QUERY_ALL_PACKAGES permission must not be declared — use <queries> instead",
                "android.permission.QUERY_ALL_PACKAGES",
                perm
            );
        }
    }
}
