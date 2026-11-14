package dev.shreyaspatil.debroid.hotreload

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.nio.charset.StandardCharsets
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

@Suppress("TooGenericExceptionCaught")
internal object InProcessGradleRunner {
    private val distUrlRegex = Regex("""gradle-([0-9][A-Za-z0-9._-]*)-(bin|all)\.zip""")
    private val classLoadersByLibDir = ConcurrentHashMap<String, URLClassLoader>()
    private val connectionsByProject = ConcurrentHashMap<String, CachedConnection>()
    private val warmedProjects = ConcurrentHashMap.newKeySet<String>()

    private class CachedConnection(
        val lock: Any = Any(),
        val loader: ClassLoader,
        val connection: Any
    )

    init {
        Runtime.getRuntime().addShutdownHook(
            Thread {
                closeAll()
            }.apply { name = "debroid-gradle-tapi-shutdown" }
        )
    }

    fun warmUpAsync(
        projectRoot: File,
        javaHome: File? = null,
        initialArgs: List<String> = listOf("--console=plain")
    ) {
        val canonicalRoot = runCatching { projectRoot.canonicalPath }.getOrElse { projectRoot.absolutePath }
        if (!warmedProjects.add(canonicalRoot)) return

        Thread {
            runCatching {
                getOrCreateConnection(projectRoot) ?: return@Thread
                // Warm the daemon & configuration cache with a lightweight help/model no-op if no tasks yet
                val noopTasks = listOf("help")
                tryRunTasks(projectRoot, noopTasks, initialArgs + listOf("-q"), javaHome)
            }
        }.apply {
            name = "debroid-gradle-warmup"
            isDaemon = true
            start()
        }
    }

    fun tryRunTasks(
        projectRoot: File,
        tasks: List<String>,
        gradleFlags: List<String>,
        javaHome: File? = null
    ): Result<String>? {
        if (tasks.isEmpty()) return Result.success("")
        val cached = getOrCreateConnection(projectRoot) ?: return null
        val filteredArgs = stripProjectDirFlag(gradleFlags)
        val out = ByteArrayOutputStream()

        return synchronized(cached.lock) {
            try {
                val conn = cached.connection
                val projectConnCls = cached.loader.loadClass("org.gradle.tooling.ProjectConnection")
                val buildLauncherCls = cached.loader.loadClass("org.gradle.tooling.BuildLauncher")
                val longRunningOpCls = cached.loader.loadClass("org.gradle.tooling.LongRunningOperation")

                val launcher = projectConnCls.getMethod("newBuild").invoke(conn)
                buildLauncherCls.getMethod("forTasks", Array<String>::class.java)
                    .invoke(launcher, tasks.toTypedArray())
                if (javaHome != null) {
                    longRunningOpCls.getMethod("setJavaHome", File::class.java)
                        .invoke(launcher, javaHome)
                }
                if (filteredArgs.isNotEmpty()) {
                    longRunningOpCls.getMethod("withArguments", Array<String>::class.java)
                        .invoke(launcher, filteredArgs.toTypedArray())
                }
                longRunningOpCls.getMethod("setStandardOutput", OutputStream::class.java)
                    .invoke(launcher, out)
                longRunningOpCls.getMethod("setStandardError", OutputStream::class.java)
                    .invoke(launcher, out)
                buildLauncherCls.getMethod("run").invoke(launcher)

                Result.success(out.toString(StandardCharsets.UTF_8))
            } catch (e: InvocationTargetException) {
                val target = e.targetException ?: e
                if (isConnectionBrokenException(target)) {
                    closeProject(projectRoot)
                    null
                } else {
                    val capturedOutput = out.toString(StandardCharsets.UTF_8)
                    val causalMessages = collectCausalMessages(target)
                    val fullMessage = buildString {
                        if (capturedOutput.isNotBlank()) appendLine(capturedOutput)
                        if (causalMessages.isNotBlank()) append(causalMessages)
                    }.ifBlank { "Gradle compilation failed" }
                    Result.failure(RuntimeException(fullMessage, target))
                }
            } catch (_: Exception) {
                closeProject(projectRoot)
                null
            }
        }
    }

    fun closeProject(projectRoot: File) {
        val canonicalRoot = runCatching { projectRoot.canonicalPath }.getOrElse { projectRoot.absolutePath }
        val removed = connectionsByProject.remove(canonicalRoot) ?: return
        warmedProjects.remove(canonicalRoot)
        runCatching {
            val projectConnCls = removed.loader.loadClass("org.gradle.tooling.ProjectConnection")
            projectConnCls.getMethod("close").invoke(removed.connection)
        }
    }

    private fun closeAll() {
        val keys = connectionsByProject.keys.toList()
        for (key in keys) {
            val removed = connectionsByProject.remove(key) ?: continue
            runCatching {
                val projectConnCls = removed.loader.loadClass("org.gradle.tooling.ProjectConnection")
                projectConnCls.getMethod("close").invoke(removed.connection)
            }
        }
    }

    private fun getOrCreateConnection(projectRoot: File): CachedConnection? {
        val canonicalRoot = runCatching { projectRoot.canonicalPath }.getOrElse { projectRoot.absolutePath }
        connectionsByProject[canonicalRoot]?.let { return it }

        val gradleHome = resolveGradleHomeForProject(projectRoot) ?: return null
        val libDir = File(gradleHome, "lib")
        if (!libDir.isDirectory) return null

        return runCatching {
            val loader = classLoadersByLibDir.computeIfAbsent(libDir.canonicalPath) {
                val jars = buildList {
                    libDir.listFiles()?.filter { it.isFile && it.extension == "jar" }?.let { addAll(it) }
                    File(libDir, "plugins").listFiles()
                        ?.filter { it.isFile && it.name.startsWith("gradle-tooling-api") && it.extension == "jar" }
                        ?.let { addAll(it) }
                }
                URLClassLoader(jars.map { it.toURI().toURL() }.toTypedArray(), ClassLoader.getPlatformClassLoader())
            }
            val connectorCls = loader.loadClass("org.gradle.tooling.GradleConnector")
            val connector = connectorCls.getMethod("newConnector").invoke(null)
            connectorCls.getMethod("forProjectDirectory", File::class.java)
                .invoke(connector, File(canonicalRoot))
            connectorCls.getMethod("useInstallation", File::class.java)
                .invoke(connector, gradleHome)
            val conn = connectorCls.getMethod("connect").invoke(connector)
            val created = CachedConnection(loader = loader, connection = conn)
            connectionsByProject.putIfAbsent(canonicalRoot, created) ?: created
        }.getOrNull()
    }

    internal fun resolveGradleHomeForProject(projectRoot: File): File? {
        val gradleUserHome = System.getenv("GRADLE_USER_HOME")
            ?.takeIf { it.isNotBlank() }
            ?.let { File(it) }
            ?: File(System.getProperty("user.home"), ".gradle")
        val distsRoot = File(gradleUserHome, "wrapper/dists")
        if (!distsRoot.isDirectory) return null

        val wrapperPropsFile = File(projectRoot, "gradle/wrapper/gradle-wrapper.properties")
        if (wrapperPropsFile.isFile) {
            val distUrl = runCatching {
                Properties().apply { wrapperPropsFile.inputStream().use { load(it) } }
                    .getProperty("distributionUrl")
            }.getOrNull().orEmpty()
            val match = distUrlRegex.find(distUrl)
            if (match != null) {
                val version = match.groupValues[1]
                val kind = match.groupValues[2]
                val distDir = File(distsRoot, "gradle-$version-$kind")
                findValidGradleHomeInDist(distDir, "gradle-$version")?.let { return it }
            }
        }

        return distsRoot.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("gradle-") }
            ?.sortedByDescending { it.lastModified() }
            ?.firstNotNullOfOrNull { distDir -> findValidGradleHomeInDist(distDir, null) }
    }

    private fun findValidGradleHomeInDist(distDir: File, expectedSubdir: String?): File? {
        if (!distDir.isDirectory) return null
        for (hashDir in distDir.listFiles().orEmpty()) {
            if (!hashDir.isDirectory) continue
            val candidates = if (expectedSubdir != null) {
                listOf(File(hashDir, expectedSubdir))
            } else {
                hashDir.listFiles()?.filter { it.isDirectory && it.name.startsWith("gradle-") }.orEmpty()
            }
            for (candidate in candidates) {
                val libDir = File(candidate, "lib")
                val hasToolingJar = libDir.isDirectory && libDir.listFiles().orEmpty().any {
                    it.isFile && it.name.startsWith("gradle-tooling-api") && it.extension == "jar"
                }
                if (hasToolingJar) return candidate
            }
        }
        return null
    }

    private fun stripProjectDirFlag(flags: List<String>): List<String> {
        val result = ArrayList<String>(flags.size)
        var skipNext = false
        for (flag in flags) {
            if (skipNext) {
                skipNext = false
                continue
            }
            if (flag == "-p" || flag == "--project-dir") {
                skipNext = true
                continue
            }
            if (flag.startsWith("--project-dir=")) continue
            result.add(flag)
        }
        return result
    }

    private fun isConnectionBrokenException(t: Throwable): Boolean {
        val clsName = t.javaClass.name
        return clsName.contains("GradleConnectionException") && !clsName.contains("BuildException")
    }

    private fun collectCausalMessages(t: Throwable): String {
        val lines = LinkedHashSet<String>()
        var curr: Throwable? = t
        var depth = 0
        val maxCausalDepth = 10
        while (curr != null && depth++ < maxCausalDepth) {
            curr.message?.trim()?.takeIf { it.isNotEmpty() }?.let { lines.add(it) }
            curr = curr.cause
        }
        return lines.joinToString("\n")
    }
}

@Suppress("MagicNumber", "CyclomaticComplexMethod")
internal class KspChangeDetector {
    private val kspSkeletonByFile = ConcurrentHashMap<File, String>()

    companion object {
        private val kspAnnotationMarkers = listOf(
            "@Inject",
            "@Assisted",
            "@Hilt",
            "@AndroidEntryPoint",
            "@EntryPoint",
            "@InstallIn",
            "@Module",
            "@Provides",
            "@Binds",
            "@IntoSet",
            "@IntoMap",
            "@Entity",
            "@Dao",
            "@Database",
            "@TypeConverter",
            "@JsonClass",
            "@Serializable",
            "@AutoService",
            "@Contributes",
            "@Component",
            "@Subcomponent"
        )
    }

    fun snapshotModules(modules: Collection<GradleModule>, resolveVariant: (GradleModule) -> String) {
        kspSkeletonByFile.clear()
        for (module in modules) {
            val modVariant = resolveVariant(module)
            if (resolveExistingKspTaskName(module, modVariant) == null) continue
            val srcDir = File(module.moduleDir, "src")
            if (!srcDir.isDirectory) continue
            srcDir.walkTopDown()
                .onEnter { dir -> !dir.name.equals("test", true) && !dir.name.equals("androidTest", true) }
                .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
                .forEach { file ->
                    val canonical = runCatching { file.canonicalFile }.getOrElse { file }
                    kspSkeletonByFile[canonical] = extractFileSkeleton(canonical)
                }
        }
    }

    fun buildKspExclusionFlags(
        changesByModule: Map<GradleModule, List<File>>,
        resolveVariant: (GradleModule) -> String
    ): List<String> {
        val flags = mutableListOf<String>()
        for ((module, changedFiles) in changesByModule) {
            val modVariant = resolveVariant(module)
            val kspTask = resolveExistingKspTaskName(module, modVariant) ?: continue
            val canSkipKsp = changedFiles.isNotEmpty() && changedFiles.all { file ->
                val canonical = runCatching { file.canonicalFile }.getOrElse { file }
                if (!canonical.isFile) {
                    false
                } else {
                    val prevSkeleton = kspSkeletonByFile[canonical]
                    val currSkeleton = extractFileSkeleton(canonical)
                    prevSkeleton != null && prevSkeleton == currSkeleton
                }
            }
            if (canSkipKsp) {
                flags.add("-x")
                flags.add("${module.gradlePath}:$kspTask")
            }
        }
        return flags
    }

    fun recordSuccessfulCompile(changedFiles: Iterable<File>) {
        for (file in changedFiles) {
            val canonical = runCatching { file.canonicalFile }.getOrElse { file }
            if (canonical.isFile) {
                kspSkeletonByFile[canonical] = extractFileSkeleton(canonical)
            } else {
                kspSkeletonByFile.remove(canonical)
            }
        }
    }

    private fun resolveExistingKspTaskName(module: GradleModule, modVariant: String): String? {
        val kspRoot = File(module.moduleDir, "build/generated/ksp")
        if (!kspRoot.isDirectory) return null
        val capVariant = modVariant.replaceFirstChar { it.uppercaseChar() }
        return when {
            File(kspRoot, modVariant).isDirectory -> "ksp${capVariant}Kotlin"
            module.type == ModuleType.KOTLIN_JVM && File(kspRoot, "main").isDirectory -> "kspKotlin"
            else -> null
        }
    }

    internal fun extractFileSkeleton(file: File): String {
        val raw = runCatching { file.readText(StandardCharsets.UTF_8) }.getOrNull() ?: return "MISSING"
        return extractSourceSkeleton(raw)
    }

    internal fun extractSourceSkeleton(raw: String): String {
        if (kspAnnotationMarkers.none { raw.contains(it) }) return ""
        val sanitized = stripCommentsAndStringBodies(raw)
        val out = StringBuilder(sanitized.length / 2)
        var braceDepth = 0
        var parenDepth = 0
        var skippingInitializer = false
        val nestedTypeDepths = ArrayDeque<Int>()
        var recentHeaderWindow = StringBuilder(64)

        var i = 0
        while (i < sanitized.length) {
            val ch = sanitized[i]
            val activeMaxBodyDepth = nestedTypeDepths.lastOrNull() ?: 1

            when (ch) {
                '(' -> {
                    parenDepth++
                    if (!skippingInitializer && braceDepth <= activeMaxBodyDepth) out.append(ch)
                }
                ')' -> {
                    parenDepth = (parenDepth - 1).coerceAtLeast(0)
                    if (!skippingInitializer && braceDepth <= activeMaxBodyDepth) out.append(ch)
                }
                '{' -> {
                    if (!skippingInitializer && braceDepth <= activeMaxBodyDepth) {
                        val headerStr = recentHeaderWindow.toString()
                        val isTypeBody = Regex("""\b(class|interface|object|enum)\b""").containsMatchIn(headerStr)
                        if (braceDepth >= 1 && isTypeBody) {
                            nestedTypeDepths.addLast(braceDepth + 1)
                        }
                        out.append('{')
                    }
                    recentHeaderWindow.setLength(0)
                    braceDepth++
                }
                '}' -> {
                    braceDepth = (braceDepth - 1).coerceAtLeast(0)
                    if (nestedTypeDepths.isNotEmpty() && braceDepth < nestedTypeDepths.last()) {
                        nestedTypeDepths.removeLast()
                    }
                    val newMax = nestedTypeDepths.lastOrNull() ?: 1
                    if (braceDepth <= newMax) {
                        skippingInitializer = false
                        recentHeaderWindow.setLength(0)
                        out.append('}')
                    }
                }
                '=' -> {
                    val nextCh = sanitized.getOrNull(i + 1)
                    val prevCh = sanitized.getOrNull(i - 1)
                    val isComparison = nextCh == '=' || prevCh == '=' || prevCh == '!' || prevCh == '<' || prevCh == '>'
                    val inDeclarationHeader = braceDepth == activeMaxBodyDepth && parenDepth == 0 && !isComparison
                    if (inDeclarationHeader && !recentHeaderWindow.contains("@Provides")) {
                        skippingInitializer = true
                    } else if (!skippingInitializer && braceDepth <= activeMaxBodyDepth) {
                        out.append(ch)
                    }
                }
                '\n' -> {
                    if (skippingInitializer && parenDepth == 0 && braceDepth <= activeMaxBodyDepth) {
                        val nextNonWs = nextNonWhitespaceChar(sanitized, i + 1)
                        if (nextNonWs != '.' && nextNonWs != '?' && nextNonWs != ':') {
                            skippingInitializer = false
                            recentHeaderWindow.setLength(0)
                        }
                    }
                    if (!skippingInitializer && braceDepth <= activeMaxBodyDepth) {
                        out.append('\n')
                    }
                }
                else -> {
                    if (!skippingInitializer && braceDepth <= activeMaxBodyDepth) {
                        out.append(ch)
                        recentHeaderWindow.append(ch)
                        if (recentHeaderWindow.length > 96) {
                            recentHeaderWindow.delete(0, 32)
                        }
                    }
                }
            }
            i++
        }
        return out.toString().replace(Regex("""\s+"""), " ").trim()
    }

    private fun nextNonWhitespaceChar(text: String, start: Int): Char? {
        for (idx in start..<text.length) {
            val c = text[idx]
            if (c != ' ' && c != '\t' && c != '\r') return c
        }
        return null
    }

    private fun stripCommentsAndStringBodies(src: String): String {
        val sb = StringBuilder(src.length)
        var i = 0
        while (i < src.length) {
            val c = src[i]
            val next = src.getOrNull(i + 1)
            when {
                c == '/' && next == '/' -> {
                    while (i < src.length && src[i] != '\n') i++
                }
                c == '/' && next == '*' -> {
                    i += 2
                    while (i + 1 < src.length && !(src[i] == '*' && src[i + 1] == '/')) i++
                    i += 2
                }
                c == '"' -> {
                    sb.append("\"\"")
                    i = skipStringLiteral(src, i)
                }
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        return sb.toString()
    }

    private fun skipStringLiteral(src: String, quoteStart: Int): Int {
        if (src.startsWith("\"\"\"", quoteStart)) {
            val end = src.indexOf("\"\"\"", quoteStart + 3)
            return if (end >= 0) end + 3 else src.length
        }
        var idx = quoteStart + 1
        while (idx < src.length) {
            if (src[idx] == '\\') {
                idx += 2
                continue
            }
            if (src[idx] == '"') return idx + 1
            idx++
        }
        return src.length
    }
}
