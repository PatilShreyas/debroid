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
        every {
            runner.runCommand(match { it.contains("ro.product.cpu.abi") }, any())
        } returns Result.success("arm64-v8a\n")
        every { runner.runCommand(match { it.contains("push") }, any()) } returns Result.success("")
        every {
            runner.runCommand(match { it.any { arg -> arg.contains("mkdir -p") || arg.contains("cp ") } }, any())
        } returns Result.success("")
        every { runner.runCommand(match { it.contains("chmod") }, any()) } returns Result.success("")
        every { runner.runCommand(match { it.contains("attach-agent") }, any()) } returns Result.success("")
        every {
            runner.runCommand(match { it.any { arg -> arg.contains("result.txt") && arg.contains("while") } }, any())
        } returns Result.success("status=OK mode=JVMTI_HOT_SWAP redefined=2 new=1 ui=COMPOSE_HOT_RELOAD\n")

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
        every {
            runner.runCommand(match { it.contains("ro.product.cpu.abi") }, any())
        } returns Result.success("x86_64\n")
        every { runner.runCommand(match { it.contains("push") }, any()) } returns Result.success("")
        every {
            runner.runCommand(match { it.any { arg -> arg.contains("mkdir -p") || arg.contains("cp ") } }, any())
        } returns Result.success("")
        every { runner.runCommand(match { it.contains("chmod") }, any()) } returns Result.success("")
        every { runner.runCommand(match { it.contains("attach-agent") }, any()) } returns Result.success("")
        every {
            runner.runCommand(match { it.any { arg -> arg.contains("result.txt") && arg.contains("while") } }, any())
        } returns Result.success("status=STRUCTURAL_INCOMPATIBLE reason=JVMTI_ERROR_63\n")

        val adbManager = AdbManager(adbPath = "adb", commandRunner = runner)
        val installer = JvmtiAgentInstaller(adbManager)
        val dex1 = File(tempDir, "redefine_0.dex").apply { writeBytes(byteArrayOf(1)) }

        val res = installer.applyHotReload(
            appId = "com.example.app",
            classPayloads = listOf(ClassDexPayload("com.example.MainScreenKt", dex1)),
            combinedDexFile = null
        )

        assertTrue(res is AgentExecutionResult.StructuralIncompatible)
        val incompatible = res as AgentExecutionResult.StructuralIncompatible
        assertEquals("JVMTI_ERROR_63", incompatible.reason)
    }

    @Test
    fun `returns error when push fails`() {
        val runner = mockk<CommandRunner>()
        every {
            runner.runCommand(match { it.contains("ro.product.cpu.abi") }, any())
        } returns Result.success("arm64-v8a\n")
        every {
            runner.runCommand(match { it.any { arg -> arg.contains("mkdir -p") } }, any())
        } returns Result.success("")
        every {
            runner.runCommand(match { it.contains("push") }, any())
        } returns Result.failure(Exception("device offline"))

        val adbManager = AdbManager(adbPath = "adb", commandRunner = runner)
        val installer = JvmtiAgentInstaller(adbManager)

        val res = installer.applyHotReload(
            appId = "com.example.app",
            classPayloads = emptyList(),
            combinedDexFile = null
        )

        assertTrue(res is AgentExecutionResult.Error)
        val error = res as AgentExecutionResult.Error
        assertEquals("ADB_PUSH_FAILED", error.code)
    }

    @Test
    fun `returns error when run-as cp fails`() {
        val runner = mockk<CommandRunner>()
        every {
            runner.runCommand(match { it.contains("ro.product.cpu.abi") }, any())
        } returns Result.success("arm64-v8a\n")
        every { runner.runCommand(match { it.contains("push") }, any()) } returns Result.success("")
        every {
            runner.runCommand(match { it.any { arg -> arg.contains("mkdir -p") && !arg.contains("cp ") } }, any())
        } returns Result.success("")
        every { runner.runCommand(match { it.contains("chmod") }, any()) } returns Result.success("")
        every {
            runner.runCommand(match { it.any { arg -> arg.contains("cp ") } }, any())
        } returns Result.failure(Exception("Permission denied"))

        val adbManager = AdbManager(adbPath = "adb", commandRunner = runner)
        val installer = JvmtiAgentInstaller(adbManager)

        val res = installer.applyHotReload(
            appId = "com.example.app",
            classPayloads = emptyList(),
            combinedDexFile = null
        )

        assertTrue(res is AgentExecutionResult.Error)
        val error = res as AgentExecutionResult.Error
        assertEquals("RUN_AS_COPY_FAILED", error.code)
    }

    @Test
    fun `returns error when attach-agent fails`() {
        val runner = mockk<CommandRunner>()
        every {
            runner.runCommand(match { it.contains("ro.product.cpu.abi") }, any())
        } returns Result.success("arm64-v8a\n")
        every { runner.runCommand(match { it.contains("push") }, any()) } returns Result.success("")
        every {
            runner.runCommand(match { it.any { arg -> arg.contains("mkdir -p") || arg.contains("cp ") } }, any())
        } returns Result.success("")
        every { runner.runCommand(match { it.contains("chmod") }, any()) } returns Result.success("")
        every {
            runner.runCommand(match { it.contains("attach-agent") }, any())
        } returns Result.failure(Exception("attach error"))

        val adbManager = AdbManager(adbPath = "adb", commandRunner = runner)
        val installer = JvmtiAgentInstaller(adbManager)

        val res = installer.applyHotReload(
            appId = "com.example.app",
            classPayloads = emptyList(),
            combinedDexFile = null
        )

        assertTrue(res is AgentExecutionResult.Error)
        val error = res as AgentExecutionResult.Error
        assertEquals("ATTACH_AGENT_FAILED", error.code)
    }

    @Test
    fun `returns error when agent times out writing result`() {
        val runner = mockk<CommandRunner>()
        every {
            runner.runCommand(match { it.contains("ro.product.cpu.abi") }, any())
        } returns Result.success("arm64-v8a\n")
        every { runner.runCommand(match { it.contains("push") }, any()) } returns Result.success("")
        every {
            runner.runCommand(match { it.any { arg -> arg.contains("mkdir -p") || arg.contains("cp ") } }, any())
        } returns Result.success("")
        every { runner.runCommand(match { it.contains("chmod") }, any()) } returns Result.success("")
        every { runner.runCommand(match { it.contains("attach-agent") }, any()) } returns Result.success("")
        every {
            runner.runCommand(match { it.any { arg -> arg.contains("result.txt") && arg.contains("while") } }, any())
        } returns Result.success("")

        val adbManager = AdbManager(adbPath = "adb", commandRunner = runner)
        val installer = JvmtiAgentInstaller(adbManager)

        val res = installer.applyHotReload(
            appId = "com.example.app",
            classPayloads = emptyList(),
            combinedDexFile = null
        )

        assertTrue(res is AgentExecutionResult.Error)
        val error = res as AgentExecutionResult.Error
        assertEquals("AGENT_TIMEOUT", error.code)
    }

    @Test
    fun `returns error when ABI is unsupported`() {
        val runner = mockk<CommandRunner>()
        every {
            runner.runCommand(match { it.contains("ro.product.cpu.abi") }, any())
        } returns Result.success("mips64\n")

        val adbManager = AdbManager(adbPath = "adb", commandRunner = runner)
        val installer = JvmtiAgentInstaller(adbManager)

        val res = installer.applyHotReload(
            appId = "com.example.app",
            classPayloads = emptyList(),
            combinedDexFile = null
        )

        assertTrue(res is AgentExecutionResult.Error)
        val error = res as AgentExecutionResult.Error
        assertEquals("UNSUPPORTED_ABI", error.code)
    }

    @Test
    fun `parseResultLine handles custom error and default values`() {
        val adbManager = mockk<AdbManager>()
        val installer = JvmtiAgentInstaller(adbManager)

        val errRes = installer.parseResultLine("status=ERROR code=DEX_PARSE_FAIL msg=invalid_dex_header")
        assertTrue(errRes is AgentExecutionResult.Error)
        val error = errRes as AgentExecutionResult.Error
        assertEquals("DEX_PARSE_FAIL", error.code)
        assertEquals("invalid dex header", error.message)
    }

    @Test
    fun `parseResultLine extracts runtime_error when present on OK result`() {
        val adbManager = mockk<AdbManager>()
        val installer = JvmtiAgentInstaller(adbManager)

        val okWithErr = installer.parseResultLine(
            "status=OK mode=JVMTI_HOT_SWAP redefined=1 new=0 ui=COMPOSE_HOT_RELOAD\n" +
                "runtime_error=IllegalStateException: Failed to render (BookmarksScreen.kt:95)\n"
        )
        assertTrue(okWithErr is AgentExecutionResult.Success)
        val success = okWithErr as AgentExecutionResult.Success
        assertEquals(ReloadMode.JVMTI_HOT_SWAP, success.mode)
        assertEquals(
            "IllegalStateException: Failed to render (BookmarksScreen.kt:95)",
            success.runtimeError
        )
    }

    @Test
    fun `writes reinit_clinit, reset_scope_key, reset_scope_class, and static_field entries to manifest`() {
        val runner = mockk<CommandRunner>()
        var capturedManifestText = ""

        every {
            runner.runCommand(match { it.contains("ro.product.cpu.abi") }, any())
        } returns Result.success("arm64-v8a\n")
        every {
            runner.runCommand(match { it.contains("push") }, any())
        } answers {
            val cmd = firstArg<List<String>>()
            val pushIdx = cmd.indexOf("push")
            val localArg = cmd[pushIdx + 1].removeSuffix("/.")
            val manifestFile = File(localArg, "manifest.txt")
            if (manifestFile.exists()) {
                capturedManifestText = manifestFile.readText()
            }
            Result.success("")
        }
        every {
            runner.runCommand(match { it.any { arg -> arg.contains("mkdir -p") || arg.contains("cp ") } }, any())
        } returns Result.success("")
        every { runner.runCommand(match { it.contains("chmod") }, any()) } returns Result.success("")
        every { runner.runCommand(match { it.contains("attach-agent") }, any()) } returns Result.success("")
        every {
            runner.runCommand(match { it.any { arg -> arg.contains("result.txt") && arg.contains("while") } }, any())
        } returns Result.success("status=OK mode=JVMTI_HOT_SWAP redefined=1 new=0 ui=COMPOSE_HOT_RELOAD\n")

        val adbManager = AdbManager(adbPath = "adb", commandRunner = runner)
        val installer = JvmtiAgentInstaller(adbManager)
        val dex1 = File(tempDir, "redefine_0.dex").apply { writeBytes(byteArrayOf(1, 2, 3)) }

        val res = installer.applyHotReload(
            appId = "com.example.app",
            classPayloads = listOf(
                ClassDexPayload(
                    className = "com.example.MainScreenKt",
                    dexFile = dex1,
                    reinitClinit = true,
                    resetScopeKeys = setOf(10101),
                    resetScope = true,
                    staticFieldUpdates = listOf(
                        StaticFieldUpdate(
                            className = "com.example.MainScreenKt",
                            fieldName = "MAX_COUNT",
                            descriptor = "I",
                            value = "42"
                        )
                    )
                )
            ),
            combinedDexFile = null
        )

        assertTrue(res is AgentExecutionResult.Success)
        val lines = capturedManifestText.lines().filter { it.isNotBlank() }
        assertTrue(lines.contains("reinit_clinit=com.example.MainScreenKt"))
        assertTrue(lines.contains("reset_scope_key=10101"))
        assertTrue(lines.contains("reset_scope_class=com.example.MainScreenKt"))
        assertTrue(lines.contains("static_field=com.example.MainScreenKt|MAX_COUNT|I|42"))
    }
}
