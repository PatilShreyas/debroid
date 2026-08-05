package dev.shreyaspatil.debroid.cli.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Manages cache file persistence (~/.debroid/update-cache.json) for throttling update checks.
 */
class UpdateCache(private val cacheFile: File = defaultCacheFile()) {

    private val json = Json { ignoreUnknownKeys = true }

    fun shouldCheckForUpdate(throttleMs: Long = TWENTY_FOUR_HOURS_MS): Boolean {
        val lastCheck = readLastCheckTimestamp()
        return (System.currentTimeMillis() - lastCheck) >= throttleMs
    }

    fun recordCheckTimestamp(now: Long = System.currentTimeMillis()) {
        try {
            val parent = cacheFile.parentFile
            if (parent != null && !parent.exists()) parent.mkdirs()
            val content = """{"lastCheckTimestamp":$now}"""
            cacheFile.writeText(content)
        } catch (_: Throwable) {
            // Ignore write failures
        }
    }

    private fun readLastCheckTimestamp(): Long {
        return try {
            if (!cacheFile.exists()) return 0L
            val text = cacheFile.readText()
            val element = json.parseToJsonElement(text).jsonObject
            element["lastCheckTimestamp"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
        } catch (_: Throwable) {
            0L
        }
    }

    companion object {
        const val TWENTY_FOUR_HOURS_MS = 86_400_000L

        private fun defaultCacheFile(): File {
            val debroidDir = File(System.getProperty("user.home"), ".debroid")
            return File(debroidDir, "update-cache.json")
        }
    }
}
