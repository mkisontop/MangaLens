package app.mangalens.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
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
    private var button: TextView? = null
    private var pill: TextView? = null
    private var menu: LinearLayout? = null
    private var controlsLp: WindowManager.LayoutParams? = null
    private var attached = false
    private val hidePill = Runnable { pill?.visibility = View.GONE }

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
                val mw = if (mv.width > 0) mv.width else dp(220f)
                val mh = if (mv.height > 0) mv.height else dp(280f)
                out.add(android.graphics.Rect(lp.x - m, lp.y + dp(52f) - m, lp.x + mw + m, lp.y + dp(52f) + mh + m))
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
        p.text = text
        p.visibility = View.VISIBLE
        if (autoHideMs > 0) p.postDelayed(hidePill, autoHideMs)
    }

    fun setPaused(paused: Boolean) {
        button?.background = circle(if (paused) 0xE6555A66.toInt() else 0xEE3B3F8C.toInt())
    }

    private fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun rounded(color: Int, radiusDp: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radiusDp).toFloat()
        setColor(color)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildControls() {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val btn = TextView(context).apply {
            text = "文A"
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            background = circle(0xEE3B3F8C.toInt())
            layoutParams = LinearLayout.LayoutParams(dp(46f), dp(46f))
            elevation = dp(4f).toFloat()
        }
        val status = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 11f
            maxLines = 2
            setPadding(dp(10f), dp(5f), dp(10f), dp(5f))
            background = rounded(0xD0202233.toInt(), 14f)
            visibility = View.GONE
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.marginStart = dp(6f)
            layoutParams = lp
        }
        row.addView(btn)
        row.addView(status)

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
                    }
                }
                MotionEvent.ACTION_UP -> {
                    v.removeCallbacks(longPress)
                    if (!moved && System.currentTimeMillis() - downTime < 450) listener.onTranslateNow()
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
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(0xF01B1C2E.toInt(), 12f)
            elevation = dp(8f).toFloat()
            setPadding(0, dp(4f), 0, dp(4f))
        }

        fun item(label: String, action: () -> Unit) {
            col.addView(TextView(context).apply {
                text = label
                setTextColor(Color.WHITE)
                textSize = 13f
                setPadding(dp(16f), dp(10f), dp(16f), dp(10f))
                setOnClickListener {
                    dismissMenu()
                    action()
                }
            })
        }

        item("⚡  Translate now") { listener.onTranslateNow() }
        item(if (listener.isPaused()) "▶  Resume live mode" else "⏸  Pause") { listener.onTogglePause() }
        item(if (listener.isAutoMode()) "✋  Switch to tap-to-translate" else "🔄  Switch to auto-live") {
            listener.onToggleMode()
        }
        item("👁  Peek at original (4 s)") { listener.onPeek() }
        item("📖  New series — forget names so far") { listener.onNewSeries() }
        item("⚙  Settings") { listener.onOpenSettings() }
        item("✕  Stop translating") { listener.onStopRequested() }

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
        lp.y = lpControls.y + dp(52f)

        col.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                dismissMenu()
                true
            } else false
        }

        wm.addView(col, lp)
        menu = col
    }

    fun dismissMenu() {
        menu?.let { runCatching { wm.removeView(it) } }
        menu = null
    }
}
