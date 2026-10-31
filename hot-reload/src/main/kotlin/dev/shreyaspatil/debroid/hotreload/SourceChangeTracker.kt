package dev.shreyaspatil.debroid.hotreload

import java.io.File
import java.security.MessageDigest
import java.util.Locale

class SourceChangeTracker(
    private val layout: GradleProjectLayout
) {
    private data class FileFingerprint(
        val lastModified: Long,
        val length: Long,
        val sha256: String
    )

    private val baseline = mutableMapOf<File, FileFingerprint>()
    private val fileModules = mutableMapOf<File, GradleModule>()
    private val pendingSnapshots = mutableMapOf<File, FileFingerprint>()

    fun initializeBaseline() {
        baseline.clear()
        fileModules.clear()
        pendingSnapshots.clear()
        for ((module, file) in scanAllTrackedFiles()) {
            baseline[file] = fingerprint(file)
            fileModules[file] = module
        }
    }

    fun pollChanges(): DetectedChanges {
        pendingSnapshots.clear()
        val codeChanges = mutableMapOf<GradleModule, MutableList<File>>()
        val resourceChanges = mutableListOf<File>()
        val seenFiles = mutableSetOf<File>()

        for ((module, file) in scanAllTrackedFiles()) {
            seenFiles.add(file)
            fileModules[file] = module
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
            recordChangedFile(module, file, codeChanges, resourceChanges)
        }

        for (deletedFile in baseline.keys - seenFiles) {
            val module = fileModules[deletedFile]
            if (module != null) {
                recordChangedFile(module, deletedFile, codeChanges, resourceChanges)
            }
        }

        return DetectedChanges(
            codeChangesByModule = codeChanges,
            resourceFiles = resourceChanges
        )
    }

    private fun recordChangedFile(
        module: GradleModule,
        file: File,
        codeChanges: MutableMap<GradleModule, MutableList<File>>,
        resourceChanges: MutableList<File>
    ) {
        if (isCodeFile(file)) {
            codeChanges.computeIfAbsent(module) { mutableListOf() }.add(file)
        } else if (isResourceOrManifestFile(file)) {
            resourceChanges.add(file)
        }
    }

    fun commitChanges(changes: DetectedChanges) {
        val allFiles = changes.codeChangesByModule.values.flatten() + changes.resourceFiles
        for (file in allFiles) {
            val canonical = file.canonicalFile
            val fp = pendingSnapshots[canonical] ?: if (canonical.exists()) fingerprint(canonical) else null
            if (fp != null) {
                baseline[canonical] = fp
            } else {
                baseline.remove(canonical)
                fileModules.remove(canonical)
            }
        }
    }

    private fun scanAllTrackedFiles(): List<Pair<GradleModule, File>> {
        val results = mutableListOf<Pair<GradleModule, File>>()
        for (module in layout.modules) {
            val srcDir = File(module.moduleDir, "src")
            if (!srcDir.isDirectory) continue
            srcDir.walkTopDown()
                .onEnter { dir -> !isIgnoredSourceSet(dir, srcDir) }
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
        val hex = digest.joinToString("") { "%02x".format(Locale.US, it) }
        return FileFingerprint(
            lastModified = file.lastModified(),
            length = bytes.size.toLong(),
            sha256 = hex
        )
    }
}
