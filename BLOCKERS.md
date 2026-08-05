# Debroid — Open Issues

Issues remaining after the B1–B6 blocker fixes. Grouped by severity.

---

## 🔴 High-impact (won't crash release, but will seriously degrade AI-agent UX)

### H1. Daemon output is discarded — agents can't debug failures
`CliRunner.ensureDaemonAndSend` (`CliRunner.kt:45`) does `redirectError(DISCARD); redirectOutput(DISCARD)` on the auto-spawned daemon. If the daemon fails (port in use, JDK missing, JDI init error), agents get only "Failed to start background daemon" with zero diagnostic detail.
**Fix:** redirect to `~/.debroid/daemon.log` (rotate on each start). Add `debroid daemon-logs` command to print it.

### H3. No CLI tests; daemon/serialization are unverified
`cli` has zero tests. Polymorphic `DaemonRequest` serialization, `Cli*` conversions, `DemoCLI.parse` arg handling — all unverified. The test coverage in `core` is decent but the reflection-based test hack (`JdiSessionTest.kt:298`) accessing the private buffer has an unchecked-cast warning and is brittle.
**Fix:** add cli tests: polymorphic round-trip of every `DaemonRequest` subtype, `CliModels` `toCli()` conversions, argparse of each subcommand, and replace the reflection hack with a test-visible `pushEvent`/`peekEvents` API.

### H5. Pretty-printed CLI responses waste AI-agent tokens
`DaemonServer`'s `Json { prettyPrint = true }` is the opposite of what you want for AI consumers — every response carries indentation whitespace that costs tokens (and parsing time). The skill explicitly markets "machine-readable JSON"; make it compact by default.
**Fix:** `prettyPrint = false` (or add `--pretty` for human use). Same for `printHelp` / version output.

### H6. Non-JSON outputs violate the "strict JSON" contract
`AGENTS.md` mandates strict JSON, but several CLI paths print free-form text: `getFormattedHelp()` (line 315), `PrintHelpMessage`/`PrintMessage`/`UsageError` (323-327), `versionOption`. An agent parsing JSON with `json.loads` will choke on help text.
**Fix:** wrap help text as a JSON object (`{"help": "...", "commands": [...]}`) so an agent can always `json.loads` output. Same for `--version`.

### H7. Security model is undocumented and unauthenticated
The daemon listens on `127.0.0.1:9876` with no auth, no encryption, and exposes `eval` (which uses `ObjectReference.invokeMethod` to call arbitrary zero-arg methods) — i.e., effectively arbitrary method invocation inside the debugged app and access to thread memory. On a shared dev box any local process can connect. For a public-released tool that's worth flagging.
**Fix:** at minimum document the trust model in README ("only run on a machine where every local user is fully trusted"). Better: a Unix-domain socket at `$XDG_RUNTIME_DIR/debroid.sock` with `0600` perms, plus a token written to a file readable only by the invoking user.


### H11. `break` command silently discards `condition`
`DaemonRequest.Break` has no `condition` field; `DaemonServer` passes `condition = null`. The `BreakpointInfo.condition` field and the SKILL's mention of conditional breakpoints become dead. Either implement a basic JDI `Conditional` filter or remove the field from `BreakpointInfo` and stop advertising the feature.

### H13. `findPid` ps fallback is loose
`AdbManager.kt:101`: `firstOrNull { it.contains(appId) }` will match an unrelated process whose name contains `appId` as a substring (`com.foo` will match `com.foo.bar` and `com.foo.sync`). Use exact match on the last whitespace-separated column, or even tighter match by user+name.

### H15. `formatValue` marks `StringReference` as `isPrimitive = true`
`JdiSession.kt:525`. Strings are reference types; an agent may decide not to `inspect` a string it thinks is primitive, or pipeline tooling that renders refs differently from primitives will misclassify. Set `isPrimitive = false` for `StringReference`.

### H16. Event buffering has no "stale cursor" signal
When `cursor < eventQueueOffset` (the cursor points to an event that aged out of the 1000-slot buffer), `pollEvents` silently replays surviving events from index 0. The agent has no way to know it missed events. Add a `droppedEventsSinceLastPoll: Long` field to `EventPollResult` and surface it in the SKILL.

### H18. Release pipeline only ships the JAR
`release.yml` uploads `debroid.jar`. The `install.sh` self-extracting-stub approach means users on Linux/WSL/Windows need to wrap the jar themselves. Either:
- Build the combined `debroid` executable in release.yml and attach it as `debroid-linux`, `debroid-macos`, `debroid` (it's actually platform-agnostic bash+jar — just attach one).
- Or publish a Brew formula / `sdkman` channel / `cargo-bundle`.


---

## 🟡 Medium (polish before/just after release)

### M1. `AdbManager` hardcoded `pidof` then `ps -A` — won't work with `pidof` returning multiple PIDs (`pidof app` may return "12345 12346" for split-process apps). You take `.firstOrNull()` — silently attaches to the wrong process. Pick by `--pid` filter or warn.

### M2. `getMainActivity` rejects launcher component names that don't `startsWith(appId)` (`AdbManager.kt:131`). Apps whose applicationId differs from the package (build-types/flavors, refactor) will silently fall back to `monkey`, which doesn't respect `set-debug-app` reliably. Either drop the startswith check or warn when rejecting.

### M3. `findObjectReference` scans every suspended thread every call (`JdiSession.kt:560`). For inspections inside deep stacks this is O(threads×frames×locals) each poll. Cache `objectId -> ObjectReference` per suspend/resume cycle. Also, `inspect` on instance-field objects (not stack locals) fails — `findObjectReference` only scans stack frames, not fields of `this`. This is a significant UX gap for agents.

### M4. `JdiSession.isAlive()` is racy: between the check and the next call on the VM, the VM may disconnect. Most usages don't re-check. Wrap calls so they degrade to "session disconnected" cleanly (a small `withConnected { }` helper that catches `VMDisconnectedException`).

### M5. `DaemonServer.startDaemon` (`DaemonServer.kt:38`) doesn't handle `BindException`. If `isDaemonRunning()` says false but the port is occupied (other process), the daemon explodes with a stacktrace and the CLI's auto-spawn waits 5s and reports generic failure.

### M6. Daemon uses an unbounded `CachedThreadPool`. Under tight agent polling this is fine in practice, but pair it with a sane limit (`newFixedThreadPool(16)` or `ThreadPoolExecutor` with a rejection policy).

### M7. `attachToPid` retry loop retries 10×300ms = 3s; on a slow emulator that's tight. Make it configurable via env (`DEBROID_ATTACH_TIMEOUT_MS`).

### M8. `JdiSessionManager.findAvailableLocalPort` (`:107`) has the classic TOCTOU: socket closed before `adb forward`. Use `adb forward tcp:0 jdwp:<pid>` which has adb pick the port (returns it).

### M9. `setVariable` only supports primitives + String; throws INTERNAL_ERROR otherwise. That error code is misleading — there's no internal corruption; it's just unsupported. Add `UNSUPPORTED_TYPE` to `ErrorCode` and use it.

### M10. `VARIABLE_SCOPE.ARGS` vs `LOCAL` filtering in `getVariables` excludes args from `LOCAL` (`JdiSession.kt:438`). This is surprising — most debuggers' "locals" include args. Document or change.

### M11. `DebugEventPayload.exceptionMessage` is overloaded — for `EXCEPTION_HIT` it stores the exception class name, for `WATCHPOINT_*_HIT` it stores the access/modify message, for `DISCONNECT` it's null. Conflating these into one field makes parsers handle multiple cases. Split into `exceptionClass: String?` and `message: String?`.

### M12. `StackTrace` collection in `getFramesSafely` swallows all `Exception`. If a frame call throws `IncompatibleThreadStateException` (real but recoverable), the event has no stack and the agent has no idea why. Log/capture at least once.

### M13. `Version.kt` is generated only into `main` sourceSet; no header comment, no name uniqueness guard — fine for now, but flag in case anyone ships it into a published artifact.

### M14. `settings.gradle.kts` has only `:core` and `:cli`. `sample-app` is its own Gradle build. Good for isolation but means a single `gradlew build` in the root does NOT verify the sample app. Add a CI job that builds `sample-app` (or at least that its APK is reproducible) — this is the canonical demo path agents will follow.

### M15. AGENTS.md mandates skill synchronization but nothing enforces it. Add a CI check that diff-matches `DaemonRequest` commands in `DaemonProtocol.kt` against the SKILL command table (a trivial Python/smoke test), so the "CRITICAL RULE" actually has teeth.

### M16. `install.sh` hard-codes `sudo mv` to `/usr/local/bin`. On modern macOS that's SIP-protected and not user-writable without sudo; on Linux users might not have sudo at all. Honor `$PREFIX` or fall back to `$HOME/.local/bin` and add that to `PATH`. Also: `set -e` + `VERSION=$(cat cli/version.txt | xargs)` — `xargs` strips whitespace; fine, but `tr -d '[:space:]'` reads cleaner.

### M17. Generated `Version.kt` task isn't wired into Kotlin compile explicitly. It worked because `kotlin.srcDir(provider)` indirectly wires it, but make `compileKotlin { dependsOn(generateVersionInfo) }` explicit for IDE/standalone robustness.

### M18. `JdiSession.evaluateExpression` returns `isPrimitive = true` for the synthesized string eval result (`:296`). Should be `false` (it's a StringReference).

### M19. Clikt `versionOption(VERSION)` prints `debroid 0.0.1` as plain text. If an agent probes `debroid --version` and `json.loads`, it fails. Either suppress version to JSON or document that flag-output is plain.

### M20. The daemon redirects `System.out` of the forked JVM only via ProcessBuilder (`redirectOutput(DISCARD)`). But `DaemonServer.startDaemon` itself prints "🤖 Debroid Daemon started..." via `println` (line 39) — that goes to the discarded stream. Fine. But comments in AGENTS.md say "never use raw println" — the daemon has 2 raw `println`s. They're not agent-facing but they violate the stated rule. Either relax the rule (allow for daemon startup) or route them through a logger.

---

## 🟢 Low / nice-to-have

- L1. `MAX_EVENT_BUFFER_SIZE = 1000` is a magic number (detekt). Pull into `companion object`. Same with `.take(50)` in `inspectObject`.
- L2. `JdiSession.eventQueueBuffer` `CopyOnWriteArrayList.removeAt(0)` is O(n) under heavy load; the event thread is on the hot path. Use a `ConcurrentLinkedDeque` + size guard, or `ArrayDeque` with lock.
- L3. `JdiSession` has unused `getFramesSafely` exposed via `private`; uses are fine but it could be inlined or removed.
- L4. `setBreakpoint` returns `activeBreakpoints[id]!!`. If the map was concurrently cleared it would NPE. Use a local `var`.
- L5. `formatValue`'s `else` branch (`:545`) catches non-`Value` types but `value.type().name()` can still NPE if the value's type is null. Add null check.
- L6. `findThread` matches by `uniqueID() == tid || it.name() == threadId`. If two threads have identical names (rare but legal in JVM), you get an arbitrary one. Disambiguate or document.
- L7. `StackTrace` is captured by `getFramesSafely(event.thread())` synchronously inside the event listener — JDI frames are inspected while holding internal locks; if the agent's `inspect`/`frames` call comes in concurrently on another socket thread, it can race. JDI is single-threaded-ish; serialize inspection calls per session via a `Mutex`.
- L8. `cli/version.txt = 0.0.1` with no CHANGELOG.md — add one before public release (track breaking changes of the JSON contract so future agents can adapt).
- L9. No license headers in source files. Apache-2.0 is the LICENSE but most Apache-2.0 projects adopt the per-file header. Tooling (`spotless`) can enforce.
- L10. The `skills/debroid-cli/SKILL.md` provides a great workflow; consider adding an end-to-end worked example (e.g., "debug NullPointerException in MainActivity.kt:42") so agents can pattern-match rather than reinvent the flow.

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
- **H8.** Command names in `README.md` aligned with actual CLI subcommands (`break`, `stop`, `pause-state`, `step`, etc.).
- **H9.** Step actions in `SKILL.md` and `README.md` verified and aligned with `StepAction` enum values (`STEP_OVER`, `STEP_INTO`, `STEP_OUT`, `RESUME_THREAD`, `RESUME_ALL`).
- **H10.** Dropped `threadId` argument from `resume` command; it now explicitly resumes all threads via `RESUME_ALL`, while per-thread resume is correctly relegated to `step <session> <tid> RESUME_THREAD`.
- **H12.** `isAppDebuggable` rewritten in `AdbManager.kt`: uses Android's native `run-as <app_id> true` check as primary mechanism, with package flags check (`flags=[` / `pkgFlags=[`) as fallback.
- **H14.** Expression evaluation upgraded to JDK internal JDI `ExpressionParser` via `JdiExpressionEvaluator.java` Java bridge; supports full method calls, arithmetic, logic, parameter passing, and string operations using Java syntax.
- **H17.** Detekt `ignoreFailures` set to `false` in `build.gradle.kts` so detekt violations fail the build as expected.
- **H19.** Fixed cumulative suspend count freeze by changing `StepRequest` to `SUSPEND_EVENT_THREAD` and ensuring `RESUME_ALL` bounds all suspend counts to 0.

Additional hardening during integration testing:
- JDI `InternalError` from ART's `SourceDebugExtension` parser no longer kills the event listener thread (catch `Throwable` in event loop + `safeSourceName()` helper).
- Clikt flag syntax fixed for `--no-access`/`--no-modify`/`--no-caught`/`--no-uncaught`.
- `bindBreakpointLocation` class-matching `filter{}` catches all `Throwable` (not just `AbsentInformationException`).

---

## Recommended pre-release checklist

1. **H1 (daemon log) + H2 (daemon-stop)**: turns 60% of agent error situations from "give up" into "self-recover".
2. **H5 + H6**: token/compliance issues that materially affect agent efficiency.
3. **H9/H11 (SKILL vs enum mismatch)**: contract bugs in the SKILL will break agents *who trust the SKILL the most*.
4. **H3**: ship CLI tests so external contributors don't break the JSON contract by accident.
5. **H17 / M14 / M15**: tighten CI (detekt teeth, sample-app build, SKILL-sync enforcement).