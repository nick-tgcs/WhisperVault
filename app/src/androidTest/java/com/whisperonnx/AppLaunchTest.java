package com.whisperonnx;

import android.Manifest;

import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.Espresso;
import androidx.test.espresso.action.ViewActions;
import androidx.test.espresso.assertion.ViewAssertions;
import androidx.test.espresso.matcher.ViewMatchers;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.GrantPermissionRule;

import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static org.junit.Assert.assertEquals;

/**
 * End-to-end smoke test for MainActivity.
 *
 * Requires a physical device or emulator:
 *   ./gradlew connectedAndroidTest
 *
 * RECORD_AUDIO is granted up-front so the model-load path proceeds normally.
 * If no model is installed the app will launch SetupActivity instead;
 * that is also an acceptable result for the launch test.
 */
@RunWith(AndroidJUnit4.class)
public class AppLaunchTest {

    /**
     * Stage model files and enable WhisperIME once before any test in this class runs.
     * Uses the real production code paths — no bypass flags.
     */
    @BeforeClass
    public static void setUpEnvironment() {
        E2ETestHelper.setUp();
    }

    @Rule
    public GrantPermissionRule permissionRule =
        GrantPermissionRule.grant(
            Manifest.permission.RECORD_AUDIO,
            // POST_NOTIFICATIONS is required on API 33+; granting it here
            // prevents the system permission dialog from stopping MainActivity.
            "android.permission.POST_NOTIFICATIONS"
        );

    // ── package sanity ────────────────────────────────────────────────────────

    @Test
    public void appContext_hasCorrectPackageName() {
        assertEquals(
            "io.github.nick_tgcs.whispervault",
            InstrumentationRegistry.getInstrumentation()
                .getTargetContext()
                .getPackageName()
        );
    }

    // ── MainActivity launch ───────────────────────────────────────────────────

    /**
     * The app must launch without crashing.  This is the first smoke test
     * after any code change — if this fails, something fundamental broke.
     */
    @Test
    public void mainActivity_launchesWithoutCrash() {
        try (ActivityScenario<MainActivity> scenario =
                 ActivityScenario.launch(MainActivity.class)) {
            E2ETestHelper.dismissStartupDialogs();
            // getState() must be called from the test thread, not from inside onActivity()
            // (onActivity runs on the main thread and the API forbids getState() there).
            assertEquals(
                "MainActivity must reach RESUMED state",
                androidx.lifecycle.Lifecycle.State.RESUMED,
                scenario.getState()
            );
        }
    }

    /**
     * The record button must be visible on launch (it is the primary UI element
     * for the app's core function).
     */
    @Test
    public void mainActivity_recordButtonIsVisible() {
        try (ActivityScenario<MainActivity> ignored =
                 ActivityScenario.launch(MainActivity.class)) {
            E2ETestHelper.dismissStartupDialogs();
            onView(withId(R.id.btnRecord))
                .check(ViewAssertions.matches(isDisplayed()));
        }
    }

    /**
     * The result text field must be present (regression: must not be removed
     * by ProGuard when minifyEnabled=true, covered by the ProGuard -keep rules).
     */
    @Test
    public void mainActivity_resultTextViewIsPresent() {
        try (ActivityScenario<MainActivity> ignored =
                 ActivityScenario.launch(MainActivity.class)) {
            E2ETestHelper.dismissStartupDialogs();
            onView(withId(R.id.tvResult))
                .check(ViewAssertions.matches(isDisplayed()));
        }
    }
}
