# Debroid 🤖⚡

**Debroid** (*DEBug + AnDROID*) is an autonomous, headless Android debugging CLI utility designed specifically for AI Agents (Claude Code, Codex, Cursor, OpenCode, Antigravity, etc.) to debug Android applications.

It uses a background Daemon and a lightweight CLI to allow AI coding assistants to set breakpoints, inspect runtime stack frames, mutate variables live in thread memory, catch uncaught exceptions, and step through code execution.


## 🛠️ Installation & Build

### Prerequisites
* Java JDK 21+ (`JAVA_HOME`)
* Android SDK & `adb`

### Install the CLI
To build the CLI and install it as a standalone executable in your `/usr/local/bin`, simply run:
```bash
./install.sh
```
*Note: This script will prompt for your `sudo` password to move the binary to your local bin.*

On Linux or if you prefer not to use `sudo`, you can install to a user-writable directory:
```bash
./gradlew :cli:jar
VERSION=$(cat cli/version.txt | tr -d '[:space:]')
# Create a self-extracting bash+jar executable
printf '#!/usr/bin/env bash\nexec java --enable-native-access=ALL-UNNAMED -jar "$0" "$@"\n' > ~/.local/bin/debroid
cat cli/build/libs/debroid-$VERSION.jar >> ~/.local/bin/debroid
chmod +x ~/.local/bin/debroid
```
Ensure `~/.local/bin` is on your `PATH`.

Once installed, you can use the CLI from anywhere:
```bash
debroid --help
```

## 🧠 AI Agent Skill Setup

Debroid is designed to be operated autonomously by AI agents (like Antigravity or Cursor). To teach your agent how to use Debroid effectively, you need to provide it with the skill instructions.

We have included a highly-optimized skill file at `skills/debroid-cli/SKILL.md`.

**To install the skill:**
Simply copy the contents of `skills/debroid-cli/SKILL.md` into your agent's context, prompt, or custom instructions/rules file. This gives the LLM explicit instructions on how to orchestrate the background daemon, set breakpoints, and poll the JDWP event queue without hallucinations or infinite loops.

## 🔌 How it Works

Debroid runs a lightweight daemon in the background that connects to the Android app via JDWP. The CLI communicates with this daemon to send commands and poll for events.

Agents can use the CLI to orchestrate the debug session. The daemon is auto-started on the first command if it isn't already running, so agents can also just start sending commands directly.

### Daemon Lifecycle
- **Auto-start**: The daemon starts automatically on the first CLI command if it isn't running.
- **Manual start**: `debroid daemon` (run in background or as a background task).
- **Stop**: `debroid stop` (shuts down daemon and detaches active debug sessions).

### Full Command List
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
| `resume` | `debroid resume <session_id> [thread_id]` | Resumes all threads |
| `poll` | `debroid poll <session_id> [cursor=0] [--with-stacktrace]` | Polls for asynchronous debugger events |
| `frames` | `debroid frames <session_id> <thread_id>` | Retrieves thread stack frames |
| `coroutine` | `debroid coroutine <session_id> <continuation_id>` | Retrieves locals from a Continuation object |
| `inspect` | `debroid inspect <session_id> <object_id> [-d/--max-depth=<int>]` | Inspects deep object fields (`nested` map populated when `--max-depth > 1`) |
| `step` | `debroid step <session_id> <thread_id> <action>` | Steps execution (`STEP_OVER`, `STEP_INTO`, `STEP_OUT`, `RESUME_THREAD`, `RESUME_ALL`) |

All command outputs are strict JSON for machine-readability.

## 📄 License
This project is licensed under the [Apache 2.0 License](LICENSE).