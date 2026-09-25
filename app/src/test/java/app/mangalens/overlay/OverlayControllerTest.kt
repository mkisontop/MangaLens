package app.mangalens.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowWindowManagerImpl
import java.time.Duration

/**
 * Where the lettering's window goes. With the accessibility host connected
 * it is an accessibility overlay added through the host's window manager,
 * which Android draws at full strength; without it, an ordinary overlay as
 * before, with the same flags either way. It moves when the host comes or
 * goes mid-read, and the floating controls stay ordinary overlays
 * throughout. Drawn over the controls, the lettering leaves them bare.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OverlayControllerTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    /** Every window MangaLens itself adds; they all go through the one window manager. */
    private val ownWindows: ShadowWindowManagerImpl = Shadow.extract(context.getSystemService(WindowManager::class.java))

    private val host = RecordingWindowManager()
    private var controller: OverlayController? = null

    private val letteringFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

    private object NoListener : OverlayController.Listener {
        override fun onTranslateNow() = Unit
        override fun onTogglePause() = Unit
        override fun onToggleMode() = Unit
        override fun onPeek() = Unit
        override fun onNewSeries() = Unit
        override fun onOpenSettings() = Unit
        override fun onStopRequested() = Unit
        override fun isPaused() = false
        override fun isAutoMode() = true
    }

    @After
    fun tearDown() {
        controller?.detach()
        LetteringHost.disconnect(host)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun attach(): OverlayController = OverlayController(context, NoListener).also {
        controller = it
        it.attach()
        idle()
    }

    /** The lettering's window, full screen and untouchable, of [type]. */
    private fun assertLetteringWindow(lp: WindowManager.LayoutParams, type: Int) {
        assertEquals(type, lp.type)
        assertEquals(letteringFlags, lp.flags and letteringFlags)
        assertEquals(WindowManager.LayoutParams.MATCH_PARENT, lp.width)
        assertEquals(WindowManager.LayoutParams.MATCH_PARENT, lp.height)
        assertEquals(PixelFormat.TRANSLUCENT, lp.format)
        assertEquals(Gravity.TOP or Gravity.START, lp.gravity)
        assertEquals(WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES, lp.layoutInDisplayCutoutMode)
    }

    private fun assertOrdinaryOverlay(c: OverlayController) {
        assertTrue("in MangaLens's own window manager", c.bubbleView in ownWindows.views)
        assertFalse("not in the host's", c.bubbleView in host.windows)
        assertLetteringWindow(c.bubbleView.layoutParams as WindowManager.LayoutParams, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
    }

    private fun assertAccessibilityOverlay(c: OverlayController) {
        assertFalse("not in MangaLens's own window manager", c.bubbleView in ownWindows.views)
        val lp = host.windows[c.bubbleView]
        assertNotNull("in the host's", lp)
        assertLetteringWindow(lp!!, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
    }

    /** The controls: touchable, so never faded, and ordinary overlays whatever the lettering is. */
    private fun assertControlsOrdinary(c: OverlayController) {
        val controls = ownWindows.views.filter { it !== c.bubbleView }
        assertTrue("the controls are up", controls.isNotEmpty())
        for (v: View in controls) {
            assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, (v.layoutParams as WindowManager.LayoutParams).type)
        }
    }

    @Test
    fun `with no host the lettering is an ordinary overlay`() {
        val c = attach()
        assertOrdinaryOverlay(c)
        assertControlsOrdinary(c)
        assertTrue(host.windows.isEmpty())
    }

    @Test
    fun `with the host connected the lettering is an accessibility overlay in the host's window`() {
        LetteringHost.connect(host)
        val c = attach()
        assertAccessibilityOverlay(c)
        assertControlsOrdinary(c)
    }

    @Test
    fun `the lettering moves when the host comes and goes mid-read`() {
        val c = attach()
        assertOrdinaryOverlay(c)

        LetteringHost.connect(host)
        idle()
        assertAccessibilityOverlay(c)
        assertControlsOrdinary(c)

        // Switched off: the system takes the window down with the service, and the lettering comes home.
        LetteringHost.disconnect(host)
        idle()
        assertOrdinaryOverlay(c)
        assertTrue(host.windows.isEmpty())
        assertControlsOrdinary(c)

        LetteringHost.connect(host)
        idle()
        assertAccessibilityOverlay(c)
    }

    @Test
    fun `a host that turns the window down leaves the lettering where it can be seen`() {
        host.refuse = true
        LetteringHost.connect(host)
        val c = attach()
        assertOrdinaryOverlay(c)
    }

    @Test
    fun `detaching takes the lettering down wherever it is, and stops listening`() {
        LetteringHost.connect(host)
        val c = attach()
        c.detach()
        controller = null
        assertTrue(host.windows.isEmpty())
        assertTrue(ownWindows.views.isEmpty())

        LetteringHost.disconnect(host)
        LetteringHost.connect(host)
        idle()
        assertTrue("nothing comes back after detach", host.windows.isEmpty() && ownWindows.views.isEmpty())
    }

    @Test
    fun `drawn over the controls, the lettering keeps off them`() {
        val c = attach()
        // Added before the controls, it sits under them, as it always has.
        assertEquals(emptyList<Rect>(), c.bubbleView.keepClear)

        LetteringHost.connect(host)
        idle()
        val footprint = c.overlayExclusions()
        assertTrue(footprint.isNotEmpty())
        assertEquals(footprint, c.bubbleView.keepClear)

        // Home again, but added after the controls: still over them.
        LetteringHost.disconnect(host)
        idle()
        assertEquals(c.overlayExclusions(), c.bubbleView.keepClear)
    }

    @Test
    fun `the menu is open from a long press on the button until a touch outside closes it`() {
        val c = attach()
        assertFalse(c.menuOpen)
        val row = ownWindows.views.single { it !== c.bubbleView } as ViewGroup
        val button = (0 until row.childCount).map { row.getChildAt(it) }.single { it is FloatingButtonView }
        button.dispatchTouchEvent(MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 10f, 10f, 0))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
        assertTrue("held open for auto-scroll to wait on", c.menuOpen)
        // Any touch outside it closes it, auto-scroll's own strokes included.
        val menu = ownWindows.views.single { it !== c.bubbleView && it !== row }
        menu.dispatchTouchEvent(MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_OUTSIDE, 0f, 0f, 0))
        assertFalse(c.menuOpen)
    }

    @Test
    fun `nothing is drawn where the lettering keeps clear`() {
        val w = 720
        val h = 1000
        val v = BubbleOverlayView(context).apply { layout(0, 0, w, h) }
        v.setBubbles(
            listOf(
                RenderBubble(
                    box = Rect(120, 200, 600, 420),
                    translated = "Over here! Over here!",
                    original = "こっちだ！こっちだ！",
                    bgColor = Color.WHITE,
                    textColor = Color.BLACK,
                    vertical = false,
                )
            )
        )
        v.finishFades()
        val painted = v.placedRects().single()
        val bare = Rect(painted.left, painted.top, painted.centerX(), painted.bottom)

        fun inked(bmp: Bitmap, r: Rect): Int {
            var n = 0
            for (y in r.top until r.bottom) for (x in r.left until r.right) if (Color.alpha(bmp.getPixel(x, y)) > 0) n++
            return n
        }
        fun render(): Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { v.draw(Canvas(it)) }

        assertTrue("the lettering paints both halves", inked(render(), bare) > 0)
        v.keepClear = listOf(bare)
        val kept = render()
        assertEquals("nothing inside the kept-clear area", 0, inked(kept, bare))
        assertTrue(
            "the rest is still lettered",
            inked(kept, Rect(painted.centerX(), painted.top, painted.right, painted.bottom)) > 0,
        )
    }
}
