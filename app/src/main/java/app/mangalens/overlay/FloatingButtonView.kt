package app.mangalens.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AnimationUtils

/**
 * The draggable 文A control, drawn on canvas so its two states can cross-fade
 * instead of snapping between backgrounds. LIVE is a violet gradient disc;
 * PAUSED trades the gradient for a near-black disc under a grey rim rather
 * than a hollow ring — the button floats over arbitrary reader content, and a
 * glyph on a see-through circle disappears into whatever page is behind it.
 *
 * Invalidation is demand-driven: the animators invalidate from their update
 * listeners and the busy sweep re-posts itself from [onDraw] only while a
 * pass is running, so an idle button draws nothing and costs no frames.
 *
 * All geometry is inset from the view bounds far enough for the tap pulse's
 * 1.06 overshoot — the parent row clips children at its own edge, and a pulse
 * that outgrows the view loses its rim exactly at the moment it should pop.
 */
class FloatingButtonView(context: Context) : View(context) {

    private var paused = false
    private var busy = false

    /** 1 = live colors, 0 = paused; the one value every paint derives from. */
    private var liveness = 1f
    private var pulseScale = 1f

    private var stateAnim: ValueAnimator? = null
    private var pulseAnim: ValueAnimator? = null

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        strokeCap = Paint.Cap.ROUND
        color = 0xE6FFFFFF.toInt()
    }
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 13f, resources.displayMetrics
        )
    }

    // The gradient shader bakes its colors in, so it is rebuilt whenever the
    // cross-fade moves them — which is only while the state animator runs.
    private var gradient: Shader? = null
    private var gradientTop = 0
    private var gradientBottom = 0

    init {
        contentDescription = "Translation on: tap to pause"
        // The elevation shadow used to come from the oval background drawable;
        // a canvas-drawn view has none, so the disc's silhouette is declared
        // here (alpha included — a custom outline defaults to 0 and casts
        // nothing).
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val pad = dp(5.5f).toInt()
                outline.setOval(pad, pad, view.width - pad, view.height - pad)
                outline.alpha = 0.9f
            }
        }
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    /**
     * Cross-fades to the [paused] look. Restarting from the current mix, not
     * from an endpoint, keeps a quick double-tap from flashing back to a color
     * the fade had already left behind.
     */
    fun setPaused(paused: Boolean) {
        if (this.paused == paused) return
        this.paused = paused
        contentDescription =
            if (paused) "Translation paused: tap to resume" else "Translation on: tap to pause"
        stateAnim?.cancel()
        stateAnim = ValueAnimator.ofFloat(liveness, if (paused) 0f else 1f).apply {
            duration = 260L
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { a ->
                liveness = a.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /** Shows the sweeping arc while a translation pass is in flight. */
    fun setBusy(busy: Boolean) {
        if (this.busy == busy) return
        this.busy = busy
        invalidate()
    }

    /** The press acknowledgment: a dip and a small overshoot back to rest. */
    fun playTapPulse() {
        pulseAnim?.cancel()
        pulseAnim = ValueAnimator.ofFloat(1f, 0.88f, 1.06f, 1f).apply {
            duration = 280L
            addUpdateListener { a ->
                pulseScale = a.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        gradient = null
    }

    override fun onDetachedFromWindow() {
        // The overlay window can be torn down mid-animation; a running
        // animator would keep invalidating a view no window will ever draw.
        stateAnim?.cancel()
        pulseAnim?.cancel()
        pulseScale = 1f
        liveness = if (paused) 0f else 1f
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val ringR = minOf(cx, cy) - dp(2.5f)
        val discR = ringR - dp(3f)

        val top = blendArgb(PAUSED_FILL, LIVE_TOP, liveness)
        val bottom = blendArgb(PAUSED_FILL, LIVE_BOTTOM, liveness)
        if (gradient == null || top != gradientTop || bottom != gradientBottom) {
            gradient = LinearGradient(
                cx - discR, cy - discR, cx + discR, cy + discR,
                top, bottom, Shader.TileMode.CLAMP
            )
            gradientTop = top
            gradientBottom = bottom
        }
        fillPaint.shader = gradient

        canvas.save()
        canvas.scale(pulseScale, pulseScale, cx, cy)
        canvas.drawCircle(cx, cy, discR, fillPaint)
        outlinePaint.color = blendArgb(PAUSED_OUTLINE, PAUSED_OUTLINE and 0x00FFFFFF, liveness)
        if (Color.alpha(outlinePaint.color) > 0) canvas.drawCircle(cx, cy, discR, outlinePaint)
        glyphPaint.alpha = (GLYPH_DIM + (255 - GLYPH_DIM) * liveness).toInt()
        canvas.drawText(GLYPH, cx, cy - (glyphPaint.ascent() + glyphPaint.descent()) / 2f, glyphPaint)
        canvas.restore()

        // The ring stays outside the pulse transform: it reports pipeline
        // progress, and progress does not flinch when the button is pressed.
        if (busy) {
            val start = (AnimationUtils.currentAnimationTimeMillis() % 1000L) * 0.36f
            canvas.drawArc(
                cx - ringR, cy - ringR, cx + ringR, cy + ringR,
                start, 270f, false, ringPaint
            )
            postInvalidateOnAnimation()
        }
    }

    companion object {
        private const val GLYPH = "文A"
        private const val GLYPH_DIM = 140
        private val LIVE_TOP = 0xEE3B3F8C.toInt()
        private val LIVE_BOTTOM = 0xEE6C5CE7.toInt()
        private val PAUSED_FILL = 0xC01B1C2E.toInt()
        private val PAUSED_OUTLINE = 0xE6555A66.toInt()

        /**
         * Straight per-channel interpolation, alpha included — dropping the
         * alpha channel would snap the translucent disc opaque mid-fade. [t]
         * clamps to the unit range so an overshooting interpolator can never
         * push a channel past its endpoints into a color neither state owns.
         */
        fun blendArgb(from: Int, to: Int, t: Float): Int {
            val f = t.coerceIn(0f, 1f)
            fun ch(shift: Int): Int {
                val a = (from ushr shift) and 0xFF
                val b = (to ushr shift) and 0xFF
                return (a + (b - a) * f + 0.5f).toInt()
            }
            return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
    }
}
