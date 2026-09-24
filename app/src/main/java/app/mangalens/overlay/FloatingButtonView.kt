package app.mangalens.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AnimationUtils
import androidx.core.content.res.ResourcesCompat
import app.mangalens.R

/**
 * The draggable 文A control, drawn on canvas so its two states can cross-fade
 * instead of snapping between backgrounds. LIVE is a flat yellow disc with an
 * ink rim and ink glyph: yellow against ink reads over a white page, a black
 * page and screentone alike, which a tinted or translucent disc never did.
 * PAUSED is a dark disc under a grey rim, with a small yellow "z" so the
 * state never rests on colour alone.
 *
 * The drop shadow is drawn, not an elevation: the button then costs no extra
 * outline layer, and the shadow stays the same hard, offset one the app uses.
 * Every paint derives from [liveness], so the cross-fade moves them together.
 *
 * Invalidation is demand-driven: the animators invalidate from their update
 * listeners and the busy ring re-posts itself from [onDraw] only while a pass
 * is running (and only when animations are on), so an idle button draws
 * nothing and costs no frames. [onDraw] allocates nothing.
 *
 * All geometry is inset from the view bounds far enough for the tap pulse's
 * 1.06 overshoot — the parent row clips children at its own edge, and a pulse
 * that outgrows the view loses its rim exactly at the moment it should pop.
 */
class FloatingButtonView(context: Context) : View(context) {

    private var paused = false
    private var busy = false
    private var manual = false

    /** 1 = live colors, 0 = paused; the one value every paint derives from. */
    private var liveness = 1f
    private var pulseScale = 1f

    private var stateAnim: ValueAnimator? = null
    private var pulseAnim: ValueAnimator? = null

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SHADOW }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f, resources.displayMetrics)
    }
    private val napPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = NAP_Z
        textAlign = Paint.Align.CENTER
        typeface = runCatching { ResourcesCompat.getFont(context, R.font.comic_neue_bold) }.getOrNull()
            ?: Typeface.create("sans-serif-medium", Typeface.BOLD)
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10f, resources.displayMetrics)
    }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(4.5f)
        strokeCap = Paint.Cap.ROUND
        color = RING_HALO
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
        strokeCap = Paint.Cap.ROUND
        color = RING
    }

    init {
        updateDescription()
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    private fun updateDescription() {
        contentDescription = when {
            paused -> "Translation paused: tap to resume"
            manual -> "Tap to translate this page"
            else -> "Translation on: tap to pause"
        }
    }

    /**
     * Cross-fades to the [paused] look. Restarting from the current mix, not
     * from an endpoint, keeps a quick double-tap from flashing back to a color
     * the fade had already left behind.
     */
    fun setPaused(paused: Boolean) {
        if (this.paused == paused) return
        this.paused = paused
        updateDescription()
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

    /**
     * In tap-to-translate mode a tap translates the page rather than
     * pausing, so the button says so to screen readers too.
     */
    fun setManual(manual: Boolean) {
        if (this.manual == manual) return
        this.manual = manual
        updateDescription()
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
        val discR = ringR - dp(3.5f)

        canvas.save()
        canvas.scale(pulseScale, pulseScale, cx, cy)
        canvas.drawCircle(cx, cy + dp(2f), discR, shadowPaint)
        fillPaint.color = blendArgb(PAUSED_FILL, LIVE_FILL, liveness)
        canvas.drawCircle(cx, cy, discR, fillPaint)
        rimPaint.color = blendArgb(PAUSED_STROKE, LIVE_STROKE, liveness)
        canvas.drawCircle(cx, cy, discR, rimPaint)
        glyphPaint.color = blendArgb(PAUSED_GLYPH, LIVE_GLYPH, liveness)
        canvas.drawText(GLYPH, cx, cy - (glyphPaint.ascent() + glyphPaint.descent()) / 2f, glyphPaint)
        val napAlpha = ((1f - liveness) * 255f).toInt().coerceIn(0, 255)
        if (napAlpha > 0) {
            napPaint.alpha = napAlpha
            canvas.drawText("z", cx + 0.62f * discR, cy - 0.55f * discR, napPaint)
        }
        canvas.restore()

        // The ring stays outside the pulse transform: it reports pipeline
        // progress, and progress does not flinch when the button is pressed.
        // With animations off it holds still rather than spinning.
        if (busy) {
            val animate = ValueAnimator.areAnimatorsEnabled()
            val start = if (animate) (AnimationUtils.currentAnimationTimeMillis() % 1000L) * 0.36f else -90f
            canvas.drawArc(cx - ringR, cy - ringR, cx + ringR, cy + ringR, start, 270f, false, haloPaint)
            canvas.drawArc(cx - ringR, cy - ringR, cx + ringR, cy + ringR, start, 270f, false, ringPaint)
            if (animate) postInvalidateOnAnimation()
        }
    }

    companion object {
        private const val GLYPH = "文A"

        internal const val LIVE_FILL = 0xF2FFCC1A.toInt()
        internal const val LIVE_STROKE = 0xFF1C1424.toInt()
        internal const val LIVE_GLYPH = 0xFF1C1424.toInt()
        internal const val PAUSED_FILL = 0xE62A2230.toInt()
        internal const val PAUSED_STROKE = 0xFF8C8292.toInt()
        internal const val PAUSED_GLYPH = 0xFFFFF4DC.toInt()
        private const val SHADOW = 0x801C1424.toInt()
        private const val NAP_Z = 0xFFFFCC1A.toInt()
        private const val RING_HALO = 0x731C1424
        private const val RING = 0xFFD92B17.toInt()

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
