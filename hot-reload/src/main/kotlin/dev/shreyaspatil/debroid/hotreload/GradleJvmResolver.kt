package dev.shreyaspatil.debroid.hotreload

import java.io.File
import java.util.Locale
import java.util.Properties

data class GradleExecutionConfig(
    val javaHome: File? = null,
    val extraGradleArgs: List<String> = emptyList()
) {
    fun buildEnvironment(): Map<String, String> {
        val home = javaHome ?: return emptyMap()
        return mapOf("JAVA_HOME" to home.absolutePath)
    }

    fun buildJvmAndExtraFlags(): List<String> {
        val flags = ArrayList<String>(extraGradleArgs.size + 1)
        if (javaHome != null && extraGradleArgs.none { it.startsWith("-Dorg.gradle.java.home=") }) {
            flags.add("-Dorg.gradle.java.home=${javaHome.absolutePath}")
        }
        flags.addAll(extraGradleArgs)
        return flags
    }

    fun cacheKeySuffix(): String {
        val homePath = javaHome?.canonicalPath.orEmpty()
        val argsJoined = extraGradleArgs.joinToString("\u001F")
        return "$homePath|$argsJoined"
    }
}

@Suppress("MagicNumber", "CyclomaticComplexMethod", "TooManyFunctions")
object GradleJvmResolver {
    private val distVersionRegex = Regex("""gradle-(\d+)\.(\d+)(?:\.(\d+))?""")
    private val majorVersionRegex = Regex("""(?:^|[^0-9])(1\.8|8|11|17|21|25)(?:[^0-9]|$)""")
    private val toolVersionsJavaRegex = Regex("""^\s*java\s+([^\s#]+)""", RegexOption.MULTILINE)
    private val sdkmanJavaRegex = Regex("""^\s*java\s*=\s*([^\s#]+)""", RegexOption.MULTILINE)
    private val jvmToolchainRegex = Regex("""(?:jvmToolchain|JavaLanguageVersion\.of)\s*\(\s*(\d+)\s*\)""")
    private val classFileMajorErrorRegex = Regex(
        """Unsupported class file major version\s+(\d+)""",
        RegexOption.IGNORE_CASE
    )
    private val requiresJavaRegex = Regex("""requires\s+Java\s+(\d+)""", RegexOption.IGNORE_CASE)

    fun parseGradleArgs(rawInputs: List<String>): List<String> {
        if (rawInputs.isEmpty()) return emptyList()
        val tokens = mutableListOf<String>()
        for (raw in rawInputs) {
            tokenizeShellArgs(raw, tokens)
        }
        return tokens
    }

    private fun tokenizeShellArgs(input: String, out: MutableList<String>) {
        val sb = StringBuilder()
        var quoteChar = '\u0000'
        var escaped = false
        var inToken = false

        for (ch in input) {
            when {
                escaped -> {
                    sb.append(ch)
                    escaped = false
                    inToken = true
                }
                ch == '\\' && quoteChar != '\'' -> {
                    escaped = true
                    inToken = true
                }
                quoteChar != '\u0000' -> {
                    if (ch == quoteChar) {
                        quoteChar = '\u0000'
                    } else {
                        sb.append(ch)
                    }
                    inToken = true
                }
                ch == '"' || ch == '\'' -> {
                    quoteChar = ch
                    inToken = true
                }
                ch.isWhitespace() -> {
                    if (inToken) {
                        out.add(sb.toString())
                        sb.setLength(0)
                        inToken = false
                    }
                }
                else -> {
                    sb.append(ch)
                    inToken = true
                }
            }
        }
        if (inToken) {
            out.add(sb.toString())
        }
    }

    fun normalizeJdkHome(candidate: File): File? {
        if (!candidate.exists()) return null
        val macContentsHome = File(candidate, "Contents/Home")
        val resolved = when {
            hasJavaExecutable(macContentsHome) -> macContentsHome
            hasJavaExecutable(candidate) -> candidate
            candidate.isFile && candidate.name.startsWith("java") -> candidate.parentFile?.parentFile
            candidate.name == "bin" -> candidate.parentFile
            else -> null
        }
        return resolved?.takeIf(::hasJavaExecutable)?.canonicalFile
    }

    fun resolveForProject(
        projectRoot: File,
        explicitJavaHome: File? = null,
        extraGradleArgs: List<String> = emptyList()
    ): GradleExecutionConfig {
        val normalizedExplicit = explicitJavaHome?.let(::normalizeJdkHome)
            ?: System.getenv("DEBROID_JAVA_HOME")?.takeIf { it.isNotBlank() }?.let { normalizeJdkHome(File(it)) }
        if (normalizedExplicit != null) {
            return GradleExecutionConfig(normalizedExplicit, extraGradleArgs)
        }

        val argJavaHome = extraGradleArgs
            .firstOrNull { it.startsWith("-Dorg.gradle.java.home=") }
            ?.removePrefix("-Dorg.gradle.java.home=")
            ?.let { normalizeJdkHome(File(it)) }
        if (argJavaHome != null) {
            return GradleExecutionConfig(argJavaHome, extraGradleArgs)
        }

        val propsJavaHome = resolveJavaHomeFromGradleProperties(projectRoot)
        if (propsJavaHome != null) {
            return GradleExecutionConfig(propsJavaHome, extraGradleArgs)
        }

        val requestedMajor = detectPinnedJavaMajor(projectRoot)
        val wrapperMaxMajor = detectMaxSupportedJavaForWrapper(projectRoot)
        val hostMajor = detectHostDefaultJavaMajor()

        val targetMajor = when {
            requestedMajor != null && (wrapperMaxMajor == null || requestedMajor <= wrapperMaxMajor) -> requestedMajor
            wrapperMaxMajor != null && hostMajor > wrapperMaxMajor -> minOf(wrapperMaxMajor, 17).coerceAtLeast(11)
            else -> null
        }

        if (targetMajor != null && targetMajor != hostMajor) {
            val discovered = findInstalledJdkForMajor(targetMajor)
            if (discovered != null) {
                return GradleExecutionConfig(discovered, extraGradleArgs)
            }
        }

        return GradleExecutionConfig(null, extraGradleArgs)
    }

    fun findFallbackJavaHomeForError(rawError: String, currentJavaHome: File?): File? {
        val isJvmError = classFileMajorErrorRegex.containsMatchIn(rawError) ||
            requiresJavaRegex.containsMatchIn(rawError) ||
            rawError.contains("JvmVendorSpec", ignoreCase = true) ||
            rawError.contains("Cannot find a Java installation", ignoreCase = true) ||
            rawError.contains("Unsupported Java", ignoreCase = true)
        if (!isJvmError) return null

        val explicitReq = requiresJavaRegex.find(rawError)?.groupValues?.get(1)?.toIntOrNull()
        val candidateMajors = buildList {
            if (explicitReq != null) add(explicitReq)
            add(17)
            add(21)
            add(11)
        }.distinct()

        val currentCanonical = runCatching { currentJavaHome?.canonicalFile }.getOrNull()
        for (major in candidateMajors) {
            val found = findInstalledJdkForMajor(major)
            if (found != null && found != currentCanonical) {
                return found
            }
        }
        return null
    }

    private fun resolveJavaHomeFromGradleProperties(projectRoot: File): File? {
        val propFiles = listOfNotNull(
            File(projectRoot, "gradle.properties").takeIf { it.isFile },
            System.getenv("GRADLE_USER_HOME")?.takeIf { it.isNotBlank() }?.let { File(it, "gradle.properties") }
                ?.takeIf { it.isFile },
            File(System.getProperty("user.home"), ".gradle/gradle.properties").takeIf { it.isFile }
        )
        for (file in propFiles) {
            val configured = runCatching {
                Properties().apply { file.inputStream().use { load(it) } }
                    .getProperty("org.gradle.java.home")
                    ?.trim()
            }.getOrNull()
            if (!configured.isNullOrEmpty()) {
                val resolved = if (File(configured).isAbsolute) {
                    File(configured)
                } else {
                    File(file.parentFile, configured)
                }
                normalizeJdkHome(resolved)?.let { return it }
            }
        }
        return null
    }

    private fun detectPinnedJavaMajor(projectRoot: File): Int? {
        val javaVersionFile = File(projectRoot, ".java-version")
        if (javaVersionFile.isFile) {
            parseJavaMajorToken(runCatching { javaVersionFile.readText().trim() }.getOrDefault(""))
                ?.let { return it }
        }
        val sdkmanFile = File(projectRoot, ".sdkmanrc")
        if (sdkmanFile.isFile) {
            val token = sdkmanJavaRegex.find(runCatching { sdkmanFile.readText() }.getOrDefault(""))
                ?.groupValues?.get(1).orEmpty()
            parseJavaMajorToken(token)?.let { return it }
        }
        val toolVersionsFile = File(projectRoot, ".tool-versions")
        if (toolVersionsFile.isFile) {
            val token = toolVersionsJavaRegex.find(runCatching { toolVersionsFile.readText() }.getOrDefault(""))
                ?.groupValues?.get(1).orEmpty()
            parseJavaMajorToken(token)?.let { return it }
        }
        val rootScripts = listOfNotNull(
            File(projectRoot, "build.gradle.kts").takeIf { it.isFile },
            File(projectRoot, "build.gradle").takeIf { it.isFile },
            File(projectRoot, "app/build.gradle.kts").takeIf { it.isFile },
            File(projectRoot, "app/build.gradle").takeIf { it.isFile }
        )
        for (script in rootScripts) {
            val text = runCatching { script.readText() }.getOrDefault("")
            val tc = jvmToolchainRegex.find(text)?.groupValues?.get(1)?.toIntOrNull()
            if (tc != null) return tc
        }
        return null
    }

    internal fun detectMaxSupportedJavaForWrapper(projectRoot: File): Int? {
        val wrapperProps = File(projectRoot, "gradle/wrapper/gradle-wrapper.properties")
        if (!wrapperProps.isFile) return null
        val url = runCatching {
            Properties().apply { wrapperProps.inputStream().use { load(it) } }
                .getProperty("distributionUrl")
        }.getOrNull().orEmpty()
        val match = distVersionRegex.find(url) ?: return null
        val major = match.groupValues[1].toIntOrNull() ?: return null
        val minor = match.groupValues[2].toIntOrNull() ?: 0

        return when {
            major < 7 -> 11
            major == 7 && minor < 3 -> 16
            major == 7 -> 19
            major == 8 && minor < 5 -> 20 // Gradle 8.0 - 8.4 requires <= Java 20 (Java 17 LTS)
            major == 8 && minor < 10 -> 22 // Gradle 8.5 - 8.9 requires <= Java 22 (Java 21 LTS)
            major == 8 && minor < 14 -> 23
            else -> null
        }
    }

    private fun detectHostDefaultJavaMajor(): Int {
        val envJavaHome = System.getenv("JAVA_HOME")?.takeIf { it.isNotBlank() }?.let(::File)
        if (envJavaHome != null) {
            readJdkMajorFromReleaseFile(envJavaHome)?.let { return it }
        }
        val runtimeSpec = System.getProperty("java.specification.version").orEmpty()
        return parseJavaMajorToken(runtimeSpec) ?: 21
    }

    internal fun findInstalledJdkForMajor(targetMajor: Int): File? {
        val candidates = discoverInstalledJdkDirs()
        for (dir in candidates) {
            val home = normalizeJdkHome(dir) ?: continue
            val major = readJdkMajorFromReleaseFile(home)
                ?: parseJavaMajorToken(dir.name)
                ?: parseJavaMajorToken(home.parentFile?.parentFile?.name.orEmpty())
            if (major == targetMajor) {
                return home
            }
        }
        return null
    }

    private fun discoverInstalledJdkDirs(): List<File> {
        val userHome = File(System.getProperty("user.home"))
        val searchParents = listOf(
            File("/Library/Java/JavaVirtualMachines"),
            File(userHome, "Library/Java/JavaVirtualMachines"),
            File(userHome, ".sdkman/candidates/java"),
            File(userHome, ".asdf/installs/java"),
            File(userHome, ".local/share/mise/installs/java"),
            File(userHome, ".jenv/versions"),
            File(userHome, ".jdks"),
            File("/usr/lib/jvm"),
            File("/usr/java")
        )
        val dirs = mutableListOf<File>()
        for (parent in searchParents) {
            if (!parent.isDirectory) continue
            parent.listFiles()
                ?.filter { it.isDirectory && it.name != "current" && it.name != "default" }
                ?.sortedByDescending { it.name }
                ?.let(dirs::addAll)
        }
        val studioJbrs = listOf(
            File("/Applications/Android Studio.app/Contents/jbr"),
            File("/Applications/Android Studio Preview.app/Contents/jbr"),
            File(userHome, "Applications/Android Studio.app/Contents/jbr")
        )
        dirs.addAll(studioJbrs.filter { it.isDirectory })
        return dirs
    }

    private fun readJdkMajorFromReleaseFile(jdkHome: File): Int? {
        val releaseFile = File(jdkHome, "release")
        if (!releaseFile.isFile) return null
        val text = runCatching { releaseFile.readText() }.getOrDefault("")
        val ver = Regex("""JAVA_VERSION="([^"]+)"""").find(text)?.groupValues?.get(1) ?: return null
        return parseJavaMajorToken(ver)
    }

    internal fun parseJavaMajorToken(token: String): Int? {
        if (token.isBlank()) return null
        if (token.startsWith("1.8")) return 8
        val direct = token.substringBefore('.').substringBefore('-').substringBefore('+').toIntOrNull()
        if (direct != null && direct >= 8) return direct
        val match = majorVersionRegex.find(token)?.groupValues?.get(1) ?: return null
        return if (match == "1.8") 8 else match.toIntOrNull()
    }

    private fun hasJavaExecutable(homeDir: File): Boolean {
        if (!homeDir.isDirectory) return false
        val isWin = System.getProperty("os.name").lowercase(Locale.US).contains("win")
        val javaBin = File(homeDir, if (isWin) "bin/java.exe" else "bin/java")
        return javaBin.isFile
    }
}
