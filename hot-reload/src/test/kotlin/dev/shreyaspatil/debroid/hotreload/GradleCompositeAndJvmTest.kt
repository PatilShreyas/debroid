package dev.shreyaspatil.debroid.hotreload

import dev.shreyaspatil.debroid.adb.AdbManager
import dev.shreyaspatil.debroid.adb.CommandRunner
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale

class GradleCompositeAndJvmTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `discovers umbrella composite builds with Groovy includeBuild and ignores ancestor settings`() {
        // Unrelated ancestor settings.gradle that MUST NOT hijack -d compositeRoot!
        File(tempDir, "settings.gradle").writeText("rootProject.name = 'unrelated-parent-workspace'")

        val umbrellaDir = File(tempDir, "umbrella-composite").apply { mkdirs() }
        File(umbrellaDir, "settings.gradle").writeText(
            """
            pluginManagement {
                includeBuild 'build-logic'
            }
            rootProject.name = 'umbrella'
            includeBuild 'mobile-app'
            includeBuild file('core-sdk')
            includeBuild("design-system") {
                name = "ds"
            }
            """.trimIndent()
        )

        val mobileAppRoot = File(umbrellaDir, "mobile-app").apply { mkdirs() }
        File(mobileAppRoot, "settings.gradle.kts").writeText("include(\":app\", \":feature:feed\")")
        val appDir = File(mobileAppRoot, "app").apply { mkdirs() }
        File(appDir, "build.gradle.kts").writeText(
            """
            plugins { id("com.android.application") }
            dependencies {
                implementation(project(":feature:feed"))
                implementation("com.acme:network")
                implementation("com.acme:components")
            }
            """.trimIndent()
        )
        val feedDir = File(mobileAppRoot, "feature/feed").apply { mkdirs() }
        File(feedDir, "build.gradle.kts").writeText("plugins { id(\"com.android.library\") }")

        val coreSdkRoot = File(umbrellaDir, "core-sdk").apply { mkdirs() }
        File(coreSdkRoot, "settings.gradle").writeText("include ':network'")
        val networkDir = File(coreSdkRoot, "network").apply { mkdirs() }
        File(networkDir, "build.gradle").writeText("plugins { id 'org.jetbrains.kotlin.jvm' }")

        val dsRoot = File(umbrellaDir, "design-system").apply { mkdirs() }
        File(dsRoot, "settings.gradle.kts").writeText("include(\":components\")")
        val compDir = File(dsRoot, "components").apply { mkdirs() }
        File(compDir, "build.gradle.kts").writeText("plugins { id(\"com.android.library\") }")

        val layout = GradleProjectScanner.scan(umbrellaDir)
        assertEquals(umbrellaDir.canonicalFile, layout.rootDir)
        val byPath = layout.modules.associateBy { it.gradlePath }
        assertEquals(
            setOf(
                ":mobile-app:app",
                ":mobile-app:feature:feed",
                ":core-sdk:network",
                ":ds:components"
            ),
            byPath.keys
        )

        val appMod = byPath.getValue(":mobile-app:app")
        assertEquals(ModuleType.ANDROID_APP, appMod.type)
        assertEquals(mobileAppRoot.canonicalFile, appMod.includedBuildRoot)
        assertEquals(":app", appMod.localGradlePath)
        assertEquals(
            setOf(":mobile-app:feature:feed", ":core-sdk:network", ":ds:components"),
            appMod.dependencies
        )
    }

    @Test
    fun `discovers dynamic loop includeBuild child projects in static fallback instead of returning modules=1`() {
        val repoDir = File(tempDir, "dynamic-composite").apply { mkdirs() }
        File(repoDir, "settings.gradle.kts").writeText(
            """
            rootProject.name = "dynamic-umbrella"
            rootDir.listFiles()?.filter { File(it, "settings.gradle.kts").exists() }?.forEach {
                includeBuild(it)
            }
            """.trimIndent()
        )

        val hostAppRoot = File(repoDir, "host-app").apply { mkdirs() }
        File(hostAppRoot, "settings.gradle.kts").writeText("include(\":app\")")
        File(hostAppRoot, "app").apply {
            mkdirs()
            File(this, "build.gradle.kts").writeText("plugins { id(\"com.android.application\") }")
        }

        val authRoot = File(repoDir, "auth-lib").apply { mkdirs() }
        File(authRoot, "settings.gradle.kts").writeText("include(\":ui\")")
        File(authRoot, "ui").apply {
            mkdirs()
            File(this, "build.gradle.kts").writeText("plugins { id(\"com.android.library\") }")
        }

        val layout = GradleProjectScanner.scan(repoDir)
        assertEquals(
            setOf(":host-app:app", ":auth-lib:ui"),
            layout.modules.map { it.gradlePath }.toSet()
        )
    }

    @Test
    fun `parses gradle-args resolves JAVA_HOME from gradle properties and wrapper compatibility`() {
        val parsedArgs = GradleJvmResolver.parseGradleArgs(
            listOf("-Penv=staging --offline", "-Pgreeting=\"hello composite\"")
        )
        assertEquals(listOf("-Penv=staging", "--offline", "-Pgreeting=hello composite"), parsedArgs)

        val fakeJdk17 = createFakeJdkHome(File(tempDir, "temurin-17.jdk/Contents/Home"), "17.0.10")
        val normalizedFromBundle = GradleJvmResolver.normalizeJdkHome(File(tempDir, "temurin-17.jdk"))
        assertEquals(fakeJdk17, normalizedFromBundle)

        val projDir = File(tempDir, "jdk-proj").apply { mkdirs() }
        File(projDir, "gradle/wrapper").mkdirs()
        File(projDir, "gradle/wrapper/gradle-wrapper.properties").writeText(
            "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.2.1-bin.zip\n"
        )
        assertEquals(20, GradleJvmResolver.detectMaxSupportedJavaForWrapper(projDir))

        File(projDir, "gradle.properties").writeText(
            "org.gradle.java.home=${fakeJdk17.absolutePath.replace("\\", "\\\\")}\n"
        )
        val execCfg = GradleJvmResolver.resolveForProject(
            projectRoot = projDir,
            explicitJavaHome = null,
            extraGradleArgs = parsedArgs
        )
        assertEquals(fakeJdk17, execCfg.javaHome)
        assertEquals(fakeJdk17.absolutePath, execCfg.buildEnvironment()["JAVA_HOME"])
        assertTrue(execCfg.buildJvmAndExtraFlags().contains("-Dorg.gradle.java.home=${fakeJdk17.absolutePath}"))
        assertTrue(execCfg.buildJvmAndExtraFlags().containsAll(parsedArgs))
        assertNull(GradleJvmResolver.normalizeJdkHome(File(tempDir, "does-not-exist")))
    }

    @Test
    fun `forwards JAVA_HOME and gradle-args and falls back to includedBuildRoot for nested composites`() {
        val umbrellaDir = File(tempDir, "nested-umbrella").apply { mkdirs() }
        val ibRoot = File(umbrellaDir, "libs/payments").apply { mkdirs() }
        val modDir = File(ibRoot, "checkout").apply { mkdirs() }
        val pkgDir = File(modDir, "build/classes/kotlin/main/com/example").apply { mkdirs() }
        val clsFile = File(pkgDir, "Pay.class")
        clsFile.writeBytes(buildSimpleClass("com/example/Pay", 1))

        val fakeJdk = createFakeJdkHome(File(tempDir, "jdk-17"), "17.0.11")
        val recordedCmds = mutableListOf<Pair<List<String>, Map<String, String>>>()

        val runner = object : CommandRunner {
            override fun runCommand(command: List<String>, timeoutSeconds: Long): Result<String> =
                runCommand(command, timeoutSeconds, emptyMap())

            override fun runCommand(
                command: List<String>,
                timeoutSeconds: Long,
                environment: Map<String, String>
            ): Result<String> {
                if (command.first() == "/mock/d8") {
                    val outDir = File(command[command.indexOf("--output") + 1]).apply { mkdirs() }
                    File(outDir, "classes.dex").writeBytes(byteArrayOf(0x64, 0x65, 0x78))
                    return Result.success("")
                }
                recordedCmds.add(command to environment)
                return if (command.contains(":sdk:payments:checkout:compileKotlin")) {
                    Result.failure(
                        RuntimeException("Project 'sdk:payments' not found in root project 'nested-umbrella'.")
                    )
                } else {
                    clsFile.writeBytes(buildSimpleClass("com/example/Pay", 2))
                    Result.success("BUILD SUCCESSFUL")
                }
            }
        }

        val mod = GradleModule(
            gradlePath = ":sdk:payments:checkout",
            moduleDir = modDir,
            type = ModuleType.KOTLIN_JVM,
            includedBuildRoot = ibRoot,
            localGradlePath = ":checkout"
        )
        val execConfig = GradleExecutionConfig(
            javaHome = fakeJdk,
            extraGradleArgs = listOf("-PcorpFlag=enabled")
        )
        val compiler = IncrementalDexCompiler(
            projectRoot = umbrellaDir,
            variant = "debug",
            commandRunner = runner,
            d8PathProvider = { "/mock/d8" },
            gradleConfig = execConfig
        )
        compiler.snapshotModuleClasses(listOf(mod))
        val src = File(modDir, "src/main/kotlin/com/example/Pay.kt")
        val outcome = compiler.compileAndDex(mapOf(mod to listOf(src)), File(tempDir, "stage"))
        assertTrue(outcome is CompilationOutcome.Success)

        // Second Gradle call retried directly against includedBuildRoot (-p ibRoot :checkout:compileKotlin)
        val fallbackCall = recordedCmds.last()
        assertTrue(":checkout:compileKotlin" in fallbackCall.first)
        assertTrue(ibRoot.absolutePath in fallbackCall.first)
        assertTrue("-PcorpFlag=enabled" in fallbackCall.first)
        assertEquals(fakeJdk.absolutePath, fallbackCall.second["JAVA_HOME"])
    }

    @Test
    fun `emits PROJECT_SCAN_FAILED instead of silent modules=1 Watching when composite Gradle scan fails`() {
        val repoDir = File(tempDir, "broken-composite").apply { mkdirs() }
        File(repoDir, "settings.gradle.kts").writeText(
            """
            includeBuild("../missing-composite-a")
            includeBuild("../missing-composite-b")
            """.trimIndent()
        )
        val brokenLayout = GradleProjectLayout(
            rootDir = repoDir,
            modules = listOf(GradleModule(":", repoDir, ModuleType.KOTLIN_JVM)),
            scanDiagnostic = "Unsupported class file major version 65"
        )

        val session = HotReloadSession(
            config = HotReloadConfig(appId = "com.example.app", projectDir = repoDir, once = true),
            adbManager = mockk<AdbManager>(relaxed = true),
            layout = brokenLayout,
            tracker = mockk(relaxed = true),
            compiler = mockk(relaxed = true),
            agentInstaller = mockk(relaxed = true)
        )

        val events = mutableListOf<HotReloadEvent>()
        session.run { events.add(it) }

        assertEquals(1, events.size)
        val error = events.single() as HotReloadEvent.Error
        assertEquals("PROJECT_SCAN_FAILED", error.code)
        assertTrue(error.message.contains("Unsupported class file major version 65"))
        assertTrue(error.message.contains("--java-home"))
    }

    private fun createFakeJdkHome(homeDir: File, version: String): File {
        val isWin = System.getProperty("os.name").lowercase(Locale.US).contains("win")
        val javaBin = File(homeDir, if (isWin) "bin/java.exe" else "bin/java").apply {
            parentFile.mkdirs()
            writeText("#!/bin/sh\n")
            setExecutable(true)
        }
        assertNotNull(javaBin)
        File(homeDir, "release").writeText("JAVA_VERSION=\"$version\"\n")
        return homeDir.canonicalFile
    }

    private fun buildSimpleClass(internalName: String, returnConst: Int): ByteArray {
        val out = ByteArrayOutputStream()
        fun u2(v: Int) {
            out.write((v ushr 8) and 0xFF)
            out.write(v and 0xFF)
        }
        fun u4(v: Int) {
            out.write((v ushr 24) and 0xFF)
            out.write((v ushr 16) and 0xFF)
            out.write((v ushr 8) and 0xFF)
            out.write(v and 0xFF)
        }
        val utf8s = listOf(internalName, "java/lang/Object", "Code", "run", "()I")
        u4(0xCAFEBABE.toInt())
        u2(0)
        u2(52)
        u2(8)
        for (s in utf8s) {
            val b = s.toByteArray(Charsets.UTF_8)
            out.write(1)
            u2(b.size)
            out.write(b)
        }
        out.write(7)
        u2(1)
        out.write(7)
        u2(2)
        u2(0x0021)
        u2(6)
        u2(7)
        u2(0)
        u2(0)
        u2(1)
        u2(0x0009)
        u2(4)
        u2(5)
        u2(1)
        val codeBytes = byteArrayOf(0x10, returnConst.toByte(), 0xAC.toByte())
        u2(3)
        u4(12 + codeBytes.size)
        u2(1)
        u2(0)
        u4(codeBytes.size)
        out.write(codeBytes)
        u2(0)
        u2(0)
        u2(0)
        return out.toByteArray()
    }
}
