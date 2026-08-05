package dev.shreyaspatil.debroid.cli.update

import dev.shreyaspatil.debroid.cli.VERSION
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
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
}
