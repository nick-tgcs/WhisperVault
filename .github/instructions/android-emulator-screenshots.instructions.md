---
applyTo: ".github/skills/android-emulator-screenshots/**"
---

## android-emulator-screenshots skill — agent testing

To test this skill with a dumb agent, run `runSubagent` with model `GPT-5 mini (copilot)` and this prompt (substitute `<workspace-root>` with the actual workspace folder path):

```
You are a junior developer following a skill document exactly.
Working directory: <workspace-root>
Read: <workspace-root>/.github/skills/android-emulator-screenshots/SKILL.md
Execute every step (Step 0 → Step 4) exactly as written. Do NOT skip unless told to stop.
Report the EXACT terminal output for every command, whether all 7 files are ≥ 80K, and any errors.
```

Pass condition: sub-agent reports `PASS: All 7 screenshots captured.` and all 7 files ≥ 80K.
