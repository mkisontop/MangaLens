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
import app.mangalens.R
import kotlin.math.abs

/**
 * Owns the three overlay windows: the untouchable full-screen result layer, the
 * draggable floating button with its status pill, and the long-press quick menu.
 * The result layer goes through [LetteringHost] when it is connected, so it is
 * drawn at full strength; see [placeLettering].
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

    /** The window manager [bubbleView] is in, [LetteringHost]'s or [wm]; null while it is in neither. */
    private var letteringWm: WindowManager? = null

    /**
     * Whether the lettering is drawn over the controls: always from the
     * accessibility layer, which sits above every app overlay, and from ours
     * once it has been added again after them. It then keeps their
     * footprint bare (see [BubbleOverlayView.keepClear]).
     */
    private var letteringOverControls = false

    private val hostChanged: () -> Unit = { if (attached) placeLettering() }

    /**
     * Called on the main thread whenever the screen area the controls
     * occupy changes: the pill comes or goes or is re-measured, the button
     * is dragged, the menu opens, is laid out or closes. The capture loop
     * masks that area out of its comparisons and must learn of every change
     * before the frame that shows it is drawn — which is why the row's and
     * the menu's own layout passes report it, ahead of that frame's draw.
     */
    var onFootprintChanged: (() -> Unit)? = null

    private fun dp(v: Float): Int = (v * context.resources.displayMetrics.density).toInt()

    fun attach() {
        if (attached) return
        placeLettering()
        buildControls()
        LetteringHost.addListener(hostChanged)
        attached = true
    }

    fun detach() {
        if (!attached) return
        LetteringHost.removeListener(hostChanged)
        dismissMenu()
        letteringWm?.let { w -> runCatching { w.removeView(bubbleView) } }
        letteringWm = null
        controls?.let { runCatching { wm.removeView(it) } }
        controls = null
        onFootprintChanged = null
        attached = false
    }

    /**
     * Puts the lettering in the best window there is, and moves it there
     * whenever [LetteringHost] comes or goes. Since Android 12 an ordinary
     * overlay that lets touches through is drawn at no more than 80%
     * opacity, which leaves a grey ghost of the original in every cleaned
     * balloon and turns black ink dark grey. An accessibility overlay is a
     * trusted window, drawn exactly as painted, so while the host is
     * connected the lettering goes there; otherwise, or if the host turns
     * the window down, it is an ordinary overlay as before.
     *
     * On a move the view leaves its window at once, so it is gone from the
     * old window before it joins the new one; the system may already have
     * taken that window down along with an unbound service. Added after the
     * controls, or from the host's layer, the lettering is drawn over them.
     */
    private fun placeLettering() {
        val host = LetteringHost.windowManager
        val current = letteringWm
        if (current != null && current === (host ?: wm)) return
        current?.let { old -> runCatching { old.removeViewImmediate(bubbleView) } }
        val strength = OverlayStrength.of(context)
        val placed = when {
            host != null && runCatching {
                host.addView(bubbleView, letteringParams(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, 1f))
            }.isSuccess -> host
            runCatching {
                wm.addView(bubbleView, letteringParams(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, strength))
            }.isSuccess -> wm
            else -> null
        }
        bubbleView.windowAlpha = if (placed != null && placed === host) 1f else strength
        letteringWm = placed
        letteringOverControls = (host != null && placed === host) || controls != null
        footprintChanged()
    }

    /**
     * The lettering's window: full screen in screen coordinates, never
     * focused or touched, drawn at [alpha]. An app overlay is asked for
     * exactly the strength Android allows one that lets touches through
     * ([OverlayStrength]) rather than left for the system to cut back, so
     * the view knows the strength it is drawn at. New each time, since
     * adding a window writes the token of the window manager it goes
     * through into its parameters.
     */
    private fun letteringParams(type: Int, alpha: Float): WindowManager.LayoutParams {
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.alpha = alpha
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        return lp
    }

    /**
     * The controls moved, grew, shrank, or opened or closed the menu. The
     * lettering keeps off them if it is drawn over them, and the capture
     * loop hears of it (see [onFootprintChanged]).
     */
    private fun footprintChanged() {
        bubbleView.keepClear = if (letteringOverControls) overlayExclusions() else emptyList()
        onFootprintChanged?.invoke()
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
            if (l != oldL || t != oldT || r != oldR || b != oldB) footprintChanged()
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
                        footprintChanged()
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
        // Until its first layout the menu's footprint is an estimate; report the real one.
        col.addOnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
            if (menu === col && (l != oldL || t != oldT || r != oldR || b != oldB)) footprintChanged()
        }

        wm.addView(col, lp)
        menu = col
        footprintChanged()
    }

    companion object {
        /**
         * The quick menu's panel with every item wired to [listener]; a
         * picked item first calls [dismiss]. Built apart from the window so
         * it can be drawn on its own.
         */
        internal fun buildQuickMenu(context: Context, listener: Listener, dismiss: () -> Unit): LinearLayout {
            val col = OverlayStyle.menuPanel(context)
            fun item(label: String, icon: Int, color: Int = OverlayStyle.MENU_INK, action: () -> Unit) {
                col.addView(OverlayStyle.menuRow(context, label, icon, color).apply {
                    setOnClickListener {
                        dismiss()
                        action()
                    }
                })
            }
            item("Translate this page", R.drawable.ic_menu_bolt) { listener.onTranslateNow() }
            if (listener.isPaused()) {
                item("Wake up (resume)", R.drawable.ic_menu_play) { listener.onTogglePause() }
            } else {
                item("Pause for a nap", R.drawable.ic_menu_pause) { listener.onTogglePause() }
            }
            if (listener.isAutoMode()) {
                item("Switch to tap-to-translate", R.drawable.ic_menu_tap) { listener.onToggleMode() }
            } else {
                item("Switch to hands-free", R.drawable.ic_menu_hands_free) { listener.onToggleMode() }
            }
            item("Peek at the original (4 s)", R.drawable.ic_menu_peek) { listener.onPeek() }
            item("New series: forget names", R.drawable.ic_menu_book) { listener.onNewSeries() }
            item("Tweaks", R.drawable.ic_menu_tweaks) { listener.onOpenSettings() }
            col.addView(OverlayStyle.menuDivider(context))
            item("Stop translating", R.drawable.ic_menu_stop, OverlayStyle.MENU_STOP) { listener.onStopRequested() }
            return col
        }
    }

    fun dismissMenu() {
        val open = menu ?: return
        runCatching { wm.removeView(open) }
        menu = null
        footprintChanged()
    }
}
