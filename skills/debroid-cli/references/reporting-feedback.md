# Reporting Bugs & Feedback (Debroid Tool Itself)

This is for problems with **Debroid** (the CLI/daemon) — a crash, a wrong or malformed response, a stuck session, a missing/confusing feature — not bugs in the app you're debugging. If you hit one of these, or simply have feedback on the tool, report it upstream rather than silently retrying, working around it, or giving up.

## Step 1: Sanitize — strip anything proprietary
Before drafting anything, abstract away all details specific to the user's app or organization. Never include:
- Real app/package IDs → use `com.example.app` instead
- Real class, file, or variable names → use generic stand-ins (`MainActivity.kt`, `someField`, `SomeViewModel`) that preserve the *shape* of the problem, not the real names
- Source code snippets, business logic, screenshots of the app UI, secrets/tokens, or unreleased feature/product names

Keep only what's needed to reproduce or understand the Debroid-level issue.

## Step 2: Draft the report in this exact format

```
### Description
<1-3 sentences: what you were trying to do and what went wrong>

### Commands Executed
debroid <command 1>
debroid <command 2>
...

### Expected Outcome
<what should have happened>

### Actual Outcome
<what actually happened — include the exact (sanitized) JSON error/response>

### Suggested Fix / Notes
<optional; write "Unknown" if you have no theory>
```

## Step 3: Confirm with the user before filing anything
**Always show the drafted report to the user and get their explicit confirmation before submitting it anywhere** — even when `gh` is available and filing is one command away. Never file automatically without confirmation. If the user wants changes, revise and reconfirm.

## Step 4: File it
1. Check whether `gh` is installed and authenticated: `gh auth status`.
2. **If available**: run
   ```bash
   gh issue create --repo PatilShreyas/debroid --title "<short descriptive title>" --body-file <path-to-drafted-report>
   ```
   then share the returned issue URL with the user.
3. **If not available/authenticated**: print the full drafted report to the user and ask them to open https://github.com/PatilShreyas/debroid/issues/new and paste it in themselves. Do not attempt to install or authenticate `gh` on your own.
