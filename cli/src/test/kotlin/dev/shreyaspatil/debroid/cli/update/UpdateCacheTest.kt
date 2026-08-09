package dev.shreyaspatil.debroid.cli.update

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class UpdateCacheTest {

    @Test
    fun `shouldCheckForUpdate returns true when cache file does not exist`(@TempDir tempDir: File) {
        val cacheFile = File(tempDir, "update-cache.json")
        val cache = UpdateCache(cacheFile)

        assertTrue(cache.shouldCheckForUpdate())
    }

    @Test
    fun `shouldCheckForUpdate returns false when check was recorded recently`(@TempDir tempDir: File) {
        val cacheFile = File(tempDir, "update-cache.json")
        val cache = UpdateCache(cacheFile)

        cache.recordCheckTimestamp(System.currentTimeMillis())

        assertFalse(cache.shouldCheckForUpdate(throttleMs = 86_400_000L))
    }

    @Test
    fun `shouldCheckForUpdate returns true when throttle duration has expired`(@TempDir tempDir: File) {
        val cacheFile = File(tempDir, "update-cache.json")
        val cache = UpdateCache(cacheFile)

        val oldTimestamp = System.currentTimeMillis() - 100_000_000L
        cache.recordCheckTimestamp(oldTimestamp)

        assertTrue(cache.shouldCheckForUpdate(throttleMs = 86_400_000L))
    }

    @Test
    fun `shouldCheckForUpdate returns true and handles corrupted JSON file gracefully`(@TempDir tempDir: File) {
        val cacheFile = File(tempDir, "update-cache.json")
        cacheFile.writeText("invalid json content {{{")

        val cache = UpdateCache(cacheFile)

        assertTrue(cache.shouldCheckForUpdate())
    }

    @Test
    fun `recordCheckTimestamp creates parent directory if it does not exist`(@TempDir tempDir: File) {
        val nestedDir = File(tempDir, "nested/cache/dir")
        val cacheFile = File(nestedDir, "update-cache.json")
        val cache = UpdateCache(cacheFile)

        cache.recordCheckTimestamp()

        assertTrue(cacheFile.exists())
        assertTrue(cacheFile.length() > 0)
    }

    @Test
    fun `readLastRunVersion returns null when cache file does not exist`(@TempDir tempDir: File) {
        val cacheFile = File(tempDir, "update-cache.json")
        val cache = UpdateCache(cacheFile)

        assertNull(cache.readLastRunVersion())
    }

    @Test
    fun `recordLastRunVersion writes version correctly`(@TempDir tempDir: File) {
        val cacheFile = File(tempDir, "update-cache.json")
        val cache = UpdateCache(cacheFile)

        cache.recordLastRunVersion("1.0.0")
        assertEquals("1.0.0", cache.readLastRunVersion())
    }

    @Test
    fun `recordLastRunVersion preserves existing timestamp`(@TempDir tempDir: File) {
        val cacheFile = File(tempDir, "update-cache.json")
        val cache = UpdateCache(cacheFile)

        val timestamp = 123456789L
        cache.recordCheckTimestamp(timestamp)
        cache.recordLastRunVersion("2.0.0")

        assertEquals("2.0.0", cache.readLastRunVersion())
        val content = cacheFile.readText()
        assertTrue(content.contains(""""lastCheckTimestamp":123456789"""))
    }

    @Test
    fun `recordCheckTimestamp preserves existing version`(@TempDir tempDir: File) {
        val cacheFile = File(tempDir, "update-cache.json")
        val cache = UpdateCache(cacheFile)

        cache.recordLastRunVersion("3.0.0")
        cache.recordCheckTimestamp(987654321L)

        assertEquals("3.0.0", cache.readLastRunVersion())
        val content = cacheFile.readText()
        assertTrue(content.contains(""""lastCheckTimestamp":987654321"""))
    }

    @Test
    fun `readLastRunVersion returns null and handles corrupted JSON file gracefully`(@TempDir tempDir: File) {
        val cacheFile = File(tempDir, "update-cache.json")
        cacheFile.writeText("invalid json content {{{")

        val cache = UpdateCache(cacheFile)

        assertNull(cache.readLastRunVersion())
    }
}
