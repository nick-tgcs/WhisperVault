package com.whisperonnx;

import android.Manifest;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.GrantPermissionRule;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;

/**
 * Instrumented security regression tests.
 *
 * These tests run on a real device/emulator and validate manifest security
 * properties as they appear to the Android framework at runtime —
 * complementing the Robolectric {@link ManifestSecurityTest} that runs on
 * the JVM.
 *
 * Requires: ./gradlew connectedAndroidTest
 */
@RunWith(AndroidJUnit4.class)
public class ManifestSecurityInstrumentedTest {

    private static final String PKG = "io.github.nick_tgcs.whispervault";

    @Rule
    public GrantPermissionRule permissionRule =
        GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO);

    private PackageManager pm() {
        return InstrumentationRegistry.getInstrumentation()
            .getTargetContext()
            .getPackageManager();
    }

    @Test
    public void setupActivity_notExported() throws Exception {
        ActivityInfo info = pm().getActivityInfo(
            new android.content.ComponentName(PKG, "com.whisperonnx.SetupActivity"),
            0
        );
        assertFalse("SetupActivity must not be exported", info.exported);
    }

    @Test
    public void settingsActivity_notExported() throws Exception {
        ActivityInfo info = pm().getActivityInfo(
            new android.content.ComponentName(PKG, "com.whisperonnx.SettingsActivity"),
            0
        );
        assertFalse("SettingsActivity must not be exported", info.exported);
    }

    @Test
    public void queryAllPackages_notDeclared() throws Exception {
        PackageInfo info = pm().getPackageInfo(PKG, PackageManager.GET_PERMISSIONS);
        if (info.requestedPermissions == null) return;
        for (String perm : info.requestedPermissions) {
            assertNotEquals(
                "QUERY_ALL_PACKAGES must not be declared",
                "android.permission.QUERY_ALL_PACKAGES",
                perm
            );
        }
    }
}
