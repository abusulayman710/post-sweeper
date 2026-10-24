package app.sweeper

import app.sweeper.net.UpdateChecker
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun testIsNewerMajorMinorPatch() {
        assertTrue(UpdateChecker.isNewer("3.1", "3.0"))
        assertTrue(UpdateChecker.isNewer("3.0.1", "3.0"))
        assertTrue(UpdateChecker.isNewer("4.0", "3.9.9"))
        assertTrue(UpdateChecker.isNewer("3.1.0", "3.0.9"))
        assertTrue(UpdateChecker.isNewer("10.0", "9.5"))

        assertFalse(UpdateChecker.isNewer("3.0", "3.0"))
        assertFalse(UpdateChecker.isNewer("2.9", "3.0"))
        assertFalse(UpdateChecker.isNewer("3.0", "3.0.1"))
        assertFalse(UpdateChecker.isNewer("3.0", "3.1"))
        assertFalse(UpdateChecker.isNewer("", "3.0"))
        assertFalse(UpdateChecker.isNewer("3.0", ""))
    }

    @Test
    fun testIsNewerWithVPrefixAndSuffix() {
        assertTrue(UpdateChecker.isNewer("v3.1", "v3.0"))
        assertTrue(UpdateChecker.isNewer("3.1-beta", "3.0"))
        assertFalse(UpdateChecker.isNewer("3.0-rc1", "3.0"))
    }
}
