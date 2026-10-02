package app.mangalens.pipeline

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import app.mangalens.ocr.Balloon
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Art that passes for a balloon must never be wiped. Line-art faces,
 * highlights and screentone patches are enclosed light regions just as
 * balloons are, and a line of lettering drawn over one — a breathy aside
 * on a face — used to get it cleaned flat. Only a detection that holds
 * nothing but the lettering is trusted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BalloonTrustTest {

    private val region = Rect(100, 100, 500, 400)
    private val text = Rect(250, 200, 350, 300)

    private fun detection(inverted: Boolean = false): Balloon {
        val mw = 100
        val mh = 75
        val mask = BooleanArray(mw * mh) { i ->
            val nx = ((i % mw) + 0.5f) / mw * 2f - 1f
            val ny = ((i / mw) + 0.5f) / mh * 2f - 1f
            nx * nx + ny * ny <= 1f
        }
        return Balloon(region, mw, mh, mask, inverted)
    }

    private fun page(paper: Int, draw: (Canvas) -> Unit = {}): Bitmap {
        val bmp = Bitmap.createBitmap(600, 500, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(90, 90, 90))
        c.drawOval(RectF(region), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = paper })
        c.drawOval(RectF(region), Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 5f; color = Color.BLACK
        })
        // The lettering itself, inside the text box.
        val ink = Paint().apply { color = if (paper == Color.BLACK) Color.WHITE else Color.BLACK; strokeWidth = 5f }
        for (k in 0 until 4) c.drawLine(260f + k * 25, 210f, 260f + k * 25, 290f, ink)
        draw(c)
        return bmp
    }

    @Test
    fun aBalloonHoldingOnlyItsLetteringIsTrusted() {
        assertTrue(BalloonTrust.holdsOnly(page(Color.WHITE), detection(), listOf(text)))
    }

    @Test
    fun aPastelBalloonIsTrusted() {
        assertTrue(BalloonTrust.holdsOnly(page(Color.rgb(250, 214, 226)), detection(), listOf(text)))
    }

    @Test
    fun aBlackNarrationBoxIsTrusted() {
        assertTrue(BalloonTrust.holdsOnly(page(Color.BLACK), detection(inverted = true), listOf(text)))
    }

    @Test
    fun aGlyphTheModelsBoxStoppedShortOfIsStillLettering() {
        // A column of four glyphs; the model's box covers only the first three.
        val column = Rect(280, 150, 320, 290)
        val glyphs = page(Color.WHITE) { c ->
            c.drawColor(Color.TRANSPARENT)
        }.also { bmp ->
            val c = Canvas(bmp)
            c.drawOval(RectF(region), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
            val ink = Paint().apply { color = Color.BLACK }
            for (k in 0 until 4) c.drawRect(284f, 154f + k * 46, 316f, 190f + k * 46, ink)
        }
        assertTrue(BalloonTrust.holdsOnly(glyphs, detection(), listOf(column)))
    }

    /** A white balloon holding [columns] of seven glyphs each, glyphs [g] px apart, with [extra] drawn after. */
    private fun columnsPage(columns: List<Float>, extra: (Canvas) -> Unit = {}): Bitmap =
        Bitmap.createBitmap(600, 500, Bitmap.Config.ARGB_8888).apply {
            val c = Canvas(this)
            c.drawColor(Color.rgb(90, 90, 90))
            c.drawOval(RectF(region), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
            val ink = Paint().apply { color = Color.BLACK }
            for (x in columns) for (k in 0 until 7) c.drawRect(x, 150f + k * 30, x + 24, 150f + k * 30 + 24, ink)
            extra(c)
        }

    @Test
    fun aColumnTheBoxStoppedShortOfIsLetteringAllTheWayDown() {
        // The model's box round the first two of seven glyphs.
        val short = Rect(290, 148, 316, 206)
        assertTrue(BalloonTrust.holdsOnly(columnsPage(listOf(290f)), detection(), listOf(short)))
    }

    @Test
    fun twoColumnsBoxedShortSideBySideAreLettering() {
        // One balloon, two columns, answered as two lines whose boxes each
        // stop two glyphs in: each column's rest counted against the other.
        val right = Rect(320, 148, 346, 206)
        val left = Rect(284, 178, 310, 236)
        assertTrue(BalloonTrust.holdsOnly(columnsPage(listOf(284f, 320f)), detection(), listOf(right, left)))
    }

    @Test
    fun aLineIsNotGrownIntoArtWiderThanItsColumn() {
        // A column the box covers, and right under it a hand drawn three
        // glyphs wide: the rest of the line stops where the art starts.
        val page = columnsPage(listOf(290f)) { c ->
            val ink = Paint().apply { color = Color.BLACK }
            c.drawRect(230f, 364f, 370f, 404f, ink)
        }
        val column = Rect(290, 148, 316, 356)
        assertFalse(BalloonTrust.holdsOnly(page, detection(), listOf(column)))
    }

    @Test
    fun aFaceIsNotABalloon() {
        val face = page(Color.WHITE) { c ->
            val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f; color = Color.BLACK }
            // Eyes, brows and a mouth around the aside drawn over the face.
            c.drawOval(RectF(160f, 170f, 220f, 210f), line)
            c.drawOval(RectF(380f, 170f, 440f, 210f), line)
            c.drawLine(150f, 150f, 230f, 140f, line)
            c.drawLine(370f, 140f, 450f, 150f, line)
            c.drawArc(RectF(200f, 320f, 400f, 380f), 0f, 180f, false, line)
        }
        assertFalse(BalloonTrust.holdsOnly(face, detection(), listOf(text)))
    }

    @Test
    fun aBalloonsLetteringIsFoundAsOneBlockEvenWhereOcrReadOnlyPartOfIt() {
        val block = BalloonTrust.letteringBlock(page(Color.WHITE), detection())
        assertTrue("found ($block)", block != null && Rect.intersects(block, text))
        val empty = Bitmap.createBitmap(600, 500, Bitmap.Config.ARGB_8888).apply {
            val c = Canvas(this)
            c.drawColor(Color.rgb(90, 90, 90))
            c.drawOval(RectF(region), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        }
        assertTrue("an empty balloon holds none", BalloonTrust.letteringBlock(empty, detection()) == null)
    }

    @Test
    fun aFacesFeaturesAreNotABlockOfLettering() {
        val face = Bitmap.createBitmap(600, 500, Bitmap.Config.ARGB_8888).apply {
            val c = Canvas(this)
            c.drawColor(Color.rgb(90, 90, 90))
            c.drawOval(RectF(region), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
            val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f; color = Color.BLACK }
            c.drawOval(RectF(160f, 170f, 220f, 210f), line)
            c.drawOval(RectF(380f, 170f, 440f, 210f), line)
            c.drawLine(150f, 150f, 230f, 140f, line)
            c.drawLine(370f, 140f, 450f, 150f, line)
            c.drawArc(RectF(200f, 320f, 400f, 380f), 0f, 180f, false, line)
        }
        assertTrue(BalloonTrust.letteringBlock(face, detection()) == null)
    }

    /** Six columns of seven glyphs, three strokes each way: lettering as dense as a real balloon's. */
    private fun fullBalloon(): Bitmap = Bitmap.createBitmap(600, 500, Bitmap.Config.ARGB_8888).apply {
        val c = Canvas(this)
        c.drawColor(Color.rgb(90, 90, 90))
        c.drawOval(RectF(region), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        val ink = Paint().apply { color = Color.BLACK }
        for (col in 0 until 6) for (glyph in 0 until 7) {
            val x = 190f + col * 36
            val y = 160f + glyph * 26
            for (stroke in 0 until 3) {
                c.drawRect(x, y + 2 + stroke * 7, x + 22, y + 6 + stroke * 7, ink)
                c.drawRect(x + 2 + stroke * 7, y, x + 6 + stroke * 7, y + 22, ink)
            }
        }
    }

    @Test
    fun aBalloonFullOfLetteringIsStillOneBlockOfIt() {
        // Every stroke is a step between neighbouring samples: counted as
        // the paper's texture, they made the lettering of any real balloon
        // look like screentone, and no balloon held a block of it.
        val block = BalloonTrust.letteringBlock(fullBalloon(), detection())
        assertTrue("found ($block)", block != null && block.contains(Rect(200, 170, 380, 320)))
    }

    @Test
    fun screentoneHoldsNoBlockOfLettering() {
        val tone = page(Color.WHITE) { c ->
            val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(150, 150, 150) }
            var y = 110f
            while (y < 400f) {
                var x = 110f
                while (x < 500f) {
                    c.drawCircle(x, y, 1.6f, dot)
                    x += 6f
                }
                y += 6f
            }
        }
        assertTrue(BalloonTrust.letteringBlock(tone, detection()) == null)
    }

    @Test
    fun screentoneIsNotABalloon() {
        val tone = page(Color.WHITE) { c ->
            val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(150, 150, 150) }
            var y = 110f
            while (y < 400f) {
                var x = 110f
                while (x < 500f) {
                    c.drawCircle(x, y, 1.6f, dot)
                    x += 6f
                }
                y += 6f
            }
        }
        assertFalse(BalloonTrust.holdsOnly(tone, detection(), listOf(text)))
    }
}
