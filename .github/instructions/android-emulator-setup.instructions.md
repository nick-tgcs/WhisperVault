---
applyTo: ".github/skills/android-emulator-setup/**"
---

## android-emulator-setup skill — agent testing

To test this skill with a dumb agent, run `runSubagent` with model `GPT-5 mini (copilot)` and this prompt (substitute `<workspace-root>` with the actual workspace folder path):

```
You are a junior developer following a skill document exactly.
Working directory: <workspace-root>
Read: <workspace-root>/.github/skills/android-emulator-setup/SKILL.md
Execute every step (Check 1 → Step 5) exactly as written. Do NOT skip unless told to stop.
Report the EXACT terminal output for every command and any errors.
```

Pass condition: sub-agent sees `==> Setup complete.` and Step 5 prints the full IME service name.
