package dev.shreyaspatil.debroid.cli.update

import dev.shreyaspatil.debroid.cli.VERSION
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class AutoUpdateManagerTest {

    @Test
    fun `checkOrUpdate returns valid result in check-only mode`() {
        val manager = AutoUpdateManager.DEFAULT
        val result = manager.checkOrUpdate(checkOnly = true)

        assertNotNull(result)
        assertEquals(VERSION, result.currentVersion)
        assertNotNull(result.latestVersion)
        assertNotNull(result.message)
        assertFalse(result.updated)
    }

    @Test
    fun `checkAndPerformSilentAutoUpdateAsync creates 24h cache file without throwing`() {
        val manager = AutoUpdateManager.DEFAULT
        manager.checkAndPerformSilentAutoUpdateAsync()

        val debroidDir = File(System.getProperty("user.home"), ".debroid")
        val cacheFile = File(debroidDir, "update-cache.json")
        assertTrue(cacheFile.exists())
    }

    @Test
    fun `checkAndPerformSilentAutoUpdateAsync handles invalid cache path gracefully`() {
        val invalidCache = UpdateCache(File("/non_existent_path_permissions_denied/cache.json"))
        val manager = AutoUpdateManager(updateCache = invalidCache)

        // Must execute cleanly without throwing any exception
        manager.checkAndPerformSilentAutoUpdateAsync()
    }

    @Test
    fun `syncSkillsIfUpdated returns false and updates cache when lastRunVersion is null`(@TempDir tempDir: File) {
        val cacheFile = File(tempDir, "update-cache.json")
        val cache = UpdateCache(cacheFile)
        val mockExtractor = mockk<SkillExtractor>(relaxed = true)
        val manager = AutoUpdateManager(updateCache = cache, skillExtractor = mockExtractor)

        // lastRunVersion is initially null
        assertFalse(manager.syncSkillsIfUpdated())
        assertEquals(VERSION, cache.readLastRunVersion())
        verify(exactly = 1) { mockExtractor.extractSkillsToMaster() }
    }

    @Test
    fun `syncSkillsIfUpdated returns true and updates cache when lastRunVersion differs`(@TempDir tempDir: File) {
        val cacheFile = File(tempDir, "update-cache.json")
        val cache = UpdateCache(cacheFile)
        cache.recordLastRunVersion("0.0.0-old")
        val mockExtractor = mockk<SkillExtractor>(relaxed = true)
        val manager = AutoUpdateManager(updateCache = cache, skillExtractor = mockExtractor)

        // lastRunVersion differs from VERSION
        assertTrue(manager.syncSkillsIfUpdated())
        assertEquals(VERSION, cache.readLastRunVersion())
        verify(exactly = 1) { mockExtractor.extractSkillsToMaster() }
    }

    @Test
    fun `syncSkillsIfUpdated returns false when lastRunVersion matches`(@TempDir tempDir: File) {
        val cacheFile = File(tempDir, "update-cache.json")
        val cache = UpdateCache(cacheFile)
        cache.recordLastRunVersion(VERSION)
        val mockExtractor = mockk<SkillExtractor>(relaxed = true)
        val manager = AutoUpdateManager(updateCache = cache, skillExtractor = mockExtractor)

        // lastRunVersion matches VERSION
        assertFalse(manager.syncSkillsIfUpdated())
        verify(exactly = 0) { mockExtractor.extractSkillsToMaster() }
    }
}
