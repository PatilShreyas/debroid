# Debroid Hot-Reload Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build `debroid hot-reload <app_id>` as a dedicated `:hot-reload` module and CLI command that watches multi-module Android Gradle projects every 1 second, compiles only modified modules to DEX, hot-swaps classes via a native JVMTI agent (`attach-agent`), refreshes Jetpack Compose / Activity UI in-place, and streams fixed plain-text `[STATUS]` lines.

**Architecture:** A new `:hot-reload` Gradle module encapsulates multi-module project discovery (`GradleProjectScanner`), 1-second source polling (`SourceChangeTracker`), leaf-module compilation and `d8` DEX packaging (`IncrementalDexCompiler`), native JVMTI `.so` delivery (`JvmtiAgentInstaller`), and session orchestration (`HotReloadSession`). `:cli` depends on `:hot-reload` and exposes `debroid hot-reload <app_id>` as a foreground streaming command with deterministic plain-text `[STATUS]` output.

**Tech Stack:** Kotlin 2.0.20 (JVM target 11), Clikt 4.4.0, Android SDK `d8` (`build-tools`), Android NDK C++17 JVMTI + JNI (`libdebroid_hotreload.so` for `arm64-v8a` and `x86_64`), JUnit 5, MockK, Detekt 1.23.7.

**Spec:** `docs/superpowers/specs/2026-10-31-hot-reload-design.md`

## Global Constraints

- Target JVM 11 across all Kotlin modules (`jvmTarget = "11"`, `sourceCompatibility = "11"`).
- All code must pass `./gradlew detekt test :cli:build` with zero Detekt issues (`maxLineLength = 140`, no wildcard imports except `java.util.*`, no `TODO:` comments).
- `debroid hot-reload` is an explicit exception to the JSON-only CLI rule and emits fixed single-line `[STATUS]` plain-text logs to `stdout`.
- Keep `:core` isolated from hot-reload logic; all new hot-reload classes and native resources reside in `:hot-reload`.
- Synchronize `skills/debroid-cli/SKILL.md`, `README.md`, and `CHANGELOG.md` (`## [UNRELEASED]`) when adding the CLI command.

---

### Task 1: Create `:hot-reload` Module & Domain Models (`HotReloadModels.kt`)

**Files:**
- Modify: `settings.gradle.kts`
- Create: `hot-reload/build.gradle.kts`
- Create: `hot-reload/src/main/kotlin/dev/shreyaspatil/debroid/hotreload/HotReloadModels.kt`
- Test: `hot-reload/src/test/kotlin/dev/shreyaspatil/debroid/hotreload/HotReloadModelsTest.kt`

**Interfaces:**
- Consumes: `:core` (`dev.shreyaspatil.debroid.adb.AdbManager`, `dev.shreyaspatil.debroid.adb.CommandRunner`)
- Produces:
  - `enum class ModuleType { ANDROID_APP, ANDROID_LIBRARY, KOTLIN_JVM }`
  - `data class GradleModule(val gradlePath: String, val moduleDir: File, val type: ModuleType)`
  - `data class DetectedChanges(val codeChangesByModule: Map<GradleModule, List<File>>, val resourceFiles: List<File>)`
  - `enum class ReloadMode { JVMTI_HOT_SWAP, JVMTI_STRUCTURAL_SWAP, FALLBACK_INSTALL }`
  - `enum class UiRefreshMode { COMPOSE_HOT_RELOAD, ACTIVITY_RECREATE, APP_RESTART, NONE }`
  - `sealed class HotReloadEvent` with `fun formatPlainText(): String`

- [ ] **Step 1: Register `:hot-reload` in `settings.gradle.kts` and create `hot-reload/build.gradle.kts`**

Update `settings.gradle.kts`:
```kotlin
rootProject.name = "debroid"
include(":core")
include(":hot-reload")
include(":cli")
```

Create `hot-reload/build.gradle.kts`:
```kotlin
plugins {
    kotlin("jvm")
    jacoco
}

dependencies {
    implementation(project(":core"))

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlin.test)
}

tasks.test {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(true)
    }
}
```

- [ ] **Step 2: Write the failing test `HotReloadModelsTest.kt`**

Create `hot-reload/src/test/kotlin/dev/shreyaspatil/debroid/hotreload/HotReloadModelsTest.kt`:
```kotlin
package dev.shreyaspatil.debroid.hotreload

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class HotReloadModelsTest {

    @Test
    fun `HotReloadEvent formats deterministic plain text lines`() {
        val watching = HotReloadEvent.Watching(
            appId = "com.example.app",
            pid = 14230,
            moduleCount = 42,
            intervalMs = 1000L
        )
        assertEquals(
            "[WATCHING] app=com.example.app pid=14230 modules=42 interval=1000ms",
            watching.formatPlainText()
        )

        val reloading = HotReloadEvent.Reloading(
            modules = listOf(":feature:home"),
            files = listOf("HomeScreen.kt")
        )
        assertEquals(
            "[RELOADING] modules=[:feature:home] files=[HomeScreen.kt]",
            reloading.formatPlainText()
        )

        val reloaded = HotReloadEvent.Reloaded(
            mode = ReloadMode.JVMTI_HOT_SWAP,
            redefinedCount = 2,
            newClassesCount = 1,
            uiRefresh = UiRefreshMode.COMPOSE_HOT_RELOAD,
            durationMs = 840L
        )
        assertEquals(
            "[RELOADED] mode=JVMTI_HOT_SWAP classes=2 new_classes=1 ui=COMPOSE_HOT_RELOAD duration=840ms",
            reloaded.formatPlainText()
        )

        val compileError = HotReloadEvent.CompileError(
            module = ":feature:home",
            diagnostic = "HomeScreen.kt:42:17 Unresolved reference 'foo'"
        )
        assertEquals(
            "[COMPILE_ERROR] module=:feature:home HomeScreen.kt:42:17 Unresolved reference 'foo'",
            compileError.formatPlainText()
        )

        val fallback = HotReloadEvent.FallbackReinstall(
            reason = "RESOURCE_CHANGE",
            files = listOf("activity_main.xml"),
            durationMs = 4120L
        )
        assertEquals(
            "[FALLBACK_REINSTALL] reason=RESOURCE_CHANGE files=[activity_main.xml] duration=4120ms",
            fallback.formatPlainText()
        )

        val error = HotReloadEvent.Error(
            code = "APP_NOT_RUNNING",
            message = "Process for com.example.app is not running"
        )
        assertEquals(
            "[ERROR] code=APP_NOT_RUNNING message=Process for com.example.app is not running",
            error.formatPlainText()
        )

        val stopped = HotReloadEvent.Stopped(appId = "com.example.app")
        assertEquals(
            "[STOPPED] app=com.example.app",
            stopped.formatPlainText()
        )
    }

    @Test
    fun `DetectedChanges reports empty and non-empty states accurately`() {
        val empty = DetectedChanges(emptyMap(), emptyList())
        assertTrue(empty.isEmpty())

        val module = GradleModule(":app", File("/tmp/app"), ModuleType.ANDROID_APP)
        val nonEmpty = DetectedChanges(mapOf(module to listOf(File("/tmp/app/Main.kt"))), emptyList())
        assertFalse(nonEmpty.isEmpty())
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :hot-reload:test`
Expected: FAIL with compilation error (`Unresolved reference: HotReloadEvent`).

- [ ] **Step 4: Implement `HotReloadModels.kt`**

Create `hot-reload/src/main/kotlin/dev/shreyaspatil/debroid/hotreload/HotReloadModels.kt`:
```kotlin
package dev.shreyaspatil.debroid.hotreload

import java.io.File

enum class ModuleType {
    ANDROID_APP,
    ANDROID_LIBRARY,
    KOTLIN_JVM
}

data class GradleModule(
    val gradlePath: String,
    val moduleDir: File,
    val type: ModuleType
)

data class DetectedChanges(
    val codeChangesByModule: Map<GradleModule, List<File>>,
    val resourceFiles: List<File>
) {
    fun isEmpty(): Boolean = codeChangesByModule.isEmpty() && resourceFiles.isEmpty()
}

data class ClassDexPayload(
    val className: String,
    val dexFile: File
)

sealed class CompilationOutcome {
    data class Success(
        val classPayloads: List<ClassDexPayload>,
        val combinedDexFile: File?
    ) : CompilationOutcome()

    data class Failure(
        val module: String,
        val diagnostic: String
    ) : CompilationOutcome()
}

enum class ReloadMode {
    JVMTI_HOT_SWAP,
    JVMTI_STRUCTURAL_SWAP,
    FALLBACK_INSTALL
}

enum class UiRefreshMode {
    COMPOSE_HOT_RELOAD,
    ACTIVITY_RECREATE,
    APP_RESTART,
    NONE
}

sealed class AgentExecutionResult {
    data class Success(
        val mode: ReloadMode,
        val redefinedCount: Int,
        val newClassesCount: Int,
        val uiRefresh: UiRefreshMode
    ) : AgentExecutionResult()

    data class StructuralIncompatible(val reason: String) : AgentExecutionResult()

    data class Error(val code: String, val message: String) : AgentExecutionResult()
}

sealed class HotReloadEvent {
    abstract fun formatPlainText(): String

    data class Watching(
        val appId: String,
        val pid: Int,
        val moduleCount: Int,
        val intervalMs: Long
    ) : HotReloadEvent() {
        override fun formatPlainText(): String =
            "[WATCHING] app=$appId pid=$pid modules=$moduleCount interval=${intervalMs}ms"
    }

    data class Reloading(
        val modules: List<String>,
        val files: List<String>
    ) : HotReloadEvent() {
        override fun formatPlainText(): String =
            "[RELOADING] modules=[${modules.joinToString(", ")}] files=[${files.joinToString(", ")}]"
    }

    data class Reloaded(
        val mode: ReloadMode,
        val redefinedCount: Int,
        val newClassesCount: Int,
        val uiRefresh: UiRefreshMode,
        val durationMs: Long
    ) : HotReloadEvent() {
        override fun formatPlainText(): String =
            "[RELOADED] mode=${mode.name} classes=$redefinedCount new_classes=$newClassesCount " +
                "ui=${uiRefresh.name} duration=${durationMs}ms"
    }

    data class CompileError(
        val module: String,
        val diagnostic: String
    ) : HotReloadEvent() {
        override fun formatPlainText(): String =
            "[COMPILE_ERROR] module=$module $diagnostic"
    }

    data class FallbackReinstall(
        val reason: String,
        val files: List<String>,
        val durationMs: Long
    ) : HotReloadEvent() {
        override fun formatPlainText(): String =
            "[FALLBACK_REINSTALL] reason=$reason files=[${files.joinToString(", ")}] duration=${durationMs}ms"
    }

    data class Error(
        val code: String,
        val message: String
    ) : HotReloadEvent() {
        override fun formatPlainText(): String =
            "[ERROR] code=$code message=$message"
    }

    data class Stopped(
        val appId: String
    ) : HotReloadEvent() {
        override fun formatPlainText(): String =
            "[STOPPED] app=$appId"
    }
}
```

- [ ] **Step 5: Run tests and Detekt to verify**

Run: `./gradlew :hot-reload:detekt :hot-reload:test`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add settings.gradle.kts hot-reload/build.gradle.kts \
  hot-reload/src/main/kotlin/dev/shreyaspatil/debroid/hotreload/HotReloadModels.kt \
  hot-reload/src/test/kotlin/dev/shreyaspatil/debroid/hotreload/HotReloadModelsTest.kt
git commit -m "feat(hot-reload): initialize hot-reload module and domain models"
```

---

### Task 2: Implement Multi-Module `GradleProjectScanner`

**Files:**
- Create: `hot-reload/src/main/kotlin/dev/shreyaspatil/debroid/hotreload/GradleProjectScanner.kt`
- Test: `hot-reload/src/test/kotlin/dev/shreyaspatil/debroid/hotreload/GradleProjectScannerTest.kt`

**Interfaces:**
- Consumes: `GradleModule`, `ModuleType` from `HotReloadModels.kt`
- Produces:
  - `data class GradleProjectLayout(val rootDir: File, val modules: List<GradleModule>)` with:
    - `fun resolveModuleForFile(file: File): GradleModule?`
    - `fun findAppModule(appId: String): GradleModule?`
  - `object GradleProjectScanner` with `fun scan(startDir: File): GradleProjectLayout`

- [ ] **Step 1: Write the failing test `GradleProjectScannerTest.kt`**

Create `hot-reload/src/test/kotlin/dev/shreyaspatil/debroid/hotreload/GradleProjectScannerTest.kt`:
```kotlin
package dev.shreyaspatil.debroid.hotreload

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class GradleProjectScannerTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `scans multi-module project and classifies Android App, Library, and JVM modules`() {
        File(tempDir, "settings.gradle.kts").writeText(
            """
            rootProject.name = "mega-app"
            include(":app")
            include(":feature:home:ui")
            include(":core:model")
            """.trimIndent()
        )

        val appDir = File(tempDir, "app").apply { mkdirs() }
        File(appDir, "build.gradle.kts").writeText(
            """
            plugins {
                id("com.android.application")
                kotlin("android")
            }
            android {
                namespace = "com.example.mega"
                defaultConfig {
                    applicationId = "com.example.mega"
                }
            }
            """.trimIndent()
        )

        val homeUiDir = File(tempDir, "feature/home/ui").apply { mkdirs() }
        File(homeUiDir, "build.gradle.kts").writeText(
            """
            plugins {
                alias(libs.plugins.android.library)
            }
            """.trimIndent()
        )

        val coreModelDir = File(tempDir, "core/model").apply { mkdirs() }
        File(coreModelDir, "build.gradle.kts").writeText(
            """
            plugins {
                kotlin("jvm")
            }
            """.trimIndent()
        )

        // Ignored build dir with a fake build.gradle.kts
        val ignoredBuildDir = File(tempDir, "app/build/intermediates").apply { mkdirs() }
        File(ignoredBuildDir, "build.gradle.kts").writeText("plugins { kotlin(\"jvm\") }")

        // Scan starting from a nested subdirectory to test upward root discovery
        val layout = GradleProjectScanner.scan(homeUiDir)

        assertEquals(tempDir.canonicalFile, layout.rootDir)
        assertEquals(3, layout.modules.size)

        val modulesByPath = layout.modules.associateBy { it.gradlePath }
        assertEquals(ModuleType.ANDROID_APP, modulesByPath[":app"]?.type)
        assertEquals(ModuleType.ANDROID_LIBRARY, modulesByPath[":feature:home:ui"]?.type)
        assertEquals(ModuleType.KOTLIN_JVM, modulesByPath[":core:model"]?.type)

        val homeSourceFile = File(homeUiDir, "src/main/kotlin/com/example/HomeScreen.kt")
        assertEquals(":feature:home:ui", layout.resolveModuleForFile(homeSourceFile)?.gradlePath)

        val outsideFile = File("/tmp/unrelated/File.kt")
        assertNull(layout.resolveModuleForFile(outsideFile))

        val matchedApp = layout.findAppModule("com.example.mega")
        assertNotNull(matchedApp)
        assertEquals(":app", matchedApp?.gradlePath)
    }

    @Test
    fun `respects custom projectDir mapping in settings gradle`() {
        File(tempDir, "settings.gradle.kts").writeText(
            """
            rootProject.name = "custom-layout"
            include(":custom-feature")
            project(":custom-feature").projectDir = file("modules/feature-x")
            """.trimIndent()
        )

        val customDir = File(tempDir, "modules/feature-x").apply { mkdirs() }
        File(customDir, "build.gradle").writeText(
            """
            plugins {
                id 'com.android.library'
            }
            """.trimIndent()
        )

        val layout = GradleProjectScanner.scan(tempDir)
        assertEquals(1, layout.modules.size)
        val mod = layout.modules.first()
        assertEquals(":custom-feature", mod.gradlePath)
        assertEquals(customDir.canonicalFile, mod.moduleDir)
        assertEquals(ModuleType.ANDROID_LIBRARY, mod.type)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :hot-reload:test`
Expected: FAIL with compilation error (`Unresolved reference: GradleProjectScanner`).

- [ ] **Step 3: Implement `GradleProjectScanner.kt`**

Create `hot-reload/src/main/kotlin/dev/shreyaspatil/debroid/hotreload/GradleProjectScanner.kt`:
```kotlin
package dev.shreyaspatil.debroid.hotreload

import java.io.File

data class GradleProjectLayout(
    val rootDir: File,
    val modules: List<GradleModule>
) {
    private val sortedByDepth = modules.sortedByDescending { it.moduleDir.path.length }

    fun resolveModuleForFile(file: File): GradleModule? {
        val canonicalPath = file.canonicalFile.path
        return sortedByDepth.firstOrNull { module ->
            val modPath = module.moduleDir.path
            canonicalPath == modPath || canonicalPath.startsWith("$modPath${File.separator}")
        }
    }

    fun findAppModule(appId: String): GradleModule? {
        val appModules = modules.filter { it.type == ModuleType.ANDROID_APP }
        val exactMatch = appModules.firstOrNull { module ->
            val script = findBuildScript(module.moduleDir)?.readText().orEmpty()
            val manifestFile = File(module.moduleDir, "src/main/AndroidManifest.xml")
            val manifestText = if (manifestFile.exists()) manifestFile.readText() else ""
            script.contains(appId) || manifestText.contains(appId)
        }
        return exactMatch ?: appModules.firstOrNull() ?: modules.firstOrNull()
    }

    private fun findBuildScript(dir: File): File? {
        return File(dir, "build.gradle.kts").takeIf { it.exists() }
            ?: File(dir, "build.gradle").takeIf { it.exists() }
    }
}

object GradleProjectScanner {
    private val ignoredDirNames = setOf(
        ".git",
        ".gradle",
        "build",
        ".idea",
        ".kotlin",
        "buildSrc"
    )

    private val customProjectDirRegex = Regex(
        """project\(\s*["']([^"']+)["']\s*\)\.projectDir\s*=\s*file\(\s*["']([^"']+)["']\s*\)"""
    )

    fun scan(startDir: File): GradleProjectLayout {
        val rootDir = findGradleRoot(startDir.canonicalFile) ?: startDir.canonicalFile
        val customPathByDir = parseCustomProjectDirs(rootDir)

        val discoveredModules = mutableListOf<GradleModule>()
        scanDirectoryRecursively(rootDir, rootDir, customPathByDir, discoveredModules)

        return GradleProjectLayout(
            rootDir = rootDir,
            modules = discoveredModules.sortedBy { it.gradlePath }
        )
    }

    private fun findGradleRoot(startDir: File): File? {
        var current: File? = if (startDir.isDirectory) startDir else startDir.parentFile
        while (current != null) {
            if (File(current, "settings.gradle.kts").exists() || File(current, "settings.gradle").exists()) {
                return current
            }
            current = current.parentFile
        }
        return null
    }

    private fun parseCustomProjectDirs(rootDir: File): Map<File, String> {
        val settingsFile = File(rootDir, "settings.gradle.kts").takeIf { it.exists() }
            ?: File(rootDir, "settings.gradle").takeIf { it.exists() }
            ?: return emptyMap()

        val text = settingsFile.readText()
        val result = mutableMapOf<File, String>()
        for (match in customProjectDirRegex.findAll(text)) {
            val gradlePath = match.groupValues[1].let { if (it.startsWith(":")) it else ":$it" }
            val relDir = match.groupValues[2]
            val targetDir = File(rootDir, relDir).canonicalFile
            result[targetDir] = gradlePath
        }
        return result
    }

    private fun scanDirectoryRecursively(
        rootDir: File,
        currentDir: File,
        customPathByDir: Map<File, String>,
        outModules: MutableList<GradleModule>
    ) {
        if (currentDir != rootDir && currentDir.name in ignoredDirNames) {
            return
        }

        if (currentDir != rootDir) {
            val buildScript = File(currentDir, "build.gradle.kts").takeIf { it.exists() }
                ?: File(currentDir, "build.gradle").takeIf { it.exists() }
            if (buildScript != null) {
                val canonicalDir = currentDir.canonicalFile
                val gradlePath = customPathByDir[canonicalDir] ?: toGradlePath(rootDir, canonicalDir)
                val moduleType = classifyModuleType(buildScript, canonicalDir)
                outModules.add(
                    GradleModule(
                        gradlePath = gradlePath,
                        moduleDir = canonicalDir,
                        type = moduleType
                    )
                )
            }
        }

        val children = currentDir.listFiles() ?: return
        for (child in children) {
            if (child.isDirectory && child.name !in ignoredDirNames) {
                scanDirectoryRecursively(rootDir, child, customPathByDir, outModules)
            }
        }
    }

    private fun toGradlePath(rootDir: File, moduleDir: File): String {
        val relative = moduleDir.relativeTo(rootDir).invariantSeparatorsPath
        return ":" + relative.replace('/', ':')
    }

    private fun classifyModuleType(buildScript: File, moduleDir: File): ModuleType {
        val content = buildScript.readText()
        val isAndroidApp = content.contains("com.android.application") ||
            content.contains("android.application") ||
            content.contains("androidApplication") ||
            content.contains("applicationId")
        if (isAndroidApp) {
            return ModuleType.ANDROID_APP
        }

        val hasManifest = File(moduleDir, "src/main/AndroidManifest.xml").exists()
        val isAndroidLib = content.contains("com.android.library") ||
            content.contains("android.library") ||
            content.contains("androidLibrary") ||
            content.contains("android {") ||
            hasManifest
        if (isAndroidLib) {
            return ModuleType.ANDROID_LIBRARY
        }

        return ModuleType.KOTLIN_JVM
    }
}
```

- [ ] **Step 4: Run tests and Detekt to verify**

Run: `./gradlew :hot-reload:detekt :hot-reload:test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add hot-reload/src/main/kotlin/dev/shreyaspatil/debroid/hotreload/GradleProjectScanner.kt \
  hot-reload/src/test/kotlin/dev/shreyaspatil/debroid/hotreload/GradleProjectScannerTest.kt
git commit -m "feat(hot-reload): add multi-module GradleProjectScanner"
```

---

### Task 3: Implement 1-Second Scoped `SourceChangeTracker`

**Files:**
- Create: `hot-reload/src/main/kotlin/dev/shreyaspatil/debroid/hotreload/SourceChangeTracker.kt`
- Test: `hot-reload/src/test/kotlin/dev/shreyaspatil/debroid/hotreload/SourceChangeTrackerTest.kt`

**Interfaces:**
- Consumes: `GradleProjectLayout`, `GradleModule`, `DetectedChanges`
- Produces:
  - `class SourceChangeTracker(private val layout: GradleProjectLayout)` with:
    - `fun initializeBaseline()`
    - `fun pollChanges(): DetectedChanges`
    - `fun commitChanges(changes: DetectedChanges)` (updates baseline only after successful reload/compile so failed compiles retry when fixed)

- [ ] **Step 1: Write the failing test `SourceChangeTrackerTest.kt`**

Create `hot-reload/src/test/kotlin/dev/shreyaspatil/debroid/hotreload/SourceChangeTrackerTest.kt`:
```kotlin
package dev.shreyaspatil.debroid.hotreload

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class SourceChangeTrackerTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `detects modified and newly added Kotlin and XML files across modules while ignoring touch-only edits`() {
        File(tempDir, "settings.gradle.kts").writeText("include(\":app\", \":feature:home\")")
        val appDir = File(tempDir, "app").apply { mkdirs() }
        File(appDir, "build.gradle.kts").writeText("plugins { id(\"com.android.application\") }")

        val homeDir = File(tempDir, "feature/home").apply { mkdirs() }
        File(homeDir, "build.gradle.kts").writeText("plugins { id(\"com.android.library\") }")

        val mainKt = File(appDir, "src/main/java/com/example/MainActivity.kt").apply {
            parentFile.mkdirs()
            writeText("package com.example\nclass MainActivity")
        }
        val homeKt = File(homeDir, "src/main/kotlin/com/example/HomeScreen.kt").apply {
            parentFile.mkdirs()
            writeText("package com.example\nfun HomeScreen() {}")
        }

        val layout = GradleProjectScanner.scan(tempDir)
        val tracker = SourceChangeTracker(layout)
        tracker.initializeBaseline()

        // Initial poll with no changes
        assertTrue(tracker.pollChanges().isEmpty())

        // Touch file with identical content -> should NOT trigger change
        homeKt.setLastModified(homeKt.lastModified() + 5000L)
        assertTrue(tracker.pollChanges().isEmpty())

        // Modify HomeScreen.kt content
        homeKt.writeText("package com.example\nfun HomeScreen() { println(\"updated\") }")
        homeKt.setLastModified(homeKt.lastModified() + 10_000L)

        val detected = tracker.pollChanges()
        assertFalse(detected.isEmpty())
        assertEquals(1, detected.codeChangesByModule.size)
        val changedModule = detected.codeChangesByModule.keys.first()
        assertEquals(":feature:home", changedModule.gradlePath)
        assertEquals(listOf(homeKt.canonicalFile), detected.codeChangesByModule[changedModule])

        // Before commitChanges, polling again still reports the uncommitted change
        assertFalse(tracker.pollChanges().isEmpty())

        // Commit changes -> next poll is empty
        tracker.commitChanges(detected)
        assertTrue(tracker.pollChanges().isEmpty())

        // Add a resource XML file
        val stringsXml = File(appDir, "src/main/res/values/strings.xml").apply {
            parentFile.mkdirs()
            writeText("<resources></resources>")
        }
        val resChanges = tracker.pollChanges()
        assertEquals(listOf(stringsXml.canonicalFile), resChanges.resourceFiles)
        assertTrue(resChanges.codeChangesByModule.isEmpty())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :hot-reload:test`
Expected: FAIL with compilation error (`Unresolved reference: SourceChangeTracker`).

- [ ] **Step 3: Implement `SourceChangeTracker.kt`**

Create `hot-reload/src/main/kotlin/dev/shreyaspatil/debroid/hotreload/SourceChangeTracker.kt`:
```kotlin
package dev.shreyaspatil.debroid.hotreload

import java.io.File
import java.security.MessageDigest

class SourceChangeTracker(
    private val layout: GradleProjectLayout
) {
    private data class FileFingerprint(
        val lastModified: Long,
        val length: Long,
        val sha256: String
    )

    private val baseline = mutableMapOf<File, FileFingerprint>()
    private val pendingSnapshots = mutableMapOf<File, FileFingerprint>()

    fun initializeBaseline() {
        baseline.clear()
        pendingSnapshots.clear()
        for ((_, file) in scanAllTrackedFiles()) {
            baseline[file] = fingerprint(file)
        }
    }

    fun pollChanges(): DetectedChanges {
        pendingSnapshots.clear()
        val codeChanges = mutableMapOf<GradleModule, MutableList<File>>()
        val resourceChanges = mutableListOf<File>()

        for ((module, file) in scanAllTrackedFiles()) {
            val previous = baseline[file]
            val currentModified = file.lastModified()
            val currentLength = file.length()

            if (previous != null && previous.lastModified == currentModified && previous.length == currentLength) {
                continue
            }

            val currentFp = fingerprint(file)
            if (previous != null && previous.sha256 == currentFp.sha256) {
                // Only timestamp changed; update baseline timestamp silently
                baseline[file] = currentFp
                continue
            }

            pendingSnapshots[file] = currentFp
            if (isCodeFile(file)) {
                codeChanges.computeIfAbsent(module) { mutableListOf() }.add(file)
            } else if (isResourceOrManifestFile(file)) {
                resourceChanges.add(file)
            }
        }

        return DetectedChanges(
            codeChangesByModule = codeChanges,
            resourceFiles = resourceChanges
        )
    }

    fun commitChanges(changes: DetectedChanges) {
        val allFiles = changes.codeChangesByModule.values.flatten() + changes.resourceFiles
        for (file in allFiles) {
            val canonical = file.canonicalFile
            val fp = pendingSnapshots[canonical] ?: if (canonical.exists()) fingerprint(canonical) else null
            if (fp != null) {
                baseline[canonical] = fp
            }
        }
    }

    private fun scanAllTrackedFiles(): List<Pair<GradleModule, File>> {
        val results = mutableListOf<Pair<GradleModule, File>>()
        for (module in layout.modules) {
            val srcDir = File(module.moduleDir, "src")
            if (!srcDir.isDirectory) continue
            srcDir.walkTopDown()
                .onEnter { dir -> ! isIgnoredSourceSet(dir, srcDir) }
                .filter { it.isFile && (isCodeFile(it) || isResourceOrManifestFile(it)) }
                .forEach { file ->
                    results.add(module to file.canonicalFile)
                }
        }
        return results
    }

    private fun isIgnoredSourceSet(dir: File, srcDir: File): Boolean {
        if (dir.parentFile != srcDir) return false
        val name = dir.name.lowercase()
        return name.endsWith("test") || name.startsWith("test") || name.startsWith("androidtest")
    }

    private fun isCodeFile(file: File): Boolean {
        val ext = file.extension.lowercase()
        return ext == "kt" || ext == "java"
    }

    private fun isResourceOrManifestFile(file: File): Boolean {
        if (file.name == "AndroidManifest.xml") return true
        if (file.extension.lowercase() != "xml") return false
        val path = file.invariantSeparatorsPath
        return path.contains("/res/")
    }

    private fun fingerprint(file: File): FileFingerprint {
        val bytes = file.readBytes()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val hex = digest.joinToString("") { "%02x".format(it) }
        return FileFingerprint(
            lastModified = file.lastModified(),
            length = bytes.size.toLong(),
            sha256 = hex
        )
    }
}
```

- [ ] **Step 4: Run tests and Detekt to verify**

Run: `./gradlew :hot-reload:detekt :hot-reload:test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add hot-reload/src/main/kotlin/dev/shreyaspatil/debroid/hotreload/SourceChangeTracker.kt \
  hot-reload/src/test/kotlin/dev/shreyaspatil/debroid/hotreload/SourceChangeTrackerTest.kt
git commit -m "feat(hot-reload): add 1-second scoped SourceChangeTracker"
```

---

### Task 4: Implement Leaf-Module `IncrementalDexCompiler` (`gradlew` + `d8`)

**Files:**
- Create: `hot-reload/src/main/kotlin/dev/shreyaspatil/debroid/hotreload/IncrementalDexCompiler.kt`
- Test: `hot-reload/src/test/kotlin/dev/shreyaspatil/debroid/hotreload/IncrementalDexCompilerTest.kt`

**Interfaces:**
- Consumes: `CommandRunner`, `DefaultCommandRunner` from `:core`; `GradleModule`, `ModuleType`, `CompilationOutcome`, `ClassDexPayload` from `:hot-reload`
- Produces:
  - `class IncrementalDexCompiler(private val projectRoot: File, private val variant: String = "debug", private val commandRunner: CommandRunner = DefaultCommandRunner(), private val d8PathProvider: () -> String = { findD8Executable() })` with:
    - `fun snapshotModuleClasses(modules: Collection<GradleModule>)`
    - `fun compileAndDex(changesByModule: Map<GradleModule, List<File>>, stagingDir: File): CompilationOutcome`
    - `fun buildGradleTasksForModules(changesByModule: Map<GradleModule, List<File>>): List<String>`

- [ ] **Step 1: Write the failing test `IncrementalDexCompilerTest.kt`**

Create `hot-reload/src/test/kotlin/dev/shreyaspatil/debroid/hotreload/IncrementalDexCompilerTest.kt`:
```kotlin
package dev.shreyaspatil.debroid.hotreload

import dev.shreyaspatil.debroid.adb.CommandRunner
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class IncrementalDexCompilerTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `builds targeted Gradle tasks for Android and JVM modules`() {
        val compiler = IncrementalDexCompiler(projectRoot = tempDir, variant = "freeDebug")
        val appModule = GradleModule(":app", File(tempDir, "app"), ModuleType.ANDROID_APP)
        val libModule = GradleModule(":feature:home", File(tempDir, "feature/home"), ModuleType.ANDROID_LIBRARY)
        val jvmModule = GradleModule(":core:model", File(tempDir, "core/model"), ModuleType.KOTLIN_JVM)

        val tasks = compiler.buildGradleTasksForModules(
            mapOf(
                appModule to listOf(File("MainActivity.kt")),
                libModule to listOf(File("HomeScreen.kt"), File("LegacyHelper.java")),
                jvmModule to listOf(File("User.kt"))
            )
        )

        assertEquals(
            listOf(
                ":app:compileFreeDebugKotlin",
                ":feature:home:compileFreeDebugKotlin",
                ":feature:home:compileFreeDebugJavaWithJavac",
                ":core:model:compileKotlin"
            ),
            tasks
        )
    }

    @Test
    fun `diffs modified class files and invokes d8 to produce single-class and combined dex payloads`() {
        val homeDir = File(tempDir, "feature/home").apply { mkdirs() }
        val classesDir = File(homeDir, "build/tmp/kotlin-classes/debug/com/example/home").apply { mkdirs() }
        val unchangedClass = File(classesDir, "Unchanged.class").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val modifiedClass = File(classesDir, "HomeScreenKt.class").apply { writeBytes(byteArrayOf(4, 5, 6)) }

        val module = GradleModule(":feature:home", homeDir, ModuleType.ANDROID_LIBRARY)
        val runner = mockk<CommandRunner>()
        val commands = mutableListOf<List<String>>()

        every { runner.runCommand(capture(commands), any()) } answers {
            val cmd = firstArg<List<String>>()
            if (cmd.first().endsWith("gradlew") || cmd.first().endsWith("gradlew.bat")) {
                // Simulate Kotlin compiler updating HomeScreenKt.class and adding a new lambda class
                modifiedClass.writeBytes(byteArrayOf(4, 5, 6, 7))
                File(classesDir, "HomeScreenKt\$HomeScreen\$1.class").writeBytes(byteArrayOf(9, 9, 9))
                Result.success("BUILD SUCCESSFUL")
            } else if (cmd.first() == "/mock/d8") {
                val outIdx = cmd.indexOf("--output")
                val outDir = File(cmd[outIdx + 1])
                outDir.mkdirs()
                File(outDir, "classes.dex").writeBytes(byteArrayOf(0x64, 0x65, 0x78))
                Result.success("")
            } else {
                Result.failure(RuntimeException("Unexpected command: $cmd"))
            }
        }

        val compiler = IncrementalDexCompiler(
            projectRoot = tempDir,
            variant = "debug",
            commandRunner = runner,
            d8PathProvider = { "/mock/d8" }
        )
        compiler.snapshotModuleClasses(listOf(module))

        val stagingDir = File(tempDir, "staging")
        val outcome = compiler.compileAndDex(
            mapOf(module to listOf(File(homeDir, "src/main/kotlin/com/example/home/HomeScreen.kt"))),
            stagingDir
        )

        assertTrue(outcome is CompilationOutcome.Success)
        val success = outcome as CompilationOutcome.Success
        val classNames = success.classPayloads.map { it.className }.sorted()
        assertEquals(
            listOf(
                "com.example.home.HomeScreenKt",
                "com.example.home.HomeScreenKt\$HomeScreen\$1"
            ),
            classNames
        )
        assertTrue(success.combinedDexFile?.exists() == true)
        assertFalse(classNames.contains("com.example.home.Unchanged"))
    }

    @Test
    fun `parses compiler error diagnostic when gradlew compile fails`() {
        val homeDir = File(tempDir, "feature/home").apply { mkdirs() }
        val module = GradleModule(":feature:home", homeDir, ModuleType.ANDROID_LIBRARY)
        val runner = mockk<CommandRunner>()

        every { runner.runCommand(any(), any()) } returns Result.failure(
            RuntimeException(
                "Task :feature:home:compileDebugKotlin FAILED\n" +
                    "e: file:///path/to/feature/home/src/main/kotlin/HomeScreen.kt:42:17 Unresolved reference 'foo'\n" +
                    "FAILURE: Build failed with an exception."
            )
        )

        val compiler = IncrementalDexCompiler(
            projectRoot = tempDir,
            variant = "debug",
            commandRunner = runner,
            d8PathProvider = { "/mock/d8" }
        )

        val outcome = compiler.compileAndDex(
            mapOf(module to listOf(File(homeDir, "HomeScreen.kt"))),
            File(tempDir, "staging")
        )

        assertTrue(outcome is CompilationOutcome.Failure)
        val failure = outcome as CompilationOutcome.Failure
        assertEquals(":feature:home", failure.module)
        assertEquals("HomeScreen.kt:42:17 Unresolved reference 'foo'", failure.diagnostic)
    }

    private fun assertFalse(condition: Boolean) = org.junit.jupiter.api.Assertions.assertFalse(condition)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :hot-reload:test`
Expected: FAIL with compilation error (`Unresolved reference: IncrementalDexCompiler`).

- [ ] **Step 3: Implement `IncrementalDexCompiler.kt`**

Create `hot-reload/src/main/kotlin/dev/shreyaspatil/debroid/hotreload/IncrementalDexCompiler.kt`:
```kotlin
package dev.shreyaspatil.debroid.hotreload

import dev.shreyaspatil.debroid.adb.CommandRunner
import dev.shreyaspatil.debroid.adb.DefaultCommandRunner
import java.io.File
import java.security.MessageDigest

class IncrementalDexCompiler(
    private val projectRoot: File,
    private val variant: String = "debug",
    private val commandRunner: CommandRunner = DefaultCommandRunner(),
    private val d8PathProvider: () -> String = { findD8Executable() }
) {
    private val classHashesByModule = mutableMapOf<String, MutableMap<String, String>>()

    companion object {
        private const val COMPILE_TIMEOUT_SECONDS = 120L
        private const val D8_TIMEOUT_SECONDS = 30L
        private val kotlincErrorRegex = Regex("""e:\s*(?:file://)?([^\s:]+):(\d+):(\d+)\s*(.+)""")

        fun findD8Executable(): String {
            val sdkRoot = System.getenv("ANDROID_HOME")
                ?: System.getenv("ANDROID_SDK_ROOT")
                ?: File(System.getProperty("user.home"), "Library/Android/sdk").takeIf { it.exists() }?.absolutePath
                ?: File(System.getProperty("user.home"), "Android/Sdk").takeIf { it.exists() }?.absolutePath

            if (sdkRoot != null) {
                val buildToolsDir = File(sdkRoot, "build-tools")
                val latestD8 = buildToolsDir.listFiles()
                    ?.filter { it.isDirectory }
                    ?.sortedByDescending { it.name }
                    ?.firstNotNullOfOrNull { dir ->
                        val isWin = System.getProperty("os.name").lowercase().contains("win")
                        val d8File = File(dir, if (isWin) "d8.bat" else "d8")
                        d8File.takeIf { it.exists() }
                    }
                if (latestD8 != null) return latestD8.absolutePath
            }
            return "d8"
        }
    }

    fun snapshotModuleClasses(modules: Collection<GradleModule>) {
        for (module in modules) {
            classHashesByModule[module.gradlePath] = scanModuleClassHashes(module).toMutableMap()
        }
    }

    fun buildGradleTasksForModules(changesByModule: Map<GradleModule, List<File>>): List<String> {
        val capVariant = variant.replaceFirstChar { it.uppercaseChar() }
        val tasks = mutableListOf<String>()

        for ((module, files) in changesByModule) {
            val hasJava = files.any { it.extension.equals("java", ignoreCase = true) }
            when (module.type) {
                ModuleType.ANDROID_APP, ModuleType.ANDROID_LIBRARY -> {
                    tasks.add("${module.gradlePath}:compile${capVariant}Kotlin")
                    if (hasJava) {
                        tasks.add("${module.gradlePath}:compile${capVariant}JavaWithJavac")
                    }
                }
                ModuleType.KOTLIN_JVM -> {
                    tasks.add("${module.gradlePath}:compileKotlin")
                    if (hasJava) {
                        tasks.add("${module.gradlePath}:compileJava")
                    }
                }
            }
        }
        return tasks.distinct()
    }

    fun compileAndDex(
        changesByModule: Map<GradleModule, List<File>>,
        stagingDir: File
    ): CompilationOutcome {
        // Ensure we have a pre-compile snapshot for any module not snapshotted yet
        for (module in changesByModule.keys) {
            if (module.gradlePath !in classHashesByModule) {
                classHashesByModule[module.gradlePath] = scanModuleClassHashes(module).toMutableMap()
            }
        }

        val tasks = buildGradleTasksForModules(changesByModule)
        val gradlew = resolveGradleWrapper()
        val gradleCmd = listOf(gradlew) + tasks + listOf("--console=plain", "-p", projectRoot.absolutePath)

        val compileResult = commandRunner.runCommand(gradleCmd, COMPILE_TIMEOUT_SECONDS)
        if (compileResult.isFailure) {
            val rawError = compileResult.exceptionOrNull()?.message.orEmpty()
            val firstModule = changesByModule.keys.firstOrNull()?.gradlePath ?: ":app"
            return CompilationOutcome.Failure(
                module = firstModule,
                diagnostic = extractDiagnostic(rawError)
            )
        }

        val modifiedClassFiles = collectModifiedClasses(changesByModule)
        if (modifiedClassFiles.isEmpty()) {
            return CompilationOutcome.Success(emptyList(), null)
        }

        return packageDexPayloads(modifiedClassFiles, stagingDir)
    }

    private fun collectModifiedClasses(
        changesByModule: Map<GradleModule, List<File>>
    ): List<Pair<String, File>> {
        val changedClasses = LinkedHashMap<String, File>()

        for ((module, sourceFiles) in changesByModule) {
            val previousHashes = classHashesByModule[module.gradlePath].orEmpty()
            val currentClasses = scanModuleClassFiles(module)
            val updatedHashes = mutableMapOf<String, String>()
            val editedBaseNames = sourceFiles.map { it.nameWithoutExtension }.toSet()

            for ((className, classFile) in currentClasses) {
                val hash = sha256(classFile)
                updatedHashes[className] = hash
                val prevHash = previousHashes[className]
                val simpleName = className.substringAfterLast('.')
                val matchesEditedSource = editedBaseNames.any { base ->
                    simpleName == base ||
                        simpleName == "${base}Kt" ||
                        simpleName.startsWith("$base$") ||
                        simpleName.startsWith("${base}Kt$") ||
                        simpleName.startsWith("ComposableSingletons\$$base")
                }

                if (prevHash == null || prevHash != hash || (previousHashes.isEmpty() && matchesEditedSource)) {
                    changedClasses[className] = classFile
                }
            }
            classHashesByModule[module.gradlePath] = updatedHashes
        }

        return changedClasses.entries.map { it.key to it.value }
    }

    private fun packageDexPayloads(
        modifiedClasses: List<Pair<String, File>>,
        stagingDir: File
    ): CompilationOutcome {
        stagingDir.deleteRecursively()
        stagingDir.mkdirs()

        val d8 = d8PathProvider()
        val payloads = mutableListOf<ClassDexPayload>()

        for ((index, entry) in modifiedClasses.withIndex()) {
            val (className, classFile) = entry
            val singleOutDir = File(stagingDir, "cls_$index").apply { mkdirs() }
            val cmd = listOf(d8, "--output", singleOutDir.absolutePath, classFile.absolutePath)
            val res = commandRunner.runCommand(cmd, D8_TIMEOUT_SECONDS)
            if (res.isFailure) {
                return CompilationOutcome.Failure(
                    module = className,
                    diagnostic = "d8 failed for $className: ${res.exceptionOrNull()?.message}"
                )
            }
            val dexFile = File(singleOutDir, "classes.dex")
            if (dexFile.exists()) {
                val targetDex = File(stagingDir, "redefine_$index.dex")
                dexFile.copyTo(targetDex, overwrite = true)
                payloads.add(ClassDexPayload(className = className, dexFile = targetDex))
            }
        }

        val combinedOutDir = File(stagingDir, "combined").apply { mkdirs() }
        val allClassPaths = modifiedClasses.map { it.second.absolutePath }
        val combinedCmd = listOf(d8, "--output", combinedOutDir.absolutePath) + allClassPaths
        val combinedRes = commandRunner.runCommand(combinedCmd, D8_TIMEOUT_SECONDS)
        if (combinedRes.isFailure) {
            return CompilationOutcome.Failure(
                module = "d8",
                diagnostic = "d8 combined dex failed: ${combinedRes.exceptionOrNull()?.message}"
            )
        }
        val combinedDex = File(combinedOutDir, "classes.dex").takeIf { it.exists() }?.let { dex ->
            val target = File(stagingDir, "new_classes.dex")
            dex.copyTo(target, overwrite = true)
            target
        }

        return CompilationOutcome.Success(
            classPayloads = payloads,
            combinedDexFile = combinedDex
        )
    }

    private fun scanModuleClassHashes(module: GradleModule): Map<String, String> {
        return scanModuleClassFiles(module).mapValues { (_, file) -> sha256(file) }
    }

    private fun scanModuleClassFiles(module: GradleModule): Map<String, File> {
        val candidateDirs = listOf(
            File(module.moduleDir, "build/tmp/kotlin-classes/$variant"),
            File(module.moduleDir, "build/intermediates/kotlin-classes/$variant"),
            File(module.moduleDir, "build/intermediates/javac/$variant/compile${variant.replaceFirstChar { it.uppercaseChar() }}JavaWithJavac/classes"),
            File(module.moduleDir, "build/intermediates/javac/$variant/classes"),
            File(module.moduleDir, "build/classes/kotlin/main"),
            File(module.moduleDir, "build/classes/java/main")
        )

        val result = LinkedHashMap<String, File>()
        for (root in candidateDirs) {
            if (!root.isDirectory) continue
            root.walkTopDown()
                .filter { it.isFile && it.extension == "class" && !it.name.contains("module-info") }
                .forEach { classFile ->
                    val relPath = classFile.relativeTo(root).invariantSeparatorsPath
                    if (!relPath.startsWith("META-INF/")) {
                        val className = relPath.removeSuffix(".class").replace('/', '.')
                        result.putIfAbsent(className, classFile)
                    }
                }
        }
        return result
    }

    private fun resolveGradleWrapper(): String {
        val isWin = System.getProperty("os.name").lowercase().contains("win")
        val wrapper = File(projectRoot, if (isWin) "gradlew.bat" else "gradlew")
        return if (wrapper.exists()) wrapper.absolutePath else "gradle"
    }

    private fun extractDiagnostic(rawError: String): String {
        for (line in rawError.lines()) {
            val match = kotlincErrorRegex.find(line)
            if (match != null) {
                val fileName = File(match.groupValues[1]).name
                val lineNum = match.groupValues[2]
                val colNum = match.groupValues[3]
                val message = match.groupValues[4].trim()
                return "$fileName:$lineNum:$colNum $message"
            }
        }
        val firstFailure = rawError.lines()
            .map { it.trim() }
            .firstOrNull { it.startsWith("e:") || it.contains("FAILED") || it.contains("error:") }
        return firstFailure ?: rawError.lines().lastOrNull { it.isNotBlank() }?.trim() ?: "Compilation failed"
    }

    private fun sha256(file: File): String {
        val bytes = file.readBytes()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
```

- [ ] **Step 4: Run tests and Detekt to verify**

Run: `./gradlew :hot-reload:detekt :hot-reload:test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add hot-reload/src/main/kotlin/dev/shreyaspatil/debroid/hotreload/IncrementalDexCompiler.kt \
  hot-reload/src/test/kotlin/dev/shreyaspatil/debroid/hotreload/IncrementalDexCompilerTest.kt
git commit -m "feat(hot-reload): add leaf-module IncrementalDexCompiler with d8 packaging"
```

---

### Task 5: Build Native C++ JVMTI Agent (`libdebroid_hotreload.so`) & `JvmtiAgentInstaller`

**Files:**
- Create: `hot-reload/src/main/cpp/jvmti_min.h`
- Create: `hot-reload/src/main/cpp/hotreload_agent.cpp`
- Create: `hot-reload/src/main/resources/native/arm64-v8a/libdebroid_hotreload.so`
- Create: `hot-reload/src/main/resources/native/x86_64/libdebroid_hotreload.so`
- Create: `hot-reload/src/main/kotlin/dev/shreyaspatil/debroid/hotreload/JvmtiAgentInstaller.kt`
- Test: `hot-reload/src/test/kotlin/dev/shreyaspatil/debroid/hotreload/JvmtiAgentInstallerTest.kt`

**Interfaces:**
- Consumes: `AdbManager` from `:core`; `ClassDexPayload`, `AgentExecutionResult`, `ReloadMode`, `UiRefreshMode` from `:hot-reload`
- Produces:
  - Native JVMTI agent `libdebroid_hotreload.so` (`arm64-v8a` & `x86_64`) with `Agent_OnAttach`
  - `class JvmtiAgentInstaller(private val adbManager: AdbManager)` with:
    - `fun applyHotReload(appId: String, classPayloads: List<ClassDexPayload>, combinedDexFile: File?): AgentExecutionResult`

- [ ] **Step 1: Create `hot-reload/src/main/cpp/jvmti_min.h` and `hot-reload/src/main/cpp/hotreload_agent.cpp` and compile `arm64-v8a` and `x86_64` `.so` binaries**

Create `hot-reload/src/main/cpp/jvmti_min.h` (copies standard JVMTI ABI definitions from `/opt/homebrew/opt/openjdk@21/include/jvmti.h` or minimal JVMTI struct definitions so it compiles with Android NDK's `clang++` against `jni.h`).

Create `hot-reload/src/main/cpp/hotreload_agent.cpp` implementing `Agent_OnAttach(JavaVM* vm, char* options, void* reserved)`:
1. Parses `manifest.txt` at path `options` (contains `combined_dex=<path>` and lines `class=<binaryName>|<dexPath>`).
2. Obtains `JNIEnv*` and `jvmtiEnv*` (`JVMTI_VERSION_1_2`), enables `can_redefine_classes = 1`.
3. Resolves `Application` via `android.app.ActivityThread.currentApplication()` and its `ClassLoader` (`dalvik.system.BaseDexClassLoader`).
4. Partition classes in `manifest.txt`:
   - Calls `ClassLoader.findLoadedClass(binaryName)` via JNI.
   - If any class is not yet loaded in the VM, calls `BaseDexClassLoader.addDexPath(combinedDexPath)` so new Kotlin/Compose lambdas and new helper classes resolve immediately.
   - For each already-loaded `jclass`, reads its single-class `.dex` bytes and calls `jvmti->RedefineClasses(1, &def)`.
   - If `RedefineClasses` returns structural error (`JVMTI_ERROR_UNSUPPORTED_REDEFINITION_METHOD_ADDED`, `SCHEMA_CHANGED`, `METHOD_DELETED`, `UNMODIFIABLE_CLASS`), looks up ART extension `com.android.art.class.structural_redefine_classes` via `GetExtensionFunctions` and invokes it.
5. Triggers UI invalidation on the Android Main Thread (`new Handler(Looper.getMainLooper()).post(...)` or `HotReloader.Companion.simulateHotReload(application)` / `Activity.recreate()`).
6. Writes `result.txt` in the same directory as `manifest.txt` with `status=OK mode=<JVMTI_HOT_SWAP|JVMTI_STRUCTURAL_SWAP> redefined=<N> new=<M> ui=<COMPOSE_HOT_RELOAD|ACTIVITY_RECREATE|NONE>` or `status=STRUCTURAL_INCOMPATIBLE reason=<code_num>`.

Compile both ABIs using Android NDK Clang into `hot-reload/src/main/resources/native/arm64-v8a/libdebroid_hotreload.so` and `hot-reload/src/main/resources/native/x86_64/libdebroid_hotreload.so`.

- [ ] **Step 2: Write the failing test `JvmtiAgentInstallerTest.kt`**

Create `hot-reload/src/test/kotlin/dev/shreyaspatil/debroid/hotreload/JvmtiAgentInstallerTest.kt`:
```kotlin
package dev.shreyaspatil.debroid.hotreload

import dev.shreyaspatil.debroid.adb.AdbManager
import dev.shreyaspatil.debroid.adb.CommandRunner
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class JvmtiAgentInstallerTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `bundled native JVMTI agent resources exist for arm64-v8a and x86_64`() {
        val arm64 = JvmtiAgentInstaller::class.java.getResourceAsStream("/native/arm64-v8a/libdebroid_hotreload.so")
        val x8664 = JvmtiAgentInstaller::class.java.getResourceAsStream("/native/x86_64/libdebroid_hotreload.so")
        assertNotNull(arm64, "arm64-v8a libdebroid_hotreload.so resource must be bundled")
        assertNotNull(x8664, "x86_64 libdebroid_hotreload.so resource must be bundled")
    }

    @Test
    fun `stages dex payload, invokes attach-agent, and parses OK result`() {
        val runner = mockk<CommandRunner>()
        every { runner.runCommand(match { it.contains("ro.product.cpu.abi") }, any()) } returns Result.success("arm64-v8a\n")
        every { runner.runCommand(match { it.contains("push") }, any()) } returns Result.success("")
        every { runner.runCommand(match { it.contains("mkdir") }, any()) } returns Result.success("")
        every { runner.runCommand(match { it.contains("cp") }, any()) } returns Result.success("")
        every { runner.runCommand(match { it.contains("attach-agent") }, any()) } returns Result.success("")
        every { runner.runCommand(match { it.contains("cat") && it.last().endsWith("result.txt") }, any()) } returns
            Result.success("status=OK mode=JVMTI_HOT_SWAP redefined=2 new=1 ui=COMPOSE_HOT_RELOAD\n")

        val adbManager = AdbManager(adbPath = "adb", commandRunner = runner)
        val installer = JvmtiAgentInstaller(adbManager)

        val dex1 = File(tempDir, "redefine_0.dex").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val combined = File(tempDir, "new_classes.dex").apply { writeBytes(byteArrayOf(4, 5, 6)) }

        val res = installer.applyHotReload(
            appId = "com.example.app",
            classPayloads = listOf(ClassDexPayload("com.example.MainScreenKt", dex1)),
            combinedDexFile = combined
        )

        assertTrue(res is AgentExecutionResult.Success)
        val success = res as AgentExecutionResult.Success
        assertEquals(ReloadMode.JVMTI_HOT_SWAP, success.mode)
        assertEquals(2, success.redefinedCount)
        assertEquals(1, success.newClassesCount)
        assertEquals(UiRefreshMode.COMPOSE_HOT_RELOAD, success.uiRefresh)
    }

    @Test
    fun `parses STRUCTURAL_INCOMPATIBLE result when structural redefine cannot apply`() {
        val runner = mockk<CommandRunner>()
        every { runner.runCommand(match { it.contains("ro.product.cpu.abi") }, any()) } returns Result.success("x86_64\n")
        every { runner.runCommand(match { it.contains("push") }, any()) } returns Result.success("")
        every { runner.runCommand(match { it.contains("mkdir") }, any()) } returns Result.success("")
        every { runner.runCommand(match { it.contains("cp") }, any()) } returns Result.success("")
        every { runner.runCommand(match { it.contains("attach-agent") }, any()) } returns Result.success("")
        every { runner.runCommand(match { it.contains("cat") && it.last().endsWith("result.txt") }, any()) } returns
            Result.success("status=STRUCTURAL_INCOMPATIBLE reason=JVMTI_ERROR_63\n")

        val adbManager = AdbManager(adbPath = "adb", commandRunner = runner)
        val installer = JvmtiAgentInstaller(adbManager)
        val dex1 = File(tempDir, "redefine_0.dex").apply { writeBytes(byteArrayOf(1)) }

        val res = installer.applyHotReload(
            appId = "com.example.app",
            classPayloads = listOf(ClassDexPayload("com.example.MainScreenKt", dex1)),
            combinedDexFile = null
        )

        assertTrue(res is AgentExecutionResult.StructuralIncompatible)
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :hot-reload:test`
Expected: FAIL with compilation error (`Unresolved reference: JvmtiAgentInstaller`).

- [ ] **Step 4: Implement `JvmtiAgentInstaller.kt`**

Create `hot-reload/src/main/kotlin/dev/shreyaspatil/debroid/hotreload/JvmtiAgentInstaller.kt`:
```kotlin
package dev.shreyaspatil.debroid.hotreload

import dev.shreyaspatil.debroid.adb.AdbManager
import java.io.File
import java.nio.file.Files

class JvmtiAgentInstaller(
    private val adbManager: AdbManager
) {
    companion object {
        private const val RESULT_POLL_ATTEMPTS = 25
        private const val RESULT_POLL_SLEEP_MS = 100L
        private const val AGENT_FILENAME = "libdebroid_hotreload.so"
    }

    fun applyHotReload(
        appId: String,
        classPayloads: List<ClassDexPayload>,
        combinedDexFile: File?
    ): AgentExecutionResult {
        val abi = detectDeviceAbi()
        val hostTempDir = Files.createTempDirectory("debroid_hr_stage").toFile()
        try {
            val agentSoFile = extractBundledAgent(abi, hostTempDir)
                ?: return AgentExecutionResult.Error("UNSUPPORTED_ABI", "No bundled JVMTI agent for ABI $abi")

            val remoteTmpDir = "/data/local/tmp/debroid_hr_$appId"
            val appAgentDir = "/data/data/$appId/code_cache/debroid_hr"

            val manifestLines = mutableListOf<String>()
            if (combinedDexFile != null && combinedDexFile.exists()) {
                val stagedCombined = File(hostTempDir, "new_classes.dex")
                combinedDexFile.copyTo(stagedCombined, overwrite = true)
                manifestLines.add("combined_dex=$appAgentDir/new_classes.dex")
            }
            classPayloads.forEachIndexed { idx, payload ->
                val fileName = "redefine_$idx.dex"
                payload.dexFile.copyTo(File(hostTempDir, fileName), overwrite = true)
                manifestLines.add("class=${payload.className}|$appAgentDir/$fileName")
            }

            val manifestFile = File(hostTempDir, "manifest.txt")
            manifestFile.writeText(manifestLines.joinToString("\n") + "\n")

            adbManager.runShell("rm", "-rf", remoteTmpDir)
            adbManager.runShell("mkdir", "-p", remoteTmpDir)

            val filesToPush = hostTempDir.listFiles().orEmpty().toList()
            for (f in filesToPush) {
                val pushRes = adbManager.pushFile(f.absolutePath, "$remoteTmpDir/${f.name}")
                if (pushRes.isFailure) {
                    return AgentExecutionResult.Error(
                        "ADB_PUSH_FAILED",
                        "Failed to push ${f.name}: ${pushRes.exceptionOrNull()?.message}"
                    )
                }
            }

            adbManager.runShell("chmod", "-R", "755", remoteTmpDir)
            adbManager.runAs(appId, "mkdir", "-p", appAgentDir)
            adbManager.runAs(appId, "rm", "-f", "$appAgentDir/result.txt")
            for (f in filesToPush) {
                val cpRes = adbManager.runAs(appId, "cp", "$remoteTmpDir/${f.name}", "$appAgentDir/${f.name}")
                if (cpRes.isFailure) {
                    return AgentExecutionResult.Error(
                        "RUN_AS_COPY_FAILED",
                        "Failed to copy ${f.name} into app code_cache: ${cpRes.exceptionOrNull()?.message}"
                    )
                }
            }
            adbManager.runAs(appId, "chmod", "700", "$appAgentDir/$AGENT_FILENAME")

            val attachSpec = "$appAgentDir/$AGENT_FILENAME=$appAgentDir/manifest.txt"
            val attachRes = adbManager.runShell("cmd", "activity", "attach-agent", appId, attachSpec)
            if (attachRes.isFailure) {
                return AgentExecutionResult.Error(
                    "ATTACH_AGENT_FAILED",
                    "cmd activity attach-agent failed: ${attachRes.exceptionOrNull()?.message}"
                )
            }

            return pollAgentResult(appId, "$appAgentDir/result.txt")
        } finally {
            hostTempDir.deleteRecursively()
        }
    }

    private fun pollAgentResult(appId: String, remoteResultPath: String): AgentExecutionResult {
        repeat(RESULT_POLL_ATTEMPTS) {
            val catRes = adbManager.runAs(appId, "cat", remoteResultPath)
            val text = catRes.getOrNull()?.trim().orEmpty()
            if (text.isNotEmpty() && !text.contains("No such file")) {
                return parseResultLine(text)
            }
            Thread.sleep(RESULT_POLL_SLEEP_MS)
        }
        return AgentExecutionResult.Error(
            "AGENT_TIMEOUT",
            "JVMTI agent did not write result.txt within ${RESULT_POLL_ATTEMPTS * RESULT_POLL_SLEEP_MS}ms"
        )
    }

    internal fun parseResultLine(text: String): AgentExecutionResult {
        val props = text.lines()
            .firstOrNull { it.startsWith("status=") }
            ?.split(" ")
            ?.mapNotNull { token ->
                val idx = token.indexOf('=')
                if (idx > 0) token.substring(0, idx) to token.substring(idx + 1) else null
            }
            ?.toMap()
            .orEmpty()

        return when (props["status"]) {
            "OK" -> {
                val mode = runCatching { ReloadMode.valueOf(props["mode"].orEmpty()) }
                    .getOrDefault(ReloadMode.JVMTI_HOT_SWAP)
                val ui = runCatching { UiRefreshMode.valueOf(props["ui"].orEmpty()) }
                    .getOrDefault(UiRefreshMode.COMPOSE_HOT_RELOAD)
                AgentExecutionResult.Success(
                    mode = mode,
                    redefinedCount = props["redefined"]?.toIntOrNull() ?: 0,
                    newClassesCount = props["new"]?.toIntOrNull() ?: 0,
                    uiRefresh = ui
                )
            }
            "STRUCTURAL_INCOMPATIBLE" -> {
                AgentExecutionResult.StructuralIncompatible(props["reason"] ?: "STRUCTURAL_CHANGE")
            }
            else -> {
                AgentExecutionResult.Error(
                    code = props["code"] ?: "AGENT_ERROR",
                    message = props["msg"]?.replace('_', ' ') ?: text
                )
            }
        }
    }

    private fun detectDeviceAbi(): String {
        val abi = adbManager.runShell("getprop", "ro.product.cpu.abi")
            .getOrNull()
            ?.trim()
            .orEmpty()
        return when {
            abi.contains("arm64") -> "arm64-v8a"
            abi.contains("x86_64") -> "x86_64"
            else -> "arm64-v8a"
        }
    }

    private fun extractBundledAgent(abi: String, targetDir: File): File? {
        val stream = JvmtiAgentInstaller::class.java.getResourceAsStream("/native/$abi/$AGENT_FILENAME")
            ?: return null
        val outFile = File(targetDir, AGENT_FILENAME)
        stream.use { input ->
            outFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        return outFile
    }
}
```
*(Also add thin helper methods `pushFile`, `runShell`, `runAs`, and `restartMainActivity` to `AdbManager.kt` in `:core` wrapping `runAdb` so `:hot-reload` can invoke them cleanly.)*

- [ ] **Step 5: Run tests and Detekt to verify**

Run: `./gradlew :core:detekt :core:test :hot-reload:detekt :hot-reload:test`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add core/src/main/kotlin/dev/shreyaspatil/debroid/adb/AdbManager.kt \
  hot-reload/src/main/cpp/ \
  hot-reload/src/main/resources/native/ \
  hot-reload/src/main/kotlin/dev/shreyaspatil/debroid/hotreload/JvmtiAgentInstaller.kt \
  hot-reload/src/test/kotlin/dev/shreyaspatil/debroid/hotreload/JvmtiAgentInstallerTest.kt
git commit -m "feat(hot-reload): add native JVMTI agent and JvmtiAgentInstaller"
```

---

### Task 6: Implement `HotReloadSession` Orchestrator & Fallback Pipeline

**Files:**
- Create: `hot-reload/src/main/kotlin/dev/shreyaspatil/debroid/hotreload/HotReloadSession.kt`
- Test: `hot-reload/src/test/kotlin/dev/shreyaspatil/debroid/hotreload/HotReloadSessionTest.kt`

**Interfaces:**
- Consumes: `AdbManager`, `CommandRunner`, `GradleProjectLayout`, `SourceChangeTracker`, `IncrementalDexCompiler`, `JvmtiAgentInstaller`
- Produces:
  - `data class HotReloadConfig(val appId: String, val projectDir: File, val variant: String = "debug", val intervalMs: Long = 1000L, val once: Boolean = false, val allowFallback: Boolean = true)`
  - `class HotReloadSession(...)` with `fun run(onEvent: (HotReloadEvent) -> Unit)` and `fun stop()`

- [ ] **Step 1: Write the failing test `HotReloadSessionTest.kt`**

Create `hot-reload/src/test/kotlin/dev/shreyaspatil/debroid/hotreload/HotReloadSessionTest.kt`:
```kotlin
package dev.shreyaspatil.debroid.hotreload

import dev.shreyaspatil.debroid.adb.AdbManager
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class HotReloadSessionTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `emits Watching, Reloading, and Reloaded events when Kotlin source file changes`() {
        File(tempDir, "settings.gradle.kts").writeText("include(\":app\")")
        val appDir = File(tempDir, "app").apply { mkdirs() }
        File(appDir, "build.gradle.kts").writeText("plugins { id(\"com.android.application\") }")
        val mainKt = File(appDir, "src/main/kotlin/MainScreen.kt").apply {
            parentFile.mkdirs()
            writeText("fun MainScreen() = 1")
        }

        val adbManager = mockk<AdbManager>(relaxed = true)
        every { adbManager.isAppDebuggable("com.example.app") } returns Result.success(true)
        every { adbManager.findPid("com.example.app") } returns Result.success(9999)

        val compiler = mockk<IncrementalDexCompiler>(relaxed = true)
        val dexFile = File(tempDir, "redefine_0.dex").apply { writeBytes(byteArrayOf(1)) }
        every { compiler.compileAndDex(any(), any()) } returns CompilationOutcome.Success(
            classPayloads = listOf(ClassDexPayload("MainScreenKt", dexFile)),
            combinedDexFile = dexFile
        )

        val installer = mockk<JvmtiAgentInstaller>()
        every { installer.applyHotReload("com.example.app", any(), any()) } returns AgentExecutionResult.Success(
            mode = ReloadMode.JVMTI_HOT_SWAP,
            redefinedCount = 1,
            newClassesCount = 0,
            uiRefresh = UiRefreshMode.COMPOSE_HOT_RELOAD
        )

        val layout = GradleProjectScanner.scan(tempDir)
        val tracker = SourceChangeTracker(layout).apply { initializeBaseline() }

        // Mutate file after baseline initialization
        mainKt.writeText("fun MainScreen() = 2")
        mainKt.setLastModified(mainKt.lastModified() + 5000L)

        val config = HotReloadConfig(
            appId = "com.example.app",
            projectDir = tempDir,
            once = true
        )
        val session = HotReloadSession(
            config = config,
            adbManager = adbManager,
            layout = layout,
            tracker = tracker,
            compiler = compiler,
            agentInstaller = installer
        )

        val events = mutableListOf<HotReloadEvent>()
        session.run { events.add(it) }

        assertEquals(3, events.size)
        assertTrue(events[0] is HotReloadEvent.Watching)
        assertTrue(events[1] is HotReloadEvent.Reloading)
        assertTrue(events[2] is HotReloadEvent.Reloaded)
    }

    @Test
    fun `triggers fallback reinstall on resource changes when allowFallback is true`() {
        File(tempDir, "settings.gradle.kts").writeText("include(\":app\")")
        val appDir = File(tempDir, "app").apply { mkdirs() }
        File(appDir, "build.gradle.kts").writeText("plugins { id(\"com.android.application\") }")
        val xmlFile = File(appDir, "src/main/res/layout/main.xml").apply {
            parentFile.mkdirs()
            writeText("<LinearLayout/>")
        }

        val adbManager = mockk<AdbManager>(relaxed = true)
        every { adbManager.isAppDebuggable("com.example.app") } returns Result.success(true)
        every { adbManager.findPid("com.example.app") } returns Result.success(9999)
        every { adbManager.restartApp("com.example.app") } returns Result.success(Unit)

        val layout = GradleProjectScanner.scan(tempDir)
        val tracker = SourceChangeTracker(layout).apply { initializeBaseline() }
        xmlFile.writeText("<FrameLayout/>")
        xmlFile.setLastModified(xmlFile.lastModified() + 5000L)

        val session = HotReloadSession(
            config = HotReloadConfig(appId = "com.example.app", projectDir = tempDir, once = true),
            adbManager = adbManager,
            layout = layout,
            tracker = tracker,
            compiler = mockk(relaxed = true),
            agentInstaller = mockk(relaxed = true),
            fallbackInstaller = { Result.success(Unit) }
        )

        val events = mutableListOf<HotReloadEvent>()
        session.run { events.add(it) }

        assertTrue(events.any { it is HotReloadEvent.FallbackReinstall })
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :hot-reload:test`
Expected: FAIL with compilation error (`Unresolved reference: HotReloadSession`).

- [ ] **Step 3: Implement `HotReloadSession.kt`**

Create `hot-reload/src/main/kotlin/dev/shreyaspatil/debroid/hotreload/HotReloadSession.kt`:
```kotlin
package dev.shreyaspatil.debroid.hotreload

import dev.shreyaspatil.debroid.adb.AdbManager
import dev.shreyaspatil.debroid.adb.CommandRunner
import dev.shreyaspatil.debroid.adb.DefaultCommandRunner
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean

data class HotReloadConfig(
    val appId: String,
    val projectDir: File,
    val variant: String = "debug",
    val intervalMs: Long = 1000L,
    val once: Boolean = false,
    val allowFallback: Boolean = true
)

@Suppress("LongParameterList")
class HotReloadSession(
    private val config: HotReloadConfig,
    private val adbManager: AdbManager = AdbManager(),
    private val commandRunner: CommandRunner = DefaultCommandRunner(),
    private val layout: GradleProjectLayout = GradleProjectScanner.scan(config.projectDir),
    private val tracker: SourceChangeTracker = SourceChangeTracker(layout).apply { initializeBaseline() },
    private val compiler: IncrementalDexCompiler = IncrementalDexCompiler(
        projectRoot = layout.rootDir,
        variant = config.variant,
        commandRunner = commandRunner
    ).apply { snapshotModuleClasses(layout.modules) },
    private val agentInstaller: JvmtiAgentInstaller = JvmtiAgentInstaller(adbManager),
    private val fallbackInstaller: (GradleModule) -> Result<Unit> = { appModule ->
        runFallbackGradleInstall(layout.rootDir, appModule, config.variant, commandRunner)
    }
) {
    private val isRunning = AtomicBoolean(true)

    companion object {
        private const val INSTALL_TIMEOUT_SECONDS = 180L

        private fun runFallbackGradleInstall(
            rootDir: File,
            appModule: GradleModule,
            variant: String,
            commandRunner: CommandRunner
        ): Result<Unit> {
            val isWin = System.getProperty("os.name").lowercase().contains("win")
            val wrapper = File(rootDir, if (isWin) "gradlew.bat" else "gradlew")
            val gradleBin = if (wrapper.exists()) wrapper.absolutePath else "gradle"
            val capVariant = variant.replaceFirstChar { it.uppercaseChar() }
            val task = "${appModule.gradlePath}:install$capVariant"
            return commandRunner.runCommand(
                listOf(gradleBin, task, "--console=plain", "-p", rootDir.absolutePath),
                INSTALL_TIMEOUT_SECONDS
            ).map { }
        }
    }

    fun stop() {
        isRunning.set(false)
    }

    fun run(onEvent: (HotReloadEvent) -> Unit) {
        val debuggableRes = adbManager.isAppDebuggable(config.appId)
        if (debuggableRes.isFailure || debuggableRes.getOrNull() == false) {
            onEvent(
                HotReloadEvent.Error(
                    code = "APP_NOT_DEBUGGABLE",
                    message = debuggableRes.exceptionOrNull()?.message
                        ?: "App ${config.appId} is not debuggable"
                )
            )
            return
        }

        val initialPidRes = adbManager.findPid(config.appId)
        if (initialPidRes.isFailure) {
            onEvent(
                HotReloadEvent.Error(
                    code = "APP_NOT_RUNNING",
                    message = "App ${config.appId} is not currently running"
                )
            )
            return
        }

        onEvent(
            HotReloadEvent.Watching(
                appId = config.appId,
                pid = initialPidRes.getOrThrow(),
                moduleCount = layout.modules.size,
                intervalMs = config.intervalMs
            )
        )

        val stagingDir = Files.createTempDirectory("debroid_dex_stage").toFile()
        try {
            while (isRunning.get()) {
                val changes = tracker.pollChanges()
                if (!changes.isEmpty()) {
                    processDetectedChanges(changes, stagingDir, onEvent)
                }
                if (config.once) {
                    break
                }
                Thread.sleep(config.intervalMs)
            }
        } catch (_: InterruptedException) {
            onEvent(HotReloadEvent.Stopped(config.appId))
        } finally {
            stagingDir.deleteRecursively()
        }
    }

    private fun processDetectedChanges(
        changes: DetectedChanges,
        stagingDir: File,
        onEvent: (HotReloadEvent) -> Unit
    ) {
        if (changes.resourceFiles.isNotEmpty()) {
            handleFallback(
                reason = "RESOURCE_CHANGE",
                files = changes.resourceFiles.map { it.name },
                changes = changes,
                onEvent = onEvent
            )
            return
        }

        val pidRes = adbManager.findPid(config.appId)
        if (pidRes.isFailure) {
            onEvent(HotReloadEvent.Error("APP_NOT_RUNNING", "App ${config.appId} is not running"))
            return
        }

        val modulePaths = changes.codeChangesByModule.keys.map { it.gradlePath }
        val fileNames = changes.codeChangesByModule.values.flatten().map { it.name }
        onEvent(HotReloadEvent.Reloading(modules = modulePaths, files = fileNames))

        val startMs = System.currentTimeMillis()
        when (val compileOutcome = compiler.compileAndDex(changes.codeChangesByModule, stagingDir)) {
            is CompilationOutcome.Failure -> {
                onEvent(HotReloadEvent.CompileError(compileOutcome.module, compileOutcome.diagnostic))
            }
            is CompilationOutcome.Success -> {
                if (compileOutcome.classPayloads.isEmpty()) {
                    tracker.commitChanges(changes)
                    return
                }
                applyDexToDevice(compileOutcome, changes, fileNames, startMs, onEvent)
            }
        }
    }

    private fun applyDexToDevice(
        compileOutcome: CompilationOutcome.Success,
        changes: DetectedChanges,
        fileNames: List<String>,
        startMs: Long,
        onEvent: (HotReloadEvent) -> Unit
    ) {
        val agentRes = agentInstaller.applyHotReload(
            appId = config.appId,
            classPayloads = compileOutcome.classPayloads,
            combinedDexFile = compileOutcome.combinedDexFile
        )
        val durationMs = System.currentTimeMillis() - startMs

        when (agentRes) {
            is AgentExecutionResult.Success -> {
                tracker.commitChanges(changes)
                onEvent(
                    HotReloadEvent.Reloaded(
                        mode = agentRes.mode,
                        redefinedCount = agentRes.redefinedCount,
                        newClassesCount = agentRes.newClassesCount,
                        uiRefresh = agentRes.uiRefresh,
                        durationMs = durationMs
                    )
                )
            }
            is AgentExecutionResult.StructuralIncompatible -> {
                handleFallback(
                    reason = agentRes.reason,
                    files = fileNames,
                    changes = changes,
                    onEvent = onEvent
                )
            }
            is AgentExecutionResult.Error -> {
                onEvent(HotReloadEvent.Error(agentRes.code, agentRes.message))
            }
        }
    }

    private fun handleFallback(
        reason: String,
        files: List<String>,
        changes: DetectedChanges,
        onEvent: (HotReloadEvent) -> Unit
    ) {
        if (!config.allowFallback) {
            onEvent(HotReloadEvent.Error("FALLBACK_DISABLED", "Cannot hot-swap $reason and --no-fallback is set"))
            return
        }
        val appModule = layout.findAppModule(config.appId)
        if (appModule == null) {
            onEvent(HotReloadEvent.Error("NO_APP_MODULE", "Could not locate Android application module for fallback"))
            return
        }

        val startMs = System.currentTimeMillis()
        val installRes = fallbackInstaller(appModule)
        if (installRes.isFailure) {
            onEvent(
                HotReloadEvent.Error(
                    "FALLBACK_FAILED",
                    installRes.exceptionOrNull()?.message ?: "Fallback install failed"
                )
            )
            return
        }

        adbManager.restartApp(config.appId)
        tracker.commitChanges(changes)
        val durationMs = System.currentTimeMillis() - startMs
        onEvent(HotReloadEvent.FallbackReinstall(reason = reason, files = files, durationMs = durationMs))
    }
}
```

- [ ] **Step 4: Run tests and Detekt to verify**

Run: `./gradlew :hot-reload:detekt :hot-reload:test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add hot-reload/src/main/kotlin/dev/shreyaspatil/debroid/hotreload/HotReloadSession.kt \
  hot-reload/src/test/kotlin/dev/shreyaspatil/debroid/hotreload/HotReloadSessionTest.kt
git commit -m "feat(hot-reload): add HotReloadSession orchestrator and fallback pipeline"
```

---

### Task 7: Wire `HotReloadCommand` in `:cli` & Sync Docs (`SKILL.md`, `README.md`, `CHANGELOG.md`)

**Files:**
- Modify: `cli/build.gradle.kts`
- Modify: `cli/src/main/kotlin/dev/shreyaspatil/debroid/cli/CliRunner.kt`
- Modify: `cli/src/test/kotlin/dev/shreyaspatil/debroid/cli/CliRunnerTest.kt`
- Modify: `skills/debroid-cli/SKILL.md`
- Modify: `README.md`
- Modify: `CHANGELOG.md`

**Interfaces:**
- Consumes: `HotReloadConfig`, `HotReloadSession`, `HotReloadEvent` from `:hot-reload`
- Produces: `HotReloadCommand` (`debroid hot-reload <app_id> [--project-dir|-d] [--variant|-v] [--interval-ms] [--once] [--no-fallback]`) registered in `CliRunner.createCli()`

- [ ] **Step 1: Add `:hot-reload` dependency to `cli/build.gradle.kts` and write failing test in `CliRunnerTest.kt`**

Add `implementation(project(":hot-reload"))` to `cli/build.gradle.kts`.

Add a test in `cli/src/test/kotlin/dev/shreyaspatil/debroid/cli/CliRunnerTest.kt` verifying `debroid hot-reload --help` and `HotReloadCommand` invocation.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :cli:test`
Expected: FAIL (`No such subcommand hot-reload`).

- [ ] **Step 3: Implement `HotReloadCommand` in `CliRunner.kt` and update `SKILL.md`, `README.md`, and `CHANGELOG.md`**

In `CliRunner.kt`:
```kotlin
    class HotReloadCommand : CliktCommand(
        name = "hot-reload",
        help = "Watches project source files and automatically hot-reloads Kotlin/Java and Compose UI changes onto the running device via JVMTI.",
        epilog = "Streams single-line [STATUS] plain-text events (WATCHING, RELOADING, RELOADED, COMPILE_ERROR, FALLBACK_REINSTALL)."
    ) {
        private val appId by argument("app_id", help = "The Android Application ID (e.g., com.example.app) to hot-reload.")
        private val projectDir by option(
            "-d",
            "--project-dir",
            help = "Root directory of the Android Gradle project (default: current directory)."
        ).default(".")
        private val variant by option(
            "-v",
            "--variant",
            help = "Build variant to compile (default: debug)."
        ).default("debug")
        private val intervalMs by option(
            "--interval-ms",
            help = "Polling interval in milliseconds for source change detection (default: 1000)."
        ).int().default(1000)
        private val once by option(
            "--once",
            help = "Perform a single check/reload pass and exit."
        ).flag(default = false)
        private val noFallback by option(
            "--no-fallback",
            help = "Disable automatic fallback to full installDebug on resource or incompatible structural changes."
        ).flag(default = false)

        override fun run() {
            val config = HotReloadConfig(
                appId = appId,
                projectDir = File(projectDir),
                variant = variant,
                intervalMs = intervalMs.toLong(),
                once = once,
                allowFallback = !noFallback
            )
            val session = HotReloadSession(config = config)
            Runtime.getRuntime().addShutdownHook(Thread { session.stop() })
            session.run { event ->
                println(event.formatPlainText())
                System.out.flush()
            }
        }
    }
```
Register `HotReloadCommand()` in `CliRunner.createCli().subcommands(...)`.

Update `skills/debroid-cli/SKILL.md`, `README.md`, and `CHANGELOG.md` (`## [UNRELEASED]` -> `### Added`) with `debroid hot-reload` usage, flags, and `[STATUS]` output reference.

- [ ] **Step 4: Run full verification suite**

Run: `./gradlew detekt test :cli:build`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add cli/build.gradle.kts \
  cli/src/main/kotlin/dev/shreyaspatil/debroid/cli/CliRunner.kt \
  cli/src/test/kotlin/dev/shreyaspatil/debroid/cli/CliRunnerTest.kt \
  skills/debroid-cli/SKILL.md \
  README.md \
  CHANGELOG.md
git commit -m "feat(cli): add debroid hot-reload command and update skill/docs"
```
