# Live Code & Compose Hot Reload (`debroid hot-reload`)

Operational playbook for AI agents using `debroid hot-reload` to build, iterate on, and verify Kotlin/Java logic and Jetpack Compose UI on a running Android app in **300ms–800ms** instead of 15s–60s Gradle APK rebuilds.

---

## 1. Core Mental Model (Read First)

1. **Standalone JVMTI Watcher (Not JSON, No Daemon):** `debroid hot-reload` runs directly against Gradle + ADB + an on-device JVMTI native agent. You do **not** need `debroid daemon`, and output is **single-line plain-text `[STATUS]` events** (never pipe into `jq`).
2. **Keep One Watcher Alive for the Whole Task:** Starting the watcher warms an in-process Gradle connection, an in-process `D8` compiler, and your pre-edit `.class`/`.dex` baseline. Never start and stop the watcher per edit.
3. **Leave Fallback Enabled (Default):** By default, edits that can hot-swap in RAM apply in ~300ms–800ms while preserving app state, and edits that cannot hot-swap (XML resources, `AndroidManifest.xml`, adding interfaces/superclasses to named classes) are caught immediately and fall back to a Gradle `install<Variant>` + app restart (`[FALLBACK_REINSTALL]`). Only pass `--no-fallback` if the user explicitly forbids app restarts.
4. **RAM Patches vs. Installed APK:** Hot-reloaded bytecode lives in the running process's memory. While the watcher is running, if the app crashes or restarts with a new PID, the watcher **automatically reapplies** all session edits onto the new PID. Once you stop the watcher at the end of a task, run `./gradlew :<app-module>:install<Variant>` once if the user wants the changes permanently baked into the installed APK for future cold launches.

---

## 2. Command Signature & Options

```bash
debroid hot-reload <app_id> [-d/--project-dir <path>] [-v/--variant <variant>] [--interval-ms <ms>] [--once] [--no-fallback] [--java-home <path>] [--gradle-args <args>...]
```

| Option | Default | When to Use |
| :--- | :--- | :--- |
| `<app_id>` | *(Required)* | Running debuggable Android package ID (e.g. `com.example.app` or `com.example.app.demo.debug`). |
| `-d, --project-dir <path>` | `.` | Root directory of the Android Gradle project (or composite umbrella root containing `settings.gradle` / `settings.gradle.kts`). Always pass `-d` if your shell CWD is outside the Android project root. |
| `-v, --variant <variant>` | `debug` *(auto-inferred from `<app_id>`)* | Build variant (e.g. `debug`, `demoDebug`, `freeDebug`). Auto-resolved from `<app_id>` and build outputs in most projects; pass explicitly only when ambiguous. |
| `--interval-ms <ms>` | `300` | File watcher polling slice in milliseconds (`>= 100`). Leave at default unless needed. |
| `--once` | `false` | **Sync barrier for non-streaming harnesses:** Connects to your active background watcher, flushes pending edits immediately, blocks until the reload pass finishes, prints the `[STATUS]` result, and exits. |
| `--no-fallback` | `false` | Disables automatic `install<Variant>` fallback on resource/manifest/hierarchy changes (emits `[ERROR] code=FALLBACK_DISABLED` instead). **Leave OFF** during normal feature work. |
| `--java-home <path>` | *(Auto-detected)* | Explicit JDK home directory for Gradle commands (also via `DEBROID_JAVA_HOME`). Auto-resolved from `org.gradle.java.home`, `.java-version`, `.sdkmanrc`, `.tool-versions`, and Gradle wrapper compatibility (e.g. automatically selecting JDK 17 when global `JAVA_HOME` is Java 21+ on older Gradle wrappers). |
| `--gradle-args <args>` | *(Empty)* | Extra arguments forwarded to all Gradle invocations (discovery, incremental compile, and fallback install), e.g. `--gradle-args="-Penv=staging --offline"`. May be passed multiple times. |

---

## 3. Step-by-Step Agent Playbook

### Step 0: Fast Target Discovery & Pre-Flight Baseline Check
1. **Discover `<app_id>` and `<MainActivity>` in one command** if the app is already visible on screen:
   ```bash
   adb shell dumpsys window | grep -E "mCurrentFocus|mFocusedApp"
   ```
   *(Example match: `com.example.app.demo.debug/com.example.app.MainActivity` $\rightarrow$ `<app_id>` is `com.example.app.demo.debug`, variant is `demoDebug`).*
2. **Ensure the installed APK matches the current workspace before starting:**
   - If the app is not installed yet, OR `git status` already shows uncommitted code/resource edits from *before* your session that have not been installed to the device, run `./gradlew :<app-module>:install<Variant>` and launch the app once **before** starting `debroid hot-reload`.
   - If the app is installed and clean but not running, launch it:
     ```bash
     adb shell am start -n <app_id>/<MainActivity>
     ```

### Step 1: Start One Persistent Watcher (Pick Your Harness Pattern)

| Your Harness Capability | Exact Pattern to Use |
| :--- | :--- |
| **Pattern A — Live Background Stream Monitoring** *(e.g. Claude Code `Monitor`, interactive streaming background tasks)* | Start `debroid hot-reload <app_id> -d <project_dir>` in the background and monitor its stdout stream directly. Wait for `[WATCHING]`. **Do not run `--once` after edits** — saving files automatically streams `[RELOADING]` $\rightarrow$ `[RELOADED]`. |
| **Pattern B — Non-Streaming Background Jobs** *(background shell only notifies on process exit, e.g. OpenCode `shell(background=true)`)* | 1. Start in background redirected to a log file:<br>`debroid hot-reload <app_id> -d <project_dir> > <tmpdir>/hotreload.log 2>&1`<br>2. Read `<tmpdir>/hotreload.log` once (~0.6s later) to confirm `[WATCHING]` is printed.<br>3. After each batch of file edits, run `debroid hot-reload <app_id> -d <project_dir> --once` in the foreground — it blocks on the warm background watcher for the exact reload duration and prints the completion `[STATUS]` line(s) with **zero `sleep` guessing**. |

> ⚠️ **Wait for `[WATCHING]` before saving any edits:**
> ```text
> [WATCHING] app=com.example.app pid=12345 modules=12 interval=300ms
> ```
> Saving files before `[WATCHING]` appears causes those edits to be absorbed into the initial baseline instead of reloaded.
>
> ⚠️ **Never use `--once` standalone without a running background watcher:** Without a persistent watcher, `--once` has no pre-edit baseline or warm compiler connection.

### Step 2: Edit Source Files & Interpret `[STATUS]` Events
Save all related `.kt` / `.java` / `.xml` edits (rapid multi-file edits are automatically coalesced into a single reload pass), then read the resulting `[STATUS]` line(s):

| Status Line | Meaning | What You Should Do Next |
| :--- | :--- | :--- |
| `[RELOADING] modules=[...] files=[...]` | Edit detected; incremental compile started. | Wait for the terminal result event (`[RELOADED]`, `[COMPILE_ERROR]`, `[FALLBACK_REINSTALL]`, or `[ERROR]`). |
| `[RELOADED] mode=... classes=N new_classes=M ui=<uiMode> duration=...ms` | Bytecode swapped into the running VM! *(Also check if a `[RUNTIME_ERROR]` line immediately follows).* | Check `ui=<uiMode>`:<br>• `COMPOSE_HOT_RELOAD`: Screen updated in-place; state preserved. Verify UI.<br>• `ACTIVITY_RECREATE`: Root `Activity`, `View`, or Activity-scoped `ViewModel` updated; Activity recreated. **If your target screen was on a non-default tab without `rememberSaveable`, check `android layout` and re-tap the tab if needed.**<br>• `NONE`: Non-UI/comment-only edit (or `[COMPILE_ERROR]` revert). Trigger the action or continue editing. |
| `[COMPILE_ERROR] module=<mod> <File.kt:line:col> <msg>` | Compilation failed. **Watcher stays alive** and keeps its last good baseline. | Fix (or revert) the compiler error at `<File.kt:line:col>` and save. Even reverting back to the prior working code will emit `[RELOADING]` $\rightarrow$ `[RELOADED]`. |
| `[RUNTIME_ERROR] <Exception>: <msg> (<File.kt:line>)` | New code threw an exception during Compose recomposition or main-thread execution. | Fix the bug at `<File.kt:line>` and save. Compose recomposition errors recover in-place on the next reload; if the process crashed, relaunch the app and the active watcher will auto-reapply your edits. |
| `[FALLBACK_REINSTALL] reason=<reason> files=[...] duration=...ms` | Resource/manifest or non-hot-reloadable hierarchy edit triggered full `install<Variant>` + app restart automatically. | In-memory state was reset by the app restart. Navigate back to your target screen and verify. |
| `[WATCHING] app=... pid=<newPid> ...` *(mid-session)* | Target app restarted with a new PID; watcher automatically re-bound and is reapplying session edits. | Wait for the immediately following `[RELOADED]` line, then continue. |
| `[ERROR] code=<code> message=<msg>` | Reload could not be applied. | Follow **Section 5 (Troubleshooting)** for the exact `code=`. |

### Step 3: Verify UI & Interactive Logic on Device
1. **Inspect UI hierarchy & text/bounds (fast, low-token):**
   - Prefer `android layout` (or `android layout --flat`) when `android` CLI is installed, otherwise `adb shell uiautomator dump /sdcard/ui.xml && adb shell cat /sdcard/ui.xml`.
2. **Test interactive business logic (`ViewModel` actions, filters, clicks):**
   - Updating an action method (`onClick`, `viewModel.toggle(...)`) hot-swaps the method body immediately, **but the new code only executes when that action is invoked**. Compute element center `(minX+maxX)/2, (minY+maxY)/2` from layout bounds and run `adb shell input tap X Y`, then re-check layout!
3. **Inspect visual styling (colors, typography, spacing, icons):**
   - Capture a screenshot (`android screen capture -o <tmpdir>/screen.png` or `adb exec-out screencap -p > <tmpdir>/screen.png`) and view it.

### Step 4: Clean Up When Finished
1. Stop/terminate the background `debroid hot-reload` process.
2. If you applied hot-swaps during the session (without ending on a `[FALLBACK_REINSTALL]`) and want the edits persisted into the installed APK on device for future cold app launches, run `./gradlew :<app-module>:install<Variant>` once.

---

## 4. Writing Hot-Reload-Friendly Code (In-Place vs. Reinstall Matrix)

Use these patterns while iterating so 95%+ of your edits hot-swap in **~300ms–600ms** without losing app state:

| Code Change | Behavior | Agent Best Practice |
| :--- | :--- | :--- |
| **`@Composable` layouts, modifiers, text, themes, lambdas** | **In-place (`COMPOSE_HOT_RELOAD`)**. Only modified composable scopes invalidate; navigation & sibling screens stay intact. | Freely add/remove/nest composables, `Scaffold` slots, `FilterChip`s, animations, and click handlers. |
| **`remember { ... }` / `collectAsState*()` inside a `@Composable`** | **Preserved** when `remember` calls in that composable are unchanged; **auto-reset for that composable only** when `remember` calls or `collectAsState` flow sources change. | Keep existing `remember` calls in the same order when tweaking UI styling if you want live counter/toggle/input state preserved during iteration. |
| **Adding fields/params to an existing `data class` or `ViewModel`** | **In-place (`JVMTI_STRUCTURAL_SWAP`)** when old call sites remain binary-compatible. | **Give new `data class` properties a default value** (e.g. `val filter: Filter = Filter.All`). `debroid` synthesizes compatibility `<init>` stubs so existing classes hot-swap without `JVMTI_ERROR_64` reinstalls. *(Note: pre-existing heap instances read `0`/`false`/`null` for brand-new fields until reconstructed).* |
| **`ViewModel` `init {}`, `StateFlow`, & `combine(...)` lambdas** | **In-place (`JVMTI_HOT_SWAP` / `JVMTI_STRUCTURAL_SWAP`)** + automatic `ViewModelStore` eviction. | Even changing `combine(2 flows)` $\rightarrow$ `combine(4 flows)` hot-reloads in-place! Active `ViewModel` instances in Activity, Fragment, and Navigation stores are automatically evicted and recreated with the new flow pipeline. |
| **`interface` / `sealed interface` default method bodies** | **In-place (`COMPOSE_HOT_RELOAD`)** — preserves all UI & `ViewModel` state. | Put evolving shared behavior in `interface` default methods freely; editing their bodies hot-swaps in-place. |
| **`coreLibraryDesugaring` APIs (`java.time.*`, `java.nio.*`, JDK backports)** | **In-place (`JVMTI_HOT_SWAP`)** — automatically desugared against the project's `minSdk` and `desugar_jdk_libs` config (`j$.*`). | Use `java.time.Instant`, `LocalDate`, `Duration`, `DateTimeFormatter`, and `kotlinx.datetime.toJavaInstant()` freely even on `minSdk < 26` projects. |
| **Adding/removing abstract methods on an `interface`, OR changing `superclass` / `: ImplementedInterface` on an existing named class** | **Pre-flight `[FALLBACK_REINSTALL]`** (`INTERFACE_STRUCTURE_CHANGED`, `INTERFACE_HIERARCHY_CHANGED`, `SUPERCLASS_CHANGED`). | ART JVMTI forbids changing the type hierarchy of an already-loaded named class. `debroid` detects this immediately on the host after `kotlinc` (skipping `D8`/ADB) and runs fallback reinstall automatically. Batch hierarchy changes together so fallback runs at most once. |
| **Hilt / Dagger / KSP (`@HiltViewModel`, `@Inject`, `@AndroidEntryPoint`)** | **Editing existing Hilt classes/ViewModels:** In-place (KSP is skipped automatically when DI signatures are unchanged!).<br>**Adding a brand-new `@HiltViewModel` or `@Provides` binding:** Requires `:app` Hilt root component codegen. | When prototyping a **brand-new** `ViewModel` mid-session in a Hilt app, either instantiate via `ViewModelProvider(owner)[MyViewModel::class.java]` while iterating fast, OR run `./gradlew :<app-module>:install<Variant>` once after adding `@HiltViewModel @Inject constructor(...)` so Hilt's `:app` component registers the multibinding. |
| **XML resources (`res/**`) & `AndroidManifest.xml`** | **Automatic `[FALLBACK_REINSTALL]`** (`reason=RESOURCE_CHANGE`). | Batch XML string/drawable/manifest edits together before saving so fallback `install<Variant>` runs once. |

---

## 5. Troubleshooting & Error Code Reference

| Output / Symptom | Exact Cause | Immediate Fix (Do Not Guess) |
| :--- | :--- | :--- |
| `[ERROR] code=APP_NOT_RUNNING` *(on initial startup)* | App is not running on the connected device/emulator. | Launch the app (`adb shell am start -n <app_id>/<MainActivity>`) and start `debroid hot-reload`. |
| `[ERROR] code=APP_NOT_RUNNING` *(mid-session while watching)* | App crashed or was force-stopped before/during a save. | **Do NOT restart `debroid hot-reload`.** Simply relaunch the app (`adb shell am start -n <app_id>/<MainActivity>`) — the running watcher will automatically detect the new PID and apply all pending + session edits! |
| `[ERROR] code=APP_NOT_DEBUGGABLE` | Package is not installed, `<app_id>` is missing a flavor/debug suffix (e.g. `.demo.debug`), or a release APK is installed. | Check `adb shell pm list packages \| grep <name>` or `dumpsys window`, install the debug variant (`./gradlew :<app>:install<Variant>`), and retry. |
| `[ERROR] code=FALLBACK_DISABLED message=Cannot hot-swap <REASON> ...` | `--no-fallback` was passed and the edit changed XML resources or a named class's superclass/interfaces (`<REASON>`). | Either revert the hierarchy/resource change, or restart the watcher **without** `--no-fallback` so it can auto-reinstall when needed. |
| `[ERROR] code=FALLBACK_FAILED` | Full Gradle `install<Variant>` failed during fallback (e.g. resource merge error, KSP/Dagger error, or device disconnected). | Read the error message, fix the underlying resource/DI/Gradle build error (verify with `./gradlew :<app>:install<Variant>`), ensure the app is running, and continue. |
| `[ERROR] code=NO_APP_MODULE` | `-d` does not point to the Gradle root containing the `com.android.application` module for `<app_id>`. | Pass `-d <path/to/android/project/root>` where `settings.gradle(.kts)` lives. |
| `[ERROR] code=PROJECT_SCAN_FAILED` or `INVALID_JAVA_HOME` | Gradle project discovery failed (e.g. incompatible JDK version or missing required `-P` project property in `settings.gradle`). | Inspect the surfaced Gradle diagnostic; pass `--java-home <jdk_path>` and/or `--gradle-args="-Pkey=val"` if required by the project's build setup. |
| `[ERROR] code=AGENT_TIMEOUT` or `ATTACH_AGENT_FAILED` | Main thread of the target app is frozen (ANR / paused on a JDI breakpoint!) so `Agent_OnAttach` could not run on the main looper. | If a `debroid` JDI debug session has threads paused, run `debroid resume <session_id>` or `debroid detach <session_id>`. If the app is ANR-hung, `adb shell am force-stop <app_id> && adb shell am start ...` (watcher will auto-reapply edits). |
| `[RUNTIME_ERROR] IllegalStateException: Expected the @HiltViewModel-annotated class ...` | A brand-new `@HiltViewModel` was added mid-session without regenerating Hilt's `:app` root component. | Stop watcher, run `./gradlew :<app>:install<Variant>`, relaunch the app, and restart `debroid hot-reload`. |
| `[RELOADED]` printed, but UI still shows previous screen/tab | `ui=ACTIVITY_RECREATE` recreated the `Activity` (resetting unsaved tab state), OR the edited code is inside an uninvoked click/action handler. | Check `android layout`: re-tap your target tab if `Activity` recreated, or tap the relevant button to fire the updated handler. |
| Saved file, but `[RELOADING]` never appeared | File was saved before `[WATCHING]` printed, `-d` points to wrong project dir, or file is inside `src/test` / `src/androidTest` (unit/instrumentation tests are intentionally ignored). | Confirm `[WATCHING]` is present and file is under `main`/variant source sets, then save again. |

---

## 6. Strict Anti-Patterns (Never Waste Turns On These)

- **NEVER run `javap`, `dexdump`, or inspect `.class` / `.dex` / `build/` files** to debug or "verify" hot reload. Only edit source files, read `[STATUS]` lines, and inspect the live device UI.
- **NEVER modify `build.gradle(.kts)`, compiler flags, or JaCoCo/Hilt plugins** to work around hot-reload behavior.
- **NEVER restart `debroid hot-reload` on `[COMPILE_ERROR]`, `[RUNTIME_ERROR]`, or mid-session app restarts** — restarting the watcher throws away its warm Gradle/`D8` state and pre-edit APK baseline!
- **NEVER run `sleep N && cat hotreload.log` loops** in non-streaming harnesses — run `debroid hot-reload <app_id> -d <project_dir> --once` against your background watcher for an exact, zero-polling synchronous barrier.
- **NEVER keep both a suspended JDWP breakpoint (`debroid attach --suspend` / paused `BREAKPOINT_HIT`) AND `debroid hot-reload` active at the exact same moment** — JVMTI `attach-agent` executes on the app's main thread, so a suspended VM cannot process a hot reload until resumed (`debroid resume <session_id>`).
