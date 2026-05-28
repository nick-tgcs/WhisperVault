---
applyTo: ".github/skills/android-manual-testing/**"
---

## android-manual-testing skill — agent testing

To test this skill with a dumb agent, run `runSubagent` with model `GPT-5 mini (copilot)` and this prompt (substitute `<workspace-root>` with the actual workspace folder path):

```
You are a junior developer following a skill document exactly.
Working directory: <workspace-root>
Read: <workspace-root>/.github/skills/android-manual-testing/SKILL.md
Execute every step exactly as written. Do NOT skip unless told to stop.
Report the EXACT terminal output for every command and any errors.
```

Pass condition: sub-agent completes all steps without error; Step 1 downloads the model (~242 MB) — this is expected and takes a few minutes.
