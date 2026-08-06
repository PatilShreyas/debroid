# Debroid — Open Issues

Issues remaining after the B1–B6 blocker fixes. Grouped by severity.

---

## 🔴 High-impact (won't crash release, but will seriously degrade AI-agent UX)

### H1. Daemon output is discarded — agents can't debug failures
`CliRunner.ensureDaemonAndSend` (`CliRunner.kt:101`) does `redirectError(DISCARD); redirectOutput(DISCARD)` on the auto-spawned daemon. If the daemon fails (port in use, JDK missing, JDI init error), agents get only "Failed to start background daemon" with zero diagnostic detail.
**Fix:** redirect to `~/.debroid/daemon.log` (rotate on each start). Add `debroid daemon-logs` command to print it.

### H3. CLI tests incomplete — daemon/serialization still partially unverified
`cli` now has tests for `JsonSchemaGenerator`, `JsonSchemaGoldenTest`, and the `update` package (`SemanticVersion`, `UpdateCache`, `BinaryUpdater`, `AutoUpdateManager`). However, the critical gaps remain: polymorphic round-trip of every `DaemonRequest` subtype, `CliModels` `toCli()` conversions, and argparse of each subcommand are still untested. The reflection-based test hack in `JdiSessionTest.kt:650` (accessing private `eventQueueBuffer` via `getDeclaredField`) is still present and brittle.
**Fix:** add `DaemonRequest` polymorphic serialization round-trip tests, `toCli()` conversion tests, subcommand argparse smoke tests, and replace the reflection hack with a test-visible `pushEvent`/`peekEvents` API.

### H6. Non-JSON outputs violate the "strict JSON" contract
`AGENTS.md` mandates strict JSON, but several CLI paths print free-form text: `getFormattedHelp()` (line 527), `PrintHelpMessage`/`PrintMessage`/`UsageError` (535-541), `versionOption`. An agent parsing JSON with `json.loads` will choke on help text.
**Fix:** wrap help text as a JSON object (`{"help": "...", "commands": [...]}`) so an agent can always `json.loads` output. Same for `--version`.

### H7. Security model is documented but unauthenticated
The daemon listens on `127.0.0.1:9876` with no auth, no encryption, and exposes `eval` (which uses `ObjectReference.invokeMethod` to call arbitrary zero-arg methods) — i.e., effectively arbitrary method invocation inside the debugged app and access to thread memory. On a shared dev box any local process can connect. The README now includes a security note (line 166), but there is no programmatic enforcement.
**Fix:** better: a Unix-domain socket at `$XDG_RUNTIME_DIR/debroid.sock` with `0600` perms, plus a token written to a file readable only by the invoking user.

### H13. `findPid` ps fallback is loose
`AdbManager.kt:118`: `firstOrNull { it.contains(appId) }` will match an unrelated process whose name contains `appId` as a substring (`com.foo` will match `com.foo.bar` and `com.foo.sync`). Use exact match on the last whitespace-separated column, or even tighter match by user+name.

### H15. `formatValue` marks `StringReference` as `isPrimitive = true`
`JdiSession.kt:688`. Strings are reference types; an agent may decide not to `inspect` a string it thinks is primitive, or pipeline tooling that renders refs differently from primitives will misclassify. Set `isPrimitive = false` for `StringReference`. Also affects `CliVariableInfo`'s `@SerialDescription("Whether the variable is a primitive or String")` which encodes the buggy semantics.

### H16. Event buffering has no "stale cursor" signal
When `cursor < eventQueueOffset` (the cursor points to an event that aged out of the 1000-slot buffer), `pollEvents` silently replays surviving events from index 0. The agent has no way to know it missed events. Add a `droppedEventsSinceLastPoll: Long` field to `EventPollResult` and surface it in the SKILL.

### H20. `version.txt = 0.0.1-rc01` is incompatible with `SemanticVersion.parse`
`SemanticVersion.kt:26` regex `^v?(\d+)\.(\d+)\.(\d+)$` strictly rejects prerelease suffixes. The shipped `VERSION = "0.0.1-rc01"` causes `SemanticVersion.parse(VERSION)` to return `null` in `AutoUpdateManager`. Consequences:
- `checkAndPerformSilentAutoUpdateAsync`: `currentVersion == null` means it ALWAYS tries to update (throttled to 24h), even if the latest release is the same version.
- `checkOrUpdate`: the release tag `v0.0.1-rc01` also fails to parse, returning "Latest tag (v0.0.1-rc01) is not a recognized stable release" — the update command is permanently broken for this version.
- `createFailureResult` sets `latestVersion = VERSION = "0.0.1-rc01"`, which is misleading.
**Fix:** either change `version.txt` to a stable `0.0.1` before release, or extend `SemanticVersion` to parse prerelease tags and treat them as older than stable releases of the same base.

### H21. Dead-code `DebroidCommand` — `debroid --version` and `--port` are broken
`CliRunner.createCli()` (line 466) uses `DebroidCli()` (line 461, no `versionOption`, no `--port` option). The `DebroidCommand` class (line 139, with `versionOption(VERSION)` and `--port`/`-p`) is never instantiated — it's dead code. As a result:
- `debroid --version` produces an "unrecognized option" usage error instead of printing the version.
- The `--port` option for custom daemon ports is non-functional; the daemon always uses 9876.
**Fix:** merge `DebroidCommand` into `DebroidCli` (or use `DebroidCommand` in `createCli()`), ensuring `versionOption` and `--port` are on the active root command.

### H22. `ThreadsCommand` `--schema` prints wrong type; thread output bypasses CLI models
`ThreadsCommand` (CliRunner.kt:323) declares `serializer = ListSerializer(CliStatusResult.serializer())` but `DaemonServer.processCommand` for `DaemonRequest.Threads` (line 162) encodes `session.listThreads()` which returns `List<Map<String, String>>` — raw maps, NOT `List<CliStatusResult>`. Consequences:
- `debroid threads --schema` prints `{status: string}` objects instead of thread info fields.
- No `CliThreadInfo` model exists; thread output skips `CliModels.kt` entirely, violating the AGENTS.md strict-JSON rule.
- No golden schema test covers thread output.
**Fix:** create `@Serializable data class CliThreadInfo(...)` in `CliModels.kt` with fields `threadId`, `threadName`, `status`, `isSuspended`, convert `listThreads()` to return `List<CliThreadInfo>`, and add a golden schema test.

### H23. No daemon JVM shutdown hook — ADB port forwards leak on SIGTERM
`DaemonServer.startDaemon` (line 61) runs `while(true)` with no `Runtime.getRuntime().addShutdownHook`. If the daemon process is killed (system shutdown, `kill <pid>`, OOM killer), `detachAllSessions()` and `removePortForward` are never called. Only the `stop` command triggers graceful cleanup via a separate thread + `exitProcess(0)`.
**Fix:** register a shutdown hook in `startDaemon` that calls `sessionManager.detachAllSessions()`.

### H24. `DefaultCommandRunner.runCommand` can hang indefinitely on `readText()`
`DefaultCommandRunner.runCommand` (line 19) calls `process.inputStream.bufferedReader().readText()` which blocks until EOF, BEFORE `waitFor(timeout)` is called. If a process produces output but doesn't close stdout (or produces very large output), the timeout never applies to the stream read. An `adb` hang becomes a permanent CLI hang.
**Fix:** read output in a separate thread or use `ProcessBuilder.redirectTo(File)` + read the file after `waitFor`.

---

## 🟡 Medium (polish before/just after release)

### M1. `AdbManager` hardcoded `pidof` then `ps -A` — won't work with `pidof` returning multiple PIDs (`pidof app` may return "12345 12346" for split-process apps). You take `.firstOrNull()` — silently attaches to the wrong process. Pick by `--pid` filter or warn.

### M2. `getMainActivity` rejects launcher component names that don't `startsWith(appId)` (`AdbManager.kt:152`). Apps whose applicationId differs from the package (build-types/flavors, refactor) will silently fall back to `monkey`, which doesn't respect `set-debug-app` reliably. Either drop the startswith check or warn when rejecting.

### M3. `findObjectReference` scans every suspended thread every call (`JdiSession.kt:720`). For inspections inside deep stacks this is O(threads×frames×locals) each poll. Cache `objectId -> ObjectReference` per suspend/resume cycle. Also, `inspect` on instance-field objects (not stack locals) fails — `findObjectReference` only scans stack frames, not fields of `this`. This is a significant UX gap for agents.

### M4. `JdiSession.isAlive()` is racy: between the check and the next call on the VM, the VM may disconnect. Most usages don't re-check. Wrap calls so they degrade to "session disconnected" cleanly (a small `withConnected { }` helper that catches `VMDisconnectedException`).

### M5. `DaemonServer.startDaemon` (`DaemonServer.kt:52`) doesn't handle `BindException`. If `isDaemonRunning()` says false but the port is occupied (other process), the daemon explodes with a stacktrace and the CLI's auto-spawn waits 5s and reports generic failure.

### M6. Daemon uses an unbounded `CachedThreadPool`. Under tight agent polling this is fine in practice, but pair it with a sane limit (`newFixedThreadPool(16)` or `ThreadPoolExecutor` with a rejection policy).

### M7. `attachToPid` retry loop retries 10×300ms = 3s; on a slow emulator that's tight. Make it configurable via env (`DEBROID_ATTACH_TIMEOUT_MS`).

### M8. `JdiSessionManager.findAvailableLocalPort` (`:122`) has the classic TOCTOU: socket closed before `adb forward`. Use `adb forward tcp:0 jdwp:<pid>` which has adb pick the port (returns it).

### M9. `setVariable` only supports primitives + String; throws INTERNAL_ERROR otherwise. That error code is misleading — there's no internal corruption; it's just unsupported. Add `UNSUPPORTED_TYPE` to `ErrorCode` and use it.

### M10. `VARIABLE_SCOPE.ARGS` vs `LOCAL` filtering in `getVariables` excludes args from `LOCAL` (`JdiSession.kt:574`). This is surprising — most debuggers' "locals" include args. Document or change.

### M11. `DebugEventPayload.exceptionMessage` is overloaded — for `EXCEPTION_HIT` it stores the exception class name, for `WATCHPOINT_*_HIT` it stores the access/modify message, for `DISCONNECT` it's null. Conflating these into one field makes parsers handle multiple cases. Split into `exceptionClass: String?` and `message: String?`.

### M12. `StackTrace` collection in `getFramesSafely` swallows all `Exception`. If a frame call throws `IncompatibleThreadStateException` (real but recoverable), the event has no stack and the agent has no idea why. Log/capture at least once.

### M13. `Version.kt` is generated only into `main` sourceSet; no header comment, no name uniqueness guard — fine for now, but flag in case anyone ships it into a published artifact.

### M14. `settings.gradle.kts` has only `:core` and `:cli`. `sample-app` is its own Gradle build. Good for isolation but means a single `gradlew build` in the root does NOT verify the sample app. Add a CI job that builds `sample-app` (or at least that its APK is reproducible) — this is the canonical demo path agents will follow.

### M15. AGENTS.md mandates skill synchronization but nothing enforces it. Add a CI check that diff-matches `DaemonRequest` commands in `DaemonRequest.kt` against the SKILL command table (a trivial Python/smoke test), so the "CRITICAL RULE" actually has teeth.

### M16. ~~`install.sh` hard-codes `sudo mv` to `/usr/local/bin`.~~ **Partially fixed:** `install.sh` now installs to `$HOME/.local/bin` without sudo (line 30-132). Remaining: `VERSION=$(cat version.txt | xargs)` — `xargs` strips whitespace; fine, but `tr -d '[:space:]'` reads cleaner. Also no `$PREFIX` override support.

### M17. Generated `Version.kt` task isn't wired into Kotlin compile explicitly. It worked because `kotlin.srcDir(provider)` indirectly wires it, but make `compileKotlin { dependsOn(generateVersionInfo) }` explicit for IDE/standalone robustness.

### M18. `JdiSession.evaluateExpression` returns `isPrimitive = true` for the synthesized string eval result (`JdiSession.kt:440` via `formatValue`). Should be `false` (it's a StringReference). Same root cause as H15.

### M19. ~~Clikt `versionOption(VERSION)` prints `debroid 0.0.1` as plain text.~~ **Now moot for `--version`:** `versionOption` is on the dead `DebroidCommand` class (see H21), so `--version` doesn't work at all. Once H21 is fixed, this becomes relevant again — either suppress version to JSON or document that flag-output is plain.

### M20. The daemon redirects `System.out` of the forked JVM only via ProcessBuilder (`redirectOutput(DISCARD)`). But `DaemonServer.startDaemon` itself prints "🤖 Debroid Daemon started..." via `println` (line 58) — that goes to the discarded stream. Fine. But comments in AGENTS.md say "never use raw println" — the daemon has raw `println`s. They're not agent-facing but they violate the stated rule. Either relax the rule (allow for daemon startup) or route them through a logger.

### M21. `AutoUpdateManagerTest` and `BinaryUpdaterTest` make real HTTP calls to GitHub
`AutoUpdateManager.DEFAULT` uses a real `GitHubReleaseClient`. `checkOrUpdate returns valid result in check-only mode` hits `api.github.com` live. Tests are flaky (rate limits, network) and slow. The `checkAndPerformSilentAutoUpdateAsync` test also triggers a background HTTP call.
**Fix:** inject mock `GitHubReleaseClient` and `BinaryUpdater` into `AutoUpdateManager` for tests.

### M22. `evaluateExpression` fallback path can throw uncaught `IncompatibleThreadStateException`
In `JdiSession.evaluateExpression` (line 441), the `catch (e: Exception)` block calls `thread.frame(0)` at line 443, which can throw `IncompatibleThreadStateException` if the thread was resumed between the evaluator call and the fallback. This escapes as a raw JDI exception, not wrapped as `DebugException`.
**Fix:** wrap the fallback logic in a nested try-catch and rethrow as `EVALUATION_FAILED` or `THREAD_NOT_SUSPENDED`.

### M23. `getPauseState` redundantly calls `findThread` 4 times
`JdiSession.getPauseState` (line 606) calls `findThread(threadId)`, then `getStackFrames(threadId)` (calls `findThread` again), then `getVariables` twice (each calls `findThread`). Four `vm.allThreads()` scans per `pause-state` call — wasteful for large thread counts.
**Fix:** resolve the `ThreadReference` once and pass it to internal overloads.

### M24. `install.sh` local mode creates `stub.sh` in CWD
Line 115: `cat << 'EOF' > stub.sh` writes to the current working directory. If run from a read-only directory, this fails.
**Fix:** use `mktemp` to create the stub file in the system temp dir.

### M25. `BinaryUpdater.downloadAndReplaceBinary` temp file may be on a different filesystem
`File.createTempFile` (line 51) uses the system temp dir. `renameTo` across filesystems is not atomic (falls back to copy+delete), so the "atomic" replacement can fail partially on systems where `/tmp` is a different mount than `~/.local/bin`.
**Fix:** create the temp file in `targetBinary.parentFile` (e.g., `File(targetBinary.parentFile, "debroid-update.tmp")`).

### M26. `CliRunner.execute` exits with code 1 for `--help` and `--schema`
`PrintHelpMessage` and `PrintMessage` (from `--help`, `--schema`) are caught as `CliktError` subtypes and `exitProcess(1)` is called (line 543). Help/schema are not errors; they should exit 0.
**Fix:** check `is PrintHelpMessage || is PrintMessage` and `exitProcess(0)` for those; `exitProcess(1)` only for `UsageError` and actual errors.

### M27. `DaemonRequest.Shutdown.force` field is dead
`DaemonRequest.Shutdown` (DaemonRequest.kt:20) has `force: Boolean = false` but `processCommand` (DaemonServer.kt:106) never reads it. The daemon always does `detachAllSessions()` + `exitProcess(0)` regardless.
**Fix:** either implement force-kill semantics or remove the field.

### M28. `inspect --max-depth` has no upper bound
An agent can pass `--max-depth 1000`, causing deep recursive inspection that can OOM the daemon or produce enormous responses.
**Fix:** cap at a reasonable max (e.g., 5) in `InspectCommand` or `inspectObject`.

### M29. `setWatchpoint` / `setExceptionBreakpoint` only use first `ReferenceType` from `classesByName`
`JdiSession.setWatchpoint` (line 234): `refTypes.first()`. `setExceptionBreakpoint` (line 223): `vm.classesByName(name).firstOrNull()`. If multiple class loaders load the same class, only the first gets the trap. Other instances are silently unwatched.
**Fix:** bind watchpoints/exception requests for all returned `ReferenceType` instances.

### M30. `listThreads()` returns raw integer for thread status
`JdiSession.listThreads` (line 363): `thread.status().toString()` returns `"1"` (THREAD_STATUS_RUNNING), which is meaningless to an AI agent without a mapping to `ThreadReference.THREAD_STATUS_*` constants.
**Fix:** include a human-readable status string (e.g., map `THREAD_STATUS_RUNNING` → `"RUNNING"`).

### M31. No CLI command to list active breakpoints
`JdiSession.listBreakpoints()` (line 354) exists but is not exposed through `DaemonRequest` or any CLI command. Agents can't query what traps are currently set — they must track IDs in their own memory.
**Fix:** add `DaemonRequest.ListBreakpoints`, `debroid list-breakpoints <session_id>` command, and `CliBreakpointInfo` list response.

### M32. `extractFrames` calls `thread.frames()` unguarded
`JdiSession.getStackFrames` checks `isSuspended` then calls `extractFrames` → `thread.frames()` (line 530) without a try-catch. If the thread is resumed between the check and the call (race), `IncompatibleThreadStateException` propagates uncaught to the daemon's generic `Exception` handler.
**Fix:** wrap `thread.frames()` in try-catch and throw `DebugException(THREAD_NOT_SUSPENDED, ...)`.

### M33. `DaemonServer.Shutdown` handler races response flush with process exit
The shutdown thread (DaemonServer.kt:109) sleeps 100ms then calls `exitProcess(0)`. If the socket write is slow, the response may be truncated before the client reads it.
**Fix:** flush the writer and close the socket explicitly before starting the exit thread, or use `socket.getOutputStream().flush()` in the `handleClient` finally block before the exit thread runs.

### M34. `AdbManager.isAppDebuggable` false-negative when `run-as` produces stderr output
`DefaultCommandRunner` uses `redirectErrorStream(true)`, merging stderr into stdout. If `run-as <appId> true` succeeds (exit 0) but prints a warning to stderr, `output` is non-empty and not `"true"`, so `isAppDebuggable` falls through to the dumpsys fallback unnecessarily. `run-as` exit 0 is sufficient proof of debuggability.
**Fix:** return `Result.success(true)` when `runAsRes.isSuccess` regardless of output content (the output check for "unknown package" should only apply on failure).

### M35. Silent auto-update runs before arg parsing on every invocation
`CliRunner.execute` (line 523) calls `checkAndPerformSilentAutoUpdateAsync()` before `createCli()`. Even `debroid skill` (just prints a file) or `debroid --help` triggers a background GitHub API call + cache file write.
**Fix:** skip the auto-update check for trivial commands (`skill`, `--help`, `--version`), or move it after arg parsing.

---

## 🟢 Low / nice-to-have

- L1. `MAX_EVENT_BUFFER_SIZE = 1000` is in a `companion object` already, but `.take(50)` in `inspectObject` (line 640) is still a magic number. Pull into companion or named constant.
- L2. ~~`CopyOnWriteArrayList.removeAt(0)` is O(n)~~ **Fixed:** event buffer now uses `ArrayDeque` with `removeFirst()` inside a `synchronized` block (O(1)). No action needed.
- L3. `JdiSession` has unused `getFramesSafely` exposed via `private`; uses are fine but it could be inlined or removed.
- L4. `setBreakpoint` returns `activeBreakpoints[id]!!` (line 146). If the map was concurrently cleared it would NPE. Use a local `var`.
- L5. `formatValue`'s `else` branch (line 707) catches non-`Value` types but `value.type().name()` can still NPE if the value's type is null. Add null check.
- L6. `findThread` matches by `uniqueID() == tid || it.name() == threadId`. If two threads have identical names (rare but legal in JVM), you get an arbitrary one. Disambiguate or document.
- L7. `StackTrace` is captured by `getFramesSafely(event.thread())` synchronously inside the event listener — JDI frames are inspected while holding internal locks; if the agent's `inspect`/`frames` call comes in concurrently on another socket thread, it can race. JDI is single-threaded-ish; serialize inspection calls per session via a `Mutex`.
- L8. `version.txt = 0.0.1-rc01` with no CHANGELOG.md — add one before public release (track breaking changes of the JSON contract so future agents can adapt). Also see H20 for the version format issue.
- L9. No license headers in source files. Apache-2.0 is the LICENSE but most Apache-2.0 projects adopt the per-file header. Tooling (`spotless`) can enforce.
- L10. The `skills/debroid-cli/SKILL.md` provides a great workflow; consider adding an end-to-end worked example (e.g., "debug NullPointerException in MainActivity.kt:42") so agents can pattern-match rather than reinvent the flow.
- L11. `DaemonRequest` sealed class subtypes have no `@SerialName` annotations. kotlinx.serialization uses class names as discriminators, so a refactor (rename/move a subclass) silently breaks the IPC wire format. Add `@SerialName("launch")` etc. for wire-format stability.
- L12. `inspectObject` `fieldsFilter` parameter is always `null` from the daemon (DaemonServer.kt:210). The `DaemonRequest.Inspect` model has no `fieldsFilter` field and the CLI has no `--fields` option. The filtering feature in `inspectRecursive` is dead code. Either expose it via CLI or remove the parameter.
- L13. `BinaryUpdater.resolveCurrentBinaryLocation` checks `processCmd.endsWith("debroid")` but when running via `java -jar` the process command is `java`, not `debroid`. It falls back to hardcoded paths (`~/.local/bin`, `/usr/local/bin`). If the user installed elsewhere (e.g., Homebrew), the update silently fails. Consider checking `which debroid` via `ProcessBuilder`.
- L14. `GitHubReleaseClient` hardcodes `REPO_OWNER = "PatilShreyas"` and `REPO_NAME = "debroid"`. Forks will still check the original repo for updates. Consider build-time injection or an env var override.

---

## ✅ Fixed Blockers (B1–B6)

These were fixed and verified end-to-end against the live sample app on emulator:

- **B1.** Deferred line breakpoints now auto-bind on `ClassPrepareEvent` via `sourceName()` + simple-name heuristic.
- **B2.** `catch-exception` semantics corrected: `notifyCaught`/`notifyUncaught` exposed as `--caught`/`--uncaught` flags; tracked for removal.
- **B3.** `inspect --max-depth N` now recurses N levels deep with cycle guard; `nested` map populated in response.
- **B4.** `detach` auto-runs `am clear-debug-app` when session was launched via `launch` (not `attach`).
- **B5.** Deferred watchpoints use per-class list (`Map<String, List<DeferredWatchpoint>>`); multiple watchpoints per class no longer overwrite.
- **B6.** `remove-break`, `remove-catch-exception`, `remove-watch` commands added; all JDI requests tracked and deletable; orphaned `ClassPrepareRequest` auto-disabled when deferral queue empties.
- **H2.** `debroid stop` daemon shutdown command added (`DaemonRequest.Shutdown`); detaches all active debug sessions, cleans up ADB port forwards, and gracefully shuts down the background server process.
- **H4.** `pollEvents` thread safety and JMM visibility fixed in `JdiSession.kt`: synchronized `pushEvent` and `pollEvents` buffer snapshot on `eventQueueLock` using `ArrayDeque` with `@Volatile eventQueueOffset`.
- **H5.** CLI JSON output is now compact by default (`compactJson` with `prettyPrint = false`); `--pretty` flag added to `BaseJsonCommand` for human-readable output. Daemon `processCommand` selects `compactJson` vs `prettyJson` based on the IPC `pretty` flag.
- **H8.** Command names in `README.md` aligned with actual CLI subcommands (`break`, `stop`, `pause-state`, `step`, etc.).
- **H9.** Step actions in `SKILL.md` and `README.md` verified and aligned with `StepAction` enum values (`STEP_OVER`, `STEP_INTO`, `STEP_OUT`, `RESUME_THREAD`, `RESUME_ALL`).
- **H10.** Dropped `threadId` argument from `resume` command; it now explicitly resumes all threads via `RESUME_ALL`, while per-thread resume is correctly relegated to `step <session> <tid> RESUME_THREAD`.
- **H11.** Removed the unused `condition` field entirely from `BreakpointInfo`, `CliBreakpointInfo`, and all `setBreakpoint` method signatures since the CLI never accepted conditions and the SKILL did not document it.
- **H12.** `isAppDebuggable` rewritten in `AdbManager.kt`: uses Android's native `run-as <app_id> true` check as primary mechanism, with package flags check (`flags=[` / `pkgFlags=[`) as fallback.
- **H14.** Expression evaluation upgraded to JDK internal JDI `ExpressionParser` via `JdiExpressionEvaluator.java` Java bridge; supports full method calls, arithmetic, logic, parameter passing, and string operations using Java syntax.
- **H17.** Detekt `ignoreFailures` set to `false` in `build.gradle.kts` so detekt violations fail the build as expected.
- **H18.** Release pipeline now builds AND attaches the combined `debroid` executable (bash stub + fat JAR) alongside `debroid.jar` and SHA-256 checksums. `install.sh` remote mode downloads the combined binary directly.
- **H19.** Fixed cumulative suspend count freeze by changing `StepRequest` to `SUSPEND_EVENT_THREAD` and ensuring `RESUME_ALL` bounds all suspend counts to 0.

Additional hardening during integration testing:
- JDI `InternalError` from ART's `SourceDebugExtension` parser no longer kills the event listener thread (catch `Throwable` in event loop + `safeSourceName()` helper).
- Clikt flag syntax fixed for `--no-access`/`--no-modify`/`--no-caught`/`--no-uncaught`.
- `bindBreakpointLocation` class-matching `filter{}` catches all `Throwable` (not just `AbsentInformationException`).

---

## Recommended pre-release checklist

1. **H1 (daemon log) + H2 (daemon-stop)**: turns 60% of agent error situations from "give up" into "self-recover".
2. **H20 (version.txt vs SemanticVersion) + H21 (--version broken)**: the auto-updater and version display are both broken for the current `0.0.1-rc01` version string. Fix before any release.
3. **H22 (threads schema) + H6 (non-JSON outputs)**: contract/schema issues that materially affect agent efficiency.
4. **H3**: ship remaining CLI tests (DaemonRequest round-trip, toCli() conversions) so external contributors don't break the JSON contract by accident.
5. **H23 (daemon shutdown hook) + H24 (CommandRunner hang)**: robustness issues that cause silent resource leaks or permanent hangs.
6. **H17 / M14 / M15**: tighten CI (detekt teeth, sample-app build, SKILL-sync enforcement).
