package app.mangalens.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import app.mangalens.settings.SourceLang
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Exercises balloon detection on a page built to reproduce the failure that
 * motivated it: a wide balloon whose vertical columns sit far enough apart to
 * cluster separately, with a narrow furigana column driving a further wedge
 * between two of them. Grouping by OCR proximity splits that balloon into
 * pieces and the translator then invents a line for each piece.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BalloonFinderTest {

    private val outputDir = File("build/balloon-preview").apply { mkdirs() }

    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        color = Color.BLACK
    }
    private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val black = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }

    /** Diagonal hatching, as heavy screentone reads to the detector. */
    private fun hatch(canvas: Canvas, area: Rect) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(40, 40, 40)
            strokeWidth = 3f
        }
        var x = area.left - area.height()
        while (x < area.right) {
            canvas.drawLine(
                x.toFloat(), area.top.toFloat(),
                (x + area.height()).toFloat(), area.bottom.toFloat(), p,
            )
            x += 9
        }
    }

    /** A balloon with vertical text columns; [narrowColumn] adds ruby. */
    private fun balloon(canvas: Canvas, box: Rect, columns: Int, narrowColumn: Boolean) {
        canvas.drawOval(RectF(box), white)
        canvas.drawOval(RectF(box), ink)
        val inner = Rect(
            box.left + box.width() / 6, box.top + box.height() / 6,
            box.right - box.width() / 6, box.bottom - box.height() / 6,
        )
        // Columns are laid right to left with a generous gap — the spacing
        // that defeats proximity clustering.
        val slot = inner.width() / columns
        for (c in 0 until columns) {
            val cx = inner.right - slot * c - slot / 2
            val narrow = narrowColumn && c == 1
            val halfW = if (narrow) slot / 10 else slot / 4
            val top = if (narrow) inner.top else inner.top + 4
            canvas.drawRect(
                Rect(cx - halfW, top, cx + halfW, inner.bottom - 4),
                black,
            )
        }
    }

    private fun page(toned: Boolean): Pair<Bitmap, List<Rect>> {
        val w = 900
        val h = 500
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        if (toned) hatch(canvas, Rect(0, 0, w, h))

        // One wide balloon of five columns (one of them furigana), plus two
        // small ones — the shape of the page that failed.
        val big = Rect(470, 60, 860, 430)
        val mid = Rect(250, 90, 420, 300)
        val small = Rect(60, 60, 190, 200)
        balloon(canvas, big, columns = 5, narrowColumn = true)
        balloon(canvas, mid, columns = 2, narrowColumn = false)
        balloon(canvas, small, columns = 1, narrowColumn = false)
        return bmp to listOf(big, mid, small)
    }

    private fun writePreview(name: String, bmp: Bitmap, found: List<Rect>) {
        val copy = bmp.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(copy)
        val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f
            color = Color.rgb(230, 0, 140)
        }
        found.forEach { canvas.drawRect(it, mark) }
        ByteArrayOutputStream().use { bos ->
            copy.compress(Bitmap.CompressFormat.PNG, 100, bos)
            File(outputDir, name).writeBytes(bos.toByteArray())
        }
        println("wrote ${File(outputDir, name).absolutePath}  (${found.size} balloons)")
    }

    /** True when [found] lands on [truth] — same balloon, allowing for the outline. */
    private fun matches(found: Rect, truth: Rect): Boolean {
        val ix = minOf(found.right, truth.right) - maxOf(found.left, truth.left)
        val iy = minOf(found.bottom, truth.bottom) - maxOf(found.top, truth.top)
        if (ix <= 0 || iy <= 0) return false
        val inter = ix.toLong() * iy
        val union = found.width().toLong() * found.height() +
            truth.width().toLong() * truth.height() - inter
        return inter.toFloat() / union > 0.55f
    }

    @Test
    fun `finds every balloon on a toned page`() {
        val (bmp, truth) = page(toned = true)
        val found = BalloonFinder.find(bmp)
        writePreview("toned.png", bmp, found)

        for (t in truth) {
            assertTrue(
                "no balloon detected at $t (found $found)",
                found.any { matches(it, t) },
            )
        }
    }

    @Test
    fun `finds every balloon on a plain white page`() {
        // The harder case: balloon interiors and the page background are both
        // white, separated only by the balloon outline. The flood must not
        // leak through it, and the background must not be reported.
        val (bmp, truth) = page(toned = false)
        val found = BalloonFinder.find(bmp)
        writePreview("plain.png", bmp, found)

        for (t in truth) {
            assertTrue(
                "no balloon detected at $t (found $found)",
                found.any { matches(it, t) },
            )
        }
        assertTrue("page background reported as a balloon", found.none { it.width() > 880 })
    }

    @Test
    fun `columns of one balloon collapse into a single region`() {
        val (bmp, truth) = page(toned = true)
        val big = truth[0]
        val found = BalloonFinder.find(bmp)

        // What proximity clustering produces for that balloon: one fragment
        // per column, each a separate region.
        val fragments = (0 until 5).map { c ->
            val slot = big.width() * 2 / 3 / 5
            val cx = big.right - big.width() / 6 - slot * c - slot / 2
            Bubble("列$c", Rect(cx - 12, big.top + 70, cx + 12, big.bottom - 70), true)
        }
        assertEquals("fixture should start fragmented", 5, fragments.size)

        val merged = BalloonMerge.apply(fragments, found, SourceLang.JA, includeEmpty = false)
        val inBig = merged.filter { matches(it.box, big) }

        assertEquals("the balloon should yield exactly one region, got $merged", 1, inBig.size)
        // Welded right-to-left, the order vertical Japanese is read in.
        assertEquals("列0列1列2列3列4", inBig[0].text)
    }

    @Test
    fun `a balloon OCR could not read still becomes a region`() {
        val (bmp, truth) = page(toned = true)
        val found = BalloonFinder.find(bmp)

        // OCR read one balloon and missed the rest, as it does on vertical
        // lettering. Without the pixels those balloons simply vanish.
        val onlyOne = listOf(Bubble("あん", Rect(80, 90, 170, 170), false))

        val withoutVision = BalloonMerge.apply(onlyOne, found, SourceLang.JA, includeEmpty = false)
        val withVision = BalloonMerge.apply(onlyOne, found, SourceLang.JA, includeEmpty = true)

        assertEquals("text-only engines cannot use blank regions", 1, withoutVision.size)
        assertTrue(
            "vision should be offered every balloon, got $withVision",
            withVision.size >= truth.size,
        )
        assertTrue("blank regions should carry no text", withVision.any { it.text.isBlank() })
    }
}
