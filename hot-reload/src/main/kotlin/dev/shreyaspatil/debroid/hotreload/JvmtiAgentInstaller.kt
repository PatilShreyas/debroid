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
            if (extractBundledAgent(abi, hostTempDir) == null) {
                return AgentExecutionResult.Error("UNSUPPORTED_ABI", "No bundled JVMTI agent for ABI $abi")
            }

            val appAgentDir = "/data/data/$appId/code_cache/debroid_hr"
            val stageError = stageAndCopyFilesToApp(appId, hostTempDir, classPayloads, combinedDexFile)
            if (stageError != null) return stageError

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

    private fun stageAndCopyFilesToApp(
        appId: String,
        hostTempDir: File,
        classPayloads: List<ClassDexPayload>,
        combinedDexFile: File?
    ): AgentExecutionResult.Error? {
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
        return null
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
            abi.contains("x86") -> "x86"
            abi.contains("arm") -> "armeabi-v7a"
            else -> abi.ifEmpty { "unknown" }
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
