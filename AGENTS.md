# AI Agent Contributor Guide 🤖

Welcome, fellow AI Agent! This document contains the context, motivation, and architectural guidelines you need to contribute to the **Debroid** repository effectively.

## 🎯 Intention & Motivation
The primary motivation behind Debroid is to bridge the gap between AI coding assistants and live Android environments. Traditional Android debugging relies heavily on the Android Studio GUI, making it nearly impossible for headless AI agents to set breakpoints, inspect runtime variables, or catch exceptions autonomously.

Debroid solves this by providing a lightweight, headless CLI wrapper around the Java Debug Wire Protocol (JDWP). It allows AI agents to orchestrate complex debugging sessions entirely through Standard I/O (terminal commands), empowering agents to solve deep runtime bugs in Android apps on behalf of users.

## 🏗️ Architecture & Layers
This project is structured into two main layers:

### 1. The `core` Layer (JDI & ADB Orchestration)
**Path:** `core/src/main/kotlin/dev/shreyaspatil/debroid/`
This is the heart of the debugger. It contains:
- **`AdbManager`**: Automates shell interactions with `adb` (finding PIDs, forwarding JDWP ports, launching suspended apps).
- **`JdiSession` & `JdiSessionManager`**: Handles the actual Java Debug Interface (JDI) connection to the Android Virtual Machine. It manages breakpoints, memory inspections, stepping, and thread state via raw JDWP packets.

*Note on `core`: This layer is completely isolated from the CLI and should only contain pure debugging and orchestration logic. All models returned here should be standard Kotlin data classes without serialization annotations.*

### 2. The `cli` Layer (Daemon & Client)
**Path:** `cli/src/main/kotlin/dev/shreyaspatil/debroid/cli/`
This layer acts as the bridge between the AI Agent (the terminal) and the `core` layer.
- **Daemon Server**: Starts a persistent background socket server (`DaemonServer.kt`). This is necessary because JDI connections must be held open continuously.
- **CLI Commands**: Uses `Clikt` to parse terminal commands (e.g., `debroid attach`, `debroid poll`) and sends them as serialized IPC requests to the Daemon.
- **JSON Serialization**: All outputs emitted by the CLI must be strict JSON (defined in `CliModels.kt`). This ensures the AI Agent consuming the CLI output can parse the responses safely without regex.

## 🛠️ How to Work on This Project & Critical Rules

All contribution guidelines, critical rules for maintaining schema contracts, and instructions on how to keep the AI Agent SKILL definitions and `CHANGELOG.md` up-to-date have been moved to a dedicated file to keep this document concise.

**You MUST read and follow the guidelines in [`CONTRIBUTING.md`](./CONTRIBUTING.md) before making any changes.**
