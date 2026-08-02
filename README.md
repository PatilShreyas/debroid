# Debroid 🤖⚡

**Debroid** (*DEBug + AnDROID*) is an autonomous, headless Android debugging CLI utility designed specifically for AI Agents (Antigravity, Cursor, etc.) to debug Android applications.

It uses a background Daemon and a lightweight CLI to allow AI coding assistants to set breakpoints, inspect runtime stack frames, mutate variables live in thread memory, catch uncaught exceptions, and step through code execution.

## Features ✨

* **JDWP & ADB Orchestration:** Automated process discovery, port forwarding, and JDI socket attachment.
* **Smart Context Bounds:** Shallow variable inspection and paginated object property drilling to prevent LLM context window bloat.
* **Live In-Memory Mutation:** Change variable values in thread stack memory on the fly.
* **Exception Traps & Watchpoints:** Catch uncaught exceptions and pause execution on field modifications.
* **Kotlin Coroutines Support:** Extract local state from compiler-generated `$continuation` objects.

## 🛠️ Installation & Build

### Prerequisites
* Java JDK 21+ (`JAVA_HOME`)
* Android SDK & `adb`

### Build CLI
```bash
./gradlew installDist
```

*Note: You can run the CLI from `./cli/build/install/cli/bin/cli` or by using Gradle:*
```bash
./gradlew run --args="--help"
```

## 🔌 How it Works

Debroid runs a lightweight daemon in the background that connects to the Android app via JDWP. The CLI communicates with this daemon to send commands and poll for events. 

Agents can use the CLI to orchestrate the debug session (e.g., `debroid launch`, `debroid attach`, `debroid breakpoint`, `debroid locals`, `debroid poll`).

## 📄 License
This project is licensed under the [Apache 2.0 License](LICENSE).
