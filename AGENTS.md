# AI Agent Contributor Guide 🤖

Welcome, fellow AI Agent! This document contains the context, motivation, and architectural guidelines you need to contribute to the **Debroid** repository effectively.

## 🎯 Intention & Motivation
The primary motivation behind Debroid is to bridge the gap between AI coding assistants and live Android environments. Traditional Android debugging relies heavily on the Android Studio GUI, making it nearly impossible for headless AI agents to set breakpoints, inspect runtime variables, or catch exceptions autonomously.

Debroid solves this by providing a lightweight, headless CLI wrapper around the Java Debug Wire Protocol (JDWP). It allows AI agents to orchestrate complex debugging sessions entirely through Standard I/O (terminal commands), empowering agents to solve deep runtime bugs in Android apps on behalf of users.

## 🏗️ Architecture & Layers
This project is structured into three main layers:

### 1. The `core` Layer (JDI & ADB Orchestration)
**Path:** `core/src/main/kotlin/dev/shreyaspatil/debroid/`
This is the heart of the debugger. It contains:
- **`AdbManager`**: Automates shell interactions with `adb` (finding PIDs, forwarding JDWP ports, launching suspended apps).
- **`JdiSession` & `JdiSessionManager`**: Handles the actual Java Debug Interface (JDI) connection to the Android Virtual Machine. It manages breakpoints, memory inspections, stepping, and thread state via raw JDWP packets.

*Note on `core`: This layer is completely isolated from the CLI and should only contain pure debugging and orchestration logic. All models returned here should be standard Kotlin data classes without serialization annotations.*

### 2. The `hot-reload` Layer (Incremental DEX Compiler & Native JVMTI Agent)
**Path:** `hot-reload/src/main/kotlin/dev/shreyaspatil/debroid/hotreload/` & `hot-reload/src/main/cpp/hotreload_agent.cpp`
This layer powers sub-second live code and Jetpack Compose UI hot-reloading (`debroid hot-reload`):
- **`HotReloadSession` & `SourceChangeTracker`**: Watches Gradle module source trees via `java.nio.file.WatchService` (with fallback polling), deduplicates uncompilable raw edits (`[COMPILE_ERROR]`), streams Compose and main-thread runtime exceptions (`[RUNTIME_ERROR]`), and orchestrates reload vs. fallback reinstall passes.
- **`IncrementalDexCompiler` & `AdditiveClassMerger`**: Runs incremental Gradle Kotlin/Java compilation, preserves removed methods/fields as bytecode stubs (`ASM` + post-`D8` DEX merging) with unique per-reload synthetic lambda markers, and invokes `D8` in-process (`InProcessD8Runner`) against pre-indexed AGP `project_dex_archive` baselines.
- **`JvmtiAgentInstaller` & `hotreload_agent.cpp`**: Batches ADB staging into the target app's private data directory, attaches `libdebroid_hotreload.so` via `cmd activity attach-agent`, executes JVMTI `RedefineClasses` / ART `structurally_redefine_classes`, injects new synthetic classes via `BaseDexClassLoader.addDexPath`, triggers scoped Compose `SlotTable` invalidation (`COMPOSE_HOT_RELOAD`) or `ActivityThread.scheduleRelaunchActivity` (`ACTIVITY_RECREATE`), and captures Compose recomposition errors (`HotReloaderKt.getCurrentCompositionErrors()` / `getRecomposerErrors()`).
- **Prebuilt Native Agent Binaries**: Whenever `hotreload_agent.cpp` is modified, recompile and strip both `arm64-v8a` (`aarch64-linux-android26-clang++`) and `x86_64` (`x86_64-linux-android26-clang++`) shared libraries into `hot-reload/src/main/resources/native/<abi>/libdebroid_hotreload.so` using the Android NDK toolchain (`-std=c++17 -O2 -fPIC -shared -static-libstdc++ -llog`).

### 3. The `cli` Layer (Daemon & Client)
**Path:** `cli/src/main/kotlin/dev/shreyaspatil/debroid/cli/`
This layer acts as the bridge between the AI Agent (the terminal) and the `core` and `hot-reload` layers.
- **Daemon Server**: Starts a persistent background socket server (`DaemonServer.kt`). This is necessary because JDI connections must be held open continuously.
- **CLI Commands**: Uses `Clikt` to parse terminal commands (e.g., `debroid attach`, `debroid poll`, `debroid hot-reload`).
- **Output Contracts**: Standard debugger commands emit strict JSON (defined in `CliModels.kt`), while `debroid hot-reload` streams single-line plain-text `[STATUS]` events (`HotReloadEventFormatter.kt`).

## 🧪 Local Maintainer Testing (`sample-app`)
To build and manually test changes against the included `sample-app` on a connected emulator or device:
```bash
# Build and install the local CLI distribution (or run ./install.sh to install ~/.local/bin/debroid)
./gradlew :cli:installDist

# Install and launch the sample app
./gradlew -p sample-app :app:installDebug
adb shell am start -n com.example.sampledebugapp/.MainActivity

# Run the locally built CLI against sample-app
./cli/build/install/cli/bin/cli hot-reload com.example.sampledebugapp -d sample-app
```

## 🛠️ How to Work on This Project & Critical Rules

All contribution guidelines, critical rules for maintaining schema contracts, and instructions on how to keep the AI Agent SKILL definitions and `CHANGELOG.md` up-to-date have been moved to a dedicated file to keep this document concise.

**You MUST read and follow the guidelines in [`CONTRIBUTING.md`](./CONTRIBUTING.md) before making any changes.**
