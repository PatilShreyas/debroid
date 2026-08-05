<div align="center">
  <h1>Debroid 🤖⚡</h1>
  <p><b>The Headless Android Debugger for AI Agents</b></p>

  <p>
    <a href="https://github.com/PatilShreyas/debroid/actions/workflows/ci.yml"><img src="https://github.com/PatilShreyas/debroid/actions/workflows/ci.yml/badge.svg" alt="CI"></a>
    <a href="https://github.com/PatilShreyas/debroid/actions/workflows/release.yml"><img src="https://github.com/PatilShreyas/debroid/actions/workflows/release.yml/badge.svg" alt="Release"></a>
    <a href="https://github.com/PatilShreyas/debroid/blob/main/LICENSE"><img src="https://img.shields.io/github/license/PatilShreyas/debroid?color=blue&style=flat-square" alt="License"></a>
  </p>
</div>

**Debroid** (*DEBug + AnDROID*) is a headless CLI that speaks the Java Debug Wire protocol (JDWP) so AI agents — Claude Code, Codex, OpenCode, Cursor, Antigravity, or anything with a terminal — can debug a live Android app the way a human would in Android Studio: set breakpoints, catch exceptions, step through code, inspect and mutate variables, and watch fields — all without a GUI, and all through strict, machine-parseable JSON.

## ❓ Why it exists

Traditional Android development environments are highly visual. While AI coding assistants can write code, they are effectively "blind" when it comes to runtime debugging because they cannot click through the Android Studio UI to inspect memory or pause execution.

**Debroid closes this gap.** It acts as a translation layer between the raw terminal (which AI agents are great at using) and the Android Virtual Machine. By providing a CLI that outputs deterministic JSON, an AI agent can autonomously hypothesize a bug, launch the app, trap the exact line of code, read the live state of the device, and evaluate a fix — granting agents the same deep runtime awareness previously reserved for human developers.

## ✨ Features

- **🔴 Breakpoints**: Set line breakpoints in any class. Debroid automatically defers them if the class isn't loaded yet.
- **⚡ Exception Traps**: Catch caught or uncaught exceptions globally across the app.
- **👀 Watchpoints**: Monitor field access and modification live.
- **🔬 Deep Inspection**: Recursively inspect deep object memory, local variables, and call frames with built-in cycle-guards.
- **🧬 Live Mutation**: Modify variable memory dynamically on paused threads using `set-var`.
- **🧮 Live Evaluation**: Fully-featured expression evaluator supporting Java syntax, method calls, and arithmetic directly in the target VM.
- **🐾 Stepping**: Step Over, Step Into, Step Out, or Resume at will.
- **🧵 Coroutine Aware**: Built-in mechanism to extract shallow locals from Kotlin Continuation frames.

## 🚀 Installation

### Prerequisites
* Java JDK 11+ (`JAVA_HOME`)
* Android SDK & `adb` configured in your `PATH`

### Install the CLI

**Option 1: Quick Install (Pre-built Release Binary)**
Run the one-liner script to download the latest release binary and install it:
```bash
curl -fsSL https://raw.githubusercontent.com/PatilShreyas/debroid/main/install.sh | bash -s -- --remote
```

**Option 2: Local Build Install (Build from Source)**
Clone the repository and build locally:
```bash
./install.sh
```
*Note: The script installs the `debroid` binary to `/usr/local/bin` (may request `sudo` if `/usr/local/bin` is not user-writable).*

## 🧠 AI Agent Skill Setup

Debroid is designed to be operated autonomously by AI agents. To teach your agent how to use Debroid effectively, you must provide it with the skill instructions.

We have included a highly-optimized skill file inside the repository.

**Skill Location:** `skills/debroid-cli/SKILL.md`

**How to install the skill:**
Copy the contents of the `SKILL.md` file into your agent's context, prompt, or custom instructions/rules configuration. This gives the LLM explicit instructions on how to orchestrate the background daemon, set breakpoints, evaluate expressions, and poll the event queue without hallucinating CLI flags or getting stuck in infinite loops.

## 🔌 How it Works

Debroid separates the persistent debugging connection from the transient CLI commands to maintain state across agent invocations.

```mermaid
flowchart LR
    A["🤖 AI Agent\n(Terminal / CLI)"] <-->|Terminal Commands| B("⚡ Debroid Daemon\n(Background Server)")
    B <-->|JDWP over ADB| C["📱 Android App\n(Dalvik/ART VM)"]
```

1. **The AI Agent** runs `debroid` commands in the terminal (e.g. `debroid break ...`).
2. **The Debroid CLI** forwards these requests to the **Debroid Daemon**, which starts automatically in the background on the first command.
3. **The Debroid Daemon** holds the long-lived JDWP socket connection open via ADB port forwarding, managing the breakpoints, event queue, and thread states of the **Android App**.
4. The Daemon returns the result back to the CLI as a strict JSON payload, which the Agent reads from standard output.

## 📖 Command Reference

| Command | Signature | Description |
| :--- | :--- | :--- |
| `daemon` | `debroid daemon` | Starts the persistent background daemon |
| `stop` | `debroid stop` | Shuts down background daemon and detaches all active sessions |
| `launch` | `debroid launch <app_id>` | Launches app suspended and attaches (auto-clears set-debug-app on detach) |
| `attach` | `debroid attach <app_id>` | Attaches to a running app |
| `detach` | `debroid detach <session_id>` | Safely detaches debugger; for `launch` sessions also clears `am set-debug-app` |
| `break` | `debroid break <session_id> <file> <line>` | Sets a line breakpoint (auto-defers if class isn't loaded yet) |
| `remove-break` | `debroid remove-break <session_id> <breakpoint_id>` | Removes a line breakpoint |
| `catch-exception` | `debroid catch-exception <session_id> [class_name] [--caught] [--uncaught]` | Sets an exception breakpoint (default: `--uncaught` only) |
| `remove-catch-exception` | `debroid remove-catch-exception <session_id> <exception_breakpoint_id>` | Removes an exception breakpoint |
| `watch` | `debroid watch <session_id> <class_name> <field_name> [--access] [--modify]` | Sets a field watchpoint (default: access+modify) |
| `remove-watch` | `debroid remove-watch <session_id> <watchpoint_id>` | Removes a watchpoint |
| `threads` | `debroid threads <session_id>` | Lists active threads |
| `locals` | `debroid locals <session_id> <thread_id>` | Gets shallow local variables |
| `pause-state` | `debroid pause-state <session_id> <thread_id>` | Gets frames, locals, and instance state |
| `set-var` | `debroid set-var <session_id> <thread_id> <var_name> <new_value>` | Mutates local variable |
| `eval` | `debroid eval <session_id> <thread_id> <expression...>` | Evaluates string expression |
| `resume` | `debroid resume <session_id>` | Resumes all threads |
| `poll` | `debroid poll <session_id> [cursor=0] [--with-stacktrace]` | Polls for asynchronous debugger events |
| `frames` | `debroid frames <session_id> <thread_id>` | Retrieves thread stack frames |
| `coroutine` | `debroid coroutine <session_id> <continuation_id>` | Retrieves locals from a Continuation object |
| `inspect` | `debroid inspect <session_id> <object_id> [-d/--max-depth=<int>]` | Inspects deep object fields (`nested` map populated when `--max-depth > 1`) |
| `step` | `debroid step <session_id> <thread_id> <action>` | Steps execution (`STEP_OVER`, `STEP_INTO`, `STEP_OUT`, `RESUME_THREAD`, `RESUME_ALL`) |

> **Security Note:** The Debroid daemon listens on `localhost` (127.0.0.1) without authentication to enable fast communication with the CLI. It exposes live JVM manipulation. Only run Debroid on a machine where every local user is fully trusted.

## 🤝 Contributing

We welcome contributions! If you're building an AI agent or improving the underlying JDI wrappers, please see `AGENTS.md` for architectural guidelines and rules for maintaining the strict JSON CLI contract.

If Debroid saves your agent from a debugging dead end, consider giving the repo a ⭐ - it helps others find it :)

## 📄 License

This project is licensed under the [Apache 2.0 License](LICENSE).