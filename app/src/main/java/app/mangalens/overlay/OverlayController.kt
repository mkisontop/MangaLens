package app.mangalens.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import kotlin.math.abs

/**
 * Owns the three overlay windows: the untouchable full-screen result layer, the
 * draggable floating button with its status pill, and the long-press quick menu.
 * All methods must be called from the main thread.
 */
class OverlayController(private val context: Context, private val listener: Listener) {

    interface Listener {
        fun onTranslateNow()
        fun onTogglePause()
        fun onToggleMode()
        fun onPeek()
        /** Forget this series' glossary, cast and story so far, and start fresh. */
        fun onNewSeries()
        fun onOpenSettings()
        fun onStopRequested()
        fun isPaused(): Boolean
        fun isAutoMode(): Boolean
    }

    private val wm = context.getSystemService(WindowManager::class.java)
    val bubbleView = BubbleOverlayView(context)

    private var controls: LinearLayout? = null
    private var button: FloatingButtonView? = null
    private var pill: TextView? = null
    private var menu: LinearLayout? = null
    private var controlsLp: WindowManager.LayoutParams? = null
    private var attached = false
    private val hidePill = Runnable { pill?.visibility = View.GONE }

    /**
     * Called on the main thread whenever the screen area the controls
     * occupy changes: the pill comes or goes or is re-measured, the button
     * is dragged, the menu opens or closes. The capture loop masks that
     * area out of its comparisons and must learn of every change before
     * the frame that shows it is drawn — which is why the row's own layout
     * pass reports it, ahead of that frame's draw.
     */
    var onFootprintChanged: (() -> Unit)? = null

    private fun dp(v: Float): Int = (v * context.resources.displayMetrics.density).toInt()

    fun attach() {
        if (attached) return
        val bubbleLp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        bubbleLp.gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 28) {
            bubbleLp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        wm.addView(bubbleView, bubbleLp)
        buildControls()
        attached = true
    }

    fun detach() {
        if (!attached) return
        dismissMenu()
        runCatching { wm.removeView(bubbleView) }
        controls?.let { runCatching { wm.removeView(it) } }
        controls = null
        onFootprintChanged = null
        attached = false
    }

    /**
     * Screen regions occupied by MangaLens's own floating controls. Excluded
     * from OCR so the app never translates its own 文A button ("Sentence A").
     */
    fun overlayExclusions(): List<android.graphics.Rect> {
        val out = ArrayList<android.graphics.Rect>(2)
        val lp = controlsLp
        val row = controls
        if (lp != null && row != null) {
            val w = if (row.width > 0) row.width else dp(220f)
            val h = if (row.height > 0) row.height else dp(52f)
            val m = dp(6f)
            out.add(android.graphics.Rect(lp.x - m, lp.y - m, lp.x + w + m, lp.y + h + m))
            menu?.let { mv ->
                val mw = if (mv.width > 0) mv.width else dp(240f)
                val mh = if (mv.height > 0) mv.height else dp(380f)
                out.add(android.graphics.Rect(lp.x - m, lp.y + dp(58f) - m, lp.x + mw + m, lp.y + dp(58f) + mh + m))
            }
        }
        return out
    }

    fun setStatus(text: String?, autoHideMs: Long = 0) {
        val p = pill ?: return
        p.removeCallbacks(hidePill)
        if (text == null) {
            p.visibility = View.GONE
            return
        }
        OverlayStyle.showStatus(p, text)
        p.visibility = View.VISIBLE
        if (autoHideMs > 0) p.postDelayed(hidePill, autoHideMs)
    }

    fun setPaused(paused: Boolean) {
        button?.setPaused(paused)
    }

    /** Tap-to-translate mode: a tap on the button then translates the page. */
    fun setManual(manual: Boolean) {
        button?.setManual(manual)
    }

    /** Sweeps the busy ring on the button while a translation pass runs. */
    fun setBusy(busy: Boolean) {
        button?.setBusy(busy)
    }

    /**
     * What a tap on the button does. Paused, it wakes translation up. In
     * hands-free mode it pauses; in tap-to-translate mode it translates the
     * page, because there a pause would do nothing the reader can see and
     * the home screen tells them the tap translates.
     */
    private fun onTap() {
        when {
            listener.isPaused() -> listener.onTogglePause()
            listener.isAutoMode() -> listener.onTogglePause()
            else -> listener.onTranslateNow()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildControls() {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val btn = FloatingButtonView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(52f), dp(52f))
        }
        btn.setOnClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            btn.playTapPulse()
            onTap()
        }
        // Long-press is a touch gesture only; screen readers get the menu as an action.
        ViewCompat.addAccessibilityAction(btn, "Open quick menu") { _, _ ->
            showMenu()
            true
        }
        val status = OverlayStyle.statusPill(context).apply { visibility = View.GONE }
        row.addView(btn)
        row.addView(status)
        row.addOnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
            if (l != oldL || t != oldT || r != oldR || b != oldB) onFootprintChanged?.invoke()
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                // Screen-space coords so overlayExclusions() matches the capture.
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = dp(8f)
        lp.y = dp(170f)

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        var downTime = 0L
        val longPress = Runnable {
            moved = true
            showMenu()
        }
        btn.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = lp.x; startY = lp.y
                    moved = false
                    downTime = System.currentTimeMillis()
                    v.postDelayed(longPress, 480)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (moved || abs(dx) > dp(6f) || abs(dy) > dp(6f)) {
                        if (!moved) {
                            moved = true
                            v.removeCallbacks(longPress)
                        }
                        lp.x = startX + dx.toInt()
                        lp.y = startY + dy.toInt()
                        controls?.let { c -> runCatching { wm.updateViewLayout(c, lp) } }
                        onFootprintChanged?.invoke()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    v.removeCallbacks(longPress)
                    if (!moved && System.currentTimeMillis() - downTime < 450) v.performClick()
                }
                MotionEvent.ACTION_CANCEL -> v.removeCallbacks(longPress)
            }
            true
        }

        wm.addView(row, lp)
        controls = row
        button = btn
        pill = status
        controlsLp = lp
    }

    private fun showMenu() {
        if (menu != null) return
        val lpControls = controlsLp ?: return
        val col = buildQuickMenu(context, listener) { dismissMenu() }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = lpControls.x
        lp.y = lpControls.y + dp(58f)

        col.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                dismissMenu()
                true
            } else false
        }

        wm.addView(col, lp)
        menu = col
        onFootprintChanged?.invoke()
    }

    companion object {
        /**
         * The quick menu's panel with every item wired to [listener]; a
         * picked item first calls [dismiss]. Built apart from the window so
         * it can be drawn on its own.
         */
        internal fun buildQuickMenu(context: Context, listener: Listener, dismiss: () -> Unit): LinearLayout {
            val col = OverlayStyle.menuPanel(context)
            fun item(label: String, color: Int = OverlayStyle.MENU_INK, action: () -> Unit) {
                col.addView(OverlayStyle.menuRow(context, label, color).apply {
                    setOnClickListener {
                        dismiss()
                        action()
                    }
                })
            }
            item("⚡  Translate this page") { listener.onTranslateNow() }
            item(if (listener.isPaused()) "▶  Wake up (resume)" else "⏸  Pause for a nap") { listener.onTogglePause() }
            item(if (listener.isAutoMode()) "✋  Switch to tap-to-translate" else "🔄  Switch to hands-free") {
                listener.onToggleMode()
            }
            item("👁  Peek at the original (4 s)") { listener.onPeek() }
            item("📖  New series: forget names") { listener.onNewSeries() }
            item("⚙  Tweaks") { listener.onOpenSettings() }
            col.addView(OverlayStyle.menuDivider(context))
            item("✕  Stop translating", OverlayStyle.MENU_STOP) { listener.onStopRequested() }
            return col
        }
    }

    fun dismissMenu() {
        val open = menu ?: return
        runCatching { wm.removeView(open) }
        menu = null
        onFootprintChanged?.invoke()
    }
}
