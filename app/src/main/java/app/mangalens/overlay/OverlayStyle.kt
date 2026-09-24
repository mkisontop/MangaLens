package app.mangalens.overlay

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import app.mangalens.R

/**
 * The look of the overlay's status pill and quick menu, in the app's POP!
 * style: ink, cream and Comic Neue. The pill is a dark capsule with a faint
 * cream edge, so it reads over white pages and black ones; the menu is a
 * cream sticker on a hard ink shadow, like the stickers in the app.
 *
 * Kept apart from [OverlayController] so the views can be built and drawn
 * without a window manager, which is how the tests render them.
 */
internal object OverlayStyle {

    const val PILL_BG = 0xEB1C1424.toInt()
    const val PILL_EDGE = 0x33FFF4DC
    const val PILL_TEXT = 0xFFFFF4DC.toInt()
    const val PILL_ALERT = 0xFFFFCC1A.toInt()
    const val MENU_PAPER = 0xFFFFF4DC.toInt()
    const val MENU_INK = 0xFF1C1424.toInt()
    const val MENU_PRESSED = 0xFFFFEFB0.toInt()
    const val MENU_STOP = 0xFFB81F0F.toInt()

    private fun dp(context: Context, v: Float): Int = (v * context.resources.displayMetrics.density).toInt()

    private fun comicBold(context: Context): Typeface? =
        runCatching { ResourcesCompat.getFont(context, R.font.comic_neue_bold) }.getOrNull()

    /** The status pill beside the button: up to two short lines, never wider than 240dp. */
    fun statusPill(context: Context): TextView = TextView(context).apply {
        setTextColor(PILL_TEXT)
        textSize = 12f
        comicBold(context)?.let { typeface = it }
        maxLines = 2
        maxWidth = dp(context, 240f)
        setPadding(dp(context, 12f), dp(context, 6f), dp(context, 12f), dp(context, 6f))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(context, 100f).toFloat()
            setColor(PILL_BG)
            setStroke(dp(context, 1f).coerceAtLeast(1), PILL_EDGE)
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.marginStart = dp(context, 6f)
        layoutParams = lp
    }

    /** Sets the pill's words; an alert (it starts with ⚠) turns yellow so it stands out from chatter. */
    fun showStatus(pill: TextView, text: String) {
        pill.text = text
        pill.setTextColor(if (text.startsWith("⚠")) PILL_ALERT else PILL_TEXT)
    }

    /** The quick menu's panel: a cream card on a hard ink shadow, drawn as two layers. */
    fun menuPanel(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val radius = dp(context, 16f).toFloat()
        val shadow = GradientDrawable().apply {
            cornerRadius = radius
            setColor(MENU_INK)
        }
        val panel = GradientDrawable().apply {
            cornerRadius = radius
            setColor(MENU_PAPER)
            setStroke(dp(context, 2f), MENU_INK)
        }
        val d = dp(context, 4f)
        background = LayerDrawable(arrayOf(shadow, panel)).apply {
            setLayerInset(0, d, d, 0, 0)
            setLayerInset(1, 0, 0, d, d)
        }
        elevation = 0f
        setPadding(0, dp(context, 6f), d, dp(context, 10f))
    }

    /** One menu row: a full 48dp target, with a pale yellow press state. */
    fun menuRow(context: Context, label: String, color: Int = MENU_INK): TextView = TextView(context).apply {
        text = label
        setTextColor(color)
        textSize = 15f
        comicBold(context)?.let { typeface = it }
        minHeight = dp(context, 48f)
        minWidth = dp(context, 232f)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(context, 16f), 0, dp(context, 16f), 0)
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(MENU_PRESSED))
            addState(intArrayOf(), ColorDrawable(0))
        }
        isClickable = true
        isFocusable = true
    }

    /**
     * The ink rule that sets "Stop" apart from the everyday items. It
     * claims no width of its own: a plain view asked to wrap takes all the
     * room it is offered, and the menu would then stretch across the
     * screen instead of hugging its longest item.
     */
    fun menuDivider(context: Context): View = object : View(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY) {
                MeasureSpec.getSize(widthMeasureSpec)
            } else 0
            setMeasuredDimension(w, getDefaultSize(suggestedMinimumHeight, heightMeasureSpec))
        }
    }.apply {
        setBackgroundColor(MENU_INK)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 2f)).apply {
            setMargins(dp(context, 12f), dp(context, 4f), dp(context, 12f), dp(context, 4f))
        }
    }
}
