package app.mangalens.overlay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import app.mangalens.ocr.Balloon
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Android 12 and later draw an overlay that lets touches through at no more
 * than 80% strength, so a fifth of the page shows through everything it
 * paints: every cleaned balloon kept a grey ghost of the lettering it
 * replaced. Under the veil the whole page goes to 80% and what is painted
 * over it is painted less the page's share, so on screen the two meet at the
 * same level and nothing of the original shows.
 *
 * The screen is simulated as the compositor makes it: the layer, at the
 * window's strength, over the page (displayed = a·layer + (1 − a·alpha)·page,
 * channel by channel). A phone's screenshots of the ghost measure exactly
 * that: white paper over black lettering at 204.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VeilTest {

    private val w = 720
    private val h = 900
    private val cap = 0.8f
    private val balloonBox = Rect(160, 140, 560, 440)
    private val cardBox = Rect(120, 600, 600, 700)
    private val outDir = File("build/render-preview").apply { mkdirs() }

    /** Mid-grey art with dark and light structure, a white balloon, black lettering in it. */
    private fun page(glyphs: Canvas.() -> Unit = { lettering(this) }): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmp ->
            val c = Canvas(bmp)
            c.drawColor(Color.rgb(150, 146, 140))
            val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 7f }
            val rnd = Random(4)
            for (k in 0 until 60) {
                line.color = if (k % 2 == 0) Color.rgb(40, 38, 36) else Color.rgb(235, 232, 228)
                val x = rnd.nextFloat() * w
                val y = rnd.nextFloat() * h
                c.drawLine(x, y, x + 220f, y + 90f, line)
            }
            val oval = RectF(balloonBox)
            c.drawOval(oval, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
            c.drawOval(oval, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 6f; color = Color.BLACK })
            c.glyphs()
        }

    /** Three columns of glyph-like strokes, well inside the balloon. */
    private fun lettering(c: Canvas) {
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; strokeWidth = 5f; strokeCap = Paint.Cap.ROUND }
        val rnd = Random(9)
        for (col in 0 until 3) {
            val x0 = 290f + col * 50f
            for (k in 0 until 5) {
                val y0 = 200f + k * 40f
                repeat(5) {
                    c.drawLine(
                        x0 + rnd.nextFloat() * 34f, y0 + rnd.nextFloat() * 34f,
                        x0 + rnd.nextFloat() * 34f, y0 + rnd.nextFloat() * 34f, ink,
                    )
                }
            }
        }
    }

    private fun ellipse(box: Rect): Balloon {
        val mw = 100
        val mh = 75
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

    private val bubbles = listOf(
        RenderBubble(
            Rect(280, 190, 450, 400), "Hey! Where do you think you're going?", "どこへ行く",
            Color.WHITE, Color.BLACK, vertical = true, balloon = ellipse(balloonBox),
        ),
        // No balloon and no patch: a card over the art.
        RenderBubble(cardBox, "Meanwhile, at the station", "一方、駅では", Color.WHITE, Color.BLACK, vertical = false),
    )

    private fun layer(page: Bitmap, alpha: Float, veiled: Boolean): Bitmap {
        val v = BubbleOverlayView(RuntimeEnvironment.getApplication()).apply { layout(0, 0, w, h) }
        v.animates = { false }
        v.windowAlpha = alpha
        v.veiled = veiled
        v.setBubbles(bubbles, page)
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { v.draw(Canvas(it)) }
    }

    /** The screen: [layer] at [alpha] over [page], per channel; unpremultiplied pixels in, as getPixels gives them. */
    private fun screen(layer: Bitmap, page: Bitmap, alpha: Float): IntArray {
        val l = IntArray(w * h).also { layer.getPixels(it, 0, w, 0, 0, w, h) }
        val u = IntArray(w * h).also { page.getPixels(it, 0, w, 0, 0, w, h) }
        return IntArray(w * h) { i ->
            val a = (l[i] ushr 24) / 255f
            fun ch(s: Int): Int {
                val c = (l[i] shr s and 0xFF) * a
                val p = (u[i] shr s and 0xFF).toFloat()
                return (alpha * c + (1f - alpha * a) * p + 0.5f).toInt().coerceIn(0, 255)
            }
            (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
    }

    private fun lum(p: Int) = ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000

    private fun save(px: IntArray, name: String) {
        val bmp = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        FileOutputStream(File(outDir, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun `under the veil no trace of the original shows through a cleaned balloon`() {
        val raw = page()
        val blank = page { }
        // Where the original lettering was: the page with and without it differ there.
        val withText = IntArray(w * h).also { raw.getPixels(it, 0, w, 0, 0, w, h) }
        val without = IntArray(w * h).also { blank.getPixels(it, 0, w, 0, 0, w, h) }

        // What the reader is meant to see: the page as lettered, at the veil's level.
        val ideal = layer(raw, 1f, veiled = false)
        val want = screen(ideal, raw, 1f).map { p -> scaled(p, cap) }.toIntArray()
        val idealPx = IntArray(w * h).also { ideal.getPixels(it, 0, w, 0, 0, w, h) }

        val before = screen(layer(raw, cap, veiled = false), raw, cap)
        val after = screen(layer(raw, cap, veiled = true), raw, cap)
        save(before, "veil-before")
        save(after, "veil-after")

        // Balloon paper the English does not touch: where the original
        // lettering was, and where there was only paper. A ghost is the one
        // showing apart from the other.
        fun spread(screen: IntArray): Int {
            var paper = 0L
            var paperN = 0
            for (y in balloonBox.top until balloonBox.bottom) for (x in balloonBox.left until balloonBox.right) {
                val i = y * w + x
                if (idealPx[i] == Color.WHITE && without[i] == withText[i]) {
                    paper += lum(screen[i])
                    paperN++
                }
            }
            val level = (paper / paperN).toInt()
            var worst = 0
            for (y in balloonBox.top until balloonBox.bottom) for (x in balloonBox.left until balloonBox.right) {
                val i = y * w + x
                if (idealPx[i] == Color.WHITE && lum(without[i]) - lum(withText[i]) >= 100) {
                    worst = maxOf(worst, abs(lum(screen[i]) - level))
                }
            }
            return worst
        }
        var n = 0
        for (i in 0 until w * h) if (idealPx[i] == Color.WHITE && lum(without[i]) - lum(withText[i]) >= 100) n++
        assertTrue("too few ghost pixels to judge: $n", n > 500)
        // Without the veil the lettering shows at 204 on paper of 255: the ghost.
        assertTrue("ghost ${spread(before)}", spread(before) >= 40)
        assertTrue("left under the veil: ${spread(after)}", spread(after) <= 2)
        // And that paper is the page as lettered, at the veil's level.
        var off = 0
        for (i in 0 until w * h) if (idealPx[i] == Color.WHITE) off = maxOf(off, abs(lum(after[i]) - lum(want[i])))
        assertTrue("paper off by $off", off <= 2)
    }

    @Test
    fun `the veil takes the rest of the page to the same level, and the English stays dark`() {
        val raw = page()
        val rawPx = IntArray(w * h).also { raw.getPixels(it, 0, w, 0, 0, w, h) }
        val ideal = layer(raw, 1f, veiled = false)
        val idealPx = IntArray(w * h).also { ideal.getPixels(it, 0, w, 0, 0, w, h) }
        val after = screen(layer(raw, cap, veiled = true), raw, cap)

        // Art nothing is painted on: 80% of itself.
        var worstArt = 0
        var paper = 0
        var ink = 0
        for (i in 0 until w * h) {
            if (idealPx[i] ushr 24 == 0) {
                worstArt = maxOf(worstArt, abs(lum(after[i]) - (lum(rawPx[i]) * cap + 0.5f).toInt()))
            } else if (idealPx[i] == Color.WHITE) {
                paper = maxOf(paper, lum(after[i]))
            } else if (idealPx[i] == Color.BLACK) {
                // Solid ink; its anti-aliased edge takes some of the paper beside it.
                ink = maxOf(ink, lum(after[i]))
            }
        }
        assertTrue("art off by $worstArt", worstArt <= 2)
        // Paper at the veil's level; the English as dark as the layer can show it (a fifth of white).
        assertEquals(204f, paper.toFloat(), 2f)
        assertTrue("English at $ink", ink <= 56)
    }

    @Test
    fun `a card over the art hides the art under it`() {
        val raw = page()
        val ideal = layer(raw, 1f, veiled = false)
        val idealPx = IntArray(w * h).also { ideal.getPixels(it, 0, w, 0, 0, w, h) }
        val want = screen(ideal, raw, 1f).map { p -> scaled(p, cap) }.toIntArray()
        val before = screen(layer(raw, cap, veiled = false), raw, cap)
        val after = screen(layer(raw, cap, veiled = true), raw, cap)
        var showing = 0
        var left = 0
        for (y in cardBox.top until cardBox.bottom) for (x in cardBox.left until cardBox.right) {
            val i = y * w + x
            // The card's plain ground, clear of its text and hairline.
            if (idealPx[i] != Color.WHITE) continue
            showing = maxOf(showing, abs(lum(before[i]) - lum(want[i])))
            left = maxOf(left, abs(lum(after[i]) - lum(want[i])))
        }
        assertTrue("art under an unveiled card: $showing", showing >= 30)
        assertTrue("art under a veiled card: $left", left <= 2)
    }

    @Test
    fun `a line streaming in paints only its own ground`() {
        val raw = page()
        val v = BubbleOverlayView(RuntimeEnvironment.getApplication()).apply { layout(0, 0, w, h) }
        v.animates = { false }
        v.windowAlpha = cap
        v.veiled = true
        fun draw() = v.draw(Canvas(Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)))
        v.setBubbles(bubbles.take(1), raw)
        draw()
        assertEquals("the balloon's cleaning", 1, v.groundsPainted)
        draw()
        assertEquals("a frame of a fade paints nothing again", 1, v.groundsPainted)
        v.setBubbles(bubbles, raw)
        draw()
        assertEquals("only the new card's ground", 2, v.groundsPainted)
        // A new page is a new frame to paint against: everything again.
        v.setBubbles(bubbles, page())
        draw()
        assertEquals(4, v.groundsPainted)
    }

    @Test
    fun `at full strength nothing is veiled`() {
        val raw = page()
        val plain = layer(raw, 1f, veiled = false)
        val asked = layer(raw, 1f, veiled = true)
        val a = IntArray(w * h).also { plain.getPixels(it, 0, w, 0, 0, w, h) }
        val b = IntArray(w * h).also { asked.getPixels(it, 0, w, 0, 0, w, h) }
        assertTrue(a.contentEquals(b))
    }

    private fun scaled(p: Int, k: Float): Int {
        fun ch(s: Int) = ((p shr s and 0xFF) * k + 0.5f).toInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
