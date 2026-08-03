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
3. **Parse JSON outputs carefully**. All CLI outputs are JSON strings. Extract `sessionId`, `threadId`, `objectId`, `breakpointId`, `exceptionBreakpointId`, `watchpointId`, and `continuationId` **exactly as provided** and reuse them verbatim in subsequent commands. Do not synthesize IDs.
4. **Track trap IDs and remove them when done**. Every `break`, `catch-exception`, and `watch` returns an ID. Use `remove-break`, `remove-catch-exception`, `remove-watch` to clear traps you no longer need so they don't fire unexpectedly and waste poll cycles.
5. **Keep IDs in working memory, not in long scratch buffers.** A typical session uses 1–3 trap IDs plus 1 thread ID — keep them inline.
6. **Use Background Tasks**: If you are using an agentic system that supports background tasks, run `debroid daemon` as a background task.
7. **Token efficiency**: All JSON responses are compact by design. Do not echo full responses back to the user verbatim — summarize the relevant fields (`valuePreview`, `type`, `objectId`, `location`).

## 🔄 Standard Debugging Workflow

### Step 1: Start the Daemon
Debroid requires a persistent background daemon to communicate with the Android device via JDWP.
```bash
debroid daemon &
```
*(Wait 1-2 seconds for it to start). The first CLI command will also auto-start the daemon if it isn't running.*

### Step 2: Attach to the App
You must attach the debugger to obtain a `sessionId`.
- **If the user wants to debug app startup:**
  ```bash
  debroid launch <app_id>
  ```
  > ⚠️ `launch` uses `am set-debug-app -w`. Debroid **automatically** clears this flag when you call `detach`, so the next normal (non-debug) launch of the app will not hang. Do not call `am clear-debug-app` yourself.
- **If the app is already running:**
  ```bash
  debroid attach <app_id>
  ```
**Action:** Extract the `sessionId` from the JSON response.

### Step 3: Set Traps
Set your breakpoints or watchpoints *before* polling. **Save the returned IDs.**
- **Line Breakpoint**: `debroid break <session_id> <FileName.kt> <line_number>` → returns `{ "id": "bp_1", "verified": true|false, ... }`
  - If `verified=false`, the class isn't loaded yet. Debroid will **automatically** bind it the moment the class is prepared — you do **not** need to do anything; just poll. (No more deferred-breakpoint dead-ends.)
  - Use `remove-break <session_id> <breakpoint_id>` to clear it.
- **Exception Trap**: `debroid catch-exception <session_id> [ExceptionClass] [--caught] [--uncaught]`
  - Defaults: `--uncaught` ON, `--caught` OFF. Add `--caught` to also trap exceptions caught by the app.
  - Returns `{ "exceptionBreakpointId": "ex_bp_1" }`.
  - Remove with `remove-catch-exception <session_id> <exception_breakpoint_id>`.
- **Watchpoint**: `debroid watch <session_id> <com.pkg.Class> <fieldName> [--access] [--modify]`
  - Defaults: BOTH access and modify ON. Pass `--no-access` or `--no-modify` to disable either.
  - Returns `{ "watchpointId": "wp_1" }`.
  - Remove with `remove-watch <session_id> <watchpoint_id>`.

### Step 4: Poll for Events
You must poll the event queue to see if a trap was triggered.
```bash
debroid poll <session_id> <cursor> --with-stacktrace
```
* **Initial Cursor:** Always start with `"0"`.
* **Subsequent Cursors:** Use the `nextCursor` value from the previous JSON response.
* **Waiting:** If `events` is empty, ask the user to trigger the action in the app, then poll again.
* **Event types you may see:** `BREAKPOINT_HIT`, `STEP_HIT`, `EXCEPTION_HIT`, `WATCHPOINT_ACCESS_HIT`, `WATCHPOINT_MODIFY_HIT`, `DISCONNECT`.

### Step 5: Inspect the Pause State
Once `poll` returns an event (e.g., `BREAKPOINT_HIT`), extract the `threadId`.
Use the **Pause State Command** to get frames and locals at once:
```bash
debroid pause-state <session_id> <thread_id>
```

**Advanced Inspections:**
- Drill into complex objects: `debroid inspect <session_id> <object_id> --max-depth 2`
  - `--max-depth N` now recurses N levels deep into object fields. Responses include a `nested` map keyed by field name; each value is itself a `CliObjectInspectionResult` with `objectId`, `type`, `fields`, and (optionally) its own `nested`. Cycles are guarded automatically (an object already visited in the current inspection is skipped).
- Coroutine state: `debroid coroutine <session_id> <continuation_id>`

### Step 6: Mutate or Step
- Mutate memory: `debroid set-var <session_id> <thread_id> <variableName> <newValue>`
- Evaluate: `debroid eval <session_id> <thread_id> "myObj.getVal()"` (Note: Dot-notation property access like `obj.field` is **not supported**. If you need to see an object's fields, use the `inspect` command instead — or `pause-state` for the top-level locals + instance variables in one call.)
- Step execution: `debroid step <session_id> <thread_id> <ACTION>` (Actions: `STEP_OVER`, `STEP_INTO`, `STEP_OUT`, `RESUME_THREAD`, `RESUME_ALL`)
- Resume all threads: `debroid resume <session_id> [thread_id]`
  > Note: `resume` currently resumes **all** threads in the VM regardless of the (optional) `thread_id` argument. For per-thread resume, use `step <session_id> <thread_id> RESUME_THREAD`.

### Step 7: Clean Up
When debugging is complete, cleanly detach:
```bash
debroid detach <session_id>
```
> If the session was opened with `launch`, `detach` automatically runs `adb shell am clear-debug-app` so the user's app resumes normal behavior. You do **not** need to clear it manually.

## 🚨 Common Pitfalls & Error Recovery
- **Error: "Failed to communicate with daemon"**: The daemon isn't running. Run `debroid daemon &`.
- **Error: "AbsentInformationException"**: The app is not debuggable or was obfuscated by ProGuard. Ensure `android:debuggable="true"` in the Manifest.
- **Missing Local Variables**: If locals are empty, you might be at a method entry point. Run a `STEP_OVER` and check again.
- **Error: "EVALUATION_FAILED" on object properties**: The `eval` command cannot evaluate object fields using dot notation (e.g. `user.name`). To see an object's properties, retrieve its `objectId` from `locals` or `pause-state` and use `debroid inspect <session_id> <object_id>`.
- **`break` returns `verified=false`**: NOT a failure — the class will be loaded later and the breakpoint will be bound automatically. Keep your `bp` id and proceed.
- **A trap no longer needed**: Remove it. Lingering exception traps in particular can fire on every exception the app throws, flooding your poll results.
- **`detached` of `launch`'d session and app won't start normally**: Should not happen — `detach` clears `am set-debug-app`. If it does (e.g. daemon was forcibly killed), run `adb shell am clear-debug-app` once.

## 📖 Complete CLI Command Reference
Here is the full list of commands and their signatures:
| Command | Signature | Description |
| :--- | :--- | :--- |
| `daemon` | `debroid daemon` | Starts the persistent background daemon |
| `launch` | `debroid launch <app_id>` | Launches app suspended and attaches (auto-clears set-debug-app on detach) |
| `attach` | `debroid attach <app_id>` | Attaches to a running app |
| `detach` | `debroid detach <session_id>` | Safely detaches debugger; for `launch` sessions also clears `am set-debug-app` |
| `break` | `debroid break <session_id> <file> <line>` | Sets a line breakpoint (auto-defers if class isn't loaded yet) |
| `remove-break` | `debroid remove-break <session_id> <breakpoint_id>` | Removes a previously set line breakpoint |
| `catch-exception` | `debroid catch-exception <session_id> [class_name] [--caught] [--uncaught]` | Sets an exception breakpoint (default: `--uncaught` only) |
| `remove-catch-exception` | `debroid remove-catch-exception <session_id> <exception_breakpoint_id>` | Removes an exception breakpoint |
| `watch` | `debroid watch <session_id> <class_name> <field_name> [--access] [--modify]` | Sets a field watchpoint (default: access+modify) |
| `remove-watch` | `debroid remove-watch <session_id> <watchpoint_id>` | Removes a watchpoint |
| `threads` | `debroid threads <session_id>` | Lists active threads |
| `locals` | `debroid locals <session_id> <thread_id>` | Gets shallow local variables |
| `pause-state` | `debroid pause-state <session_id> <thread_id>` | Gets frames, locals, and instance state |
| `set-var` | `debroid set-var <session_id> <thread_id> <var_name> <new_value>` | Mutates local variable |
| `eval` | `debroid eval <session_id> <thread_id> <expression...>` | Evaluates string expression |
| `resume` | `debroid resume <session_id> [thread_id]` | Resumes **all** threads (thread_id currently ignored — use `step ... RESUME_THREAD` for per-thread resume) |
| `poll` | `debroid poll <session_id> [cursor=0] [--with-stacktrace]` | Polls for asynchronous debugger events |
| `frames` | `debroid frames <session_id> <thread_id>` | Retrieves thread stack frames |
| `coroutine` | `debroid coroutine <session_id> <continuation_id>` | Retrieves locals from a Continuation object |
| `inspect` | `debroid inspect <session_id> <object_id> [-d/--max-depth=<int>]` | Inspects deep object fields (`nested` map populated when `--max-depth > 1`) |
| `step` | `debroid step <session_id> <thread_id> <action>` | Steps execution (`STEP_OVER`, `STEP_INTO`, `STEP_OUT`, `RESUME_THREAD`, `RESUME_ALL`) |