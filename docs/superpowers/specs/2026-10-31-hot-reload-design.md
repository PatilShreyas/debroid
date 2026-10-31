# Debroid Hot-Reload (`debroid hot-reload`) Design Specification

**Date:** 2026-10-31  
**Status:** Draft for Review  
**Modules:** `:hot-reload` (new), `:cli`, `:core`

---

## 1. Overview & Motivation

AI agents and developers iterating on Android UI (Jetpack Compose or classic Android Views) and Kotlin/Java application logic need fast, automatic runtime feedback without waiting for full `:app:installDebug` cycles or losing runtime navigation/UI state.

`debroid hot-reload <app_id>` introduces an Android Studio "Apply Code Changes / Live Edit"-style hot-reload pipeline designed specifically for headless CLI and AI agent workflows:
- Runs as a foreground streaming command (commonly launched by an AI agent as a background shell task).
- Monitors all modules in a single- or multi-module Android Gradle project every 1 second.
- Compiles only the modified Gradle module(s), converts changed `.class` files to `.dex` via Android SDK `d8`, and injects them into the running application via a native **JVMTI Agent (`cmd activity attach-agent`)**.
- Preserves the JDWP debugging port completely free so `debroid` debugging sessions (`attach`, `break`, `inspect`) can run concurrently.
- Immediately triggers Jetpack Compose recomposition via `androidx.compose.runtime.HotReloader` (or `Activity.recreate()` fallback for classic Views) on the main thread.
- Emits a fixed, single-line plain-text `[STATUS]` stream optimized for minimal token usage by AI agents.

---

## 2. Module Architecture

To preserve Single Responsibility and keep `:core` focused on JDWP/JDI debugging and ADB primitives, all hot-reload functionality lives in a dedicated **`:hot-reload`** Gradle module:

```text
debroid/
├── core/                  # Existing JDI debugger & AdbManager / CommandRunner
├── hot-reload/            # NEW: Dedicated hot-reload engine & native JVMTI agent
│   ├── src/main/cpp/      # C++ JVMTI agent (libdebroid_hotreload.so for arm64-v8a & x86_64)
│   ├── src/main/resources/# Bundled prebuilt/NDK-built .so agents per ABI
│   └── src/main/kotlin/dev/shreyaspatil/debroid/hotreload/
│       ├── GradleProjectScanner.kt
│       ├── SourceChangeTracker.kt
│       ├── IncrementalDexCompiler.kt
│       ├── JvmtiAgentInstaller.kt
│       ├── HotReloadSession.kt
│       └── HotReloadModels.kt
└── cli/                   # Exposes `debroid hot-reload` via HotReloadCommand
```

### Module Dependencies
- `:hot-reload` depends on `:core` (reusing `AdbManager`, `CommandRunner`, `DefaultCommandRunner`, `DebugException`).
- `:cli` depends on `:core` and `:hot-reload`.

---

## 3. CLI Interface & Plain-Text Streaming Format

### Command Signature
```bash
debroid hot-reload <app_id> [options]
```

| Argument / Option | Default | Description |
| :--- | :--- | :--- |
| `<app_id>` | *(Required)* | Target Android application package name (e.g., `com.example.sampledebugapp`). |
| `--project-dir`, `-d` | `.` | Root directory of the Android Gradle project. |
| `--variant`, `-v` | `debug` | Build variant to compile (e.g., `debug`, `freeDebug`). |
| `--interval-ms` | `1000` | Polling interval in milliseconds for checking source file changes. |
| `--once` | `false` | Perform a single change-check / hot-reload pass and exit. |
| `--no-fallback` | `false` | Disable automatic fallback to full `installDebug` + Activity restart when structural or resource changes cannot be hot-swapped in memory. |

### Fixed Plain-Text Output Contract
Unlike JDWP debugger commands in `:cli`, `debroid hot-reload` outputs deterministic, single-line plain-text status messages flushed immediately to `stdout`:

```text
[WATCHING] app=com.example.sampledebugapp pid=14230 modules=42 interval=1000ms
[RELOADING] modules=[:feature:home] files=[HomeScreen.kt]
[RELOADED] mode=JVMTI_HOT_SWAP classes=2 new_classes=1 ui=COMPOSE_HOT_RELOAD duration=840ms
[RELOADED] mode=JVMTI_STRUCTURAL_SWAP classes=1 new_classes=0 ui=COMPOSE_HOT_RELOAD duration=910ms
[COMPILE_ERROR] module=:feature:home file=HomeScreen.kt:42:17 Unresolved reference 'foo'
[FALLBACK_REINSTALL] reason=RESOURCE_CHANGE files=[activity_main.xml] duration=4120ms
[ERROR] code=APP_NOT_RUNNING message=Process for com.example.sampledebugapp is not running
[STOPPED] app=com.example.sampledebugapp
```

---

## 4. Multi-Module Change Detection & Compilation Pipeline

### 4.1 `GradleProjectScanner` (Multi-Module Discovery)
1. **Root Discovery**: Locates `settings.gradle.kts` or `settings.gradle` from `--project-dir` (walking up parent directories if `--project-dir` points to a subdirectory).
2. **Module Indexing**:
   - Scans the project tree for directories containing `build.gradle.kts` or `build.gradle`, strictly skipping `.git`, `.gradle`, `build`, `.idea`, and `.kotlin` directories.
   - Parses custom `project(":foo").projectDir = file("...")` mappings from `settings.gradle(.kts)` when present.
   - Classifies each module's type by inspecting its build script plugins:
     - `ANDROID_APP` (`com.android.application` or convention equivalent)
     - `ANDROID_LIBRARY` (`com.android.library` or convention equivalent)
     - `KOTLIN_JVM` (`org.jetbrains.kotlin.jvm` / `kotlin("jvm")`)
3. **O(1) File-to-Module Resolution**:
   - Maps any modified source file `<moduleDir>/src/<sourceSet>/<lang>/...` to its owning `GradleModule` via longest-prefix directory match.

### 4.2 `SourceChangeTracker` (1-Second Scoped Polling)
- Watches only `<moduleDir>/src/` directories across discovered modules (never scanning `build/` or `.gradle/`).
- Tracks `.kt`, `.java`, and `res/**/*.xml` + `AndroidManifest.xml` files.
- Uses a two-stage check every `intervalMs` (default `1000ms`):
  1. Compares file `lastModified` (`mtime`) and byte size against the in-memory baseline snapshot.
  2. When `mtime` or size differs, computes SHA-256 content hash to confirm a real content edit and avoid false triggers from file touch events.
- Categorizes detected changes into:
  - `CODE_CHANGES` (`.kt`, `.java`) grouped by `GradleModule`.
  - `RESOURCE_OR_MANIFEST_CHANGES` (`res/**/*.xml`, `AndroidManifest.xml`).

### 4.3 `IncrementalDexCompiler` (Leaf-Module Compile + Delta `d8`)
When `CODE_CHANGES` are detected in one or more modules `[M_1, M_2, ...]`:
1. **Pre-Compile Class Snapshot**:
   - For each dirty module, records the existing `.class` file paths and SHA-256 hashes under its compiled output directories:
     - Android modules: `<moduleDir>/build/tmp/kotlin-classes/<variant>/`, `<moduleDir>/build/intermediates/kotlin-classes/<variant>/`, `<moduleDir>/build/intermediates/javac/<variant>/classes/`
     - JVM modules: `<moduleDir>/build/classes/kotlin/main/`, `<moduleDir>/build/classes/java/main/`
2. **Targeted Gradle Compilation**:
   - Determines the exact compile task per dirty module:
     - Android module: `:<modulePath>:compile<VariantCapitalized>Kotlin` (plus `compile<VariantCapitalized>JavaWithJavac` if `.java` files changed).
     - JVM module: `:<modulePath>:compileKotlin` (plus `compileJava` if `.java` files changed).
   - Executes a single `./gradlew <tasks...> --console=plain` invocation against the project's Gradle wrapper (`gradlew`).
   - Because only leaf/dirty module compile tasks are invoked, Gradle skips configuring or packaging `:app` and downstream modules.
3. **Post-Compile Bytecode Diff**:
   - Diffs `.class` files in the dirty modules' output directories against the pre-compile snapshot (and also picks up any `.class` files matching the modified source file's base name and `$` inner/lambda classes if modified during baseline startup).
   - Collects all changed/added `.class` files (ignoring `META-INF/` and `*.kotlin_module`).
4. **Delta DEX Packaging via `d8`**:
   - Locates `d8` from `$ANDROID_HOME/build-tools/<latest>/d8` (or `~/Library/Android/sdk/build-tools/<latest>/d8`).
   - Invokes `d8 --release --file-per-class --output <stagingDir> <changed.class...>` (or packages each `.class` into its own single-class `.dex` for JVMTI `RedefineClasses` plus a combined `new_classes.dex` for `BaseDexClassLoader` injection).

---

## 5. On-Device JVMTI Native Agent (`libdebroid_hotreload.so`)

### 5.1 Agent Delivery & Invocation
1. `JvmtiAgentInstaller` detects the target device ABI (`adb shell getprop ro.product.cpu.abi` -> `arm64-v8a` or `x86_64`).
2. Extracts the corresponding `libdebroid_hotreload.so` from `:hot-reload` classpath resources.
3. Copies the `.so`, single-class `.dex` files, `new_classes.dex`, and a plain-text `manifest.txt` to `/data/local/tmp/debroid_hr_<appId>/` via `adb push`, then copies them into `/data/data/<appId>/code_cache/debroid_hr/` using `adb shell run-as <appId>`.
4. Triggers the agent inside the running app process via:
   ```bash
   adb shell cmd activity attach-agent <appId> /data/data/<appId>/code_cache/debroid_hr/libdebroid_hotreload.so=/data/data/<appId>/code_cache/debroid_hr/manifest.txt
   ```

### 5.2 Native Agent Execution (`Agent_OnAttach`)
Inside `hotreload_agent.cpp`, `Agent_OnAttach(JavaVM* vm, char* options, void* reserved)` executes:
1. **Acquire Environments**: Attaches current thread via `vm->GetEnv` / `AttachCurrentThread` to obtain `JNIEnv*` and creates a `jvmtiEnv*` with `can_redefine_classes = 1`.
2. **Resolve App ClassLoader**:
   - Calls `android.app.ActivityThread.currentApplication()` via JNI and retrieves its `ClassLoader` (`dalvik.system.BaseDexClassLoader`).
3. **Partition & Inject New / Unloaded Classes**:
   - For each class listed in `manifest.txt`, checks if the class is already loaded in the app's `ClassLoader` via `ClassLoader.findLoadedClass(binaryName)`.
   - If any class in the delta is **not yet loaded** (such as newly generated Kotlin/Compose lambda classes `MainScreenKt$MainScreen$2$1` or new helper classes), calls `BaseDexClassLoader.addDexPath(newClassesDexPath)` via JNI so the app's classloader resolves the new bytecode immediately.
4. **In-Memory Redefinition of Loaded Classes**:
   - For each **already-loaded** class:
     - Calls standard `jvmti->RedefineClasses(1, &classDef)` with its single-class `.dex` bytes.
     - If `RedefineClasses` returns a structural error (`JVMTI_ERROR_UNSUPPORTED_REDEFINITION_METHOD_ADDED`, `SCHEMA_CHANGED`, `METHOD_DELETED`, `UNMODIFIABLE_CLASS`), queries `jvmti->GetExtensionFunctions` for `com.android.art.class.structural_redefine_classes`, enables capability `com.android.art.can_redefine_classes_structurally` if available, and invokes structural redefinition.
     - If structural redefinition also fails (e.g., incompatible superclass change or active frame constraint), records `STRUCTURAL_INCOMPATIBLE` in `result.txt` so the host can trigger the fallback installer.
5. **Main-Thread UI Invalidation**:
   - Posts a `Runnable` (or JNI callback) to `android.os.Handler(Looper.getMainLooper())`:
     - **Jetpack Compose (`COMPOSE_HOT_RELOAD`)**: Looks up `androidx.compose.runtime.HotReloader$Companion` via `appClassLoader.loadClass(...)` and invokes `simulateHotReload(application)` (falling back to `Recomposer$Companion.loadStateAndComposeForHotReload` if needed). This forces all active `@Composable` groups to recompose with the new code while preserving `remember` state.
     - **Activity Recreate Fallback (`ACTIVITY_RECREATE`)**: If Compose `HotReloader` is not present in the app, iterates active activities in `ActivityThread.mActivities` and calls `activity.recreate()` on resumed activities.
6. **Result Handshake**:
   - Writes `result.txt` in `/data/data/<appId>/code_cache/debroid_hr/` containing `status=OK mode=<JVMTI_HOT_SWAP|JVMTI_STRUCTURAL_SWAP> redefined=<N> new=<M> ui=<COMPOSE_HOT_RELOAD|ACTIVITY_RECREATE>` or `status=ERR code=<CODE> msg=<DETAIL>`.

---

## 6. Error Handling & Fallback Flow

1. **Kotlin / Java Compilation Errors**:
   - When `./gradlew <compileTask>` exits non-zero, `IncrementalDexCompiler` parses compiler error lines (`e: ...`) and emits `[COMPILE_ERROR]`.
   - The watcher does **not** update its baseline hashes for the failed files, wait-coalesces until the file is edited again, and automatically retries on the next save.
2. **Automatic Fallback (`[FALLBACK_REINSTALL]`)**:
   - Triggered when `RESOURCE_OR_MANIFEST_CHANGES` are detected or when the JVMTI agent returns `STRUCTURAL_INCOMPATIBLE` (unless `--no-fallback` is set).
   - Runs `./gradlew :<appModule>:install<VariantCapitalized>` and restarts the main launcher Activity via `AdbManager`.
3. **Target Process Monitoring**:
   - Before pushing a reload, verifies `adbManager.findPid(appId)`. If the app is not running, emits `[ERROR] code=APP_NOT_RUNNING`. If the PID changed (e.g., app restarted), logs the new PID and re-attaches seamlessly.

---

## 7. Testing Strategy

1. **`GradleProjectScannerTest`**:
   - Tests single-module and nested multi-module layouts (`:app`, `:feature:home:ui`, `:core:designsystem`), custom `settings.gradle.kts` `projectDir` overrides, and module type detection.
2. **`SourceChangeTrackerTest`**:
   - Verifies 1-second change detection across multiple modules, SHA-256 deduplication on touch without content change, classification of `.kt`/`.java` vs `res/` XML changes, and exclusion of `build/` and `.gradle/`.
3. **`IncrementalDexCompilerTest`**:
   - Verifies Gradle task resolution per module type/variant, `.class` output diffing (including `$` inner/lambda classes), compiler error parsing, and `d8` command invocation.
4. **`HotReloadSessionTest` & `CliRunnerTest`**:
   - Verifies `adb` staging commands, `attach-agent` invocation, `result.txt` parsing, fallback execution, `--once` flag behavior, and exact `[STATUS]` plain-text output lines.
5. **Live Integration Verification**:
   - End-to-end verification on `sample-app` running on an Android device/emulator: modifying `MainScreen.kt` Compose text and lambdas and confirming instant in-place UI updates via `debroid hot-reload com.example.sampledebugapp`.
