package app.mangalens.pipeline

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import app.mangalens.ocr.BubbleGrouper
import app.mangalens.ocr.OcrEngine
import app.mangalens.overlay.RenderBubble
import app.mangalens.settings.AppSettings
import app.mangalens.translate.TranslationService

/**
 * One pass over one stable frame: OCR -> group into bubbles -> translate ->
 * sample bubble background colors for seamless-looking patches.
 */
class TranslatePipeline(
    private val ocr: OcrEngine,
    private val translation: TranslationService,
) {

    data class PageResult(
        val bubbles: List<RenderBubble>,
        val engineLabel: String,
        val note: String?,
    )

    suspend fun process(bitmap: Bitmap, settings: AppSettings): PageResult {
        val ocrResult = ocr.recognize(bitmap, settings.sourceLang)
        val ignoreTop = (bitmap.height * settings.ignoreTopPct).toInt()
        val ignoreBottom = (bitmap.height * settings.ignoreBottomPct).toInt()
        val bubbles = BubbleGrouper.group(ocrResult.lines, bitmap.height, ignoreTop, ignoreBottom, ocrResult.lang)
        if (bubbles.isEmpty()) return PageResult(emptyList(), "", null)

        val outcome = translation.translate(bubbles.map { it.text }, ocrResult.lang, settings)

        val rendered = bubbles.mapIndexed { i, b ->
            val bg = sampleBackground(bitmap, b.box)
            RenderBubble(
                box = Rect(b.box),
                translated = outcome.texts[i],
                original = b.text,
                bgColor = bg,
                textColor = if (luminance(bg) < 140) Color.WHITE else 0xFF17181C.toInt(),
                vertical = b.vertical,
            )
        }
        return PageResult(rendered, outcome.engineLabel, outcome.note)
    }

    private fun luminance(c: Int) = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000

    /** Averages the pixels in a thin ring just outside the text box. */
    private fun sampleBackground(bmp: Bitmap, box: Rect): Int {
        val left = (box.left - 8).coerceIn(0, bmp.width - 1)
        val top = (box.top - 8).coerceIn(0, bmp.height - 1)
        val right = (box.right + 8).coerceIn(0, bmp.width - 1)
        val bottom = (box.bottom + 8).coerceIn(0, bmp.height - 1)
        var r = 0L
        var g = 0L
        var b = 0L
        var count = 0

        fun sample(x: Int, y: Int) {
            val p = bmp.getPixel(x, y)
            r += Color.red(p)
            g += Color.green(p)
            b += Color.blue(p)
            count++
        }

        var x = left
        while (x <= right) {
            sample(x, top)
            sample(x, bottom)
            x += 4
        }
        var y = top
        while (y <= bottom) {
            sample(left, y)
            sample(right, y)
            y += 4
        }
        if (count == 0) return Color.WHITE
        val avg = Color.rgb((r / count).toInt(), (g / count).toInt(), (b / count).toInt())
        // Most bubbles are white; snap near-white samples to pure white for a clean look.
        return if (luminance(avg) > 190) Color.WHITE else avg
    }
}
