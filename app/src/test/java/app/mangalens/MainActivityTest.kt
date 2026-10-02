package app.mangalens

import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import android.provider.Settings
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/**
 * Setup's first step, "Allow it ↗", on phones whose Settings answer the
 * overlay switch differently: the page for MangaLens, only the plain list,
 * or nothing at all, as on Android Go.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivityTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val app: Application = RuntimeEnvironment.getApplication()
    private var scenario: ActivityScenario<MainActivity>? = null

    private val forMangaLens = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${app.packageName}"))
    private val plainList = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)

    @Before
    fun setUp() {
        // With no version to compare, the screen asks for no update: a test goes nowhere.
        shadowOf(app.packageManager).getInternalMutablePackageInfo(app.packageName).versionName = ""
    }

    @After
    fun tearDown() {
        scenario?.close()
    }

    /** Settings has a page for [intent]. */
    private fun settingsAnswers(intent: Intent) {
        shadowOf(app.packageManager).addResolveInfoForIntent(
            intent,
            ResolveInfo().apply {
                activityInfo = ActivityInfo().apply {
                    packageName = "com.android.settings"
                    name = "com.android.settings.Settings\$AppDrawOverlaySettingsActivity"
                }
            },
        )
    }

    /** Opens the app on setup, overlay not yet allowed, and taps "Allow it ↗". */
    private fun tapAllowIt() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        // As a phone does, and Robolectric does not by default: an intent
        // nothing answers throws instead of opening.
        shadowOf(app).checkActivities(true)
        // The settings store answers a moment later; the screen shows nothing till then.
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Allow it ↗").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Allow it ↗").performScrollTo().performClick()
    }

    @Test
    fun `the overlay switch opens on MangaLens's own page`() {
        settingsAnswers(forMangaLens)
        settingsAnswers(plainList)
        tapAllowIt()
        assertEquals(forMangaLens.data, shadowOf(app).nextStartedActivity.data)
        assertEquals("Find MangaLens and switch on “Display over other apps”, then come back.", ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `where Settings has only the plain list, that opens`() {
        settingsAnswers(plainList)
        tapAllowIt()
        val opened = shadowOf(app).nextStartedActivity
        assertEquals(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, opened.action)
        assertNull(opened.data)
        assertEquals("Find MangaLens and switch on “Display over other apps”, then come back.", ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `where there is no overlay switch at all, it says so instead of crashing`() {
        tapAllowIt()
        assertNull(shadowOf(app).nextStartedActivity)
        assertEquals(
            "This phone doesn’t let apps draw over other apps, so MangaLens can’t letter here.",
            ShadowToast.getTextOfLatestToast(),
        )
    }
}
