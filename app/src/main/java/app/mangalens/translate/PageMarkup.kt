package app.mangalens.translate

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.Base64
import app.mangalens.ocr.Bubble
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Draws region ids onto the page before it is sent to the vision model.
 *
 * The model is asked to answer per region id, but previously it had to work
 * out which text a given id referred to by matching normalized box
 * coordinates against what it saw — and matching coordinates to positions is
 * exactly the thing vision models are least reliable at. When that mapping
 * slips, every downstream answer is attached to the wrong balloon: the text is
 * read correctly and then painted over someone else's line.
 *
 * Marking the image removes the inference entirely. Each detected region gets
 * a thin outline and a numbered badge drawn at its corner, so "region 7" is
 * something the model can *see* next to the text rather than compute. This is
 * set-of-mark visual prompting, and it is the highest-leverage change
 * available here: published manga-translation results put a numbered-overlay
 * page ahead of every text-only and plain-image variant tested.
 *
 * Badges straddle the region's top-left corner rather than covering it — the
 * model still has to read the original lettering underneath, so the marks are
 * kept off the body of the text.
 */
object PageMarkup {

    /**
     * Scales the page down for upload and marks each anchor on it.
     *
     * @return base64 JPEG of the marked page, ready for the provider payload.
     */
    fun encodeMarkedPage(
        bitmap: Bitmap,
        anchors: List<Bubble>,
        dataSaver: Boolean,
    ): String {
        val maxDim = if (dataSaver) 1000 else 1400
        val quality = if (dataSaver) 55 else 72
        val scale = (maxDim.toFloat() / max(bitmap.width, bitmap.height)).coerceAtMost(1f)

        val w = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
        val h = (bitmap.height * scale).roundToInt().coerceAtLeast(1)

        // createScaledBitmap hands back the source unchanged at scale 1, and a
        // captured frame may be hardware-backed or immutable either way — so
        // draw only ever happens on a copy we own.
        val resized = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, w, h, true) else bitmap
        val canvasBitmap = if (resized !== bitmap && resized.isMutable) {
            resized
        } else {
            resized.copy(Bitmap.Config.ARGB_8888, true)
                ?: return VisionLlmEngine.encodePage(bitmap, dataSaver)
        }
        if (resized !== bitmap && resized !== canvasBitmap) resized.recycle()

        val marked = runCatching {
            drawMarks(canvasBitmap, anchors, scale)
            encodeJpeg(canvasBitmap, quality)
        }.getOrElse {
            // Marking is an enhancement, not a requirement: an unmarked page
            // still translates, just without the id anchoring.
            encodeJpeg(canvasBitmap, quality)
        }
        canvasBitmap.recycle()
        return marked
    }

    /**
     * Enlarged close-ups of the regions in [ids], each badged with its own
     * id in the same style as the page marks, as base64 JPEGs.
     *
     * The page goes up at 1400 pixels on its long side, which turns the
     * lettering of a balloon on a tablet capture into glyphs a dozen pixels
     * tall. A model that could read the stylised or vertical text at full
     * size is reduced to guessing at it, and a stronger model gains nothing
     * over a weaker one. The close-ups are cut from the full-resolution
     * frame, so the regions on-device OCR could not read reach the model
     * legible — the exact regions where its reading matters.
     */
    fun encodeRegionCrops(
        bitmap: Bitmap,
        anchors: List<Bubble>,
        ids: List<Int>,
        dataSaver: Boolean,
    ): List<String> {
        val maxDim = if (dataSaver) 384 else 768
        val quality = if (dataSaver) 62 else 80
        val out = ArrayList<String>(ids.size)
        for (id in ids) {
            val box = anchors.getOrNull(id)?.box ?: continue
            val pad = (max(box.width(), box.height()) * 0.08f).toInt().coerceAtLeast(6)
            val crop = Rect(
                (box.left - pad).coerceIn(0, bitmap.width),
                (box.top - pad).coerceIn(0, bitmap.height),
                (box.right + pad).coerceIn(0, bitmap.width),
                (box.bottom + pad).coerceIn(0, bitmap.height),
            )
            if (crop.width() < 8 || crop.height() < 8) continue
            val scale = (maxDim.toFloat() / max(crop.width(), crop.height())).coerceAtMost(1f)
            val w = (crop.width() * scale).roundToInt().coerceAtLeast(1)
            val h = (crop.height() * scale).roundToInt().coerceAtLeast(1)
            val cut = Bitmap.createBitmap(bitmap, crop.left, crop.top, crop.width(), crop.height())
            val sized = if (scale < 1f) Bitmap.createScaledBitmap(cut, w, h, true) else cut
            val canvasBitmap = if (sized !== bitmap && sized.isMutable) sized else sized.copy(Bitmap.Config.ARGB_8888, true)
            if (sized !== cut) cut.recycle()
            if (canvasBitmap !== sized) sized.recycle()
            if (canvasBitmap == null) continue
            runCatching { drawBadge(canvasBitmap, id) }
            out.add(encodeJpeg(canvasBitmap, quality))
            canvasBitmap.recycle()
        }
        return out
    }

    /** The region's badge at a close-up's top-left corner. */
    private fun drawBadge(target: Bitmap, id: Int) {
        val canvas = Canvas(target)
        val badge = (max(target.width, target.height) * 0.07f).coerceIn(16f, 34f)
        val plate = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = MARK_COLOR
        }
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = badge * 0.72f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        val r = badge / 2f
        canvas.drawRoundRect(RectF(0f, 0f, badge, badge), r * 0.35f, r * 0.35f, plate)
        canvas.drawText(id.toString(), r, r + label.textSize * 0.35f, label)
    }

    private fun drawMarks(target: Bitmap, anchors: List<Bubble>, scale: Float) {
        if (anchors.isEmpty()) return
        val canvas = Canvas(target)

        // Badge size tracks the page so marks stay legible after downscaling
        // without swallowing small balloons.
        val badge = (max(target.width, target.height) * 0.026f).coerceIn(13f, 30f)
        val stroke = (badge * 0.11f).coerceAtLeast(1.4f)

        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            color = MARK_COLOR
        }
        val plate = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = MARK_COLOR
        }
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = badge * 0.72f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }

        anchors.forEachIndexed { id, b ->
            val box = scaled(b.box, scale, target.width, target.height) ?: return@forEachIndexed
            canvas.drawRect(box, outline)

            // Anchor the badge on the top-left corner so it overlaps the
            // balloon's border, not the lettering inside it.
            var cx = box.left.toFloat()
            var cy = box.top.toFloat()
            val r = badge / 2f
            cx = cx.coerceIn(r, target.width - r)
            cy = cy.coerceIn(r, target.height - r)

            canvas.drawRoundRect(
                RectF(cx - r, cy - r, cx + r, cy + r),
                r * 0.35f, r * 0.35f, plate,
            )
            // drawText places the baseline, so nudge down by roughly half the
            // cap height to sit the digits visually centred in the plate.
            canvas.drawText(id.toString(), cx, cy + label.textSize * 0.35f, label)
        }
    }

    private fun scaled(box: Rect, scale: Float, w: Int, h: Int): Rect? {
        val r = Rect(
            (box.left * scale).roundToInt().coerceIn(0, w),
            (box.top * scale).roundToInt().coerceIn(0, h),
            (box.right * scale).roundToInt().coerceIn(0, w),
            (box.bottom * scale).roundToInt().coerceIn(0, h),
        )
        return if (r.width() < 2 || r.height() < 2) null else r
    }

    private fun encodeJpeg(bitmap: Bitmap, quality: Int): String {
        val bytes = ByteArrayOutputStream().use { bos ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, bos)
            bos.toByteArray()
        }
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    /**
     * Saturated magenta: absent from ink, screentone and skin, so a mark is
     * never mistaken for art the model should be reading.
     */
    private val MARK_COLOR = Color.rgb(230, 0, 140)
}
