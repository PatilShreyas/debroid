package dev.shreyaspatil.debroid.hotreload

import dev.shreyaspatil.debroid.adb.CommandRunner
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CoreLibraryDesugarResolverTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `returns null when coreLibraryDesugaring is not enabled`() {
        val projectRoot = File(tempDir, "plain-project").apply { mkdirs() }
        val appDir = File(projectRoot, "app").apply { mkdirs() }
        File(appDir, "build.gradle.kts").writeText("plugins { id(\"com.android.application\") }")
        val appModule = GradleModule(":app", appDir, ModuleType.ANDROID_APP)

        val resolved = CoreLibraryDesugarResolver.resolve(
            projectRoot = projectRoot,
            modules = listOf(appModule),
            resolveVariant = { "debug" },
            gradleUserHome = File(tempDir, "empty-gradle")
        )
        assertNull(resolved)
    }

    @Test
    fun `resolves minSdk androidJar and matching desugar json flavor and passes flags to D8`() {
        val projectRoot = File(tempDir, "desugared-project").apply { mkdirs() }
        val appDir = File(projectRoot, "app").apply { mkdirs() }
        File(projectRoot, "gradle").mkdirs()
        File(projectRoot, "gradle/libs.versions.toml").writeText(
            """
            [versions]
            androidDesugarJdkLibs = "2.1.4"
            [libraries]
            android-desugarJdkLibs = { group = "com.android.tools", name = "desugar_jdk_libs_nio", version.ref = "androidDesugarJdkLibs" }
            """.trimIndent()
        )

        // Merged manifest with minSdkVersion=24
        val manifestFile = File(
            appDir,
            "build/intermediates/merged_manifest/demoDebug/processDemoDebugMainManifest/AndroidManifest.xml"
        ).apply {
            parentFile.mkdirs()
            writeText(
                """
                <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.app">
                    <uses-sdk android:minSdkVersion="24" android:targetSdkVersion="35" />
                </manifest>
                """.trimIndent()
            )
        }
        assertTrue(manifestFile.isFile)

        // l8DexDesugarLib intermediate containing nio marker
        val l8DexFile = File(
            appDir,
            "build/intermediates/desugar_lib_dex/demoDebug/l8DexDesugarLibDemoDebug/classes1000.dex"
        ).apply {
            parentFile.mkdirs()
            writeBytes("dex\n039\u0000...Lj\$/nio/channels/DesugarChannels;...".toByteArray(Charsets.US_ASCII))
        }
        assertTrue(l8DexFile.isFile)

        // Fake SDK platform android.jar
        val fakeSdk = File(tempDir, "fake-sdk")
        val android35Jar = File(fakeSdk, "platforms/android-35/android.jar").apply {
            parentFile.mkdirs()
            writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04))
        }
        File(fakeSdk, "platforms/android-34/android.jar").apply {
            parentFile.mkdirs()
            writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04))
        }
        File(projectRoot, "local.properties").writeText("sdk.dir=${fakeSdk.absolutePath.replace("\\", "\\\\")}\n")

        // Fake Gradle cache with desugar_jdk_libs_configuration_nio 2.1.4
        val fakeGradleHome = File(tempDir, "fake-gradle-home")
        val relJarPath = "caches/modules-2/files-2.1/com.android.tools/" +
            "desugar_jdk_libs_configuration_nio/2.1.4/abc/desugar_jdk_libs_configuration_nio-2.1.4.jar"
        val configJar = File(fakeGradleHome, relJarPath).apply {
            parentFile.mkdirs()
        }
        val expectedJson = """{"configuration_format_version":200,""" +
            """"Identifier":"com.tools.android:desugar_jdk_libs_nio:2.1.4"}"""
        ZipOutputStream(configJar.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("META-INF/desugar/d8/desugar.json"))
            zos.write(expectedJson.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }

        val appModule = GradleModule(":app", appDir, ModuleType.ANDROID_APP)
        val resolved = CoreLibraryDesugarResolver.resolve(
            projectRoot = projectRoot,
            modules = listOf(appModule),
            resolveVariant = { "demoDebug" },
            gradleUserHome = fakeGradleHome
        )

        assertNotNull(resolved)
        assertEquals(24, resolved!!.minApi)
        assertEquals(android35Jar, resolved.androidJar)
        assertEquals(expectedJson, resolved.desugarConfigJson.readText())

        // Verify IncrementalDexCompiler passes --min-api 24, --lib, --desugared-lib and rewrites Backport markers
        val pkgDir = File(appDir, "build/tmp/kotlin-classes/demoDebug/com/example/app").apply { mkdirs() }
        val timeClassFile = File(pkgDir, "TimeCardKt.class")
        timeClassFile.writeBytes(buildMinimalClass("com/example/app/TimeCardKt", returnConst = 1))

        val prevGradleUserHome = System.getProperty("user.home")
        val d8Commands = mutableListOf<List<String>>()
        val runner = mockk<CommandRunner>()
        every { runner.runCommand(any(), any()) } answers {
            val cmd = firstArg<List<String>>()
            if (cmd.first() == "gradle" || cmd.first().endsWith("gradlew") || cmd.first().endsWith("gradlew.bat")) {
                timeClassFile.writeBytes(buildMinimalClass("com/example/app/TimeCardKt", returnConst = 9))
                Result.success("BUILD SUCCESSFUL")
            } else if (cmd.first() == "/mock/d8") {
                d8Commands.add(cmd)
                val outIdx = cmd.indexOf("--output")
                val outDir = File(cmd[outIdx + 1]).apply { mkdirs() }
                if ("--file-per-class" in cmd) {
                    File(outDir, "com/example/app/TimeCardKt.dex").apply {
                        parentFile.mkdirs()
                        writeBytes(byteArrayOf(0x64, 0x65, 0x78))
                    }
                    File(outDir, "com/example/app/TimeCardKt\$\$ExternalSyntheticBackport0.dex").apply {
                        parentFile.mkdirs()
                        writeBytes(byteArrayOf(0x64, 0x65, 0x78))
                    }
                } else {
                    File(outDir, "classes.dex").writeBytes(byteArrayOf(0x64, 0x65, 0x78))
                }
                Result.success("")
            } else {
                Result.failure(RuntimeException("Unexpected command: $cmd"))
            }
        }

        // Copy fake cache under user.home/.gradle ONLY if needed? Instead test via GRADLE_USER_HOME-independent check:
        // Move fake-gradle-home/.gradle structure under a temporary user.home so default resolver finds it cleanly!
        val tempUserHome = File(tempDir, "temp-home").apply { mkdirs() }
        fakeGradleHome.copyRecursively(File(tempUserHome, ".gradle"), overwrite = true)
        System.setProperty("user.home", tempUserHome.absolutePath)
        try {
            val compiler = IncrementalDexCompiler(
                projectRoot = projectRoot,
                variant = "demoDebug",
                commandRunner = runner,
                d8PathProvider = { "/mock/d8" }
            )
            compiler.snapshotModuleClasses(listOf(appModule))
            val srcFile = File(appDir, "src/main/kotlin/com/example/app/TimeCard.kt")
            val outcome = compiler.compileAndDex(mapOf(appModule to listOf(srcFile)), File(tempDir, "stage"))
            assertTrue(outcome is CompilationOutcome.Success)
            val payloads = (outcome as CompilationOutcome.Success).classPayloads

            val firstD8 = d8Commands.first()
            val minApiIdx = firstD8.indexOf("--min-api")
            assertEquals("24", firstD8[minApiIdx + 1])
            assertTrue("--lib" in firstD8)
            assertTrue("--desugared-lib" in firstD8)

            // Verify $$ExternalSyntheticBackport0 was renamed to unique $$ExternalSyntheticBp<TOKEN>_0
            assertTrue(payloads.any { it.className.contains("\$\$ExternalSyntheticBp") && it.className.endsWith("_0") })
        } finally {
            System.setProperty("user.home", prevGradleUserHome)
        }
    }

    private fun buildMinimalClass(internalName: String, returnConst: Int): ByteArray {
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
        val utf8s = listOf(internalName, "java/lang/Object", "Code", "now", "()I")
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
