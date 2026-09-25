package app.mangalens.scroll

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import app.mangalens.overlay.LetteringHost

/**
 * The accessibility service auto-scroll drags the page through.
 *
 * On Android only an accessibility service may touch another app's screen,
 * so this is how MangaLens scrolls a browser, a reader app or anything else
 * the reader has open. It does nothing on its own: its configuration
 * (res/xml/auto_scroll.xml) asks for no events and no window content, only
 * to perform gestures, and it performs one only while auto-scroll is
 * running, when [AutoScrollHost] hands it the drag to send.
 */
class AutoScrollService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        AutoScrollHost.connect(this)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        AutoScrollHost.disconnect(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        AutoScrollHost.disconnect(this)
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit
}

/**
 * Where auto-scroll finds [AutoScrollService] while the system has it
 * connected. Main thread only, like the service's callbacks.
 */
object AutoScrollHost {

    private var service: AutoScrollService? = null

    private val listeners = ArrayList<() -> Unit>()

    internal fun connect(s: AutoScrollService) {
        if (service === s) return
        service = s
        changed()
    }

    /** A service going after a newer one replaced it takes nothing with it. */
    internal fun disconnect(s: AutoScrollService) {
        if (service !== s) return
        service = null
        changed()
    }

    /** Whether the service is connected, so a drag can be sent now. */
    val connected: Boolean get() = service != null

    /** The connected service as a [GestureScroller.GestureSink], or null. */
    internal fun sink(): GestureScroller.GestureSink? {
        val s = service ?: return null
        return object : GestureScroller.GestureSink {
            override fun dispatch(gesture: GestureDescription, done: (Boolean) -> Unit): Boolean =
                runCatching {
                    s.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) = done(true)
                        override fun onCancelled(gestureDescription: GestureDescription?) = done(false)
                    }, null)
                }.getOrDefault(false)
        }
    }

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    private fun changed() {
        for (l in listeners.toList()) l()
    }

    /**
     * Whether auto-scroll is switched on in Accessibility, or connected.
     * The setting alone counts too: the system connects a switched-on
     * service a moment after the switch flips.
     */
    fun isOn(context: Context): Boolean =
        connected || LetteringHost.switchedOn(
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
            ComponentName(context, AutoScrollService::class.java),
        )
}
