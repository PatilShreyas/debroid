---
name: Debroid CLI Debugger
description: "Orchestrate headless Android debugging via JDWP. ACTIVATE this skill whenever asked to debug an Android application, set line or exception breakpoints, inspect runtime variables or Jetpack Compose state, step through execution, evaluate live expressions, watch fields, or diagnose runtime crashes."
---

# Debroid CLI Debugger Skill

This skill provides mandatory instructions for AI agents to use the `debroid` CLI for headless Android debugging. **Follow these instructions strictly to avoid context window bloat and execution errors.**

## 🎯 Trigger Conditions
Activate this skill whenever the user or task involves:
- Debugging an Android application or process live
- Setting line breakpoints in Kotlin or Java source files (`debroid break`)
- Catching uncaught or caught runtime exceptions (`debroid catch-exception`)
- Inspecting live variables, object memory, or Jetpack Compose state (`debroid locals`, `pause-state`, `inspect`)
- Stepping through Kotlin/Java code execution (`debroid step`)
- Monitoring field access or modifications with watchpoints (`debroid watch`)
- Evaluating expressions or mutating variable values in runtime memory (`debroid eval`, `set-var`)

## ⚠️ Critical Agent Rules (DO NOT IGNORE)
1. **Always use `--with-stacktrace`** when polling to get immediate context.
2. **Never poll in an infinite loop**. Poll once or twice, and if the app hasn't hit a breakpoint, instruct the user to interact with the app.
3. **Parse JSON outputs carefully**. All CLI outputs are JSON strings. Extract `sessionId`, `threadId`, `objectId`, `breakpointId`, `exceptionBreakpointId`, `watchpointId`, and `continuationId` **exactly as provided** and reuse them verbatim in subsequent commands. Do not synthesize IDs.
4. **Track trap IDs and remove them when done**. Every `break`, `catch-exception`, and `watch` returns an ID. Use `remove-break`, `remove-catch-exception`, `remove-watch` to clear traps you no longer need so they don't fire unexpectedly and waste poll cycles.
5. **Keep IDs in working memory, not in long scratch buffers.** A typical session uses 1–3 trap IDs plus 1 thread ID — keep them inline.
6. **Use Background Tasks**: If you are using an agentic system that supports background tasks, run `debroid daemon` as a background task.
7. **Token efficiency & Schema Inspection**: All JSON responses are compact single-line by default to conserve agent context tokens. If formatted multi-line JSON is required, append the `--pretty` flag to any JSON command. To inspect the expected output JSON structure for any command without running a session, pass the `--schema` flag (e.g., `debroid pause-state --schema`). Do not echo full responses back to the user verbatim — summarize the relevant fields (`valuePreview`, `type`, `objectId`, `location`).
8. **Self-Recovery**: If a session becomes unresponsive or gets out of sync, run `debroid stop` to cleanly terminate the daemon and release ADB ports. The next `debroid` command will auto-restart a fresh daemon.
9. **Report, don't silently work around**: If you hit unexpected Debroid behavior or have feedback on the tool itself, see **"🐛 Reporting Bugs & Feedback"** below instead of giving up or quietly routing around it.

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
  debroid resume <session_id>
  ```
  > ⚠️ `launch` starts the app suspended via `am set-debug-app -w`. **Always call `debroid resume <session_id>` right after `launch`** so the VM completes startup and loads application classes.
  > ⚠️ `launch` uses `am set-debug-app -w`. Debroid **automatically** clears this flag when you call `detach`, so the next normal (non-debug) launch of the app will not hang. Do not call `am clear-debug-app` yourself.
- **If the app is already running:**
  ```bash
  debroid attach <app_id>
  ```
**Action:** Extract the `sessionId` from the JSON response.

### Step 3: Set Traps
Set your breakpoints or watchpoints *before* polling. **Save the returned IDs.**
- **Line Breakpoint**: `debroid break <session_id> <FileName.kt> <line_number> [--package <pkg>] [--pretty]` → returns `{ "id": "bp_1", "verified": true|false, ... }`
  - **Always pass `--package`** when you know it (e.g. `--package com.example.app.search`). Providing the package name lets the daemon resolve the class with a single targeted lookup instead of scanning every loaded class across the entire VM, which takes seconds or minutes on large applications and drastically improves responsiveness.
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
  - **Important:** `newValue` is parsed as a Java expression! To set a String literal, you must wrap the value in escaped quotes (e.g., `\"my string\"`). To set it to the result of an expression, provide the expression (e.g., `5`, `true`, `varName + 1`, Int/Long: `10`, `100L`, Float/Double: `10.5f`, `20.5d`).
- Evaluate expressions: `debroid eval <session_id> <thread_id> "<expression>"`
  - **Expression Engine**: Evaluates full expressions using standard Java syntax.
  - **Evaluating Kotlin Code using Java Syntax**:
    - **Properties & Getters**: Access Kotlin properties via their generated Java getter methods (e.g., `order.getAmount()` or `user.getName()` instead of synthetic property syntax `order.amount` or `user.name`).
    - **Method Calls & Arithmetic**: Supports method invocations, parameter passing, and math operators (e.g., `order.getAmount() * 0.15`, `Math.max(x, y)`).
    - **String Operations & Logic**: Supports boolean logic and string method invocations (e.g., `order.getCustomerType().equals("GOLD")`).
    - **Field Inspection**: To view an object's instance fields without getter methods, retrieve its `objectId` from `locals` or `pause-state` and use `debroid inspect <session_id> <object_id>`.
- Step execution: `debroid step <session_id> <thread_id> <ACTION>` (Actions: `STEP_OVER`, `STEP_INTO`, `STEP_OUT`, `RESUME_THREAD`, `RESUME_ALL`)
- Resume all threads: `debroid resume <session_id>`
  > Note: `resume` resumes **all** threads in the VM. For per-thread resume, use `step <session_id> <thread_id> RESUME_THREAD`.

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
- **Error: "EVALUATION_FAILED" on Kotlin properties**: The `eval` engine evaluates Java syntax. Kotlin properties with backing getters must be called as Java methods (e.g., `user.getName()` instead of `user.name`). Alternatively, retrieve the `objectId` from `locals` or `pause-state` and use `debroid inspect <session_id> <object_id>`.
- **`break` returns `verified=false`**: NOT a failure — the class will be loaded later and the breakpoint will be bound automatically. Keep your `bp` id and proceed.
- **A trap no longer needed**: Remove it. Lingering exception traps in particular can fire on every exception the app throws, flooding your poll results.
- **Jetpack Compose Breakpoints**: Place line breakpoints on executable statements inside `@Composable` function bodies (e.g. `val x = ...`), not on the `@Composable fun Name(...)` signature header line where no bytecode is generated.
- **Jetpack Compose State Delegates**: Compose `remember { mutableStateOf(...) }` or `collectAsState()` variables appear as `<varName>$delegate` in `pause-state` or `locals`. To inspect Compose state values, retrieve the `objectId` of the `$delegate` object and use `debroid inspect <session_id> <object_id>`.
- **Autonomous UI Interaction**: Instead of asking the user to click buttons on the device, inspect the UI bounds using `android layout --pretty` (prefer `android-cli` if installed) or `adb shell uiautomator dump /sdcard/window_dump.xml && adb shell cat /sdcard/window_dump.xml`, locate the target element's `bounds="[minX,minY][maxX,maxY]"`, calculate the center coordinates `(minX + maxX)/2`, and simulate a tap with `adb shell input tap X Y`.
- **`detached` of `launch`'d session and app won't start normally**: Should not happen — `detach` clears `am set-debug-app`. If it does (e.g. daemon was forcibly killed), run `adb shell am clear-debug-app` once.
- **Daemon or Session Unresponsive**: Run `debroid stop` to terminate the background server and clear ADB port forwards. The next `debroid` command will automatically spawn a fresh, clean daemon.

## 🐛 Reporting Bugs & Feedback (Debroid Tool Itself)

If you hit unexpected Debroid behavior (a crash, a wrong/malformed result, a stuck session `debroid stop` doesn't fix) — not a bug in the app you're debugging — or have feedback on the tool itself, don't silently retry, work around it, or give up.

Before doing anything else, fetch the full reporting workflow (sanitizing details, exact report format, mandatory user confirmation, filing via `gh`):
```bash
curl -fsSL https://raw.githubusercontent.com/PatilShreyas/debroid/main/skills/debroid-cli/references/reporting-feedback.md
```
Then follow it exactly.

## 📊 Token-Efficient JSON Extraction & Querying Guidelines

To keep context windows clean and minimize token consumption during debugging sessions:

1. **Direct Output Reading (Default for Small/Medium Commands)**:
   - For `attach`, `launch`, `break`, `locals`, `pause-state`, `set-var`, `eval`: Read `stdout` directly into context memory. Single-line compact responses take only ~50-80 tokens.
2. **Filtering Large Payloads via `jq` / Python (When Payload is Large)**:
   - For deep `inspect` queries (`-d 2+`) or long event `poll` responses with stacktraces, pass `--schema` upfront to know key names, then pipe output through `jq` in the shell to extract target fields before reading into context:
     ```bash
     # Filter specific local variable from pause-state:
     debroid pause-state <session_id> <thread_id> | jq '.locals[] | select(.name=="targetVar")'

     # Extract specific field during deep object inspection:
     debroid inspect <session_id> <object_id> -d 2 | jq '.fields.order'

     # Extract event types and exception messages from poll:
     debroid poll <session_id> <cursor> --with-stacktrace | jq '.events[] | {eventType, className, exceptionMessage}'
     ```
3. **Direct VM Expression Querying (`eval`)**:
   - To query a single calculated primitive value or method result (e.g. `order.getAmount()` or `list.size()`), call `debroid eval <session_id> <thread_id> "<expr>"`. This evaluates the expression directly in the Dalvik/ART VM and returns a tiny single-value payload (< 20 tokens).

## 📖 Complete CLI Command Reference
Here is the full list of commands and their signatures (note: all JSON commands accept an optional `--pretty` flag to format response JSON, and an eager `--schema` flag to inspect output types):

| Command | Signature | Description |
| :--- | :--- | :--- |
| `daemon` | `debroid daemon` | Starts the persistent background daemon |
| `stop` | `debroid stop [--pretty]` | Shuts down background daemon and detaches all active sessions |
| `launch` | `debroid launch <app_id> [--pretty]` | Launches app suspended and attaches (auto-clears set-debug-app on detach) |
| `attach` | `debroid attach <app_id> [--pretty]` | Attaches to a running app |
| `detach` | `debroid detach <session_id> [--pretty]` | Safely detaches debugger; for `launch` sessions also clears `am set-debug-app` |
| `break` | `debroid break <session_id> <file> <line> [-p/--package=<pkg>] [--pretty]` | Sets a line breakpoint (always pass `--package` for fast targeted lookup; auto-defers if class isn't loaded yet) |
| `remove-break` | `debroid remove-break <session_id> <breakpoint_id> [--pretty]` | Removes a previously set line breakpoint |
| `catch-exception` | `debroid catch-exception <session_id> [class_name] [--caught] [--uncaught] [--pretty]` | Sets an exception breakpoint (default: `--uncaught` only) |
| `remove-catch-exception` | `debroid remove-catch-exception <session_id> <exception_breakpoint_id> [--pretty]` | Removes an exception breakpoint |
| `watch` | `debroid watch <session_id> <class_name> <field_name> [--access] [--modify] [--pretty]` | Sets a field watchpoint (default: access+modify) |
| `remove-watch` | `debroid remove-watch <session_id> <watchpoint_id> [--pretty]` | Removes a watchpoint |
| `threads` | `debroid threads <session_id> [--pretty]` | Lists active threads |
| `locals` | `debroid locals <session_id> <thread_id> [--pretty]` | Gets shallow local variables |
| `pause-state` | `debroid pause-state <session_id> <thread_id> [--pretty]` | Gets frames, locals, and instance state |
| `set-var` | `debroid set-var <session_id> <thread_id> <var_name> <new_value> [--pretty]` | Mutates local variable. `<new_value>` is parsed as a Java expression (e.g. `10`, `100L`, `10.5f`, `20.5d`, `true`, `"\"my string\""`). Ensure quotes are escaped in shell. |
| `eval` | `debroid eval <session_id> <thread_id> <expression...> [--pretty]` | Evaluates string expression |
| `resume` | `debroid resume <session_id> [--pretty]` | Resumes **all** threads (use `step ... RESUME_THREAD` for per-thread resume) |
| `poll` | `debroid poll <session_id> [cursor=0] [--with-stacktrace] [--pretty]` | Polls for asynchronous debugger events |
| `frames` | `debroid frames <session_id> <thread_id> [--pretty]` | Retrieves thread stack frames |
| `coroutine` | `debroid coroutine <session_id> <continuation_id> [--pretty]` | Retrieves locals from a Continuation object |
| `inspect` | `debroid inspect <session_id> <object_id> [-d/--max-depth=<int>] [--pretty]` | Inspects deep object fields (`nested` map populated when `--max-depth > 1`) |
| `step` | `debroid step <session_id> <thread_id> <action> [--pretty]` | Steps execution (`STEP_OVER`, `STEP_INTO`, `STEP_OUT`, `RESUME_THREAD`, `RESUME_ALL`) |
| `update` | `debroid update [--check-only] [--pretty]` | Checks for CLI updates or performs an in-place self-update to the latest release |
| `skill` | `debroid skill` | Prints embedded skill instructions to stdout (e.g. `debroid skill > ~/.claude/skills/debroid/SKILL.md`) |
