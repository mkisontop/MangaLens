package app.mangalens.overlay

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.Display
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Whether solid lettering is on, as the home screen asks it: the service
 * named in Accessibility's list of switched-on services, in either of the
 * forms a component name is written in, or connected. And the registry the
 * service lends its window manager to, which must never be emptied by a
 * service that has already been replaced.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LetteringHostTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val service = ComponentName("app.mangalens", "app.mangalens.overlay.LetteringHostService")
    private val lent = ArrayList<WindowManager>()

    @After
    fun tearDown() {
        lent.forEach { LetteringHost.disconnect(it) }
    }

    private fun connect(): WindowManager = RecordingWindowManager().also {
        lent += it
        LetteringHost.connect(it)
    }

    @Test
    fun `the list names the service in its long or its short form`() {
        assertTrue(LetteringHost.switchedOn("app.mangalens/app.mangalens.overlay.LetteringHostService", service))
        assertTrue(LetteringHost.switchedOn("app.mangalens/.overlay.LetteringHostService", service))
        assertTrue(
            "among others",
            LetteringHost.switchedOn(
                "com.example.reader/.ReaderService:app.mangalens/.overlay.LetteringHostService:org.other/org.other.A",
                service,
            ),
        )
    }

    @Test
    fun `the download declares only auto-scroll's accessibility service, and nothing else Play Protect blocks`() {
        // Enhanced fraud protection refuses a browser- or file-manager-installed
        // app with any of these, whatever it does with them: 1.0.1 could not
        // be installed at all where it is on. Auto-scroll needs one (only an
        // accessibility service may move another app's page), and the reader
        // chose to have it at that price; nothing else is declared.
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_SERVICES or PackageManager.GET_PERMISSIONS,
        )
        val accessibility = info.services.orEmpty()
            .filter { it.permission == "android.permission.BIND_ACCESSIBILITY_SERVICE" }
            .map { it.name }
        assertEquals(listOf("app.mangalens.scroll.AutoScrollService"), accessibility)
        val listeners = info.services.orEmpty().filter { it.permission == "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE" }
        assertEquals(emptyList<Any>(), listeners)
        val sms = info.requestedPermissions.orEmpty().filter { it == "android.permission.RECEIVE_SMS" || it == "android.permission.READ_SMS" }
        assertEquals(emptyList<String>(), sms)
        // Solid lettering is still not offered: its service is not declared.
        assertFalse(LetteringHost.declared(context))
    }

    @Test
    fun `nothing else in the list switches it on`() {
        assertFalse(LetteringHost.switchedOn(null, service))
        assertFalse(LetteringHost.switchedOn("", service))
        assertFalse(LetteringHost.switchedOn("com.example.reader/.ReaderService", service))
        // The same class in another package, a longer name, a bare package: none of them is it.
        assertFalse(LetteringHost.switchedOn("com.example/app.mangalens.overlay.LetteringHostService", service))
        assertFalse(LetteringHost.switchedOn("app.mangalens/.overlay.LetteringHostServiceX", service))
        assertFalse(LetteringHost.switchedOn("app.mangalens", service))
        assertFalse(LetteringHost.switchedOn("app.mangalens/.overlay", service))
    }

    @Test
    fun `it is on once Accessibility lists it, or while the service is connected`() {
        val resolver = context.contentResolver
        val key = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        assertFalse(LetteringHost.isOn(context))
        Settings.Secure.putString(resolver, key, "com.example.reader/.ReaderService")
        assertFalse(LetteringHost.isOn(context))
        Settings.Secure.putString(resolver, key, "com.example.reader/.ReaderService:" + service.flattenToString())
        assertTrue(LetteringHost.isOn(context))

        // Connected before the setting has caught up still counts.
        Settings.Secure.putString(resolver, key, "")
        connect()
        assertTrue(LetteringHost.isOn(context))
    }

    @Test
    fun `listeners hear the host come and go`() {
        var heard = 0
        val listener: () -> Unit = { heard++ }
        LetteringHost.addListener(listener)
        try {
            val wm = connect()
            assertSame(wm, LetteringHost.windowManager)
            assertEquals(1, heard)
            // The same window manager lent again is no change.
            LetteringHost.connect(wm)
            assertEquals(1, heard)
            LetteringHost.disconnect(wm)
            assertNull(LetteringHost.windowManager)
            assertEquals(2, heard)
        } finally {
            LetteringHost.removeListener(listener)
        }
        connect()
        assertEquals("a removed listener hears nothing", 2, heard)
    }

    @Test
    fun `a service that is already replaced takes nothing with it`() {
        val old = connect()
        val new = connect()
        assertSame(new, LetteringHost.windowManager)
        LetteringHost.disconnect(old)
        assertSame("the newer connection stays", new, LetteringHost.windowManager)
        LetteringHost.disconnect(new)
        assertNull(LetteringHost.windowManager)
    }
}

/**
 * A window manager that keeps a record instead of making windows, standing
 * in for the accessibility service's: only a connected service has the
 * token its windows need. [refuse] turns every window down, as a stale
 * token would.
 */
internal class RecordingWindowManager : WindowManager {
    val windows = LinkedHashMap<View, WindowManager.LayoutParams>()
    var refuse = false

    override fun addView(view: View, params: ViewGroup.LayoutParams) {
        if (refuse) throw WindowManager.BadTokenException("Unable to add window -- token is not valid")
        check(view !in windows) { "already added" }
        windows[view] = params as WindowManager.LayoutParams
    }

    override fun updateViewLayout(view: View, params: ViewGroup.LayoutParams) {
        check(view in windows) { "not added" }
        windows[view] = params as WindowManager.LayoutParams
    }

    override fun removeView(view: View) {
        requireNotNull(windows.remove(view)) { "not added" }
    }

    override fun removeViewImmediate(view: View) = removeView(view)

    @Deprecated("Deprecated in Java")
    override fun getDefaultDisplay(): Display = throw UnsupportedOperationException()
}
