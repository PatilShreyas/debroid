package dev.shreyaspatil.debroid.cli.update

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SemanticVersionTest {

    @Test
    fun `parse valid stable version strings with various formats`() {
        val v1 = SemanticVersion.parse("v1.2.3")
        assertNotNull(v1)
        assertEquals(1, v1?.major)
        assertEquals(2, v1?.minor)
        assertEquals(3, v1?.patch)
        assertTrue(v1?.isStable == true)

        val v2 = SemanticVersion.parse("0.0.1")
        assertNotNull(v2)
        assertEquals(0, v2?.major)
        assertEquals(0, v2?.minor)
        assertEquals(1, v2?.patch)

        val v3 = SemanticVersion.parse("   v20.12.34   ")
        assertNotNull(v3)
        assertEquals(20, v3?.major)
        assertEquals(12, v3?.minor)
        assertEquals(34, v3?.patch)
    }

    @Test
    fun `parse rejects prerelease, candidate, alpha, beta, and malformed version strings`() {
        assertNull(SemanticVersion.parse("v1.0.0-rc01"))
        assertNull(SemanticVersion.parse("v1.0.0-rc.1"))
        assertNull(SemanticVersion.parse("v1.0.0-alpha1"))
        assertNull(SemanticVersion.parse("v1.0.0-beta.2"))
        assertNull(SemanticVersion.parse("v1.0.0-SNAPSHOT"))
        assertNull(SemanticVersion.parse("v1.0.0-dev"))
        assertNull(SemanticVersion.parse("v1.0.0-build123"))

        assertNull(SemanticVersion.parse("v1.0"))
        assertNull(SemanticVersion.parse("1"))
        assertNull(SemanticVersion.parse("1.2.3.4"))
        assertNull(SemanticVersion.parse("v1.a.2"))
        assertNull(SemanticVersion.parse(""))
        assertNull(SemanticVersion.parse("   "))
        assertNull(SemanticVersion.parse("debroid-v1.0.0"))
    }

    @Test
    fun `isNewerThan compares major, minor, and patch levels correctly`() {
        val v001 = SemanticVersion(0, 0, 1)
        val v002 = SemanticVersion(0, 0, 2)
        val v010 = SemanticVersion(0, 1, 0)
        val v100 = SemanticVersion(1, 0, 0)

        // Patch upgrade
        assertTrue(v002.isNewerThan(v001))
        assertFalse(v001.isNewerThan(v002))

        // Minor upgrade
        assertTrue(v010.isNewerThan(v002))
        assertFalse(v002.isNewerThan(v010))

        // Major upgrade
        assertTrue(v100.isNewerThan(v010))
        assertFalse(v010.isNewerThan(v100))

        // Equal version comparison
        assertFalse(v100.isNewerThan(v100))
    }

    @Test
    fun `isNewerThan handles multi-digit version numbers`() {
        val v190 = SemanticVersion(1, 9, 0)
        val v1100 = SemanticVersion(1, 10, 0)
        val v009 = SemanticVersion(0, 0, 9)
        val v0010 = SemanticVersion(0, 0, 10)

        assertTrue(v1100.isNewerThan(v190))
        assertFalse(v190.isNewerThan(v1100))

        assertTrue(v0010.isNewerThan(v009))
        assertFalse(v009.isNewerThan(v0010))
    }

    @Test
    fun `compareTo orders versions correctly`() {
        val v1 = SemanticVersion(1, 0, 0)
        val v2 = SemanticVersion(1, 0, 1)
        val v3 = SemanticVersion(2, 0, 0)

        assertTrue(v1 < v2)
        assertTrue(v2 < v3)
        assertEquals(0, v1.compareTo(SemanticVersion(1, 0, 0)))
    }
}
