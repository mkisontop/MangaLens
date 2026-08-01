package app.mangalens.overlay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import app.mangalens.ocr.Balloon
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Draws the overlay against a synthetic page and reads the pixels back: the
 * lettering inside a cleaned balloon must be gone, the English must be inside
 * the balloon, and the balloon's own outline must survive the fill. The
 * original lettering is drawn in a dark red no rendered layer uses, so any
 * pixel still that color is proof the page shows through where it must not.
 *
 * TypeSet is exercised with a one-unit-per-character measure, where line
 * widths are checkable by eye.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScanlationRenderTest {

    private val outputDir = File("build/render-preview").apply { mkdirs() }

    private val pageW = 720
    private val pageH = 900
    private val box = Rect(160, 180, 560, 460)

    /** Dark enough to read as lettering, but a color nothing we render emits. */
    private val letteringInk = Color.rgb(176, 0, 0)

    /**
     * Rows of "lettering" kept well inside the ellipse. The middle row runs
     * far left of where the typeset block can reach (78% of the box), so one
     * of its pixels is guaranteed to show pure fill after cleaning rather
     * than fill-or-text.
     */
    private val lettering = listOf(
        Rect(box.left + 130, box.top + 86, box.right - 130, box.top + 106),
        Rect(box.left + 28, box.centerY() - 10, box.left + 172, box.centerY() + 10),
        Rect(box.left + 130, box.bottom - 106, box.right - 130, box.bottom - 86),
    )

    private fun ellipseBalloon(): Balloon {
        val mw = 100
        val mh = 70
        val mask = BooleanArray(mw * mh)
        for (cy in 0 until mh) {
            for (cx in 0 until mw) {
                val nx = (cx + 0.5f) / mw * 2f - 1f
                val ny = (cy + 0.5f) / mh * 2f - 1f
                mask[cy * mw + cx] = nx * nx + ny * ny <= 1f
            }
        }
        return Balloon(box, mw, mh, mask, false)
    }

    private fun page(): Bitmap {
        val bmp = Bitmap.createBitmap(pageW, pageH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 6f
            color = Color.BLACK
        }
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = letteringInk }
        canvas.drawOval(RectF(box), white)
        canvas.drawOval(RectF(box), outline)
        for (r in lettering) canvas.drawRect(r, ink)
        return bmp
    }

    private fun view(): BubbleOverlayView =
        BubbleOverlayView(RuntimeEnvironment.getApplication()).apply { layout(0, 0, pageW, pageH) }

    private fun bubble(text: String) = RenderBubble(
        box = Rect(box),
        translated = text,
        original = "元のセリフ",
        bgColor = Color.WHITE,
        textColor = 0xFF17181C.toInt(),
        vertical = true,
        balloon = ellipseBalloon(),
    )

    private fun luminance(c: Int) =
        (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000

    private fun isLetteringInk(c: Int) =
        Color.red(c) >= 140 && Color.green(c) <= 60 && Color.blue(c) <= 60

    private fun pixels(bmp: Bitmap): IntArray {
        val px = IntArray(pageW * pageH)
        bmp.getPixels(px, 0, pageW, 0, 0, pageW, pageH)
        return px
    }

    private fun darkCount(px: IntArray, area: Rect): Int {
        var n = 0
        for (y in area.top until area.bottom) {
            val row = y * pageW
            for (x in area.left until area.right) {
                val c = px[row + x]
                if (Color.red(c) < 90 && Color.green(c) < 90 && Color.blue(c) < 90) n++
            }
        }
        return n
    }

    private fun writePreview(name: String, bmp: Bitmap) {
        ByteArrayOutputStream().use { bos ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
            File(outputDir, name).writeBytes(bos.toByteArray())
        }
        println("wrote ${File(outputDir, name).absolutePath}")
    }

    @Test
    fun `a balloon is wiped clean and retypeset`() {
        val v = view()
        v.setBubbles(listOf(bubble("I NEVER ASKED FOR THIS")))
        val out = page()
        v.draw(Canvas(out))
        writePreview("cleaned.png", out)
        val px = pixels(out)

        assertEquals(
            "a cleaned lettering pixel clear of the text block must be exactly the fill",
            Color.WHITE,
            out.getPixel(box.left + 32, box.centerY()),
        )

        var leftover = 0
        for (c in px) if (isLetteringInk(c)) leftover++
        assertEquals("original lettering must not survive anywhere on the page", 0, leftover)

        val center = Rect(box.centerX() - 150, box.centerY() - 90, box.centerX() + 150, box.centerY() + 90)
        val textPixels = darkCount(px, center)
        assertTrue("typeset text must land inside the balloon (found $textPixels dark px)", textPixels > 40)

        assertTrue(
            "the balloon's own outline stroke must survive the fill",
            luminance(out.getPixel(box.left + 1, box.centerY())) < 100,
        )
    }

    /**
     * The field failure this pins: devices upgraded from the patch era carry
     * a low card-opacity value in their saved settings, and honoring it in
     * cleaning mode painted the fill translucent — both languages showing
     * interleaved in one balloon. A cleaning is a replacement; the slider
     * belongs to floating cards only.
     */
    @Test
    fun `a low card opacity setting cannot make the cleaning translucent`() {
        val v = view()
        v.bgOpacity = 0.6f
        v.setBubbles(listOf(bubble("I NEVER ASKED FOR THIS")))
        val out = page()
        v.draw(Canvas(out))
        writePreview("cleaned-low-opacity.png", out)
        val px = pixels(out)

        assertEquals(
            "the fill must stay exactly opaque paper under a low slider",
            Color.WHITE,
            out.getPixel(box.left + 32, box.centerY()),
        )
        var leftover = 0
        for (c in px) if (isLetteringInk(c)) leftover++
        assertEquals("no original lettering may bleed through the fill", 0, leftover)
    }

    @Test
    fun `a bubble without a balloon keeps the rounded card`() {
        val v = view()
        v.setBubbles(
            listOf(
                RenderBubble(
                    box = Rect(200, 600, 420, 700),
                    translated = "OKAY",
                    original = "はい",
                    bgColor = Color.WHITE,
                    textColor = 0xFF17181C.toInt(),
                    vertical = false,
                )
            )
        )
        val rects = v.placedRects()
        assertEquals(1, rects.size)
        val card = rects[0]
        assertTrue("cards stay sized to the text, not to the box", card.width() < 220)

        val out = Bitmap.createBitmap(pageW, pageH, Bitmap.Config.ARGB_8888)
        Canvas(out).drawColor(Color.rgb(128, 128, 128))
        v.draw(Canvas(out))
        writePreview("card.png", out)

        assertTrue(
            "the card fill must paint opaquely by default",
            luminance(out.getPixel(card.left + 4, card.centerY())) > 200,
        )
        assertTrue(
            "outside the card the page persists",
            luminance(out.getPixel(card.right + 30, card.centerY())) in 100..160,
        )

        v.clear()
        assertTrue(v.placedRects().isEmpty())
        assertTrue(!v.hasBubbles())
    }

    /**
     * The night-scene failure: a card whose sampled fill is near-black
     * arriving with near-black text painted an invisible translation onto a
     * black panel. The fill must paint effectively solid, and the lettering
     * must be light on a dark fill no matter what color upstream chose.
     */
    @Test
    fun `a dark card on a dark panel stays readable`() {
        val v = view()
        v.bgOpacity = 0.6f
        v.setBubbles(
            listOf(
                RenderBubble(
                    box = Rect(200, 600, 460, 700),
                    translated = "IT IS PITCH DARK IN HERE",
                    original = "여긴 너무 어두워",
                    bgColor = Color.rgb(12, 12, 16),
                    textColor = Color.rgb(20, 20, 24),
                    vertical = false,
                )
            )
        )
        val out = Bitmap.createBitmap(pageW, pageH, Bitmap.Config.ARGB_8888)
        Canvas(out).drawColor(Color.BLACK)
        v.draw(Canvas(out))
        writePreview("dark-card.png", out)

        val card = v.placedRects().single()
        var light = 0
        for (y in card.top until card.bottom) {
            for (x in card.left until card.right) {
                if (luminance(out.getPixel(x, y)) > 170) light++
            }
        }
        assertTrue("light lettering must exist on the dark card (found $light px)", light > 60)
    }

    /**
     * The dense-page failure: a tall on-art narration column used to demand
     * a card its own height and nearly the screen's width, and a page of
     * columns vanished under its own translations. The column treatment
     * wipes the narrow column and floats a compact card instead.
     */
    @Test
    fun `a tall vertical column gets a wipe and a compact card, not a blanket`() {
        val v = view()
        val column = Rect(560, 120, 640, 780)
        val page = Bitmap.createBitmap(pageW, pageH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(page)
        canvas.drawColor(Color.WHITE)
        val ink = Paint().apply { color = letteringInk }
        for (y in column.top + 10 until column.bottom - 10 step 40) {
            canvas.drawRect(Rect(column.left + 14, y, column.right - 14, y + 24), ink)
        }
        v.setBubbles(
            listOf(
                RenderBubble(
                    box = Rect(column),
                    translated = "AS THE YEARS WENT BY HE GREW INTO AN ACTIVE FIGURE WHOSE NAME EVERYONE KNEW",
                    original = "縦書きの長い語り",
                    bgColor = Color.WHITE,
                    textColor = Color.BLACK,
                    vertical = true,
                )
            )
        )
        v.draw(canvas)
        writePreview("column.png", page)

        var leftover = 0
        val px = pixels(page)
        for (c in px) if (isLetteringInk(c)) leftover++
        assertEquals("the column's original lettering must be wiped", 0, leftover)

        val covered = v.placedRects().single()
        val pageArea = pageW.toLong() * pageH
        val coveredArea = covered.width().toLong() * covered.height()
        assertTrue(
            "column translation must not blanket the page (covered $coveredArea of $pageArea)",
            coveredArea * 100 < pageArea * 45,
        )
    }

    /** Two bubbles sharing a center must not stack their cards into a slab. */
    @Test
    fun `overlapping cards nudge apart`() {
        val v = view()
        val boxA = Rect(220, 500, 480, 580)
        val boxB = Rect(240, 510, 500, 590)
        v.setBubbles(
            listOf(
                RenderBubble(boxA, "FIRST LINE OF DIALOGUE", "一", Color.WHITE, Color.BLACK, false),
                RenderBubble(boxB, "SECOND LINE OF DIALOGUE", "二", Color.WHITE, Color.BLACK, false),
            )
        )
        val rects = v.placedRects()
        assertEquals(2, rects.size)
        val a = rects[0]
        val b = rects[1]
        val inter = Rect()
        val overlap = if (inter.setIntersect(a, b)) {
            inter.width().toLong() * inter.height()
        } else {
            0L
        }
        val smaller = minOf(a.width().toLong() * a.height(), b.width().toLong() * b.height())
        assertTrue(
            "cards must not majority-overlap (overlap $overlap of $smaller)",
            overlap * 100 < smaller * 35,
        )
    }

    // ---- TypeSet: pure shaping over a 1-unit-per-character measure ----

    private val unitMeasure: (String) -> Float = { it.length.toFloat() }

    @Test
    fun `a three line break is widest in the middle`() {
        val lines = TypeSet.breakLines("ONE TWO THREE FOUR FIVE SIX", unitMeasure, 10f)
        assertEquals(listOf("ONE TWO", "THREE FOUR", "FIVE SIX"), lines)
        assertTrue(lines[1].length > lines[0].length && lines[1].length > lines[2].length)
    }

    @Test
    fun `the last line is not left a stub the way greedy filling would`() {
        assertEquals(listOf("AA BB", "CC DD"), TypeSet.breakLines("AA BB CC DD", unitMeasure, 8f))
    }

    @Test
    fun `no line ever exceeds the width limit and no word is lost`() {
        val text = "WHAT ON EARTH DO YOU THINK YOU ARE DOING ALL THE WAY OUT HERE"
        for (max in listOf(8f, 12f, 16f, 24f)) {
            val lines = TypeSet.breakLines(text, unitMeasure, max)
            for (l in lines) assertTrue("'$l' wider than $max", unitMeasure(l) <= max)
            assertEquals(text, lines.joinToString(" "))
        }
    }

    @Test
    fun `a single short word stays one line`() {
        assertEquals(listOf("HEY"), TypeSet.breakLines("HEY", unitMeasure, 100f))
    }

    @Test
    fun `a word wider than the limit stands alone rather than being split`() {
        assertEquals(
            listOf("EXTRAORDINARY", "YES"),
            TypeSet.breakLines("EXTRAORDINARY YES", unitMeasure, 5f),
        )
    }

    @Test
    fun `breaking is deterministic`() {
        val text = "SO THIS IS THE PLACE YOU KEPT TALKING ABOUT ALL ALONG"
        val a = TypeSet.breakLines(text, unitMeasure, 14f)
        val b = TypeSet.breakLines(text, unitMeasure, 14f)
        assertEquals(a, b)
        assertTrue(a.size >= 3)
    }
}
