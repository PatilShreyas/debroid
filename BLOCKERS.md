# Debroid — Open Issues

Issues tracked across the `debroid` codebase, verified against the current state (`v0.1.0+`). Grouped by severity.

---

## 🔴 High-impact (Won't crash release, but will degrade AI-agent reliability or contract adherence)

### H3. CLI tests incomplete — daemon/serialization still partially unverified
`cli` has tests for `JsonSchemaGeneratorTest`, `JsonSchemaGoldenTest`, and the `update` package. However, critical gaps remain:
- No polymorphic round-trip test verifies all `DaemonRequest` subtypes across JSON serialization.
- `toCli()` mapping tests for several core models are missing.
- The reflection test hack in `JdiSessionTest.kt:887` (accessing private `eventQueueBuffer` via `getDeclaredField`) is still present and brittle.
**Fix:** Add `DaemonRequest` polymorphic serialization round-trip unit tests, `toCli()` conversion tests, and replace the reflection hack with a test-visible helper.

### H6. Non-JSON outputs violate the "strict JSON" contract
`AGENTS.md` mandates strict JSON output for all CLI commands. However, several CLI paths still emit plain text:
- Default help when no args are provided (`CliRunner.kt:618`).
- `PrintHelpMessage`, `PrintMessage`, and `UsageError` handlers (`CliRunner.kt:626-631`).
- Clikt's built-in `versionOption` output.
An agent parsing CLI standard output with `json.loads` will fail on these paths.
**Fix:** Emit structured JSON error or help models so an agent can always parse stdout as JSON.

### H7. Security model is documented but unauthenticated
The daemon listens on `127.0.0.1:9876` (`DaemonServer.kt:54`) over an unauthenticated TCP socket. It exposes `eval`, variable inspection, and thread manipulation to any local process running on the host machine.
**Fix:** Migrate to a Unix Domain Socket at `$XDG_RUNTIME_DIR/debroid.sock` with `0600` permissions and local session token validation.

### H16. Event buffering has no "stale cursor" signal
When `cursor < eventQueueOffset` (the requested cursor has aged out of the 1000-slot buffer), `JdiSession.pollEvents` (`JdiSession.kt:1017`) silently clamps start index to 0 and replays surviving events. The agent has no signal that it missed intermediate events.
**Fix:** Add `droppedEventsSinceLastPoll: Long` to `EventPollResult`.

### H20. `SemanticVersion.parse` rejects pre-release tags
`SemanticVersion.kt:26` uses `stableVersionRegex = Regex("""^v?(\d+)\.(\d+)\.(\d+)$""")`. While `version.txt` was updated to stable `0.1.0`, any pre-release release tag (e.g., `0.2.0-rc01`) causes `SemanticVersion.parse` to return `null`, breaking `checkOrUpdate`.
**Fix:** Support SemVer 2.0 pre-release identifiers in `SemanticVersion.kt`.

---

## 🟡 Medium (Polish, Error Handling & Edge Cases)

### M1. `AdbManager.findPid` picks first PID without split-process validation
`AdbManager.kt:109` splits `pidof` output and takes `.firstOrNull()`. For multi-process applications, it may silently attach to a background or service process instead of the main process.

### M2. `getMainActivity` rejects launcher components not starting with `appId`
`AdbManager.kt:152` checks `defaultActivity.startsWith(appId)`. Apps where `applicationId` differs from the main activity's package name (flavors, refactored components) fall back to `monkey`, which does not reliably honour `set-debug-app`.

### M3. Object Reference Resolution outside suspended stack scope
While v0.1.0 introduced `objectReferenceCache` for cached object proxies during suspension, objects instantiated outside stack frames or past suspension points may not be resolved if not in cache.

### M4. `JdiSession.isAlive()` is racy
`JdiSession.kt:120` checks `isConnected.get()`. Between the check and VM invocation, the VM may disconnect. Add a `withConnected { }` helper catching `VMDisconnectedException`.

### M5. `DaemonServer.startDaemon` doesn't handle `BindException`
`DaemonServer.kt:54` throws an unhandled `BindException` if port 9876 is occupied by an external process, causing auto-spawn to wait 5s and fail generically.

### M6. Daemon uses unbounded `CachedThreadPool`
`DaemonServer.kt:62` uses `Executors.newCachedThreadPool()`. Replace with a bounded thread pool (`newFixedThreadPool(16)`) to prevent thread exhaustion under aggressive polling.

### M7. `attachToPid` retry loop timeout is hardcoded
`JdiSessionManager.kt:81` hardcodes 10 retries × 300ms (3s max). On slow emulators, this can time out prematurely. Make configurable via environment variable.

### M8. `findAvailableLocalPort` TOCTOU race
`JdiSessionManager.kt:161-165` opens and closes `ServerSocket(0)` before `adb forward`. Another process could claim the port in between. Use `adb forward tcp:0 jdwp:<pid>` to let ADB assign and return the port.

### M10. `VARIABLE_SCOPE.LOCAL` filtering excludes method arguments
In `JdiSession.kt:778`, `if (scope == VariableScope.LOCAL && v.isArgument) continue` excludes arguments from `debroid locals`. Since there is no `debroid args` command, method arguments cannot be inspected in local scope. Include arguments in `LOCAL` scope.

### M11. `DebugEventPayload.exceptionMessage` field is overloaded
In `Models.kt:101`, `exceptionMessage` stores exception class names on `EXCEPTION_HIT` and access/modify messages on `WATCHPOINT_*_HIT`. Split into distinct fields.

### M12. `getFramesSafely` swallows all exceptions
`JdiSession.kt:982` swallows all `Exception` instances without logging when collecting stack traces for events.

### M13. Generated `Version.kt` sourceSet placement
`generateVersionInfo` task writes directly into `cli/build/generated/sources/version/dev/shreyaspatil/debroid/cli/Version.kt`. Ensure explicit source directory wiring across multi-module builds.

### M14. Root build does not include `sample-app`
`settings.gradle.kts` only includes `:core` and `:cli`. `sample-app` is a standalone Gradle build. Add CI validation verifying `sample-app` builds cleanly.

### M15. Skill definition synchronization enforcement
Add a CI verification check ensuring `DaemonRequest` commands match the tools and command references documented in `skills/debroid-cli/SKILL.md`.

### M16. `install.sh` path & prefix flexibility
`install.sh:30` defaults to `~/.local/bin`. Add `$PREFIX` / `$DEBROID_INSTALL_DIR` override support for container and package-managed installations.

### M17. Explicit task dependency for `generateVersionInfo`
Ensure `compileKotlin.dependsOn(generateVersionInfo)` is explicitly registered in `cli/build.gradle.kts`.

### M19. Clikt built-in version option outputs plain text
`versionOption(VERSION)` prints plain text string `debroid 0.1.0`. Wrap or document for AI agent consumers.

### M20. Daemon process startup logging
`DaemonServer.kt:58` uses `println` for daemon lifecycle logs. Route through a structured logger into `daemon.log`.

### M21. `AutoUpdateManagerTest` performs live GitHub network requests
`AutoUpdateManagerTest.kt:18` uses `AutoUpdateManager.DEFAULT`, triggering live requests to `api.github.com` during test execution. Inject mock HTTP client for deterministic offline testing.

### M22. `evaluateExpression` fallback calls `thread.frame(0)` unguarded
`JdiSession.kt:623` invokes `thread.frame(0)` inside the `catch` block, which can throw an uncaught `IncompatibleThreadStateException` if the thread resumed.

### M23. `getPauseState` redundantly calls `findThread` 4 times
`JdiSession.kt:811-815` calls `findThread` 4 times in sequence (`findThread`, `getStackFrames`, `getVariables(LOCAL)`, `getVariables(INSTANCE)`). Pass resolved `ThreadReference` internally.

### M24. `install.sh` local mode writes `stub.sh` in current directory
`install.sh:115` writes `stub.sh` to `./stub.sh`. If executed in a read-only repository path, it fails. Use `mktemp` in temp directory.

### M25. `BinaryUpdater.downloadAndReplaceBinary` creates temp file in `/tmp`
`BinaryUpdater.kt:51` creates temporary files via `File.createTempFile` in the system temp directory. `File.renameTo` across filesystem boundaries is not atomic and will fail if `/tmp` is a separate mount from `~/.local/bin`. Create temp files in `targetBinary.parentFile`.

### M27. `DaemonRequest.Shutdown.force` field is unused
`DaemonRequest.Shutdown` (`DaemonRequest.kt:20`) defines `force: Boolean = false`, but `DaemonServer.kt:106-114` ignores it.

### M28. `inspect --max-depth` has no upper bound
`CliRunner.kt:516` does not cap `--max-depth`. Passing a large depth can cause deep recursion and heavy memory usage. Cap at a reasonable limit (e.g., 5 or 10).

### M29. `setWatchpoint` and `setExceptionBreakpoint` only bind to first `ReferenceType`
`JdiSession.kt:278, 324` only binds to `refTypes.first()`. If classes are loaded across multiple ClassLoaders, only the first instance receives debug requests. Bind across all matching `ReferenceType` instances.

### M32. `extractFrames` calls `thread.frames()` unguarded
`JdiSession.kt:732` calls `thread.frames()` without catching `IncompatibleThreadStateException`. If the thread resumes concurrently, it escapes as an untyped internal error.

### M33. `DaemonServer.Shutdown` socket response flush race
`DaemonServer.kt:109` spawns a thread sleeping 100ms before `exitProcess(0)`. Explicitly flush the socket stream before initiating shutdown.

### M34. `AdbManager.isAppDebuggable` fallback on `run-as` stderr output
`AdbManager.kt:64` merges stderr into stdout. If `run-as` returns exit 0 with a harmless stderr warning, `output == "true"` fails and falls through to dumpsys. Exit code 0 should be treated as success.

### M35. Silent auto-update runs before argument parsing
`CliRunner.kt:606` triggers background auto-update checks before argument validation or help execution.

---

## 🟢 Low / Nice-to-Have

- **L1.** Magic number `.take(50)` in `inspectObject` (`JdiSession.kt:873`); extract to named companion constant.
- **L3.** `getFramesSafely` helper method visibility clean up.
- **L4.** `setBreakpoint` local variable assignment instead of `activeBreakpoints[id]!!` force-unwrap.
- **L5.** `formatValue` null safety on `value.type()`.
- **L6.** `findThread` disambiguation when multiple threads share identical names.
- **L7.** Concurrency lock / mutex per session for thread inspection calls.
- **L9.** Apache-2.0 file license headers in source files.
- **L10.** End-to-end worked examples in `skills/debroid-cli/SKILL.md`.
- **L11.** Explicit `@SerialName` annotations on all `DaemonRequest` subclasses.
- **L12.** Remove unused `fieldsFilter` parameter in `inspectObject` or expose `--fields` in `InspectCommand`.
- **L13.** `BinaryUpdater.resolveCurrentBinaryLocation` fallback when running via `java -jar`.
- **L14.** `GitHubReleaseClient` repository owner/name override via environment variable.

---

## ✅ Fixed Issues & Resolved Blockers

### Core Fixes (B1–B6 & v0.1.0)
- **B1.** Deferred line breakpoints auto-bind on `ClassPrepareEvent` via `sourceName()` + simple-name heuristic.
- **B2.** `catch-exception` semantics corrected: `notifyCaught`/`notifyUncaught` exposed as `--caught`/`--uncaught` flags and tracked for removal.
- **B3.** `inspect --max-depth N` recurses N levels deep with cycle guard; `nested` map populated in response.
- **B4.** `detach` auto-runs `am clear-debug-app` when session was launched via `launch`.
- **B5.** Deferred watchpoints use per-class map (`Map<String, MutableList<DeferredWatchpoint>>`); multiple watchpoints per class supported without overwriting.
- **B6.** `remove-break`, `remove-catch-exception`, `remove-watch` commands implemented; all JDI requests tracked and deletable; orphaned `ClassPrepareRequest` auto-disabled when deferral queue empties.
- **H2.** `debroid stop` daemon shutdown command added (`ShutdownCommand` / `DaemonRequest.Shutdown`).
- **H4.** `pollEvents` thread safety and JMM visibility fixed with `eventQueueLock`, `ArrayDeque`, and `@Volatile eventQueueOffset`.
- **H5.** Compact JSON output by default (`compactJson`) with `--pretty` flag support in `BaseJsonCommand`.
- **H8.** Command names in `README.md` aligned with CLI subcommands (`break`, `stop`, `pause-state`, `step`, etc.).
- **H9.** Step actions in `SKILL.md` and `README.md` aligned with `StepAction` enum values (`STEP_OVER`, `STEP_INTO`, `STEP_OUT`, `RESUME_THREAD`, `RESUME_ALL`).
- **H10.** Dropped `threadId` argument from `resume`; resumes all threads via `RESUME_ALL`.
- **H11.** Removed unused `condition` field from `BreakpointInfo` and signatures.
- **H12.** `isAppDebuggable` rewritten in `AdbManager.kt` using `run-as <appId> true` as primary mechanism.
- **H14.** Expression evaluation upgraded to JDK internal JDI `ExpressionParser` via `JdiExpressionEvaluator.java`.
- **H17.** Detekt `ignoreFailures` set to `false` in `build.gradle.kts`.
- **H18.** Release pipeline builds standalone executable binary stub + Fat JAR.
- **H19.** Cumulative suspend count freeze resolved by setting `StepRequest` to `SUSPEND_EVENT_THREAD` and bounding `RESUME_ALL`.
- **M9.** `setVariable` expression evaluation support added via `JdiExpressionEvaluator` with primitive coercion.
- **M31.** `debroid points` command added (`PointsCommand` / `CliPointsResult`) to query all active breakpoints, watchpoints, and exception points.
- **L2.** Event buffer converted from `CopyOnWriteArrayList` to `ArrayDeque` with `removeFirst()` under lock.
- **L8.** `CHANGELOG.md` created in root directory following Keep-a-Changelog conventions.
- **#43.** App startup breakpoint catching: `launch` suspends by default with `--no-suspend` option, and `attach` supports `--suspend`.

