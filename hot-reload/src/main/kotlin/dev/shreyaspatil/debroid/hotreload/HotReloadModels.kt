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
    val type: ModuleType,
    val dependencies: Set<String> = emptySet()
)

data class DetectedChanges(
    val codeChangesByModule: Map<GradleModule, List<File>>,
    val resourceFiles: List<File>
) {
    fun isEmpty(): Boolean = codeChangesByModule.isEmpty() && resourceFiles.isEmpty()
}

data class StaticFieldUpdate(
    val className: String,
    val fieldName: String,
    val descriptor: String,
    val value: String
)

data class ClassDexPayload(
    val className: String,
    val dexFile: File,
    val reinitClinit: Boolean = false,
    val resetScopeKeys: Set<Int> = emptySet(),
    val resetScope: Boolean = false,
    val changedScopeKeys: Set<Int> = emptySet(),
    val invalidateAllClassScopes: Boolean = true,
    val staticFieldUpdates: List<StaticFieldUpdate> = emptyList()
)

sealed class CompilationOutcome {
    data class Success(
        val classPayloads: List<ClassDexPayload>,
        val combinedDexFile: File?
    ) : CompilationOutcome()

    data class StructuralIncompatible(
        val reason: String
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
        val uiRefresh: UiRefreshMode,
        val runtimeError: String? = null
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

    data class RuntimeError(
        val error: String
    ) : HotReloadEvent() {
        override fun formatPlainText(): String =
            "[RUNTIME_ERROR] $error"
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
