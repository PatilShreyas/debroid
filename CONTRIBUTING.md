# Contributing to Debroid

Welcome! This document outlines the rules and guidelines for contributing to this project.

## 📖 General Contribution Guidelines

Before writing code or making significant changes, please follow these standard practices:
1. **Check Existing Issues:** Before opening a new issue or starting work, please search the issue tracker to check if the bug or feature request has already been reported.
2. **Discuss Major Changes:** For significant architectural changes, large refactors, or new features, please open an issue to discuss your proposal with the maintainers *before* writing code. This saves time and ensures alignment.
3. **Small Changes:** For minor bug fixes, typos, or small documentation updates, feel free to open a Pull Request directly.

## 🛠️ How to Work on This Project

### 1. Build, Test, and Lint Before Committing
Whenever you make a change, verify that the project compiles, static analysis passes, and all tests pass:
```bash
./gradlew build
```

### 2. Strict JSON Output
When modifying the CLI layer, **never** use raw `println("some string")`. All CLI outputs intended for the user/agent must be printed as serialized JSON via `kotlinx.serialization` to maintain machine-readability.

### 3. State Management & Memory Leaks
The `core` layer handles live VM connections. Always ensure that:
- ADB ports are un-forwarded upon disconnect.
- Event queues (like in `JdiSession`) are bounded to prevent OutOfMemory errors during long-running polls.
- Exceptions thrown by the Android VM (like `AbsentInformationException` or `IncompatibleThreadStateException`) are caught and handled gracefully without crashing the daemon.

## 🚨 CRITICAL RULE: Keeping Skills and Documentation in Sync
We maintain an AI Agent Skill document at **`skills/debroid-cli/SKILL.md`**. This document teaches *other* AI agents how to use the Debroid CLI. We also maintain a command reference in the project's **`README.md`**.

**Whenever you:**
1. Add a new CLI command.
2. Change the arguments or signature of an existing CLI command.
3. Modify the JSON output structure.

**You MUST:**
1. Update the `skills/debroid-cli/SKILL.md` file to reflect the new command signatures, usage examples, and JSON parsing rules. Keeping the skill file synchronized with the CLI is mandatory to ensure AI agents using Debroid do not break!
2. Update the `README.md` file's Command Reference section to include the new or updated command signature and description.

## 🚨 CRITICAL RULE: Changelog Maintenance
We maintain a `CHANGELOG.md` in the root of the project to track all notable changes. 

**Whenever you:**
1. Make any source code related change.
2. Make a tooling related change.
3. Make a SKILL related change.
4. Raise a Pull Request.

**You MUST:**
1. Add an entry describing your changes to the `## [UNRELEASED]` section of `CHANGELOG.md`.
2. Categorize the entry under the appropriate subsection (e.g., `### Added`, `### Fixed`, `### Changed`).
3. If your change fixes a GitHub issue, mention the issue number in the changelog entry.
4. **Never manually mention or change the version number** in `CHANGELOG.md` or `version.txt`. Always append your changes strictly under the `## [UNRELEASED]` header. Versions are bumped automatically during the release process.

## 🚨 CRITICAL RULE: Schema Contracts & Golden Tests
All CLI JSON response models are guarded by Golden Schema tests in `JsonSchemaGoldenTest` (`cli/src/test/resources/golden-schemas/*.schema.json`) to prevent unintended breaking changes for downstream AI agents.

**Whenever you:**
1. **Modify an existing response model (`CliModels.kt`)**:
   - Run `./gradlew test -DupdateGoldenSchemas=true` to update the `.schema.json` files.
   - Verify the diff in `cli/src/test/resources/golden-schemas/` to ensure no unintended field breakages occurred, and commit the updated `.schema.json` files.
2. **Add a NEW CLI command or response model**:
   - Pass the model's `KSerializer` (e.g. `serializer = CliNewResult.serializer()`) to `BaseJsonCommand`.
   - Add a corresponding test method in `JsonSchemaGoldenTest.kt` (e.g. `assertGoldenSchema("new-model.schema.json", CliNewResult.serializer())`).
   - Run `./gradlew test -DupdateGoldenSchemas=true` to generate the initial golden schema file and commit it.
