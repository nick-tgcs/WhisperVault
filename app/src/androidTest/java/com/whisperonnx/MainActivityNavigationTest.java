package com.whisperonnx;

import android.Manifest;
import android.view.View;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.GrantPermissionRule;

import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;

/**
 * E2E navigation tests for MainActivity.
 *
 * These tests exercise UI flows that touch code paths affected by the
 * security fixes (e.g., settings navigation, which would break if
 * SettingsActivity's ProGuard rules were wrong or if the activity were
 * not properly exported for explicit intents).
 *
 * Requires: ./gradlew connectedAndroidTest   (physical device or emulator)
 */
@RunWith(AndroidJUnit4.class)
public class MainActivityNavigationTest {

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
            "android.permission.POST_NOTIFICATIONS"
        );

    /**
     * Tapping the settings menu item must open SettingsActivity without
     * crashing.  This also verifies that SettingsActivity survives ProGuard
     * obfuscation (all key classes kept via proguard-rules.pro).
     */
    @Test
    public void settingsMenuItem_opensSettingsActivity() throws Exception {
        try (ActivityScenario<MainActivity> ignored =
                 ActivityScenario.launch(MainActivity.class)) {
            E2ETestHelper.dismissStartupDialogs();

            // Settings is shown as a direct action icon (showAsAction="always"),
            // so click it by ID rather than opening the overflow menu.
            onView(withId(R.id.menu_settings))
                .check(matches(isDisplayed()))
                .perform(click());

            // SettingsActivity root element must appear
            onView(withId(R.id.layout_language))
                .check(matches(isDisplayed()));
        }
    }

    /**
     * Tapping the info button must not crash the app.
     */
    @Test
    public void infoButton_doesNotCrash() {
        try (ActivityScenario<MainActivity> ignored =
                 ActivityScenario.launch(MainActivity.class)) {
            E2ETestHelper.dismissStartupDialogs();

            onView(withId(R.id.btnInfo))
                .check(matches(isDisplayed()))
                .perform(click());
            // If we reach here without an exception the test passes
        }
    }
}
