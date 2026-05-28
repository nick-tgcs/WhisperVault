---
applyTo: ".github/skills/**"
---

## Running skills with a dumb sub-agent

When the user asks to test or run a skill, use `runSubagent` with model `GPT-5 mini (copilot)`.

Prompt template (fill in SKILL_PATH with the absolute path resolved from the workspace root):

```
You are a junior developer following a skill document exactly.

Working directory: <workspace-root>

Read the skill file at <workspace-root>/SKILL_PATH.

Execute EVERY step in the skill document exactly as written, in order.
Do NOT skip any step unless the SKILL.md explicitly tells you to stop.

Report back:
1. The EXACT terminal output from EVERY command, labelled by step number.
2. Whether all expected outputs match.
3. The exact text of any error messages if anything failed.
```

If the sub-agent fails a step, fix the SKILL.md or the companion script, then re-run the sub-agent until it passes.
