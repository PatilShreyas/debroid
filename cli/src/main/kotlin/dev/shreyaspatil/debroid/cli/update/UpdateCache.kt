package dev.shreyaspatil.debroid.cli.update

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Represents the parsed JSON from the update cache file.
 * NOTE: For backward compatibility, any new fields added here MUST have
 * nullable types and default values (e.g. `val newField: String? = null`).
 */
@Serializable
data class CacheData(
    val lastCheckTimestamp: Long = 0L,
    val lastRunVersion: String? = null
)

/**
 * Manages cache file persistence (~/.debroid/update-cache.json) for throttling update checks.
 */
class UpdateCache(private val cacheFile: File = defaultCacheFile()) {

    private val json = Json { ignoreUnknownKeys = true }

    fun shouldCheckForUpdate(throttleMs: Long = TWENTY_FOUR_HOURS_MS): Boolean {
        val lastCheck = readCacheData().lastCheckTimestamp
        return (System.currentTimeMillis() - lastCheck) >= throttleMs
    }

    fun recordCheckTimestamp(now: Long = System.currentTimeMillis()) {
        val currentData = readCacheData()
        writeCacheData(currentData.copy(lastCheckTimestamp = now))
    }

    fun readLastRunVersion(): String? {
        return readCacheData().lastRunVersion
    }

    fun recordLastRunVersion(version: String) {
        val currentData = readCacheData()
        writeCacheData(currentData.copy(lastRunVersion = version))
    }

    private fun readCacheData(): CacheData {
        return try {
            if (!cacheFile.exists()) return CacheData()
            val text = cacheFile.readText()
            json.decodeFromString<CacheData>(text)
        } catch (_: Throwable) {
            CacheData()
        }
    }

    private fun writeCacheData(data: CacheData) {
        try {
            val parent = cacheFile.parentFile
            if (parent != null && !parent.exists()) parent.mkdirs()
            cacheFile.writeText(json.encodeToString(data))
        } catch (_: Throwable) {
            // Ignore write failures
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
