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

    /**
     * A white-backed panel holding two balloons — the arrangement at the top of
     * an ordinary manga page, and the one that merged two speakers into a
     * single card. The panel interior is itself an enclosed light region
     * bounded by ink, so nothing but its rectangularity and the balloons nested
     * inside it distinguishes it from a balloon.
     */
    private fun panelPage(): Pair<Bitmap, List<Rect>> {
        // Proportions matter: the panel has to be a believable share of the
        // page (about a quarter, as the top panel of a manga page is) and the
        // balloons a modest share of the panel. Make the panel too large and
        // the area cap rejects it for the wrong reason; make the balloons fill
        // it and the fill floor does — and then the fixture proves nothing.
        val w = 900
        val h = 1400
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)

        // Top panel: white inside, ruled border, roughly a quarter of the page.
        canvas.drawRect(Rect(40, 40, 860, 420), ink)

        val right = Rect(560, 110, 790, 290)
        val left = Rect(160, 140, 390, 320)
        balloon(canvas, right, columns = 4, narrowColumn = true)
        balloon(canvas, left, columns = 3, narrowColumn = false)
        return bmp to listOf(right, left)
    }

    @Test
    fun `a white panel holding balloons is not itself taken for a balloon`() {
        val (bmp, truth) = panelPage()
        val found = BalloonFinder.find(bmp)
        writePreview("panel.png", bmp, found)

        for (t in truth) {
            assertTrue("no balloon detected at $t (found $found)", found.any { matches(it, t) })
        }
        assertTrue(
            "the panel was reported as a balloon (found $found)",
            found.none { it.width() > 600 && it.height() > 300 },
        )
        assertEquals("expected exactly the two balloons, got $found", 2, found.size)
    }

    @Test
    fun `two speakers in one panel keep their own cards`() {
        val (bmp, truth) = panelPage()
        val found = BalloonFinder.find(bmp)

        // One OCR fragment per balloon, as the grouper would produce.
        val fragments = listOf(
            Bubble("あちちこのココア", Rect(520, 150, 750, 350), true),
            Bubble("そうこれGABAとか", Rect(160, 190, 370, 370), true),
        )
        val merged = BalloonMerge.apply(fragments, found, SourceLang.JA, includeEmpty = false)

        assertEquals("the two lines must not be welded into one, got $merged", 2, merged.size)
        assertTrue(
            "each speaker keeps their own text, got ${merged.map { it.text }}",
            merged.any { it.text == "あちちこのココア" } && merged.any { it.text == "そうこれGABAとか" },
        )
    }

    /**
     * A burst (shout) balloon: a white interior ringed by radiating tick
     * marks with open gaps between them, lettering inside. There is no drawn
     * boundary line at all — between the ticks the interior runs straight
     * into the page background, which is what let the flood leak out and the
     * balloon go undetected. These are the balloons whose translations then
     * vanished or floated to wherever a vision model guessed.
     */
    private fun burstPage(): Pair<Bitmap, Rect> {
        val w = 900
        val h = 500
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)

        val cx = 450f
        val cy = 250f
        val rx = 170f
        val ry = 110f
        val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f
            color = Color.BLACK
        }
        var a = 0.0
        while (a < 2 * Math.PI) {
            val dx = Math.cos(a)
            val dy = Math.sin(a)
            canvas.drawLine(
                (cx + rx * 0.98 * dx).toFloat(), (cy + ry * 0.98 * dy).toFloat(),
                (cx + rx * 1.14 * dx).toFloat(), (cy + ry * 1.14 * dy).toFloat(),
                tick,
            )
            // Roughly nine pixels of arc per step: a four-pixel tick, then an
            // open gap the flood used to escape through.
            a += 9.0 / (Math.sqrt((rx * rx * dy * dy + ry * ry * dx * dx)) + 1)
        }
        // Three rows of lettering, comfortably inside.
        for (r in 0 until 3) {
            val top = (cy - 52 + r * 38).toInt()
            canvas.drawRect(Rect((cx - 110).toInt(), top, (cx + 110).toInt(), top + 14), black)
        }
        return bmp to Rect(
            (cx - rx).toInt(), (cy - ry).toInt(), (cx + rx).toInt(), (cy + ry).toInt(),
        )
    }

    @Test
    fun `a burst balloon with a ticked border is still found`() {
        val (bmp, truth) = burstPage()
        val found = BalloonFinder.find(bmp)
        writePreview("burst.png", bmp, found)

        assertTrue(
            "the burst balloon at $truth must be detected (found $found)",
            found.any { matches(it, truth) },
        )
        assertTrue("page background reported as a balloon", found.none { it.width() > 880 })
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

    /**
     * A solid black narration box with light lettering rows — flashback
     * furniture. Its interior is exactly what the light-interior model calls
     * ink, so only the inverted pass can see it.
     */
    private fun invertedBox(canvas: Canvas, box: Rect, rows: Int) {
        canvas.drawRect(box, black)
        for (r in 0 until rows) {
            val top = box.top + 40 + r * 50
            canvas.drawRect(Rect(box.left + 40, top, box.right - 40, top + 22), white)
        }
    }

    @Test
    fun `a dark narration box with light lettering is found inverted`() {
        val bmp = Bitmap.createBitmap(900, 500, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        val box = Rect(200, 120, 700, 360)
        invertedBox(canvas, box, rows = 4)

        val balloons = BalloonFinder.findDetailed(bmp)
        writePreview("inverted.png", bmp, balloons.map { it.box })

        val hits = balloons.filter { matches(it.box, box) }
        assertEquals(
            "the narration box at $box must be detected exactly once, got ${balloons.map { it.box }}",
            1, hits.size,
        )
        assertTrue("a dark box carrying light lettering must be flagged inverted", hits[0].inverted)
        assertEquals(balloons.map { it.box }, BalloonFinder.find(bmp))
    }

    /**
     * A lone balloon with sparse lettering, for pinning the mask itself. The
     * column lettering the [balloon] helper draws lands its interiors near
     * the fill floor, where which pass accepts the component — and therefore
     * the exact mask — turns on antialiasing noise; sparse rows keep this one
     * an unambiguous first-pass find.
     */
    private fun lonePage(): Pair<Bitmap, Rect> {
        val bmp = Bitmap.createBitmap(900, 500, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        val box = Rect(280, 100, 620, 400)
        canvas.drawOval(RectF(box), white)
        canvas.drawOval(RectF(box), ink)
        for (r in 0 until 3) {
            val top = 195 + r * 40
            canvas.drawRect(Rect(370, top, 530, top + 20), black)
        }
        return bmp to box
    }

    @Test
    fun `the mask is the flooded interior, not the box`() {
        val (bmp, truth) = lonePage()
        val balloons = BalloonFinder.findDetailed(bmp)
        writePreview("mask.png", bmp, balloons.map { it.box })

        assertTrue(
            "no balloon detected at $truth (found ${balloons.map { it.box }})",
            balloons.any { matches(it.box, truth) },
        )
        val b = balloons.first { matches(it.box, truth) }
        assertEquals("a row-major mask covers exactly its dims", b.maskW * b.maskH, b.mask.size)

        // Masks live at the finder's analysis resolution: at most 640 pixels
        // on the page's long side.
        val scale = 640f / bmp.width
        assertEquals("mask width is the component's at work scale", b.box.width() * scale, b.maskW.toFloat(), 2f)
        assertEquals("mask height is the component's at work scale", b.box.height() * scale, b.maskH.toFloat(), 2f)

        val filled = b.mask.count { it }.toFloat() / b.mask.size
        assertTrue(
            "an ellipse fills its box partially, not fully, got $filled",
            filled > 0.55f && filled < 0.95f,
        )
    }

    /**
     * A page mixing polarities: the two smaller balloons of the plain-page
     * fixture beside a black narration box. Each pass must contribute its own
     * kind while the dedupe and container logic runs across all of them.
     */
    private fun mixedPage(): Triple<Bitmap, List<Rect>, Rect> {
        val bmp = Bitmap.createBitmap(900, 500, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)

        val mid = Rect(250, 90, 420, 300)
        val small = Rect(60, 60, 190, 200)
        balloon(canvas, mid, columns = 2, narrowColumn = false)
        balloon(canvas, small, columns = 1, narrowColumn = false)
        val box = Rect(500, 120, 860, 340)
        invertedBox(canvas, box, rows = 3)
        return Triple(bmp, listOf(mid, small), box)
    }

    @Test
    fun `an inverted box and ordinary balloons share a page`() {
        val (bmp, ellipses, darkBox) = mixedPage()
        val balloons = BalloonFinder.findDetailed(bmp)
        writePreview("mixed.png", bmp, balloons.map { it.box })

        for (t in ellipses) {
            assertTrue(
                "no ordinary balloon detected at $t (found ${balloons.map { it.box }})",
                balloons.any { matches(it.box, t) && !it.inverted },
            )
        }
        assertTrue(
            "the narration box at $darkBox must be found inverted",
            balloons.any { matches(it.box, darkBox) && it.inverted },
        )
        assertEquals(
            "each region exactly once across passes, got ${balloons.map { it.box }}",
            3, balloons.size,
        )
    }
}
