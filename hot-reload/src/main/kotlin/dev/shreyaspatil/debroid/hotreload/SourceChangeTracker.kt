package dev.shreyaspatil.debroid.hotreload

import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchEvent
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

class SourceChangeTracker(
    private val layout: GradleProjectLayout
) : AutoCloseable {
    private data class FileFingerprint(
        val lastModified: Long,
        val length: Long,
        val sha256: String
    )

    private data class WatchedDir(
        val module: GradleModule,
        val srcDir: File,
        val dir: File,
        var lastModified: Long
    )

    private val baseline = mutableMapOf<File, FileFingerprint>()
    private val fileModules = mutableMapOf<File, GradleModule>()
    private val pendingSnapshots = mutableMapOf<File, FileFingerprint>()
    private val failedAttemptHashes = mutableMapOf<File, String>()
    private val canonicalFileCache = mutableMapOf<File, File>()

    private var watchService: WatchService? = null
    private val watchKeys = mutableMapOf<WatchKey, WatchedDir>()
    private val watchedDirTimestamps = mutableMapOf<File, WatchedDir>()
    private val dirtyCandidates = mutableSetOf<File>()
    private var needsFullScan: Boolean = false
    private val wakeLock = Object()

    @Volatile
    private var wokenUp: Boolean = false

    companion object {
        private const val DELETED_SENTINEL = "<DELETED>"
        private const val DEFAULT_AWAIT_TIMEOUT_MS = 300L
        private const val FAST_POLL_SLICE_MS = 80L
        private const val COALESCE_QUIET_WINDOW_MS = 140L
        private const val MAX_COALESCE_PASSES = 3

        private val watchKinds = arrayOf<WatchEvent.Kind<*>>(
            StandardWatchEventKinds.ENTRY_CREATE,
            StandardWatchEventKinds.ENTRY_MODIFY,
            StandardWatchEventKinds.ENTRY_DELETE
        )

        private val highSensitivityModifier: WatchEvent.Modifier? = runCatching {
            val clazz = Class.forName("com.sun.nio.file.SensitivityWatchEventModifier")
            clazz.enumConstants?.firstOrNull { (it as? Enum<*>)?.name == "HIGH" } as? WatchEvent.Modifier
        }.getOrNull()
    }

    fun initializeBaseline() {
        baseline.clear()
        fileModules.clear()
        pendingSnapshots.clear()
        failedAttemptHashes.clear()
        dirtyCandidates.clear()
        needsFullScan = false
        resetWatchService()

        for ((module, file) in scanAllTrackedFiles(registerWatchers = true)) {
            baseline[file] = fingerprint(file)
            fileModules[file] = module
        }
    }

    fun wakeUp() {
        synchronized(wakeLock) {
            wokenUp = true
            wakeLock.notifyAll()
        }
    }

    fun awaitChanges(timeoutMs: Long = DEFAULT_AWAIT_TIMEOUT_MS): DetectedChanges {
        val ws = watchService
        if (ws == null) {
            if (timeoutMs > 0L) {
                waitOnWakeLock(timeoutMs)
            }
            return pollChanges()
        }

        drainWatchEvents(ws, null)
        if (dirtyCandidates.isEmpty() && !needsFullScan) {
            collectTimestampDirtyFiles()
        }

        var remainingMs = timeoutMs
        while (shouldContinueAwaiting(remainingMs)) {
            val sliceMs = minOf(remainingMs, FAST_POLL_SLICE_MS)
            val startNs = System.nanoTime()
            val signaledKey = runCatching { ws.poll(sliceMs, TimeUnit.MILLISECONDS) }.getOrNull()
            if (signaledKey != null) {
                drainWatchEvents(ws, signaledKey)
            }
            if (dirtyCandidates.isEmpty() && !needsFullScan) {
                collectTimestampDirtyFiles()
            }
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs).coerceAtLeast(1L)
            remainingMs -= elapsedMs
        }

        if (needsFullScan) {
            needsFullScan = false
            dirtyCandidates.clear()
            return pollChanges()
        }

        if (dirtyCandidates.isEmpty()) {
            return DetectedChanges(emptyMap(), emptyList())
        }

        if (timeoutMs > 0L) {
            coalesceMultiFileEdits(ws)
        }

        val candidates = LinkedHashSet<File>(dirtyCandidates.size + failedAttemptHashes.size)
        candidates.addAll(dirtyCandidates)
        candidates.addAll(failedAttemptHashes.keys)
        dirtyCandidates.clear()
        return evaluateCandidates(candidates)
    }

    private fun shouldContinueAwaiting(remainingMs: Long): Boolean {
        if (dirtyCandidates.isNotEmpty() || needsFullScan || remainingMs <= 0L) return false
        return !consumeWokenUp()
    }

    private fun coalesceMultiFileEdits(ws: WatchService) {
        var passes = 0
        while (passes++ < MAX_COALESCE_PASSES) {
            val prevSize = dirtyCandidates.size
            val signaled = runCatching { ws.poll(COALESCE_QUIET_WINDOW_MS, TimeUnit.MILLISECONDS) }.getOrNull()
            if (signaled != null) {
                drainWatchEvents(ws, signaled)
            }
            collectTimestampDirtyFiles()
            if (dirtyCandidates.size == prevSize && signaled == null) {
                break
            }
        }
    }

    private fun consumeWokenUp(): Boolean {
        synchronized(wakeLock) {
            val wasWoken = wokenUp
            wokenUp = false
            return wasWoken
        }
    }

    private fun waitOnWakeLock(timeoutMs: Long) {
        synchronized(wakeLock) {
            if (!wokenUp && timeoutMs > 0L) {
                wakeLock.wait(timeoutMs)
            }
            wokenUp = false
        }
    }

    fun pollChanges(): DetectedChanges {
        watchService?.let { drainWatchEvents(it, null) }
        dirtyCandidates.clear()
        needsFullScan = false

        val candidates = LinkedHashSet<File>(baseline.size + 16)
        for ((module, file) in scanAllTrackedFiles(registerWatchers = true)) {
            fileModules[file] = module
            candidates.add(file)
        }
        candidates.addAll(baseline.keys)
        candidates.addAll(failedAttemptHashes.keys)
        return evaluateCandidates(candidates)
    }

    private fun evaluateCandidates(candidates: Set<File>): DetectedChanges {
        val codeChanges = mutableMapOf<GradleModule, MutableList<File>>()
        val resourceChanges = mutableListOf<File>()

        for (file in candidates) {
            val module = fileModules[file] ?: continue
            if (!file.exists()) {
                pendingSnapshots.remove(file)
                if (file in baseline) {
                    recordChangedFile(module, file, codeChanges, resourceChanges)
                } else {
                    failedAttemptHashes.remove(file)
                }
                continue
            }

            val previous = baseline[file]
            val currentModified = file.lastModified()
            val currentLength = file.length()

            if (previous != null && previous.lastModified == currentModified && previous.length == currentLength) {
                pendingSnapshots.remove(file)
                failedAttemptHashes.remove(file)
                continue
            }

            val currentFp = fingerprint(file)
            if (previous != null && previous.sha256 == currentFp.sha256) {
                // Only timestamp changed; update baseline timestamp silently
                baseline[file] = currentFp
                pendingSnapshots.remove(file)
                failedAttemptHashes.remove(file)
                continue
            }

            pendingSnapshots[file] = currentFp
            recordChangedFile(module, file, codeChanges, resourceChanges)
        }

        val allChangedFiles = codeChanges.values.flatten() + resourceChanges
        if (allChangedFiles.isEmpty()) {
            failedAttemptHashes.clear()
            return DetectedChanges(emptyMap(), emptyList())
        }

        val hasNewChangeSinceLastAttempt = allChangedFiles.any { file ->
            val currentHash = pendingSnapshots[file]?.sha256 ?: DELETED_SENTINEL
            failedAttemptHashes[file] != currentHash
        } || (failedAttemptHashes.keys - allChangedFiles.toSet()).isNotEmpty()

        if (!hasNewChangeSinceLastAttempt) {
            return DetectedChanges(emptyMap(), emptyList())
        }

        failedAttemptHashes.clear()
        for (file in allChangedFiles) {
            failedAttemptHashes[file] = pendingSnapshots[file]?.sha256 ?: DELETED_SENTINEL
        }

        return DetectedChanges(
            codeChangesByModule = codeChanges,
            resourceFiles = resourceChanges
        )
    }

    private fun drainWatchEvents(ws: WatchService, firstKey: WatchKey?) {
        var key: WatchKey? = firstKey ?: runCatching { ws.poll() }.getOrNull()
        while (key != null) {
            val watched = watchKeys[key]
            if (watched == null) {
                key.cancel()
            } else {
                for (event in key.pollEvents()) {
                    if (event.kind() === StandardWatchEventKinds.OVERFLOW) {
                        needsFullScan = true
                        continue
                    }
                    val contextPath = event.context() as? Path ?: continue
                    val targetFile = watched.dir.toPath().resolve(contextPath).toFile()
                    handleWatchEventFile(watched, targetFile)
                }
                watched.lastModified = watched.dir.lastModified()
                if (!key.reset()) {
                    watchKeys.remove(key)
                }
            }
            key = runCatching { ws.poll() }.getOrNull()
        }
    }

    private fun handleWatchEventFile(watched: WatchedDir, rawFile: File) {
        val canonicalSrc = canonicalFile(watched.srcDir)
        val canonicalTarget = canonicalFile(rawFile)
        if (!canonicalTarget.toPath().startsWith(canonicalSrc.toPath())) return
        if (isInsideIgnoredSourceSet(canonicalTarget, canonicalSrc)) return

        when {
            canonicalTarget.isDirectory -> {
                registerDirectoryTree(watched.module, canonicalSrc, canonicalTarget)
            }
            canonicalTarget.isFile -> {
                if (isTrackedFile(canonicalTarget)) {
                    fileModules[canonicalTarget] = watched.module
                    dirtyCandidates.add(canonicalTarget)
                }
            }
            canonicalTarget in baseline -> {
                dirtyCandidates.add(canonicalTarget)
            }
            else -> {
                val prefix = "${canonicalTarget.path}${File.separator}"
                for (tracked in baseline.keys) {
                    if (tracked.path.startsWith(prefix)) {
                        dirtyCandidates.add(tracked)
                    }
                }
            }
        }
    }

    private fun collectTimestampDirtyFiles() {
        for (watched in watchedDirTimestamps.values.toList()) {
            if (!watched.dir.exists()) {
                watchedDirTimestamps.remove(watched.dir)
                val prefix = "${watched.dir.path}${File.separator}"
                for (tracked in baseline.keys) {
                    if (tracked.path.startsWith(prefix)) {
                        dirtyCandidates.add(tracked)
                    }
                }
                continue
            }
            val currentDirMod = watched.dir.lastModified()
            if (currentDirMod != watched.lastModified) {
                watched.lastModified = currentDirMod
                scanModifiedDirectoryChildren(watched)
            }
        }

        for ((file, fp) in baseline) {
            if (!file.exists() || file.lastModified() != fp.lastModified || file.length() != fp.length) {
                dirtyCandidates.add(file)
            }
        }
    }

    private fun scanModifiedDirectoryChildren(watched: WatchedDir) {
        val children = watched.dir.listFiles().orEmpty()
        for (child in children) {
            val canonicalChild = canonicalFile(child)
            if (!canonicalChild.toPath().startsWith(watched.srcDir.toPath())) continue
            if (isInsideIgnoredSourceSet(canonicalChild, watched.srcDir)) continue
            if (canonicalChild.isDirectory && canonicalChild !in watchedDirTimestamps) {
                registerDirectoryTree(watched.module, watched.srcDir, canonicalChild)
            } else if (canonicalChild.isFile && isTrackedFile(canonicalChild) && canonicalChild !in baseline) {
                fileModules[canonicalChild] = watched.module
                dirtyCandidates.add(canonicalChild)
            }
        }
    }

    private fun registerDirectoryTree(module: GradleModule, canonicalSrcDir: File, rootDir: File) {
        if (!rootDir.isDirectory) return
        rootDir.walkTopDown()
            .onEnter { dir ->
                val canonicalDir = canonicalFile(dir)
                if (isInsideIgnoredSourceSet(canonicalDir, canonicalSrcDir)) {
                    false
                } else {
                    registerDirectory(module, canonicalSrcDir, canonicalDir)
                    true
                }
            }
            .filter { it.isFile && isTrackedFile(it) }
            .forEach { file ->
                val canonical = canonicalFile(file)
                fileModules[canonical] = module
                dirtyCandidates.add(canonical)
            }
    }

    private fun registerDirectory(module: GradleModule, canonicalSrcDir: File, canonicalDir: File) {
        val watched = watchedDirTimestamps.getOrPut(canonicalDir) {
            WatchedDir(
                module = module,
                srcDir = canonicalSrcDir,
                dir = canonicalDir,
                lastModified = canonicalDir.lastModified()
            )
        }
        watched.lastModified = canonicalDir.lastModified()
        val ws = watchService ?: return
        runCatching {
            val key = if (highSensitivityModifier != null) {
                canonicalDir.toPath().register(ws, watchKinds, highSensitivityModifier)
            } else {
                canonicalDir.toPath().register(
                    ws,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE
                )
            }
            watchKeys[key] = watched
        }
    }

    private fun resetWatchService() {
        close()
        runCatching {
            watchService = FileSystems.getDefault().newWatchService()
        }
    }

    override fun close() {
        runCatching { watchService?.close() }
        watchService = null
        watchKeys.clear()
        watchedDirTimestamps.clear()
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
            val canonical = canonicalFile(file)
            val fp = pendingSnapshots[canonical] ?: if (canonical.exists()) fingerprint(canonical) else null
            if (fp != null) {
                baseline[canonical] = fp
            } else {
                baseline.remove(canonical)
                fileModules.remove(canonical)
                canonicalFileCache.remove(file)
            }
            pendingSnapshots.remove(canonical)
            failedAttemptHashes.remove(canonical)
        }
    }

    private fun scanAllTrackedFiles(registerWatchers: Boolean): List<Pair<GradleModule, File>> {
        val results = mutableListOf<Pair<GradleModule, File>>()
        for (module in layout.modules) {
            val srcDir = File(module.moduleDir, "src")
            val canonicalSrcDir = canonicalFile(srcDir)
            if (!srcDir.isDirectory) {
                if (registerWatchers && module.moduleDir.isDirectory) {
                    registerDirectory(module, canonicalSrcDir, canonicalFile(module.moduleDir))
                }
                continue
            }
            srcDir.walkTopDown()
                .onEnter { dir ->
                    if (isIgnoredSourceSet(dir, srcDir)) {
                        false
                    } else {
                        if (registerWatchers) {
                            registerDirectory(module, canonicalSrcDir, canonicalFile(dir))
                        }
                        true
                    }
                }
                .filter { it.isFile && isTrackedFile(it) }
                .forEach { file ->
                    val canonical = canonicalFile(file)
                    results.add(module to canonical)
                }
        }
        return results
    }

    private fun canonicalFile(file: File): File =
        canonicalFileCache.getOrPut(file) { file.canonicalFile }

    private fun isTrackedFile(file: File): Boolean =
        isCodeFile(file) || isResourceOrManifestFile(file)

    private fun isIgnoredSourceSet(dir: File, srcDir: File): Boolean {
        if (dir.parentFile != srcDir) return false
        val name = dir.name.lowercase(Locale.US)
        return name.endsWith("test") || name.startsWith("test") || name.startsWith("androidtest")
    }

    private fun isInsideIgnoredSourceSet(target: File, canonicalSrcDir: File): Boolean {
        val srcPath = canonicalSrcDir.toPath()
        val targetPath = target.toPath()
        if (!targetPath.startsWith(srcPath) || targetPath == srcPath) return false
        val sourceSetSegment = srcPath.relativize(targetPath).getName(0).toString().lowercase(Locale.US)
        return sourceSetSegment.endsWith("test") ||
            sourceSetSegment.startsWith("test") ||
            sourceSetSegment.startsWith("androidtest")
    }

    private fun isCodeFile(file: File): Boolean {
        val ext = file.extension.lowercase(Locale.US)
        return ext == "kt" || ext == "java"
    }

    private fun isResourceOrManifestFile(file: File): Boolean {
        if (file.name == "AndroidManifest.xml") return true
        if (file.extension.lowercase(Locale.US) != "xml") return false
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
