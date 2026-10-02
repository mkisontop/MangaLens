package app.mangalens.ui

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import app.mangalens.overlay.LetteringHost
import app.mangalens.overlay.LetteringHostService
import app.mangalens.overlay.OverlayStrength
import app.mangalens.settings.SettingsRepository
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The home screen as the activity shows it, reading the system rather than
 * a fixed state: what it makes of the switches it finds in Settings.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HomeScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context: Context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        // With no version to compare, the screen asks for no update: a test goes nowhere.
        shadowOf(context.packageManager).getInternalMutablePackageInfo(context.packageName).versionName = ""
    }

    private fun showTweaks(repo: SettingsRepository) {
        compose.setContent {
            MangaLensTheme {
                HomeScreen(
                    repo = repo,
                    browserName = "Brave",
                    tweaksRequests = 1,
                    tweaksTarget = TweaksTarget.TOP,
                    startRefused = false,
                    onStart = {},
                    onStop = {},
                    onGrantOverlay = {},
                    onTogglePause = {},
                    onOpenBrowser = {},
                    onTurnOnSolid = {},
                    onOpenAppInfo = {},
                    onTurnOnAutoScroll = {},
                )
            }
        }
        // The settings store answers a moment later; the page shows nothing till then.
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("How big I letter the English on the page.").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `a solid lettering switch left over from 1_0_1 does not hide No ghosts`() {
        // 1.0.1 declared the lettering host. Android 12 to 14 before QPR3
        // keep a service switched on there in Accessibility's list after an
        // update that drops it, so the list still names it here.
        assertFalse(LetteringHost.declared(context))
        assertTrue("Android holds this overlay below full strength", OverlayStrength.of(context) < 1f)
        Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ComponentName(context, LetteringHostService::class.java).flattenToString(),
        )
        assertTrue("the list alone reads as switched on", LetteringHost.isOn(context))

        showTweaks(SettingsRepository(context))
        // The veil is still on by default, so its switch must be there.
        compose.onNodeWithText("No ghosts").performScrollTo()
        compose.onNodeWithText("Solid lettering", substring = true).assertDoesNotExist()
    }
}
