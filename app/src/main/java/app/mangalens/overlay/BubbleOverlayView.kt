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
 * what was prepared, so a redraw is never more than blits and text.
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
        /**
         * Rectangle wiped to the sampled page color before the card paints —
         * the original lettering of an on-art vertical column, hidden without
         * demanding a card the column's own height.
         */
        val wipe: RectF? = null,
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
        placed = placeAll(bubbles)
        invalidate()
    }

    /**
     * Places bubbles in order, letting each card see the space already
     * claimed: on a dense page, cards centered on neighbouring columns land
     * on the same spot, and a stack of cards reads as one unreadable slab.
     */
    private fun placeAll(bubbles: List<RenderBubble>): List<Placed> {
        val out = ArrayList<Placed>(bubbles.size)
        val occupied = ArrayList<RectF>(bubbles.size)
        for (b in bubbles) {
            val p = place(b, occupied) ?: continue
            out.add(p)
            occupied.add(p.bounds)
        }
        return out
    }

    fun setDebugBalloons(rects: List<Rect>) {
        debugBalloons = rects
        invalidate()
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
        placed = placeAll(source)
        invalidate()
    }

    fun hasBubbles() = placed.isNotEmpty()

    /**
     * Screen rectangles the overlay currently paints. The capture pipeline
     * masks these out when watching for a page change and excludes them from
     * detection — they are captured along with the page, so a rect that
     * understates the painting hides a change beneath it. For a cleaned
     * balloon this is the balloon's box (grown by any text overhang), not
     * the text rect.
     */
    fun placedRects(): List<Rect> = placed.map {
        Rect(
            it.bounds.left.toInt(),
            it.bounds.top.toInt(),
            it.bounds.right.toInt(),
            it.bounds.bottom.toInt(),
        )
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    private fun place(b: RenderBubble, occupied: List<RectF>): Placed? {
        if (b.translated.isBlank()) return null
        val balloon = b.balloon
        if (balloon != null) {
            val stamp = erodedStamp(balloon)
            if (stamp != null) return placeClean(b, balloon, stamp)
        }
        return placeCard(b, occupied)
    }

    /**
     * The text color a fill can actually carry. The pipeline's colors are
     * sampled from the page, and a card whose sampled fill is near-black can
     * arrive paired with near-black text — invisible on the very panels
     * (night scenes, flashbacks) where cards appear most. This is the last
     * gate: whatever upstream decided, dark fills get light lettering and
     * light fills get dark, keeping the preferred color when it already
     * contrasts.
     */
    private fun readableText(bg: Int, preferred: Int): Int {
        val bgLum = (Color.red(bg) * 299 + Color.green(bg) * 587 + Color.blue(bg) * 114) / 1000
        val prefLum = (Color.red(preferred) * 299 + Color.green(preferred) * 587 + Color.blue(preferred) * 114) / 1000
        return if (bgLum < 140) {
            if (prefLum > 170) preferred else Color.rgb(244, 245, 248)
        } else {
            if (prefLum < 100) preferred else 0xFF17181C.toInt()
        }
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
            color = readableText(b.bgColor, b.textColor)
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

        // A cleaning is a replacement, not a patch: whatever the card-opacity
        // slider says — and devices upgraded from the patch era carry low
        // values in their saved settings — the original lettering must be
        // fully gone, or the balloon shows both languages interleaved.
        val fill = Color.argb(255, Color.red(b.bgColor), Color.green(b.bgColor), Color.blue(b.bgColor))
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

    private fun placeCard(b: RenderBubble, occupied: List<RectF>): Placed? {
        val sfx = b.kind == BubbleKind.SFX
        val screenW = (if (width > 0) width else resources.displayMetrics.widthPixels).toFloat()
        val screenH = (if (height > 0) height else resources.displayMetrics.heightPixels).toFloat()
        val pad = if (sfx) dp(4f) else dp(7f)

        // A tall on-art column gets the column treatment: the narrow column
        // itself is wiped to the sampled page color, and the English sits in
        // a compact horizontal card over it. The old rule — widen the card to
        // the column's height and grow it to bury the column — turned every
        // narration column into a card the size of the panel; a dense page
        // disappeared under its own translations.
        val column = !sfx && b.vertical &&
            b.box.height() > b.box.width() * 2.2f && b.box.height() > dp(120f)

        var boxW = b.box.width().toFloat()
        if (column) {
            boxW = screenW * 0.56f
        } else if (b.vertical) {
            boxW = maxOf(boxW, b.box.height() * 0.85f)
        }
        boxW = boxW.coerceAtLeast(if (sfx) dp(48f) else dp(88f)).coerceAtMost(screenW * 0.92f)
        val maxH = if (column) screenH * 0.38f else maxOf(b.box.height() + dp(26f), dp(64f))

        // Colors first: the text color depends on the fill it will sit on.
        // Dialogue cards paint effectively solid whatever the legacy opacity
        // slider saved — a see-through card over black art is how both
        // languages vanish at once — and SFX keep their dark caption style.
        val bg = if (sfx) {
            Color.argb(200, 24, 25, 40)
        } else {
            val alpha = (255 * bgOpacity).toInt().coerceIn(235, 255)
            Color.argb(alpha, Color.red(b.bgColor), Color.green(b.bgColor), Color.blue(b.bgColor))
        }
        val ink = if (sfx) Color.WHITE else readableText(bg, b.textColor)

        var layout: StaticLayout? = null
        var size = (if (sfx) 12f else 18f) * textScale
        while (size >= 9f) {
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ink
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
        // A dialogue card must bury the original block it floats on — CJK
        // runs taller than its translation, and a card sized to the text
        // leaves source lines peeking out. But burying is capped at half the
        // screen: past that the card is no longer covering a block, it is
        // covering the page. Columns don't bury at all — their wipe does it.
        val h = when {
            sfx || column -> chosen.height + pad * 2
            else -> maxOf(
                chosen.height + pad * 2,
                minOf(b.box.height() + dp(6f), screenH * 0.5f),
            )
        }

        var left = b.box.centerX() - w / 2f
        var top = b.box.centerY() - h / 2f
        left = left.coerceAtMost(screenW - w - dp(2f)).coerceAtLeast(dp(2f))
        top = top.coerceAtMost(screenH - h - dp(2f)).coerceAtLeast(dp(2f))

        val rect = RectF(left, top, left + w, top + h)
        nudgeClear(rect, occupied, screenH)

        val wipe = if (column) {
            RectF(b.box).apply { inset(-dp(2f), -dp(2f)) }
        } else {
            null
        }
        val bounds = RectF(rect)
        if (wipe != null) bounds.union(wipe)
        return Placed(
            bounds = bounds,
            layout = chosen,
            textX = rect.centerX() - chosen.width / 2f,
            textY = rect.centerY() - chosen.height / 2f,
            bg = bg,
            card = rect,
            wipe = wipe,
        )
    }

    /**
     * Shifts a card off cards already placed. Only meaningful overlap moves
     * it (over a third of the card's own area) — cards on a comic page brush
     * against each other constantly, and jittering every card for a grazing
     * corner would tear placements away from their balloons for nothing.
     */
    private fun nudgeClear(rect: RectF, occupied: List<RectF>, screenH: Float) {
        for (attempt in 0 until 3) {
            val hit = occupied.firstOrNull { other ->
                RectF.intersects(other, rect) && overlapShare(other, rect) > 0.35f
            } ?: return
            val below = hit.bottom + dp(4f)
            val above = hit.top - rect.height() - dp(4f)
            val top = when {
                rect.centerY() >= hit.centerY() && below + rect.height() <= screenH - dp(2f) -> below
                above >= dp(2f) -> above
                below + rect.height() <= screenH - dp(2f) -> below
                else -> return
            }
            rect.offsetTo(rect.left, top)
        }
    }

    /** Intersection area as a share of [self]'s area. */
    private fun overlapShare(other: RectF, self: RectF): Float {
        val ix = minOf(other.right, self.right) - maxOf(other.left, self.left)
        val iy = minOf(other.bottom, self.bottom) - maxOf(other.top, self.top)
        if (ix <= 0f || iy <= 0f) return 0f
        val area = self.width() * self.height()
        return if (area <= 0f) 0f else (ix * iy) / area
    }

    override fun onDraw(canvas: Canvas) {
        val debug = debugBalloons
        for (i in 0 until debug.size) canvas.drawRect(debug[i], debugPaint)
        val list = placed
        if (list.isEmpty()) return
        val radius = dp(9f)
        for (i in 0 until list.size) {
            val p = list[i]
            if (p.mask != null && p.maskDst != null) {
                maskPaint.colorFilter = p.tint
                canvas.drawBitmap(p.mask, null, p.maskDst, maskPaint)
            } else if (p.card != null) {
                p.wipe?.let { wipe ->
                    bgPaint.color = Color.argb(
                        255, Color.red(p.bg), Color.green(p.bg), Color.blue(p.bg)
                    )
                    canvas.drawRoundRect(wipe, dp(3f), dp(3f), bgPaint)
                }
                bgPaint.color = p.bg
                canvas.drawRoundRect(p.card, radius, radius, bgPaint)
                // The hairline must contrast with the fill it outlines, or a
                // page-black card on a black panel has no edge at all.
                val lum = (Color.red(p.bg) * 299 + Color.green(p.bg) * 587 + Color.blue(p.bg) * 114) / 1000
                strokePaint.color = if (lum < 140) 0x59FFFFFF else 0x2E000000
                canvas.drawRoundRect(p.card, radius, radius, strokePaint)
            }
            canvas.save()
            canvas.translate(p.textX, p.textY)
            p.layout.draw(canvas)
            canvas.restore()
        }
    }
}
