---
name: Debroid CLI Debugger
description: "Orchestrate headless Android debugging. Use this skill when asked to debug an Android app, set breakpoints, catch exceptions, inspect variables, or evaluate expressions live."
---

# Debroid CLI Debugger Skill

This skill provides mandatory instructions for AI agents to use the `debroid` CLI for headless Android debugging. **Follow these instructions strictly to avoid context window bloat and execution errors.**

## 🎯 Trigger Conditions
Use this skill when the user asks to:
- "Debug the Android app"
- "Set a breakpoint in MainActivity"
- "Catch why the app is crashing"
- "Check the value of X live"
- "Step through the Android code"

## ⚠️ Critical Agent Rules (DO NOT IGNORE)
1. **Always use `--with-stacktrace`** when polling to get immediate context.
2. **Never poll in an infinite loop**. Poll once or twice, and if the app hasn't hit a breakpoint, instruct the user to interact with the app.
3. **Parse JSON outputs carefully**. All CLI outputs are JSON strings. Extract `sessionId`, `threadId`, `objectId`, and `continuationId` exactly as provided.
4. **Use Background Tasks**: If you are using an agentic system that supports background tasks, run `debroid daemon` as a background task.

## 🔄 Standard Debugging Workflow

### Step 1: Start the Daemon
Debroid requires a persistent background daemon to communicate with the Android device via JDWP.
```bash
debroid daemon &
```
*(Wait 1-2 seconds for it to start).*

### Step 2: Attach to the App
You must attach the debugger to obtain a `sessionId`.
- **If the user wants to debug app startup:**
  ```bash
  debroid launch <app_id>
  ```
- **If the app is already running:**
  ```bash
  debroid attach <app_id>
  ```
**Action:** Extract the `sessionId` from the JSON response.

### Step 3: Set Traps
Set your breakpoints or watchpoints *before* polling.
- **Line Breakpoint**: `debroid break <session_id> <FileName.kt> <line_number>`
- **Exception Trap**: `debroid catch-exception <session_id> [optional.Exception.Class]`
- **Watchpoint**: `debroid watch <session_id> <com.pkg.Class> <fieldName>`

### Step 4: Poll for Events
You must poll the event queue to see if a trap was triggered.
```bash
debroid poll <session_id> <cursor> --with-stacktrace
```
* **Initial Cursor:** Always start with `"0"`.
* **Subsequent Cursors:** Use the `nextCursor` value from the previous JSON response.
* **Waiting:** If `events` is empty, ask the user to trigger the action in the app, then poll again.

### Step 5: Inspect the Pause State
Once `poll` returns an event (e.g., `BREAKPOINT_HIT`), extract the `threadId`.
Use the **Pause State Command** to get frames and locals at once:
```bash
debroid pause-state <session_id> <thread_id>
```

**Advanced Inspections:**
- Drill into complex objects: `debroid inspect <session_id> <object_id> --max-depth 2`
- Coroutine state: `debroid coroutine <session_id> <continuation_id>`

### Step 6: Mutate or Step
- Mutate memory: `debroid set-var <session_id> <thread_id> <variableName> <newValue>`
- Evaluate: `debroid eval <session_id> <thread_id> "myObj.getVal()"` (Note: Dot-notation property access like `obj.field` is **not supported**. If you need to see an object's fields, use the `inspect` command instead.)
- Step execution: `debroid step <session_id> <thread_id> <ACTION>` (Actions: `STEP_OVER`, `STEP_INTO`, `STEP_OUT`, `RESUME`)

### Step 7: Clean Up
When debugging is complete, cleanly detach:
```bash
debroid detach <session_id>
```

## 🚨 Common Pitfalls & Error Recovery
- **Error: "Failed to communicate with daemon"**: The daemon isn't running. Run `debroid daemon &`.
- **Error: "AbsentInformationException"**: The app is not debuggable or was obfuscated by ProGuard. Ensure `android:debuggable="true"` in the Manifest.
- **Missing Local Variables**: If locals are empty, you might be at a method entry point. Run a `STEP_OVER` and check again.
- **Error: "EVALUATION_FAILED" on object properties**: The `eval` command cannot evaluate object fields using dot notation (e.g. `user.name`). To see an object's properties, retrieve its `objectId` from `locals` or `pause-state` and use `debroid inspect <session_id> <object_id>`.

## 📖 Complete CLI Command Reference
Here is the full list of commands and their signatures:
| Command | Signature | Description |
| :--- | :--- | :--- |
| `daemon` | `debroid daemon` | Starts the persistent background daemon |
| `launch` | `debroid launch <app_id>` | Launches app suspended and attaches |
| `attach` | `debroid attach <app_id>` | Attaches to running app |
| `detach` | `debroid detach <session_id>` | Safely detaches debugger |
| `break` | `debroid break <session_id> <file> <line>` | Sets a line breakpoint |
| `catch-exception` | `debroid catch-exception <session_id> [class_name]` | Sets an exception breakpoint |
| `watch` | `debroid watch <session_id> <class_name> <field_name>` | Sets a field watchpoint |
| `threads` | `debroid threads <session_id>` | Lists active threads |
| `locals` | `debroid locals <session_id> <thread_id>` | Gets shallow local variables |
| `pause-state` | `debroid pause-state <session_id> <thread_id>` | Gets frames, locals, and instance state |
| `set-var` | `debroid set-var <session_id> <thread_id> <var_name> <new_value>` | Mutates local variable |
| `eval` | `debroid eval <session_id> <thread_id> <expression...>` | Evaluates string expression |
| `resume` | `debroid resume <session_id> [thread_id=1]` | Resumes thread execution |
| `poll` | `debroid poll <session_id> [cursor=0] [--with-stacktrace]` | Polls for asynchronous debugger events |
| `frames` | `debroid frames <session_id> <thread_id>` | Retrieves thread stack frames |
| `coroutine` | `debroid coroutine <session_id> <continuation_id>` | Retrieves locals from a Continuation object |
| `inspect` | `debroid inspect <session_id> <object_id> [-d/--max-depth=<int>]` | Inspects deep object fields |
| `step` | `debroid step <session_id> <thread_id> <action>` | Steps execution (`STEP_OVER`, `STEP_INTO`, `STEP_OUT`, `RESUME`, `RESUME_ALL`) |
