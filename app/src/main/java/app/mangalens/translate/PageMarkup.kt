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
