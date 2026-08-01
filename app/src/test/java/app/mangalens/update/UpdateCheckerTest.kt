package app.mangalens.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The comparison the update banner hangs on. The expensive mistake is a
 * false positive — a banner nagging about an "update" that is the installed
 * version, or one conjured from a malformed tag — so ambiguity always
 * resolves to "no update".
 */
class UpdateCheckerTest {

    @Test
    fun `numeric strips tag prefixes and build suffixes`() {
        assertEquals("0.9.1", UpdateChecker.numeric("v0.9.1"))
        assertEquals("0.9.1", UpdateChecker.numeric("mangalens-v0.9.1"))
        assertEquals("0.9.1", UpdateChecker.numeric("0.9.1-debug"))
        assertEquals("", UpdateChecker.numeric("nightly"))
    }

    @Test
    fun `newer versions are recognized across segment rollovers`() {
        assertTrue(UpdateChecker.isNewer("0.10.0", "0.9.1"))
        assertTrue(UpdateChecker.isNewer("1.0.0", "0.99.99"))
        assertTrue(UpdateChecker.isNewer("0.9.2", "0.9.1"))
        assertTrue(UpdateChecker.isNewer("0.9.1.1", "0.9.1"))
    }

    @Test
    fun `equal and older versions never announce an update`() {
        assertFalse(UpdateChecker.isNewer("0.9.1", "0.9.1"))
        assertFalse(UpdateChecker.isNewer("0.9.1", "0.9.1.0"))
        assertFalse(UpdateChecker.isNewer("0.9.0", "0.9.1"))
        assertFalse(UpdateChecker.isNewer("0.9.1", "0.10.0"))
    }

    @Test
    fun `prefixed and suffixed real-world strings compare through numeric`() {
        assertTrue(UpdateChecker.isNewer("v0.10.0", "0.9.1-debug"))
        assertFalse(UpdateChecker.isNewer("v0.9.1", "0.9.1"))
    }

    @Test
    fun `malformed input can never produce an update banner`() {
        assertFalse(UpdateChecker.isNewer("nightly", "0.9.1"))
        assertFalse(UpdateChecker.isNewer("", "0.9.1"))
        assertFalse(UpdateChecker.isNewer("0.9.2", ""))
        assertFalse(UpdateChecker.isNewer("a.b.c", "0.9.1"))
    }
}
