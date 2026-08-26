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

    @Test
    fun `private-signed installs download the primary apk directly`() {
        val update = updateFor("0.9.2", 35, UpdateChecker.SigningTrack.RELEASE)!!

        assertEquals("0.9.3", update.version)
        assertEquals("https://download/primary", update.url)
        assertFalse(update.legacyBridge)
        assertFalse(update.requiresReinstall)
    }

    @Test
    fun `debug-signed installs use the rotation bridge on Android 9 and newer`() {
        val update = updateFor("0.9.1", 28, UpdateChecker.SigningTrack.LEGACY_DEBUG)!!

        assertEquals("https://download/bridge", update.url)
        assertTrue(update.legacyBridge)
        assertFalse(update.requiresReinstall)
    }

    @Test
    fun `debug-signed installs explain the Android 8 reinstall boundary`() {
        val update = updateFor("0.9.1", 27, UpdateChecker.SigningTrack.LEGACY_DEBUG)!!

        assertEquals("https://download/primary", update.url)
        assertFalse(update.legacyBridge)
        assertTrue(update.requiresReinstall)
    }

    @Test
    fun `missing apk asset falls back to the release page`() {
        val update = updateFor(
            "0.9.2",
            35,
            UpdateChecker.SigningTrack.RELEASE,
            emptyMap(),
        )!!

        assertEquals("https://github/release", update.url)
    }

    @Test
    fun `debug signing track wins over a release-like version name`() {
        val update = updateFor("9.9.8", 35, UpdateChecker.SigningTrack.LEGACY_DEBUG, latest = "9.9.9")!!

        assertTrue(update.legacyBridge)
        assertEquals("https://download/bridge", update.url)
    }

    @Test
    fun `unknown signer never claims update compatibility`() {
        val update = updateFor("0.9.2", 35, UpdateChecker.SigningTrack.UNKNOWN)!!

        assertTrue(update.requiresReinstall)
        assertFalse(update.legacyBridge)
    }

    @Test
    fun `pinned certificate digests select their signing tracks`() {
        assertEquals(
            UpdateChecker.SigningTrack.RELEASE,
            UpdateChecker.signingTrack("6BDE3720DC055D1F233970E4C038CB5955D816A6353C557AA59689742AB75AB6"),
        )
        assertEquals(
            UpdateChecker.SigningTrack.LEGACY_DEBUG,
            UpdateChecker.signingTrack("df0e8ef059da2b1bb121e49252a07c55e468fb4453a4fbb7d90263d3c7b7a344"),
        )
        assertEquals(UpdateChecker.SigningTrack.UNKNOWN, UpdateChecker.signingTrack("other"))
    }

    private fun updateFor(
        currentVersion: String,
        sdkInt: Int,
        signingTrack: UpdateChecker.SigningTrack,
        assets: Map<String, String> = mapOf(
            "MangaLens.apk" to "https://download/primary",
            "MangaLens-legacy-update.apk" to "https://download/bridge",
        ),
        latest: String = "0.9.3",
    ) = UpdateChecker.updateForAssets(
        latest = latest,
        releaseUrl = "https://github/release",
        currentVersion = currentVersion,
        sdkInt = sdkInt,
        signingTrack = signingTrack,
        assets = assets,
    )
}
