package dev.shreyaspatil.debroid.hotreload

import dev.shreyaspatil.debroid.adb.AdbManager
import java.io.File
import java.nio.file.Files

class JvmtiAgentInstaller(
    private val adbManager: AdbManager
) {
    private var cachedAbi: String? = null
    private val installedAgentApps = mutableSetOf<String>()

    companion object {
        private const val RESULT_POLL_ATTEMPTS = 50
        private const val RESULT_POLL_SLEEP_MS = 20L
        private const val AGENT_FILENAME = "libdebroid_hotreload.so"
        private const val SYNTHETIC_MARKER_PREFIX = "\$\$ExternalSynthetic"
    }

    fun invalidateInstalledAgent(appId: String) {
        installedAgentApps.remove(appId)
    }

    fun applyHotReload(
        appId: String,
        classPayloads: List<ClassDexPayload>,
        combinedDexFile: File?
    ): AgentExecutionResult {
        val abi = cachedAbi ?: detectDeviceAbi().also { detected ->
            if (isSupportedAbi(detected)) {
                cachedAbi = detected
            }
        }
        if (!isSupportedAbi(abi)) {
            return AgentExecutionResult.Error("UNSUPPORTED_ABI", "No bundled JVMTI agent for ABI $abi")
        }

        val firstAttempt = executeHotReloadAttempt(
            appId = appId,
            abi = abi,
            classPayloads = classPayloads,
            combinedDexFile = combinedDexFile,
            includeAgentBinary = appId !in installedAgentApps
        )
        if (shouldRetryWithAgentReinstall(firstAttempt, appId)) {
            installedAgentApps.remove(appId)
            return executeHotReloadAttempt(
                appId = appId,
                abi = abi,
                classPayloads = classPayloads,
                combinedDexFile = combinedDexFile,
                includeAgentBinary = true
            )
        }
        return firstAttempt
    }

    private fun shouldRetryWithAgentReinstall(result: AgentExecutionResult, appId: String): Boolean {
        if (appId !in installedAgentApps) return false
        val error = result as? AgentExecutionResult.Error ?: return false
        return error.code == "ATTACH_AGENT_FAILED" || error.code == "RUN_AS_COPY_FAILED"
    }

    private fun executeHotReloadAttempt(
        appId: String,
        abi: String,
        classPayloads: List<ClassDexPayload>,
        combinedDexFile: File?,
        includeAgentBinary: Boolean
    ): AgentExecutionResult {
        val hostTempDir = Files.createTempDirectory("debroid_hr_stage").toFile()
        try {
            if (includeAgentBinary && extractBundledAgent(abi, hostTempDir) == null) {
                return AgentExecutionResult.Error("UNSUPPORTED_ABI", "No bundled JVMTI agent for ABI $abi")
            }

            val appAgentDir = "/data/data/$appId/code_cache/debroid_hr"
            val stageError = stageAndCopyFilesToApp(
                appId = appId,
                hostTempDir = hostTempDir,
                classPayloads = classPayloads,
                combinedDexFile = combinedDexFile,
                includeAgentBinary = includeAgentBinary
            )
            if (stageError != null) return stageError

            val attachSpec = "$appAgentDir/$AGENT_FILENAME=$appAgentDir/manifest.txt"
            val attachRes = adbManager.runShell("cmd", "activity", "attach-agent", appId, attachSpec)
            if (attachRes.isFailure) {
                return AgentExecutionResult.Error(
                    "ATTACH_AGENT_FAILED",
                    "cmd activity attach-agent failed: ${attachRes.exceptionOrNull()?.message}"
                )
            }
            installedAgentApps.add(appId)

            return pollAgentResult(appId, "$appAgentDir/result.txt")
        } finally {
            hostTempDir.deleteRecursively()
        }
    }

    @Suppress("SpreadOperator")
    private fun stageAndCopyFilesToApp(
        appId: String,
        hostTempDir: File,
        classPayloads: List<ClassDexPayload>,
        combinedDexFile: File?,
        includeAgentBinary: Boolean
    ): AgentExecutionResult.Error? {
        val remoteTmpDir = "/data/local/tmp/debroid_hr_$appId"
        val appAgentDir = "/data/data/$appId/code_cache/debroid_hr"
        val hasCombinedDex = combinedDexFile != null && combinedDexFile.exists()

        val reloadToken = System.nanoTime()
        val manifestLines = mutableListOf<String>()
        if (hasCombinedDex) {
            val combinedName = "new_classes_$reloadToken.dex"
            val stagedCombined = File(hostTempDir, combinedName)
            combinedDexFile.copyTo(stagedCombined, overwrite = true)
            manifestLines.add("combined_dex=$appAgentDir/$combinedName")
        }

        var redefineFileIdx = 0
        for (payload in classPayloads) {
            if (hasCombinedDex && payload.className.contains(SYNTHETIC_MARKER_PREFIX)) {
                // Brand-new per-reload synthetic lambda classes are never already loaded in ART;
                // they are loaded via BaseDexClassLoader.addDexPath(combined_dex).
                manifestLines.add("class=${payload.className}|")
            } else {
                val fileName = "redefine_${redefineFileIdx++}.dex"
                payload.dexFile.copyTo(File(hostTempDir, fileName), overwrite = true)
                manifestLines.add("class=${payload.className}|$appAgentDir/$fileName")
            }
        }

        val manifestFile = File(hostTempDir, "manifest.txt")
        manifestFile.writeText(manifestLines.joinToString("\n") + "\n")

        adbManager.runShell("rm", "-rf", remoteTmpDir)
        adbManager.runShell("mkdir", "-p", remoteTmpDir)

        val filesToPush = hostTempDir.listFiles().orEmpty().sortedBy { it.name }
        val pushError = pushStagedFiles(hostTempDir, filesToPush, remoteTmpDir)
        if (pushError != null) return pushError

        adbManager.runShell("chmod", "-R", "755", remoteTmpDir)
        if (includeAgentBinary) {
            adbManager.runAs(appId, "mkdir", "-p", appAgentDir)
        }

        val pathsToRemove = buildList {
            add("$appAgentDir/result.txt")
            filesToPush.forEach { add("$appAgentDir/${it.name}") }
        }.toTypedArray()
        adbManager.runAs(appId, "rm", "-f", *pathsToRemove)

        val remoteSources = filesToPush.map { "$remoteTmpDir/${it.name}" }.toTypedArray()
        val cpRes = adbManager.runAs(appId, "cp", *remoteSources, "$appAgentDir/")
        if (cpRes.isFailure) {
            return AgentExecutionResult.Error(
                "RUN_AS_COPY_FAILED",
                "Failed to copy staged payload into app code_cache: ${cpRes.exceptionOrNull()?.message}"
            )
        }

        val dexTargets = filesToPush
            .filter { it.extension == "dex" }
            .map { "$appAgentDir/${it.name}" }
            .toTypedArray()
        if (dexTargets.isNotEmpty()) {
            adbManager.runAs(appId, "chmod", "400", *dexTargets)
        }
        if (includeAgentBinary) {
            adbManager.runAs(appId, "chmod", "700", "$appAgentDir/$AGENT_FILENAME")
        }
        return null
    }

    private fun pushStagedFiles(
        hostTempDir: File,
        filesToPush: List<File>,
        remoteTmpDir: String
    ): AgentExecutionResult.Error? {
        if (filesToPush.isEmpty()) return null
        val pushRes = if (filesToPush.size == 1) {
            val single = filesToPush.first()
            adbManager.pushFile(single.absolutePath, "$remoteTmpDir/${single.name}")
        } else {
            adbManager.pushFile("${hostTempDir.absolutePath}/.", remoteTmpDir)
        }
        if (pushRes.isFailure) {
            return AgentExecutionResult.Error(
                "ADB_PUSH_FAILED",
                "Failed to push staged files: ${pushRes.exceptionOrNull()?.message}"
            )
        }
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

    private fun isSupportedAbi(abi: String): Boolean {
        return JvmtiAgentInstaller::class.java.getResource("/native/$abi/$AGENT_FILENAME") != null
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
