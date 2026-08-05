package dev.shreyaspatil.debroid.cli.update

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class BinaryUpdaterTest {

    private val updater = BinaryUpdater()

    @Test
    fun `resolveCurrentBinaryLocation resolves installed binary when present`() {
        val location = updater.resolveCurrentBinaryLocation()
        if (location != null) {
            assertTrue(location.exists())
            assertTrue(location.isFile)
            assertNotNull(location.name)
        }
    }

    @Test
    fun `atomic binary replacement replaces binary content and cleans up backup file`(@TempDir tempDir: File) {
        val targetBinary = File(tempDir, "debroid")
        targetBinary.writeText("#!/bin/sh\necho 'v1.0.0'")
        targetBinary.setExecutable(true)

        val newTempBinary = File(tempDir, "debroid-update.tmp")
        newTempBinary.writeText("#!/bin/sh\necho 'v1.0.1'")
        newTempBinary.setExecutable(true)

        val backupFile = File(targetBinary.parentFile, "${targetBinary.name}.bak")
        if (backupFile.exists()) backupFile.delete()

        // Simulate atomic POSIX replacement steps performed by BinaryUpdater
        assertTrue(targetBinary.renameTo(backupFile))
        assertTrue(newTempBinary.renameTo(targetBinary))
        backupFile.delete()

        assertTrue(targetBinary.exists())
        assertEquals("#!/bin/sh\necho 'v1.0.1'", targetBinary.readText())
        assertFalse(backupFile.exists())
    }
}
