package app.mangalens.pipeline

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import app.mangalens.ocr.BalloonFinder
import app.mangalens.translate.ItemKind
import app.mangalens.translate.PageItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.random.Random

/**
 * Balloons the lettering in them leads to. Real pages are full of balloons
 * page-wide detection cannot see — see-through ones, ones breaking a panel
 * border, ones the page edge cuts — and of model boxes that leave a column
 * of a vertical balloon out. Each of those left lettering on the page beside
 * the English. Text drawn straight on the art, white outline and all, must
 * still never pass for a balloon.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BalloonSeedTest {

    private val w = 900
    private val h = 1400

    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        strokeWidth = 4f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    /** A column of [n] glyphs, each a seeded tangle of strokes in a [size]-pixel cell, top at [top]. */
    private fun column(c: Canvas, x: Float, top: Float, n: Int, size: Float, paint: Paint = ink, seed: Int = 1): Rect {
        val rnd = Random(seed)
        for (k in 0 until n) {
            val y = top + k * size * 1.05f
            repeat(5) {
                c.drawLine(
                    x + rnd.nextFloat() * size, y + rnd.nextFloat() * size,
                    x + rnd.nextFloat() * size, y + rnd.nextFloat() * size, paint,
                )
            }
        }
        return Rect(x.toInt() - 2, top.toInt() - 2, (x + size).toInt() + 2, (top + n * size * 1.05f).toInt() + 2)
    }

    /** Dense dot screentone over the whole page, the ground see-through balloons sit on. */
    private fun tone(c: Canvas) {
        c.drawColor(Color.WHITE)
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(40, 40, 40) }
        var y = 3f
        while (y < h) {
            var x = 3f
            while (x < w) {
                c.drawCircle(x, y, 2.2f, dot)
                x += 7f
            }
            y += 7f
        }
    }

    private fun blank(draw: (Canvas) -> Unit): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { draw(Canvas(it)) }

    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5f; color = Color.BLACK }
    private val paper = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    @Test
    fun aLineWhoseBoxMissedAColumnStillCleansTheWholeBalloon() {
        // A two-column balloon on tone; the model's box covers the left column only.
        var left = Rect()
        val oval = RectF(300f, 300f, 520f, 760f)
        val page = blank { c ->
            tone(c)
            c.drawOval(oval, paper)
            c.drawOval(oval, outline)
            column(c, 425f, 380f, 7, 36f, seed = 3)
            left = column(c, 370f, 380f, 5, 36f, seed = 5)
        }
        val detected = BalloonFinder.analyze(page, 0, 0, emptyList()).balloons
        val item = PageItem(left, ItemKind.SPEECH, "有感覺到視線", "I can feel someone watching...", vertical = true)
        val out = ReadResolver(page, detected, emptyList(), 0, 0, emptyList()).resolve(listOf(item))
        val balloon = assertNotNull(out.single().balloon).let { out.single().balloon!! }
        // The whole balloon, both columns, is cleaned — not the boxed column alone.
        assertTrue("balloon ${balloon.box}", balloon.box.left <= 330 && balloon.box.right >= 490)
    }

    @Test
    fun aGlyphCounterPassedForABalloonDoesNotTakeTheLine() {
        // A burst's big lettering; detection took the white inside of one
        // glyph for a balloon of its own. The line belongs to the burst.
        val oval = RectF(250f, 400f, 650f, 700f)
        var text = Rect()
        val page = blank { c ->
            tone(c)
            c.drawOval(oval, paper)
            c.drawOval(oval, outline)
            text = column(c, 330f, 520f, 1, 70f, Paint(ink).apply { strokeWidth = 9f })
            text.union(column(c, 410f, 520f, 1, 70f, Paint(ink).apply { strokeWidth = 9f }, seed = 4))
            text.union(column(c, 490f, 520f, 1, 70f, Paint(ink).apply { strokeWidth = 9f }, seed = 6))
        }
        val counter = Rect(text.left + 6, text.top + 4, text.left + 30, text.top + 40)
        val fake = app.mangalens.ocr.Balloon(counter, 6, 9, BooleanArray(54) { true }, inverted = false)
        val item = PageItem(text, ItemKind.SPEECH, "破天印！", "Sky-Shattering Seal!")
        val out = ReadResolver(page, listOf(fake), emptyList(), 0, 0, emptyList()).resolve(listOf(item))
        val balloon = out.single().balloon
        assertTrue("lettered into ${balloon?.box}", balloon != null && balloon.box.width() > 300 && balloon.box.height() > 220)
    }

    @Test
    fun aFaceBesideTheTextIsStillArt() {
        val oval = RectF(200f, 200f, 700f, 700f)
        var text = Rect()
        val page = blank { c ->
            c.drawColor(Color.rgb(90, 90, 90))
            c.drawOval(oval, paper)
            c.drawOval(oval, outline)
            text = column(c, 420f, 330f, 6, 34f)
            // Eyes and a mouth to the left of the text, running on well past it.
            c.drawCircle(300f, 380f, 18f, ink)
            c.drawCircle(300f, 470f, 18f, ink)
            c.drawLine(250f, 300f, 250f, 640f, ink)
        }
        val detected = BalloonFinder.analyze(page, 0, 0, emptyList()).balloons
            .firstOrNull { it.box.contains(text.centerX(), text.centerY()) } ?: return
        assertFalse(BalloonTrust.holdsOnly(page, detected, listOf(text)))
    }

    @Test
    fun aSeeThroughBalloonIsFoundFromItsLettering() {
        val oval = RectF(260f, 300f, 460f, 700f)
        var col = Rect()
        val page = blank { c ->
            tone(c)
            // A wash of white, 80% strong, over the tone: the dots show faintly.
            c.drawOval(oval, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(204, 255, 255, 255) })
            c.drawOval(oval, outline)
            col = column(c, 340f, 380f, 6, 38f)
        }
        val found = assertNotNull(BalloonSeed.find(page, col)).let { BalloonSeed.find(page, col)!! }
        assertTrue("found ${found.box}", found.box.left in 250..285 && found.box.right in 435..470)
        assertTrue("found ${found.box}", found.box.top in 290..325 && found.box.bottom in 675..710)
    }

    @Test
    fun aBalloonThePageEdgeCutsIsFound() {
        val oval = RectF(-120f, 400f, 180f, 900f)
        var col = Rect()
        val page = blank { c ->
            tone(c)
            c.drawOval(oval, paper)
            c.drawOval(oval, outline)
            col = column(c, 50f, 500f, 7, 36f)
        }
        val found = assertNotNull(BalloonSeed.find(page, col)).let { BalloonSeed.find(page, col)!! }
        assertTrue("found ${found.box}", found.box.left <= 4 && found.box.right in 160..190)
    }

    @Test
    fun aBalloonBreakingABorderIsCutFromTheGutter() {
        // Two toned panels with a white gutter between; the balloon straddles
        // the gutter with no outline inside it, so its paper runs on into the gutter.
        val gutter = 430..460
        val oval = RectF(330f, 420f, 560f, 900f)
        var col = Rect()
        val page = blank { c ->
            tone(c)
            c.drawRect(gutter.first.toFloat(), 0f, gutter.last.toFloat(), h.toFloat(), paper)
            c.drawOval(oval, outline)
            c.drawOval(oval, paper)
            // Only the parts inside the panels are outlined.
            c.save()
            c.clipRect(0f, 0f, gutter.first.toFloat(), h.toFloat())
            c.drawOval(oval, outline)
            c.restore()
            c.save()
            c.clipRect(gutter.last.toFloat(), 0f, w.toFloat(), h.toFloat())
            c.drawOval(oval, outline)
            c.restore()
            c.drawRect(gutter.first.toFloat(), 0f, gutter.last.toFloat(), oval.top + 20, paper)
            c.drawRect(gutter.first.toFloat(), oval.bottom - 20, gutter.last.toFloat(), h.toFloat(), paper)
            col = column(c, 470f, 500f, 7, 36f)
        }
        val found = assertNotNull(BalloonSeed.find(page, col)).let { BalloonSeed.find(page, col)!! }
        // The balloon, not the gutter running the height of the page.
        assertTrue("found ${found.box}", found.box.top >= 380 && found.box.bottom <= 950)
    }

    @Test
    fun outlinedTextOnSpeedLinesIsNoBalloon() {
        var col = Rect()
        val page = blank { c ->
            c.drawColor(Color.WHITE)
            val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; strokeWidth = 2f }
            for (k in 0 until 90) {
                val a = Math.toRadians(k * 4.0)
                c.drawLine(450f + 120f * Math.cos(a).toFloat(), 700f + 120f * Math.sin(a).toFloat(),
                    450f + 900f * Math.cos(a).toFloat(), 700f + 900f * Math.sin(a).toFloat(), line)
            }
            val halo = Paint(ink).apply { color = Color.WHITE; strokeWidth = 16f }
            column(c, 200f, 300f, 6, 60f, halo, seed = 9)
            col = column(c, 200f, 300f, 6, 60f, Paint(ink).apply { strokeWidth = 7f }, seed = 9)
        }
        assertNull(BalloonSeed.find(page, col))
    }

    @Test
    fun textOnOpenPaperIsNoBalloon() {
        var col = Rect()
        val page = blank { c ->
            c.drawColor(Color.WHITE)
            col = column(c, 420f, 500f, 6, 36f)
        }
        assertNull(BalloonSeed.find(page, col))
    }
}
