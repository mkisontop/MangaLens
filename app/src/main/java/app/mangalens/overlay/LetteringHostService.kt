package app.mangalens.overlay

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

/**
 * An accessibility service with a single job: to host the lettering.
 *
 * Since Android 12 the system draws an app overlay that lets touches
 * through at no more than 80% opacity, so the app under it shows. For
 * MangaLens that left a grey ghost of the original text in every cleaned
 * balloon, and English in dark grey instead of black. An accessibility
 * overlay is a trusted window: touches pass through it just the same, and
 * it is drawn exactly as painted. Only a connected accessibility service
 * can add one, through its own window manager, which carries the
 * service's window token.
 *
 * So the service reads nothing and does nothing. Its configuration
 * (res/xml/lettering_host.xml) asks for no events, no window content and
 * no gestures; while it is connected it lends its window manager to
 * [LetteringHost], and [OverlayController] letters through that.
 *
 * The published APK does not declare it (see [LetteringHost.declared]):
 * Play Protect blocks installing a sideloaded app with any accessibility
 * service. A build that declares it again in the manifest gets solid
 * lettering back, setup step and all.
 */
class LetteringHostService : AccessibilityService() {

    /** What this service lent [LetteringHost], so it withdraws only its own. */
    private var lent: WindowManager? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        // The service's own window manager: it carries the window token.
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        lent = wm
        LetteringHost.connect(wm)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        withdraw()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        withdraw()
        super.onDestroy()
    }

    private fun withdraw() {
        lent?.let { LetteringHost.disconnect(it) }
        lent = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit
}
