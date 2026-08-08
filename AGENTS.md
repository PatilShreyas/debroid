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

## 🛠️ How to Work on This Project

### 1. Build, Test, and Lint Before Committing
Whenever you make a change, verify that the project compiles, static analysis passes, and all tests pass:
```bash
# Run static analysis (Detekt)
./gradlew detekt

# Run tests
./gradlew test

# Build the CLI
./gradlew :cli:build
```

### 2. Strict JSON Output
When modifying the CLI layer, **never** use raw `println("some string")`. All CLI outputs intended for the user/agent must be printed as serialized JSON via `kotlinx.serialization` to maintain machine-readability.

### 3. State Management & Memory Leaks
The `core` layer handles live VM connections. Always ensure that:
- ADB ports are un-forwarded upon disconnect.
- Event queues (like in `JdiSession`) are bounded to prevent OutOfMemory errors during long-running polls.
- Exceptions thrown by the Android VM (like `AbsentInformationException` or `IncompatibleThreadStateException`) are caught and handled gracefully without crashing the daemon.

## 🚨 CRITICAL RULE: Keeping Skills and Documentation in Sync
We maintain an AI Agent Skill document at **`skills/debroid-cli/SKILL.md`**. This document teaches *other* AI agents how to use the Debroid CLI. We also maintain a command reference in the project's **`README.md`**.

**Whenever you:**
1. Add a new CLI command.
2. Change the arguments or signature of an existing CLI command.
3. Modify the JSON output structure.

**You MUST:**
1. Update the `skills/debroid-cli/SKILL.md` file to reflect the new command signatures, usage examples, and JSON parsing rules. Keeping the skill file synchronized with the CLI is mandatory to ensure AI agents using Debroid do not break!
2. Update the `README.md` file's Command Reference section to include the new or updated command signature and description.

## 🚨 CRITICAL RULE: Changelog Maintenance
We maintain a `CHANGELOG.md` in the root of the project to track all notable changes. 

**Whenever you:**
1. Make any source code related change.
2. Make a tooling related change.
3. Make a SKILL related change.
4. Raise a Pull Request.

**You MUST:**
1. Add an entry describing your changes to the `## [UNRELEASED]` section of `CHANGELOG.md`.
2. Categorize the entry under the appropriate subsection (e.g., `### Added`, `### Fixed`, `### Changed`).
3. If your change fixes a GitHub issue, mention the issue number in the changelog entry.

## 🚨 CRITICAL RULE: Schema Contracts & Golden Tests
All CLI JSON response models are guarded by Golden Schema tests in `JsonSchemaGoldenTest` (`cli/src/test/resources/golden-schemas/*.schema.json`) to prevent unintended breaking changes for downstream AI agents.

**Whenever you:**
1. **Modify an existing response model (`CliModels.kt`)**:
   - Run `./gradlew test -DupdateGoldenSchemas=true` to update the `.schema.json` files.
   - Verify the diff in `cli/src/test/resources/golden-schemas/` to ensure no unintended field breakages occurred, and commit the updated `.schema.json` files.
2. **Add a NEW CLI command or response model**:
   - Pass the model's `KSerializer` (e.g. `serializer = CliNewResult.serializer()`) to `BaseJsonCommand`.
   - Add a corresponding test method in `JsonSchemaGoldenTest.kt` (e.g. `assertGoldenSchema("new-model.schema.json", CliNewResult.serializer())`).
   - Run `./gradlew test -DupdateGoldenSchemas=true` to generate the initial golden schema file and commit it.

