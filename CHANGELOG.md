# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [UNRELEASED]

### Added
- **Automated Shell PATH Configuration:** Enhanced `install.sh` to automatically detect the user's active shell (`bash`, `zsh`, `fish`) and register `~/.local/bin` in the corresponding profile (`.bashrc`, `.zshrc`, `config.fish`). Includes recursive symlink resolution to preserve GNU Stow / Chezmoi dotfile setups, idempotent delimiter replacement (`# >>> debroid installer >>>`), and automatic skip when already on PATH (#75).
- **Installer Integration Test Suite:** Added a hermetic multi-shell integration test suite (`scripts/test_install.sh`) executed on every CI PR run, validating binary installation, shell PATH exports, subshell CLI version invocation, AI skill extraction, and dotfile symlink safety across environments (#75).

### Fixed
- **JVM User Home Resolution in Launcher Stub:** Updated the standalone binary launcher stub in `install.sh` and release workflow to pass `-Duser.home="$USER_HOME"` to the Java runtime, and added `$HOME` environment variable fallbacks in `SkillExtractor`, `UpdateCache`, and `BinaryUpdater` to ensure proper path resolution under custom `$HOME` configurations and Linux environments (#75).

### Changed

## [v0.2.0] - 2026-08-21

### Added
- **VM Suspend Controls for Launch and Attach:** Added `--no-suspend` option to `debroid launch` and `--suspend` flag to `debroid attach` allowing explicit control over whether the VM should be suspended immediately upon connection (#43).

### Fixed
- **Exit Code 0 for Informational CLI Flags:** Ensured `CliRunner` exits with status code 0 when invoked with `--help`, `--version`, or `--schema` instead of terminating with code 1 (#60).
- **Support Custom Daemon Port and Clean Up Dead Code:** Added `--port` / `-p` option and `DEBROID_PORT` environment variable to `DebroidCli` root command to allow configuring custom daemon ports, and removed unused dead `DebroidCommand` class (#61).
- **Clean ADB Port Forwards on Process Termination:** Registered a JVM shutdown hook in `DaemonServer` to ensure active JDI sessions and ADB port forwards are safely cleaned up and un-forwarded when the daemon process is terminated via `SIGTERM`, `Ctrl+C`, or killed by the OS (#56).
- **Hang on Detach / Stop & App Freezing:** Fixed an issue where `debroid detach` and `debroid stop` hung indefinitely when disconnecting from a stepped or suspended target application on Android ART. Event request tracking is now cleared in-memory without sending blocking JDWP delete requests, suspended threads and the target VM are explicitly resumed upon teardown to keep the app responsive, a 15s timeout safeguard is configured on the `SocketAttach` JDI connector, CLI socket communication timeouts are added, and step execution requests are updated to `SUSPEND_ALL` with `resumeAll()` to prevent deadlocks (#65).
- **Hang in Command Runner on Stream Reading:** Fixed a potential hang in `DefaultCommandRunner` where process standard output was read synchronously before checking process timeout, causing CLI execution to block indefinitely if a subprocess stalled without closing its output stream (#55).
- **Threads Command Schema and Type Mismatch:** Fixed schema descriptor and response format for `debroid threads <session_id>`. Replaced numeric status codes and snake_case strings with strongly-typed `ThreadInfo` and `CliThreadInfo` models featuring camelCase properties (`threadId`, `threadName`, `status`, `isSuspended`) and a typed `ThreadStatus` enum (`RUNNING`, `SLEEPING`, `WAIT`, `MONITOR`, `NOT_STARTED`, `ZOMBIE`, `UNKNOWN`) (#54).
- **App Startup Breakpoint Catching:** Fixed an issue where `debroid launch` returned before the VM was actually suspended (`suspendedThreadsCount: 0`), causing Android to finish startup execution before breakpoints could be set. The VM is now suspended immediately upon connection by default (#43).

## [v0.1.0] - 2026-08-09

### Added
- **State-Based Auto-Update Synchronization:** The CLI now maintains a version cache (`~/.debroid/update-cache.json`) to track its execution state. Upon detecting a binary update, it will automatically extract the bundled `SKILL.md` to `~/.debroid/skills/debroid-cli/SKILL.md` and synchronously alert AI agents to re-read their symlinked instructions by returning a `CLI_UPDATED` JSON error.
- **Daemon Version Handshake:** The background daemon now supports a `GetVersion` protocol. The CLI pings this before executing commands to detect stale daemons left running after a binary update, returning a `VERSION_MISMATCH` JSON error to instruct agents to safely restart the daemon via `debroid stop`.
- **Points Command:** Introduce `debroid points` to list all active debug hooks (breakpoints, watchpoints, and exception points) for an ongoing session to maintain agent state awareness (#37, #36).

### Changed
- **Removed `debroid skill` Command:** The CLI command to print raw skill instructions to `stdout` has been removed. AI agents are now instructed via `README.md` to symlink their internal skills directly to the auto-extracted file (`~/.debroid/skills/debroid-cli/SKILL.md`), significantly reducing token bloat and maintaining synchronization.
- **CLI Error Codes Isolation:** Removed CLI-specific error codes (e.g. `VERSION_MISMATCH`, `CLI_UPDATED`) from the core JDI `ErrorCode` definitions, moving them to a dedicated `CliErrorCode` enum within the CLI module to enforce clean architectural boundaries.
- **Reduced Inspection Noise:** Filter `static` and `synthetic` fields by default during deep object inspection and prevent useless recursion into terminal types. This drastically reduces JSON output size (~30x reduction) to save LLM context tokens (#33, #30).
- **Documentation:** Clarify `objectId` lifecycle and reuse guarantees in `SKILL.md` so agents know nested IDs can be reused as long as the VM is suspended (#32, #29).

### Fixed
- **Ghost Sessions & Disconnect Leaks:** Deduplicate `JdiSessionManager` attachments by `appId` to prevent duplicate JDWP socket connections. Consolidate session teardown to prevent stale requests and memory leaks after unexpected disconnects (#40, #34).
- **Duplicate Event Requests:** Implement deduplication and upsert logic for breakpoints, exception points, and watchpoints. Trying to create an identical debug hook now safely returns the existing ID or upserts the parameters instead of spawning duplicate requests in the VM (#39, #35).
- **Expression Evaluation for Variables:** Overhaul the `set-var` mutation engine to use `JdiExpressionEvaluator`. String variables and complex types are now evaluated as pure Java expressions (with primitive coercion) rather than relying on brittle raw string parsing (#31, #28).
- **Deferred Exception Breakpoints:** Fix exception class filtering which previously failed if the target class wasn't yet loaded by the VM. Exceptions are now deferred until the class is prepared. Also clarified Android-specific behavior around uncaught exceptions (#27, #22).
- **Nested Object Inspection:** Cache `ObjectReference` proxies globally during suspension so nested object IDs revealed via `--max-depth` can be successfully queried in standalone `inspect` calls later (#26, #21).
- **Early Breakpoint Registration:** Mitigate an Android ART bug where breakpoints registered before the first VM resume were silently ignored. The CLI now automatically re-arms early requests upon the first resume (#25, #19).

## [v0.0.1] - 2026-08-06

- Initial release. First version of the CLI.
