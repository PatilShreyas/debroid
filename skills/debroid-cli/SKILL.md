---
name: Debroid CLI Debugger
description: "Use Debroid CLI to orchestrate headless Android debugging, set breakpoints, inspect variables, catch exceptions, and evaluate expressions live in Android applications."
---

# Debroid CLI Debugger Skill

This skill provides instructions for AI agents on how to use the `debroid` CLI tool to orchestrate headless Android debugging sessions. The CLI returns structured JSON responses that should be parsed to extract identifiers (session IDs, thread IDs, object IDs).

## 1. Background Daemon Initialization

Debroid relies on a persistent background daemon to maintain the JDWP socket connection with the Android device/emulator. 

**Before running any other command, ensure the daemon is running.**
```bash
debroid daemon &
```

## 2. Launching and Attaching

To start a debugging session, you must attach the debugger to an Android application. 
- **Launch Suspended (Recommended)**: Safely launches the app and pauses it before any initialization code runs.
  ```bash
  debroid launch com.example.app.id
  ```
- **Attach to Running**: Attaches to a process that is already running.
  ```bash
  debroid attach com.example.app.id
  ```

Both commands return a JSON payload with a `sessionId`:
```json
{"sessionId": "sess_100"}
```
*Note: Keep track of this `sessionId`; you need it for all subsequent commands.*

## 3. Setting Breakpoints & Watchpoints

Use breakpoints to pause execution at specific lines or events.

- **Line Breakpoint**: Pause when a specific line of code is executed.
  ```bash
  debroid break sess_100 MainActivity.kt 42
  ```
- **Exception Breakpoint**: Catch uncaught exceptions before the app crashes. You can specify a class name (e.g., `java.lang.NullPointerException`) or catch all uncaught exceptions by omitting it.
  ```bash
  debroid catch-exception sess_100
  ```
- **Watchpoint**: Pause when a specific class field is accessed or modified.
  ```bash
  debroid watch sess_100 com.example.app.MyClass myField
  ```

## 4. Polling for Events (Crucial Step)

Since debugging is asynchronous, you must poll the event queue to see if breakpoints have been hit, exceptions thrown, or if the app detached.

```bash
debroid poll sess_100 0 --with-stacktrace
```
* Pass `0` as the cursor initially. 
* The response will include a `nextCursor` value. Pass this value into your *next* poll command to avoid receiving duplicate events.
* `--with-stacktrace` is optional but highly recommended to instantly see where the app paused.

When a breakpoint is hit, the JSON response will include an array of events with `eventType: "BREAKPOINT_HIT"` and a `threadId` (e.g., `"1"`).

## 5. Inspecting Paused State

Once the poll command indicates a thread is suspended (e.g., you hit a breakpoint), use the `threadId` to inspect the app's memory state:

- **List Threads**: 
  ```bash
  debroid threads sess_100
  ```
- **Get Comprehensive Pause State** (Recommended - retrieves stack frames, local variables, and instance variables in one shot):
  ```bash
  debroid pause-state sess_100 1
  ```
- **Get Locals Only**:
  ```bash
  debroid locals sess_100 1
  ```
- **Get Frames Only**:
  ```bash
  debroid frames sess_100 1
  ```
- **Inspect Deep Objects**: If a variable is a complex object, it will return an `objectId`. Use this command to drill into it:
  ```bash
  debroid inspect sess_100 <object_id> --max-depth 2
  ```
- **Inspect Coroutine State**: If the thread is inside a Kotlin coroutine, pass the `continuation` object ID to extract the actual coroutine locals.
  ```bash
  debroid coroutine sess_100 <continuation_id>
  ```

## 6. Live Evaluation & Memory Mutation

- **Set Variable**: Mutate a primitive or string local variable live in memory!
  ```bash
  debroid set-var sess_100 1 isPremiumUser true
  ```
- **Evaluate Expression**: Execute simple expressions or string concatenations on the fly.
  ```bash
  debroid eval sess_100 1 "myObject.toString()"
  ```

## 7. Execution Control (Stepping)

Once you've inspected what you need, you can step through the code or resume execution.

Using the `step` command:
```bash
debroid step sess_100 1 STEP_OVER
debroid step sess_100 1 STEP_INTO
debroid step sess_100 1 STEP_OUT
```

Shortcut to just resume:
```bash
debroid resume sess_100 1
```

## 8. Clean up

When you are done debugging, cleanly detach the debugger without killing the app process:
```bash
debroid detach sess_100
```
