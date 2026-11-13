# Live Code & Compose Hot Reload (`debroid hot-reload`)

Instructions for AI agents using `debroid hot-reload` to apply and verify Kotlin/Java logic and Jetpack Compose UI changes on a running Android application without restarting the app.

## Command Signature & Options

```bash
debroid hot-reload <app_id> [-d/--project-dir <path>] [-v/--variant <variant>] [--interval-ms <ms>] [--once] [--no-fallback]
```

| Option | Default | Description |
| :--- | :--- | :--- |
| `<app_id>` | *(Required)* | Target Android application package ID (e.g. `com.example.app`). The app must be running and debuggable (`android:debuggable="true"`). |
| `-d, --project-dir <path>` | `.` | Root directory of the Android Gradle project (containing `settings.gradle` or `settings.gradle.kts`). |
| `-v, --variant <variant>` | `debug` *(auto-detected from `<app_id>` when flavored)* | Build variant to compile and watch (e.g. `debug`, `demoDebug`, `freeDebug`). Automatically inferred from `<app_id>` and build metadata when left as default. |
| `--interval-ms <ms>` | `300` | File polling interval in milliseconds (minimum `100`). |
| `--once` | `false` | Synchronize with an active background watcher for `<app_id>` (flushing pending edits immediately and returning the `[STATUS]` result), or perform a single reload pass and exit if no watcher is running. |
| `--no-fallback` | `false` | Disable automatic fallback to full APK installation (`install<Variant>`) when XML resources, `AndroidManifest.xml`, or incompatible class hierarchy changes occur. |

---

## Agent Workflow

Always keep a **single continuous `debroid hot-reload` watcher** running in the background for the duration of your task so Gradle module metadata, baseline `.class`/`.dex` snapshots, and the in-process `D8` compiler stay warm in memory.

### Choosing How to Read Reload Events (Based on Your Harness)

| Harness Capability | Recommended Mode | How It Works |
| :--- | :--- | :--- |
| **Has live background stream monitoring** *(e.g. Claude Code `Monitor` / live background task output, interactive terminals)* | **Continuous Watcher Stream (Preferred)** | Start `debroid hot-reload <app_id> -d <project_dir>` in the background and monitor its streamed stdout directly. **Do not run `--once` after edits** — saving a file automatically triggers the reload and streams `[RELOADED]` / `[COMPILE_ERROR]` / `[RUNTIME_ERROR]` with zero extra commands. |
| **No live stream monitoring** *(background tasks only notify on exit; `sleep`-polling log files is undesirable)* | **Background Watcher + `--once` Sync Barrier** | Start `debroid hot-reload <app_id> -d <project_dir>` in the background once, then after saving edits run `debroid hot-reload <app_id> -d <project_dir> --once` in the foreground to flush and block until the active watcher finishes its reload pass (instead of `sleep` + log scraping). |

> ⚠️ **Never invoke `--once` standalone without a running background watcher during iteration:** Without an active watcher, `--once` cannot hold a pre-edit baseline or warm compiler state across edits.

### Step 1: Ensure the App is Running on the Target Screen
1. Check if the process is running (`adb shell pidof <app_id>`). If not, launch it:
   ```bash
   adb shell am start -n <app_id>/<MainActivity>
   ```
   *(If the APK is not yet installed or is out of sync with the workspace, run `./gradlew :<app-module>:installDebug` once before launching).*
2. Navigate to the target screen and populate any necessary state (e.g. text inputs, list items, `ViewModel` state) so edits are verified against active state.

### Step 2: Start the Continuous Watcher in the Background
```bash
debroid hot-reload <app_id> -d <project_dir>
```
Wait for `[WATCHING]` in the output before editing files:
```text
[WATCHING] app=<app_id> pid=<pid> modules=<count> interval=300ms
```
> ⚠️ Do not edit source files before `[WATCHING]` is printed; the startup scan snapshots current files as the baseline.

### Step 3: Edit, Observe `[STATUS]`, and Verify
1. **Save changes** to the target `.kt` or `.java` file(s). Multiple rapid/parallel edits are automatically coalesced into a single reload pass.
2. **Wait for the completion `[STATUS]` event**:
   - **If your harness supports live background monitoring (Preferred):** Simply wait for the monitored background watcher stream to emit `[RELOADED]` (or `[COMPILE_ERROR]` / `[RUNTIME_ERROR]`) — no extra CLI command needed:
     - `[RELOADING] modules=[:app] files=[MyScreen.kt]`
     - `[RELOADED] mode=JVMTI_HOT_SWAP classes=2 new_classes=8 ui=COMPOSE_HOT_RELOAD duration=...ms`
   - **Only if your harness cannot monitor live background streams:** Use `--once` as a synchronous barrier against the running background watcher instead of guessing `sleep` durations:
     ```bash
     debroid hot-reload <app_id> -d <project_dir> --once && android layout --pretty
     ```
   - *(Note: If an edit only touched comments, imports, formatting, or reverted a `[COMPILE_ERROR]` back to the prior working code, `debroid` still emits `[RELOADING]` followed by `[RELOADED] mode=JVMTI_HOT_SWAP classes=0 new_classes=0 ui=NONE duration=...ms` so every save pairs 1-to-1 with a completion event).*
3. **Verify the updated behavior on device**:
   - Visual UI check: `android screen capture -o screen.png` or `adb exec-out screencap -p > screen.png`.
   - Hierarchy/bounds check: `android layout --pretty` or `adb shell uiautomator dump`.
   - Event-handler check: If you modified an action method (e.g. a button click handler or `ViewModel` function), trigger the interaction (`adb shell input tap X Y`) to execute the new logic.
4. Stop the background watcher task once all iterations are complete.

---

## Status Events Reference

`debroid hot-reload` streams single-line plain-text events to `stdout`:

| Event | Format | Agent Action |
| :--- | :--- | :--- |
| `[WATCHING]` | `[WATCHING] app=<appId> pid=<pid> modules=<count> interval=<ms>ms` | Baseline recorded. Begin editing source files. |
| `[RELOADING]` | `[RELOADING] modules=[<modules>] files=[<files>]` | Modification detected; compilation in progress. Wait for completion event. |
| `[RELOADED]` | `[RELOADED] mode=<mode> classes=<count> new_classes=<count> ui=<uiRefresh> duration=<ms>ms` | Bytecode swapped and UI refreshed. Proceed with verification. |
| `[COMPILE_ERROR]` | `[COMPILE_ERROR] module=<module> <diagnostic>` | Compilation failed. **Watcher stays running** and ignores the broken file until modified again. Fix the compiler error and save. |
| `[RUNTIME_ERROR]` | `[RUNTIME_ERROR] <Exception>: <message> (<File.kt:line>)` | The hot-reloaded code threw an exception during Compose recomposition or on the main thread. Fix the exception at the reported file/line and save to recover. |
| `[FALLBACK_REINSTALL]` | `[FALLBACK_REINSTALL] reason=<reason> files=[<files>] duration=<ms>ms` | Change required a full Gradle `install<Variant>` and Activity restart (performed automatically). Note that in-memory state was reset. |
| `[ERROR]` | `[ERROR] code=<code> message=<msg>` | Runtime or connection failure. See Troubleshooting below. |
| `[STOPPED]` | `[STOPPED] app=<appId>` | Watcher exited cleanly. |

### `[RELOADED]` Fields
- `mode=JVMTI_HOT_SWAP`: Existing method/composable bodies were updated in-place.
- `mode=JVMTI_STRUCTURAL_SWAP`: Structural additions (new methods, fields, properties, or lambdas) were applied in-place.
- `ui=COMPOSE_HOT_RELOAD`: Affected Jetpack Compose scopes were invalidated and recomposed on the main thread while preserving state.
- `ui=ACTIVITY_RECREATE`: An `Activity` or `android.view.View` subclass was modified (or no Compose compositions were active), triggering Activity recreation on the main thread.

---

## Supported Changes & State Behavior

### 1. Jetpack Compose (`@Composable` functions)
- **Supported:** Any layout container (`Column`, `Row`, `Box`, `Surface`, `Scaffold`, `Card`, `LazyColumn`, etc.), modifiers, styling, gradients, animations (`rememberInfiniteTransition`, `animate*AsState`, `AnimatedVisibility`), and event lambdas.
- **`remember` State:** `remember { ... }` state is preserved across reloads when a composable's `remember` calls are unchanged. If you add, remove, or reorder `remember` calls inside a `@Composable` function, `debroid` automatically resets the `remember` slots for that specific composable while preserving state in all other composables on screen.
- **Multi-Screen Navigation:** Only the Compose scopes in the modified classes are invalidated. Editing a child screen updates it in-place without resetting `NavHost` / `rememberNavBackStack` or navigation arguments.

### 2. `ViewModel`, `Repository`, & Domain Classes
- **Methods & Event Handlers:** Updated in-place on **existing instances** in memory. Existing `MutableStateFlow` and field values are preserved, and the next call to a modified method immediately executes the new code.
- **New Methods, Fields, & Lambdas:** Adding new helper methods, properties, or `viewModelScope.launch { ... }` blocks is supported in-place.
- **Static & Top-Level Properties:** Changes to `const val` constants and static top-level / `companion object` / `object` property initializers are automatically updated in memory while keeping unchanged static state intact.
- **Cross-Module & Composite (`includeBuild`) Edits:** Modifying method signatures, `inline` functions, or `const val` values in a library or `includeBuild` module automatically recompiles dependent modules and reloads all affected call sites together.
- **Computed Getters (`val x: String get() = ...`):** Re-evaluated automatically during the Compose recomposition triggered at the end of the reload.
- **Constructors, `init {}` Blocks, & `StateFlow` Pipelines on `ViewModel`s:** Editing a `ViewModel` subclass, an inner coroutine/`Flow` lambda, or a helper in the same `*ViewModel.kt` file automatically evicts active instances from Activity, Fragment, and Jetpack Navigation `ViewModelStore`s so a fresh `ViewModel` is constructed with the updated `<init>()` / `StateFlow` pipeline. (Non-`ViewModel` singletons do not re-run `init {}` on already-constructed instances).
- **`interface` & `sealed interface` Default Methods:** Editing default methods or properties on Kotlin `interface` / `sealed interface` types hot-reloads in-place without triggering `JVMTI_ERROR_79` fallback reinstalls.

### 3. `Activity`, `Fragment`, & Android `View` Subclasses (Including Hilt `@AndroidEntryPoint`)
- Modifying an `Activity` or `android.view.View` subclass (including classes annotated with Hilt's `@AndroidEntryPoint` or `@HiltAndroidApp`) hot-reloads in-place and triggers `ui=ACTIVITY_RECREATE`, recreating the active `Activity` through Android's standard saved-instance-state lifecycle without reinstalling the APK.

### 4. Resources (`res/**`) & `AndroidManifest.xml`
- XML resource and manifest edits cannot be hot-swapped via JVMTI and automatically trigger `[FALLBACK_REINSTALL]` (unless `--no-fallback` is set).

---

## Dos, Don'ts, & Gotchas

- **DO focus strictly on editing source files, reading `[STATUS]` events, and verifying the app UI.** All compilation, variant resolution, DEX merging, and runtime bytecode swapping are handled automatically by the CLI.
- **DO NOT run `javap`, `dexdump`, or inspect `.class` / `.dex` / `build/` internals.** Never disassemble bytecode or modify Gradle build logic (such as JaCoCo or compiler flags) to work around hot-reload behavior.
- **DO keep a single watcher running** across edits instead of starting and stopping it per edit. If the target app crashes or is restarted mid-session while the watcher is running, `debroid` automatically detects the new PID (`[WATCHING] ... pid=<newPid>`) and reapplies all session edits onto the restarted process from cached compiler outputs without requiring a Gradle rebuild or reinstall. (To bake edits permanently into the installed APK after stopping the watcher, run `./gradlew :<app-module>:install<Variant>`).
- **DO NOT restart the watcher on `[COMPILE_ERROR]`:** The watcher deduplicates failed builds and keeps its baseline anchored to the last successful reload. Fixing or reverting the syntax/type error and saving will always emit `[RELOADING]` followed by `[RELOADED]`.
- **DO NOT run `./gradlew install<Variant>` or `adb shell am force-stop` unnecessarily while watching:** Killing the app PID resets in-memory UI/`ViewModel` state (though an active watcher will automatically reapply your code edits when the app relaunches).
- **Automatic `remember` state reset on structural `remember` edits:** When a `@Composable` function's `remember` calls are unchanged, its remembered values are preserved across hot reloads. When `remember` calls in a composable are added, removed, or reordered, `debroid` automatically resets the `remember` slots for that composable so new initial values take effect cleanly without affecting other composables.
- **New fields on existing instances:** When adding a new field/property to a class that already has live instances in memory, that field reads as its default zero/null value (`0`, `false`, `null`) on pre-existing instances until a new instance is constructed.
- **Pre-flight class hierarchy checks:** Changing a named class's superclass, implemented interfaces (e.g. adding `: Serializable`), class kind/modifiers, or `interface` method signatures is detected immediately on the host after incremental compilation (before `D8` or device staging) and triggers `[FALLBACK_REINSTALL]` with a descriptive reason such as `INTERFACE_HIERARCHY_CHANGED:<Class>` or `SUPERCLASS_CHANGED:<Class>` (or `[ERROR] code=FALLBACK_DISABLED` when `--no-fallback` is enabled).

---

## Troubleshooting

| Symptom / Output | Cause | Fix |
| :--- | :--- | :--- |
| `[ERROR] code=APP_NOT_RUNNING` | Target app process is not running or crashed. | Start the app (`adb shell am start -n <app_id>/<Activity>`) and restart `debroid hot-reload`. |
| `[COMPILE_ERROR] module=...` | Compiler error in modified source file. | Fix the error in the source file and save; the active watcher will automatically retry. |
| `[RUNTIME_ERROR] <Exception>: ...` | Hot-reloaded code threw a runtime exception during Compose recomposition or main-thread execution. | Inspect the reported exception message and `(<File.kt:line>)`, fix the bug in the source file, and save; Compose recomposition errors recover in-place on the next reload. |
| File saved, but no `[RELOADING]` event | File was saved before `[WATCHING]` printed, or `-d` points to the wrong directory. | Confirm `-d` points to the Gradle project root and save the file again after `[WATCHING]` is visible. |
| `ViewModel` method updated and `[RELOADED]` printed, but UI still shows old state | The modified code is inside an action method that hasn't been invoked since the reload. | Trigger the UI action (e.g. `adb shell input tap X Y`) so the `ViewModel` runs the new method and emits updated state. |
| `[ERROR] code=RELOAD_FAILED` (with `--no-fallback`) | Edit changed class inheritance/interfaces, or the installed APK on device does not match the local Gradle build baseline. | Remove `--no-fallback`, or run `./gradlew :<app-module>:installDebug` once, relaunch the app, and restart `debroid hot-reload`. |
