package app.mangalens.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import androidx.core.content.res.ResourcesCompat
import app.mangalens.R
import app.mangalens.ocr.Balloon
import app.mangalens.ocr.BubbleKind

data class RenderBubble(
    val box: Rect,
    val translated: String,
    val original: String,
    val bgColor: Int,
    val textColor: Int,
    val vertical: Boolean,
    val kind: BubbleKind = BubbleKind.DIALOGUE,
    /**
     * The balloon this region was detected inside, when the page pixels
     * yielded one. Present, the overlay wipes the balloon's interior and
     * typesets into it; absent, the text floats on a rounded card. Anything
     * that translates [box] must translate [Balloon.box] with it, or the
     * cleaning lands where the balloon used to be.
     */
    val balloon: Balloon? = null,
)

/**
 * Full-screen, untouchable layer that puts the English on the page.
 *
 * A bubble that carries its detected [Balloon] is rendered the way a human
 * scanlation is made: the balloon interior is wiped to the sampled fill
 * through the balloon's own mask, and the translation is typeset into the
 * balloon in comic lettering. The old rounded card floated over the middle
 * of the balloon with the original lettering peeking out around it — the
 * single loudest "this is an AI overlay" signal on the page. A bubble with
 * no balloon (SFX captions, drifted extras, lettering on open art) keeps
 * the card: there is no interior to clean, and a card is better than
 * painting over art that was never proved to be a balloon.
 *
 * Mask stamps and layouts are built in [setBubbles]; [onDraw] only stamps
 * what was prepared, because it runs every animation frame while the cards
 * ride a scrolling strip.
 */
class BubbleOverlayView(context: Context) : View(context) {

    private class Placed(
        val bounds: RectF,
        val layout: StaticLayout,
        val textX: Float,
        val textY: Float,
        val bg: Int,
        val card: RectF? = null,
        val mask: Bitmap? = null,
        val maskDst: RectF? = null,
        val tint: PorterDuffColorFilter? = null,
    )

    private var placed: List<Placed> = emptyList()

    /**
     * Kept so cards can be laid out again once the view knows its real size.
     * [place] clamps each card inside the screen, and the first pass often runs
     * before layout, when the only width available is the display metric — on a
     * multi-window or letterboxed reader that is wider than the view, and a
     * card against the right edge is clipped.
     */
    private var source: List<RenderBubble> = emptyList()

    @Volatile var textScale = 1f
    @Volatile var bgOpacity = 1f

    /**
     * Vertical offset the capture service applies while the reader scrolls a
     * tracked strip, so cards ride the page they were typeset for instead of
     * hovering over whatever slid underneath them. Debug outlines are never
     * shifted — they describe the current frame, not the painted one.
     */
    @Volatile private var cardShiftY: Int = 0

    /**
     * Comic Neue is the lettering hand; the platform faces stand in when the
     * resource fails to inflate, so a broken font asset costs the page its
     * look but never its text. Loaded once — font inflation parses the file
     * on every call, and [place] runs on each translated page.
     */
    private val dialogueFace: Typeface =
        font(R.font.comic_neue_bold) ?: Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val sfxFace: Typeface =
        font(R.font.comic_neue_bold_italic) ?: Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC)

    private fun font(id: Int): Typeface? =
        runCatching { ResourcesCompat.getFont(context, id) }.getOrNull()

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = 0x2E000000
    }
    private val debugPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        color = 0xCCE6008C.toInt()
    }

    /**
     * Balloons the page detector found, outlined when diagnostics are on. An
     * untranslated balloon means something different depending on whether it
     * was outlined: a detection failure if not, a translation failure if so.
     */
    private var debugBalloons: List<Rect> = emptyList()

    fun setBubbles(bubbles: List<RenderBubble>) {
        source = bubbles
        placed = bubbles.mapNotNull { place(it) }
        invalidate()
    }

    fun setDebugBalloons(rects: List<Rect>) {
        debugBalloons = rects
        invalidate()
    }

    fun setCardShift(v: Int) {
        if (cardShiftY != v) {
            cardShiftY = v
            postInvalidateOnAnimation()
        }
    }

    fun clear() {
        source = emptyList()
        placed = emptyList()
        debugBalloons = emptyList()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (source.isEmpty()) return
        placed = source.mapNotNull { place(it) }
        invalidate()
    }

    fun hasBubbles() = placed.isNotEmpty()

    /**
     * Screen rectangles the overlay currently paints, with [cardShiftY]
     * applied. The capture pipeline masks these out when watching for a page
     * change and excludes them from detection — they are captured along with
     * the page, so a rect that understates the painting hides a change and a
     * stale one blinds the detector to a balloon. For a cleaned balloon this
     * is the balloon's box (grown by any text overhang), not the text rect.
     */
    fun placedRects(): List<Rect> {
        val dy = cardShiftY
        return placed.map {
            Rect(
                it.bounds.left.toInt(),
                it.bounds.top.toInt() + dy,
                it.bounds.right.toInt(),
                it.bounds.bottom.toInt() + dy,
            )
        }
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    private fun place(b: RenderBubble): Placed? {
        if (b.translated.isBlank()) return null
        val balloon = b.balloon
        if (balloon != null) {
            val stamp = erodedStamp(balloon)
            if (stamp != null) return placeClean(b, balloon, stamp)
        }
        return placeCard(b)
    }

    /**
     * The balloon interior as a tintable stamp, shrunk by one mask cell: a
     * cell survives only when all four neighbours are interior too, and the
     * mask border always dies. The ring this gives up is what keeps the
     * balloon's own outline stroke visible around the fill — a fill that
     * erases the outline reads as a hole punched in the page rather than a
     * cleaned balloon. Null when nothing survives (a sliver of a mask); that
     * bubble falls back to the rounded card instead of stamping nothing.
     */
    private fun erodedStamp(balloon: Balloon): Bitmap? {
        val w = balloon.maskW
        val h = balloon.maskH
        val mask = balloon.mask
        if (w < 3 || h < 3 || mask.size < w * h) return null
        val px = IntArray(w * h)
        var any = false
        for (y in 1 until h - 1) {
            var i = y * w + 1
            for (x in 1 until w - 1) {
                if (mask[i] && mask[i - 1] && mask[i + 1] && mask[i - w] && mask[i + w]) {
                    px[i] = Color.WHITE
                    any = true
                }
                i++
            }
        }
        if (!any) return null
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /**
     * Clean-and-typeset: fill through the mask, then set the translation the
     * way a letterer would — centered in the balloon, sized down from
     * generous until the block sits inside about 78% of the width and 80% of
     * the height, lines broken to [TypeSet]'s taper rather than greedily.
     * The fill is opaque by default; the whole point is that the original
     * lettering must not ghost through the English.
     */
    private fun placeClean(b: RenderBubble, balloon: Balloon, stamp: Bitmap): Placed? {
        val sfx = b.kind == BubbleKind.SFX
        val box = balloon.box
        val maxTextW = box.width() * 0.78f
        val maxTextH = box.height() * 0.80f

        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = b.textColor
            typeface = if (sfx) sfxFace else dialogueFace
        }
        var size = (box.height() * 0.24f / resources.displayMetrics.density)
            .coerceIn(15f, 34f) * textScale
        var layout: StaticLayout? = null
        while (true) {
            tp.textSize = dp(size)
            val lines = TypeSet.breakLines(b.translated, { s -> tp.measureText(s) }, maxTextW)
            var widest = 0f
            for (line in lines) widest = maxOf(widest, tp.measureText(line))
            val block = lines.joinToString("\n")
            val candidate = StaticLayout.Builder
                .obtain(block, 0, block.length, tp, (widest + 2f).toInt().coerceAtLeast(16))
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(0f, 1.06f)
                .setIncludePad(false)
                .build()
            layout = candidate
            if (widest <= maxTextW && candidate.height <= maxTextH) break
            if (size <= 9f) break
            size = (size - 1.25f).coerceAtLeast(9f)
        }
        val chosen = layout ?: return null

        val textX = box.exactCenterX() - chosen.width / 2f
        val textY = box.exactCenterY() - chosen.height / 2f
        // At the minimum type size a verbose line can still overrun the
        // balloon; the reported bounds must cover whatever was painted, not
        // whatever was hoped for.
        val bounds = RectF(box)
        bounds.union(textX, textY, textX + chosen.width, textY + chosen.height)

        val alpha = (255 * bgOpacity).toInt().coerceIn(70, 255)
        val fill = Color.argb(alpha, Color.red(b.bgColor), Color.green(b.bgColor), Color.blue(b.bgColor))
        return Placed(
            bounds = bounds,
            layout = chosen,
            textX = textX,
            textY = textY,
            bg = fill,
            mask = stamp,
            maskDst = RectF(box),
            tint = PorterDuffColorFilter(fill, PorterDuff.Mode.SRC_IN),
        )
    }

    private fun placeCard(b: RenderBubble): Placed? {
        val sfx = b.kind == BubbleKind.SFX
        val screenW = (if (width > 0) width else resources.displayMetrics.widthPixels).toFloat()
        val screenH = (if (height > 0) height else resources.displayMetrics.heightPixels).toFloat()
        val pad = if (sfx) dp(4f) else dp(7f)

        var boxW = b.box.width().toFloat()
        if (b.vertical) boxW = maxOf(boxW, b.box.height() * 0.85f)
        boxW = boxW.coerceAtLeast(if (sfx) dp(48f) else dp(88f)).coerceAtMost(screenW * 0.92f)
        val maxH = maxOf(b.box.height() + dp(26f), dp(64f))

        var layout: StaticLayout? = null
        var size = (if (sfx) 12f else 18f) * textScale
        while (size >= 9f) {
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (sfx) Color.WHITE else b.textColor
                textSize = dp(size)
                typeface = if (sfx) sfxFace else dialogueFace
            }
            val candidate = StaticLayout.Builder
                .obtain(b.translated, 0, b.translated.length, tp, (boxW - pad * 2).toInt().coerceAtLeast(40))
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(0f, 1.06f)
                .setIncludePad(false)
                .build()
            layout = candidate
            if (candidate.height <= maxH - pad * 2) break
            size -= 1.25f
        }
        val chosen = layout ?: return null

        var maxLine = 0f
        for (i in 0 until chosen.lineCount) maxLine = maxOf(maxLine, chosen.getLineWidth(i))
        val w = (maxLine + pad * 2).coerceAtLeast(dp(40f)).coerceAtMost(boxW + pad * 2)
        val h = chosen.height + pad * 2

        var left = b.box.centerX() - w / 2f
        var top = b.box.centerY() - h / 2f
        left = left.coerceAtMost(screenW - w - dp(2f)).coerceAtLeast(dp(2f))
        top = top.coerceAtMost(screenH - h - dp(2f)).coerceAtLeast(dp(2f))

        // SFX render as compact dark captions floating on the art, not as
        // page-colored patches — closer to how scanlators letter small SFX.
        val bg = if (sfx) {
            Color.argb(200, 24, 25, 40)
        } else {
            val alpha = (255 * bgOpacity).toInt().coerceIn(70, 255)
            Color.argb(alpha, Color.red(b.bgColor), Color.green(b.bgColor), Color.blue(b.bgColor))
        }
        val rect = RectF(left, top, left + w, top + h)
        return Placed(
            bounds = rect,
            layout = chosen,
            textX = rect.centerX() - chosen.width / 2f,
            textY = rect.top + pad,
            bg = bg,
            card = rect,
        )
    }

    override fun onDraw(canvas: Canvas) {
        val debug = debugBalloons
        for (i in 0 until debug.size) canvas.drawRect(debug[i], debugPaint)
        val list = placed
        if (list.isEmpty()) return
        val radius = dp(9f)
        canvas.save()
        canvas.translate(0f, cardShiftY.toFloat())
        for (i in 0 until list.size) {
            val p = list[i]
            if (p.mask != null && p.maskDst != null) {
                maskPaint.colorFilter = p.tint
                canvas.drawBitmap(p.mask, null, p.maskDst, maskPaint)
            } else if (p.card != null) {
                bgPaint.color = p.bg
                canvas.drawRoundRect(p.card, radius, radius, bgPaint)
                canvas.drawRoundRect(p.card, radius, radius, strokePaint)
            }
            canvas.save()
            canvas.translate(p.textX, p.textY)
            p.layout.draw(canvas)
            canvas.restore()
        }
        canvas.restore()
    }
}
