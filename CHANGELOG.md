# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [UNRELEASED]

### Added

### Fixed

### Changed

## [v0.1.0] - 2026-08-08

### Added
- **Points Command:** Introduce `debroid points` to list all active debug hooks (breakpoints, watchpoints, and exception points) for an ongoing session to maintain agent state awareness (#37, #36).

### Changed
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
