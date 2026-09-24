package app.mangalens

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import app.mangalens.capture.ScreenCaptureService
import app.mangalens.overlay.LetteringHostService
import app.mangalens.settings.SettingsRepository
import app.mangalens.ui.HomeScreen
import app.mangalens.ui.MangaLensTheme

class MainActivity : ComponentActivity() {

    companion object {
        /** Set by the overlay's "Tweaks" item: open straight onto the Tweaks page. */
        const val EXTRA_OPEN_TWEAKS = "open_tweaks"

        private const val BRAVE = "com.brave.browser"

        /** Settings.ACTION_ACCESSIBILITY_DETAILS_SETTINGS, which the public SDK leaves out. */
        private const val ACCESSIBILITY_DETAILS = "android.settings.ACCESSIBILITY_DETAILS_SETTINGS"

        /** Settings' extras for the preference to scroll to and highlight; other builds ignore them. */
        private const val FRAGMENT_ARG_KEY = ":settings:fragment_args_key"
        private const val SHOW_FRAGMENT_ARGS = ":settings:show_fragment_args"
    }

    private lateinit var repo: SettingsRepository

    /** Bumped for every request to open Tweaks; the screen opens it when the count changes. */
    private var tweaksRequests by mutableIntStateOf(0)

    /**
     * The last start ended at the screen-capture dialog's Cancel. Fuki
     * says so on the home screen, which is still in front of the reader,
     * instead of a toast that is gone before it is read.
     */
    private var startRefused by mutableStateOf(false)

    private var browserName by mutableStateOf<String?>(null)

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == Activity.RESULT_OK && data != null) {
                val intent = Intent(this, ScreenCaptureService::class.java)
                    .setAction(ScreenCaptureService.ACTION_START)
                    .putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                    .putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
                ContextCompat.startForegroundService(this, intent)
            } else {
                startRefused = true
            }
        }

    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        repo = SettingsRepository(applicationContext)
        browserName = findBrowserName()
        // A recreated activity still holds the intent it was opened with;
        // only a fresh open is a new request.
        if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_OPEN_TWEAKS, false)) tweaksRequests++
        setContent {
            MangaLensTheme {
                HomeScreen(
                    repo = repo,
                    browserName = browserName,
                    tweaksRequests = tweaksRequests,
                    startRefused = startRefused,
                    onStart = { startFlow() },
                    onStop = { stopCapture() },
                    onGrantOverlay = { openOverlaySettings() },
                    onTogglePause = { togglePause() },
                    onOpenBrowser = { openBrowser() },
                    onTurnOnSolid = { openSolidLetteringSettings() },
                    onOpenAppInfo = { openAppInfo() },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_TWEAKS, false)) tweaksRequests++
    }

    override fun onResume() {
        super.onResume()
        // Brave may have been installed or removed while we were away.
        browserName = findBrowserName()
    }

    private fun findBrowserName(): String? =
        if (packageManager.getLaunchIntentForPackage(BRAVE) != null) "Brave" else null

    private fun startFlow() {
        startRefused = false
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (!Settings.canDrawOverlays(this)) {
            openOverlaySettings()
            return
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    /** The system settings page covers the app, so its hint is a toast. */
    private fun openOverlaySettings() {
        Toast.makeText(
            this,
            "Find MangaLens and switch on “Display over other apps”, then come back.",
            Toast.LENGTH_LONG
        ).show()
        startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        )
    }

    /**
     * Where solid lettering is switched on. On Android 13 and later that is
     * MangaLens's own page in Accessibility, where Settings will open it;
     * it is tried rather than looked up first, since package visibility can
     * hide Settings from a lookup and the page may turn an app away.
     * Otherwise it is the Accessibility list, with MangaLens picked out
     * where Settings knows how. Settings covers the app, so the hint is a
     * toast, as for the overlay switch.
     */
    private fun openSolidLetteringSettings() {
        Toast.makeText(this, "Find MangaLens, switch it on and tap Allow, then come back.", Toast.LENGTH_LONG).show()
        val service = ComponentName(this, LetteringHostService::class.java).flattenToString()
        if (Build.VERSION.SDK_INT >= 33) {
            val page = Intent(ACCESSIBILITY_DETAILS).putExtra(Intent.EXTRA_COMPONENT_NAME, service)
            if (runCatching { startActivity(page) }.isSuccess) return
        }
        val list = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .putExtra(FRAGMENT_ARG_KEY, service)
            .putExtra(SHOW_FRAGMENT_ARGS, Bundle().apply { putString(FRAGMENT_ARG_KEY, service) })
        runCatching { startActivity(list) }
    }

    /** App info, whose ⋮ menu holds "Allow restricted settings" on Android 13 and later. */
    private fun openAppInfo() {
        runCatching {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    /** Brave when it is installed, else whatever the reader's default browser is. */
    private fun openBrowser() {
        val launch = packageManager.getLaunchIntentForPackage(BRAVE)
            ?: Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER)
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(launch) }.onFailure {
            Toast.makeText(this, "Open your browser and start reading", Toast.LENGTH_LONG).show()
        }
    }

    private fun togglePause() {
        startService(
            Intent(this, ScreenCaptureService::class.java).setAction(ScreenCaptureService.ACTION_TOGGLE_PAUSE)
        )
    }

    private fun stopCapture() {
        startService(
            Intent(this, ScreenCaptureService::class.java).setAction(ScreenCaptureService.ACTION_STOP)
        )
    }
}
