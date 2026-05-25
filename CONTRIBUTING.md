# Contributing to WhisperVault

WhisperVault is a security-focused project. Contributions are welcome, but the bar is deliberately high: untested code will not be merged, and security regressions will be closed immediately.

## Non-negotiable requirements

Every pull request **must** include:

1. **Unit tests** — JVM tests in `app/src/test/` covering all new logic. Test the behaviour, not the implementation. If you add a utility class, it needs a `*Test.java` alongside it. If you fix a bug, write a test that would have caught it.

2. **Integration tests** — Robolectric tests where the change touches Android framework behaviour (manifest attributes, permissions, system services, SharedPreferences). See `ManifestSecurityTest` and `RecordBufferTest` for examples.

3. **End-to-end tests** — Espresso/UIAutomator tests in `app/src/androidTest/` for anything user-facing (new UI flows, new IME behaviour, new recognition paths). The E2E harness in `E2ETestHelper` and `AudioInjectionTest` is already wired up — extend it rather than working around it.

4. **Test results** — Run all tests and paste the summary in your PR description:
   ```
   ./gradlew testDebugUnitTest
   ./gradlew connectedDebugAndroidTest   # requires emulator, see scripts/setup_e2e_tests.sh
   ```
   Include the counts: how many tests exist before your change, how many after, zero failures.

## Security requirements

- **Do not weaken any existing security control.** The controls in place (model integrity checks, Zip Slip guard, export flags, volatile fields, debug-only logging) must remain intact. If your change touches one of these areas, say so explicitly in the PR description.
- **New network-accessible surfaces** require a threat model in the PR description. We don't add them lightly.
- **No `QUERY_ALL_PACKAGES`**, no new exported components without explicit justification, no unconditional logging of user speech content or package names.
- **If you add ProGuard `keep` rules**, explain exactly why reflection is needed and what the runtime path is.
- **Dependency upgrades** must state the version being replaced, the version being introduced, and why (CVE fix, required API, etc.).

## Code quality

- No `TODO` or `FIXME` in submitted code without a linked issue.
- Replace `printStackTrace()` with `Log.e()`. Do not log at `Log.d` or `Log.v` in paths that handle user audio or text unless gated behind `BuildConfig.DEBUG`.
- Use `SharedPreferences.apply()`, not `commit()`, on the main thread.
- Match the existing code style — the project uses standard Android Java conventions with no formatter enforced, so just be consistent with the surrounding file.

## Submitting

1. Fork the repo, create a feature branch off `master`.
2. Make your changes, write your tests, run everything green.
3. Open a PR with:
   - A clear description of what changed and why
   - The security implications (even if none — say "no security impact")
   - The test result summary (counts + zero failures)

PRs that arrive without tests, with failing tests, or with no mention of security implications will be closed without review.
