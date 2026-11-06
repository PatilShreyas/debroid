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
| `--once` | `false` | Perform a single check/reload pass for modified files and exit immediately. |
| `--no-fallback` | `false` | Disable automatic fallback to full APK installation (`install<Variant>`) when XML resources, `AndroidManifest.xml`, or incompatible class hierarchy changes occur. |

---

## Agent Workflow

When making multiple edits across a task, run `debroid hot-reload` continuously as a background task rather than invoking `--once` repeatedly.

### Step 1: Ensure the App is Running on the Target Screen
1. Check if the process is running (`adb shell pidof <app_id>`). If not, launch it:
   ```bash
   adb shell am start -n <app_id>/<MainActivity>
   ```
   *(If the APK is not yet installed or is out of sync with the workspace, run `./gradlew :<app-module>:installDebug` once before launching).*
2. Navigate to the target screen and populate any necessary state (e.g. text inputs, list items, `ViewModel` state) so edits are verified against active state.

### Step 2: Start the Watcher in the Background
```bash
debroid hot-reload <app_id> -d <project_dir>
```
Wait for `[WATCHING]` in the output before editing files:
```text
[WATCHING] app=<app_id> pid=<pid> modules=<count> interval=300ms
```
> ⚠️ Do not edit source files before `[WATCHING]` is printed; the startup scan snapshots current files as the baseline.

### Step 3: Edit, Check Status, and Verify
1. **Save changes** to the target `.kt` or `.java` file(s).
2. **Read the watcher output**:
   - `[RELOADING] modules=[:app] files=[MyScreen.kt]`
   - `[RELOADED] mode=JVMTI_HOT_SWAP classes=2 new_classes=8 ui=COMPOSE_HOT_RELOAD duration=...ms`
3. **Verify the updated behavior on device**:
   - Visual UI check: `android screen` or `adb exec-out screencap -p > screen.png`.
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
- **Multi-Screen Navigation:** Only the Compose scopes in the modified classes are invalidated. Editing a child screen updates it in-place without resetting `NavHost` / `rememberNavBackStack` or navigation arguments.

### 2. `ViewModel`, `Repository`, & Domain Classes
- **Methods & Event Handlers:** Updated in-place on **existing instances** in memory. Existing `MutableStateFlow` and field values are preserved, and the next call to a modified method immediately executes the new code.
- **New Methods, Fields, & Lambdas:** Adding new helper methods, properties, or `viewModelScope.launch { ... }` blocks is supported in-place.
- **Computed Getters (`val x: String get() = ...`):** Re-evaluated automatically during the Compose recomposition triggered at the end of the reload.
- **Constructors & `init {}` Blocks:** Do **not** re-run on already-instantiated `ViewModel` or singleton objects in memory. To execute updated `init {}` or constructor property initialization logic, navigate away and back so a new instance is created, or move the logic into a callable method.

### 3. `Activity` & Android `View` Subclasses
- Modifying an `Activity` or `android.view.View` subclass triggers `ui=ACTIVITY_RECREATE`, recreating the active `Activity` through Android's standard saved-instance-state lifecycle without reinstalling the APK.

### 4. Resources (`res/**`) & `AndroidManifest.xml`
- XML resource and manifest edits cannot be hot-swapped via JVMTI and automatically trigger `[FALLBACK_REINSTALL]` (unless `--no-fallback` is set).

---

## Dos, Don'ts, & Gotchas

- **DO focus strictly on editing source files, reading `[STATUS]` events, and verifying the app UI.** All compilation, variant resolution, DEX merging, and runtime bytecode swapping are handled automatically by the CLI.
- **DO NOT run `javap`, `dexdump`, or inspect `.class` / `.dex` / `build/` internals.** Never disassemble bytecode or modify Gradle build logic (such as JaCoCo or compiler flags) to work around hot-reload behavior.
- **DO keep a single watcher running** across edits instead of starting and stopping it per edit.
- **DO NOT restart the watcher on `[COMPILE_ERROR]`:** The watcher deduplicates failed builds and keeps its baseline anchored to the last successful reload. Fixing the syntax/type error and saving will compile and reload all pending changes together.
- **DO NOT run `./gradlew install<Variant>` or `adb shell am force-stop` while watching:** This kills the app PID and wipes runtime state.
- **Positional `remember` ordering in Compose:** Top-level `remember` slots in a `@Composable` function are matched by declaration order. When adding a new `remember { ... }` variable during a live session, declare it **after** existing `remember` variables in that function so existing `remember` values are not shifted to a different slot index.
- **New fields on existing instances:** When adding a new field/property to a class that already has live instances in memory, that field reads as its default zero/null value (`0`, `false`, `null`) on pre-existing instances until a new instance is constructed.
- **Class hierarchy changes:** Changing a class's superclass, implemented interfaces, or `enum` constants cannot be hot-swapped in memory and will trigger `[FALLBACK_REINSTALL]` (or `[ERROR] code=RELOAD_FAILED` when `--no-fallback` is enabled).

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
