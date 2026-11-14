package dev.shreyaspatil.debroid.hotreload

import java.io.File
import java.util.Properties
import java.util.zip.ZipFile

internal object CoreLibraryDesugarResolver {
    data class DesugarD8Config(
        val minApi: Int,
        val androidJar: File,
        val desugarConfigJson: File
    )

    private const val DEFAULT_DESUGAR_MIN_API = 24
    private const val DESUGAR_JSON_ENTRY = "META-INF/desugar/d8/desugar.json"
    private const val ARTIFACT_NIO = "desugar_jdk_libs_configuration_nio"
    private const val ARTIFACT_STD = "desugar_jdk_libs_configuration"
    private const val ARTIFACT_MINIMAL = "desugar_jdk_libs_configuration_minimal"
    private val minSdkManifestRegex = Regex("""android:minSdkVersion="(\d+)"""")
    private val minSdkScriptRegex = Regex("""\bminSdk(?:Version)?\s*[=(]\s*"?(\d+)"?""")
    private val desugarEnabledRegex = Regex(
        """\b(?:isCoreLibraryDesugaringEnabled|coreLibraryDesugaringEnabled)\s*[=(]?\s*true\b"""
    )
    private val desugarVersionRegex = Regex(
        """desugar[_-]jdk[_-]libs(?:[_-](?:nio|minimal))?["':\s=]+(\d+\.\d+\.\d+(?:-[A-Za-z0-9.]+)?)""",
        RegexOption.IGNORE_CASE
    )
    private val nioMarkerBytes = "Lj\$/nio/channels/".toByteArray(Charsets.US_ASCII)
    private val timeMarkerBytes = "Lj\$/time/".toByteArray(Charsets.US_ASCII)

    fun resolve(
        projectRoot: File,
        modules: Collection<GradleModule>,
        resolveVariant: (GradleModule) -> String,
        gradleUserHome: File = System.getenv("GRADLE_USER_HOME")?.takeIf { it.isNotBlank() }?.let(::File)
            ?: File(System.getProperty("user.home"), ".gradle"),
        sdkRootOverride: File? = null
    ): DesugarD8Config? {
        val appModules = modules.filter { it.type == ModuleType.ANDROID_APP }
        val desugarDexDirs = appModules.mapNotNull { app ->
            val appVariant = resolveVariant(app)
            File(app.moduleDir, "build/intermediates/desugar_lib_dex/$appVariant").takeIf { it.isDirectory }
        }
        val enabledInScripts = desugarDexDirs.isNotEmpty() || isEnabledInBuildScripts(projectRoot)
        if (!enabledInScripts) return null

        val androidJar = resolveAndroidJar(projectRoot, sdkRootOverride) ?: return null
        val minApi = resolveMinSdk(projectRoot, appModules, resolveVariant)
        val (preferredArtifact, versionHint) = detectArtifactAndVersion(projectRoot, desugarDexDirs)
        val configJar = findDesugarConfigJar(gradleUserHome, preferredArtifact, versionHint) ?: return null
        val extractedJson = extractDesugarJson(projectRoot, preferredArtifact, configJar) ?: return null

        return DesugarD8Config(
            minApi = minApi,
            androidJar = androidJar,
            desugarConfigJson = extractedJson
        )
    }

    private fun isEnabledInBuildScripts(projectRoot: File): Boolean {
        val candidates = buildList {
            projectRoot.listFiles()
                ?.filter { it.isFile && it.name.startsWith("build.gradle") }
                ?.let(::addAll)
            val buildLogicDir = File(projectRoot, "build-logic")
            if (buildLogicDir.isDirectory) {
                addAll(
                    buildLogicDir.walkTopDown()
                        .filter { it.isFile && (it.extension == "kt" || it.extension == "kts") }
                        .toList()
                )
            }
            val appBuild = File(projectRoot, "app/build.gradle.kts").takeIf { it.isFile }
                ?: File(projectRoot, "app/build.gradle").takeIf { it.isFile }
            if (appBuild != null) add(appBuild)
        }
        return candidates.any { file ->
            runCatching { desugarEnabledRegex.containsMatchIn(file.readText()) }.getOrDefault(false)
        }
    }

    internal fun resolveAndroidJar(projectRoot: File, sdkRootOverride: File? = null): File? {
        val sdkRoot = sdkRootOverride
            ?: resolveSdkRootFromLocalProperties(projectRoot)
            ?: System.getenv("ANDROID_HOME")?.let(::File)?.takeIf { it.isDirectory }
            ?: System.getenv("ANDROID_SDK_ROOT")?.let(::File)?.takeIf { it.isDirectory }
            ?: File(System.getProperty("user.home"), "Library/Android/sdk").takeIf { it.isDirectory }
            ?: File(System.getProperty("user.home"), "Android/Sdk").takeIf { it.isDirectory }
            ?: return null

        val platformsDir = File(sdkRoot, "platforms")
        if (!platformsDir.isDirectory) return null

        return platformsDir.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("android-") }
            ?.sortedWith(compareByDescending { it.name.removePrefix("android-").toDoubleOrNull() ?: 0.0 })
            ?.firstNotNullOfOrNull { dir -> File(dir, "android.jar").takeIf { it.isFile } }
    }

    private fun resolveSdkRootFromLocalProperties(projectRoot: File): File? {
        val localProps = File(projectRoot, "local.properties")
        if (!localProps.isFile) return null
        return runCatching {
            val props = Properties()
            localProps.inputStream().use { props.load(it) }
            props.getProperty("sdk.dir")?.let(::File)?.takeIf { it.isDirectory }
        }.getOrNull()
    }

    private fun resolveMinSdk(
        projectRoot: File,
        appModules: List<GradleModule>,
        resolveVariant: (GradleModule) -> String
    ): Int {
        for (app in appModules) {
            val variant = resolveVariant(app)
            val manifestDirs = listOf(
                File(app.moduleDir, "build/intermediates/merged_manifest/$variant"),
                File(app.moduleDir, "build/intermediates/merged_manifests/$variant"),
                File(app.moduleDir, "build/intermediates/packaged_manifests/$variant")
            )
            for (dir in manifestDirs) {
                if (!dir.isDirectory) continue
                val found = dir.walkTopDown()
                    .filter { it.isFile && it.name == "AndroidManifest.xml" }
                    .firstNotNullOfOrNull { manifest ->
                        runCatching {
                            minSdkManifestRegex.find(manifest.readText())?.groupValues?.get(1)?.toIntOrNull()
                        }.getOrNull()
                    }
                if (found != null) return found
            }
        }

        val scriptFiles = listOfNotNull(
            File(projectRoot, "gradle/libs.versions.toml").takeIf { it.isFile },
            File(projectRoot, "app/build.gradle.kts").takeIf { it.isFile },
            File(projectRoot, "app/build.gradle").takeIf { it.isFile }
        )
        for (script in scriptFiles) {
            val found = runCatching {
                minSdkScriptRegex.find(script.readText())?.groupValues?.get(1)?.toIntOrNull()
            }.getOrNull()
            if (found != null) return found
        }
        return DEFAULT_DESUGAR_MIN_API
    }

    private fun detectArtifactAndVersion(
        projectRoot: File,
        desugarDexDirs: List<File>
    ): Pair<String, String?> {
        var artifactFromDex: String? = null
        for (dir in desugarDexDirs) {
            val dexFile = dir.walkTopDown().firstOrNull { it.isFile && it.extension == "dex" } ?: continue
            val bytes = runCatching { dexFile.readBytes() }.getOrNull() ?: continue
            artifactFromDex = when {
                containsBytes(bytes, nioMarkerBytes) -> ARTIFACT_NIO
                containsBytes(bytes, timeMarkerBytes) -> ARTIFACT_STD
                else -> ARTIFACT_MINIMAL
            }
            break
        }

        val catalogTexts = listOfNotNull(
            File(projectRoot, "gradle/libs.versions.toml").takeIf { it.isFile },
            File(projectRoot, "app/build.gradle.kts").takeIf { it.isFile },
            File(projectRoot, "app/build.gradle").takeIf { it.isFile }
        ).mapNotNull { runCatching { it.readText() }.getOrNull() }

        val combinedText = catalogTexts.joinToString("\n")
        val artifactFromText = when {
            combinedText.contains("desugar_jdk_libs_nio") -> ARTIFACT_NIO
            combinedText.contains("desugar_jdk_libs_minimal") -> ARTIFACT_MINIMAL
            combinedText.contains("desugar_jdk_libs") -> ARTIFACT_STD
            else -> null
        }
        val versionHint = desugarVersionRegex.find(combinedText)?.groupValues?.get(1)
            ?: extractVersionCatalogDesugarVersion(combinedText)

        return (artifactFromDex ?: artifactFromText ?: ARTIFACT_STD) to versionHint
    }

    private fun extractVersionCatalogDesugarVersion(tomlText: String): String? {
        val refMatch = Regex("""desugar_jdk_libs[^\n]*version\.ref\s*=\s*"([^"]+)"""").find(tomlText)
            ?: return null
        val refKey = Regex.escape(refMatch.groupValues[1])
        return Regex("""^\s*$refKey\s*=\s*"([^"]+)"""", RegexOption.MULTILINE)
            .find(tomlText)
            ?.groupValues
            ?.get(1)
    }

    private fun findDesugarConfigJar(
        gradleUserHome: File,
        preferredArtifact: String,
        versionHint: String?
    ): File? {
        val toolsGroupDir = File(gradleUserHome, "caches/modules-2/files-2.1/com.android.tools")
        if (!toolsGroupDir.isDirectory) return null

        val searchOrder = listOf(preferredArtifact, ARTIFACT_STD, ARTIFACT_NIO, ARTIFACT_MINIMAL).distinct()
        for (artifact in searchOrder) {
            val artifactDir = File(toolsGroupDir, artifact)
            if (!artifactDir.isDirectory) continue
            val versionDirs = artifactDir.listFiles()?.filter { it.isDirectory }.orEmpty()
            if (versionDirs.isEmpty()) continue

            val orderedVersionDirs = buildList {
                if (versionHint != null) {
                    versionDirs.firstOrNull { it.name == versionHint }?.let(::add)
                }
                addAll(
                    versionDirs
                        .filter { it.name != versionHint }
                        .sortedWith(
                            compareByDescending { dir ->
                                dir.name.split('.', '-')
                                    .joinToString(".") { p -> p.toIntOrNull()?.let { "%06d".format(it) } ?: p }
                            }
                        )
                )
            }

            for (verDir in orderedVersionDirs) {
                val jar = verDir.walkTopDown()
                    .firstOrNull { file ->
                        file.isFile &&
                            file.extension == "jar" &&
                            !file.name.endsWith("-sources.jar") &&
                            !file.name.endsWith("-javadoc.jar") &&
                            runCatching {
                                ZipFile(file).use { zip -> zip.getEntry(DESUGAR_JSON_ENTRY) != null }
                            }.getOrDefault(false)
                    }
                if (jar != null) return jar
            }
        }
        return null
    }

    private fun extractDesugarJson(projectRoot: File, artifact: String, jarFile: File): File? = runCatching {
        val outDir = File(projectRoot, "build/tmp/debroid-hotreload")
        outDir.mkdirs()
        val outFile = File(outDir, "desugar-$artifact-${jarFile.nameWithoutExtension}.json")
        if (outFile.isFile && outFile.length() > 0L) {
            return outFile
        }
        ZipFile(jarFile).use { zip ->
            val entry = zip.getEntry(DESUGAR_JSON_ENTRY) ?: return null
            zip.getInputStream(entry).use { input ->
                outFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
        outFile
    }.getOrNull()

    private fun containsBytes(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || haystack.size < needle.size) return false
        val limit = haystack.size - needle.size
        for (i in 0..limit) {
            if (haystack[i] == needle[0] && needle.indices.all { j -> haystack[i + j] == needle[j] }) {
                return true
            }
        }
        return false
    }
}
