package app.mangalens.overlay

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.WindowManager

/**
 * Where the lettering can be drawn at full strength. [LetteringHostService]
 * lends its window manager here while the system has it connected, and
 * [OverlayController] listens: the lettering moves into an accessibility
 * overlay the moment the reader switches the service on, and back to an
 * ordinary overlay if they switch it off mid-read. Main thread only, like
 * the service's callbacks and the controller.
 */
object LetteringHost {

    /**
     * The connected service's window manager, which carries the window
     * token an accessibility overlay is added with; null while the service
     * is not connected.
     */
    var windowManager: WindowManager? = null
        private set

    private val listeners = ArrayList<() -> Unit>()

    /** The service connected and lends [wm]. */
    internal fun connect(wm: WindowManager) {
        if (windowManager === wm) return
        windowManager = wm
        changed()
    }

    /**
     * The service that lent [wm] is going. One that goes after a newer
     * connection has replaced it takes nothing with it.
     */
    internal fun disconnect(wm: WindowManager) {
        if (windowManager !== wm) return
        windowManager = null
        changed()
    }

    /** Calls [listener] whenever the host comes or goes. */
    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    private fun changed() {
        // A copy, so a listener may remove itself while it is being told.
        for (l in listeners.toList()) l()
    }

    /**
     * Whether solid lettering is on: the reader has switched the service on
     * in Accessibility, or the system has it connected. The setting alone
     * counts too: the system connects a switched-on service a moment after
     * the switch flips, and the reader may be back before it has.
     */
    fun isOn(context: Context): Boolean =
        windowManager != null || switchedOn(
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
            ComponentName(context, LetteringHostService::class.java),
        )

    /**
     * Whether [enabled], ENABLED_ACCESSIBILITY_SERVICES' colon-separated
     * list of component names, names [service]. Settings writes each name
     * in full, but one put there by hand may be in the short form.
     */
    internal fun switchedOn(enabled: String?, service: ComponentName): Boolean {
        if (enabled.isNullOrEmpty()) return false
        val names = setOf(service.flattenToString(), service.flattenToShortString())
        return enabled.split(':').any { it.trim() in names }
    }
}
