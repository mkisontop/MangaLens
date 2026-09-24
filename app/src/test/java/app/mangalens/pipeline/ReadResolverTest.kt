package app.mangalens.pipeline

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import app.mangalens.ocr.Balloon
import app.mangalens.overlay.LetterStyle
import app.mangalens.translate.ItemKind
import app.mangalens.translate.PageItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Where each answer is lettered. Manhwa routinely joins one speaker's two
 * balloons into a single drawn shape, and the detector returns them as one;
 * each line must still be lettered into its own lobe. The model, for its
 * part, sometimes answers one balloon's columns as separate items; those
 * belong back together in one balloon.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReadResolverTest {

    private val page: Bitmap = Bitmap.createBitmap(800, 1000, Bitmap.Config.ARGB_8888).apply {
        Canvas(this).drawColor(Color.WHITE)
    }

    /** A detection whose interior is the union of the given ellipses, on a 4-px mask grid. */
    private fun detection(box: Rect, vararg ellipses: Rect): Balloon {
        val mw = box.width() / 4
        val mh = box.height() / 4
        val mask = BooleanArray(mw * mh) { i ->
            val x = box.left + (i % mw) * 4 + 2
            val y = box.top + (i / mw) * 4 + 2
            ellipses.any { e ->
                val nx = (x - e.exactCenterX()) / (e.width() / 2f)
                val ny = (y - e.exactCenterY()) / (e.height() / 2f)
                nx * nx + ny * ny <= 1f
            }
        }
        return Balloon(box, mw, mh, mask, inverted = false)
    }

    private fun resolver(vararg balloons: Balloon) =
        ReadResolver(page, balloons.toList(), emptyList(), 0, 0, emptyList())

    @Test
    fun joinedBalloonsKeepALineEach() {
        val upper = Rect(80, 100, 480, 300)
        val lower = Rect(360, 260, 760, 460)
        val joined = detection(Rect(80, 100, 760, 460), upper, lower)
        val a = PageItem(Rect(200, 180, 360, 220), ItemKind.SPEECH, "어떻게 된 거야?", "What happened?", who = "Seo-yeon")
        val b = PageItem(Rect(480, 340, 640, 380), ItemKind.SPEECH, "믿을 수 없어.", "I can't believe it.", who = "Seo-yeon")

        val out = resolver(joined).resolve(listOf(a, b))
        assertEquals(2, out.size)
        val ra = out.single { it.translated == a.en }
        val rb = out.single { it.translated == b.en }
        val la = assertNotNull(ra.balloon).let { ra.balloon!! }
        val lb = rb.balloon!!
        assertNotSame(la, lb)
        // Each lobe holds its own line and not the other's.
        assertTrue(la.box.contains(a.box.centerX(), a.box.centerY()))
        assertTrue(lb.box.contains(b.box.centerX(), b.box.centerY()))
        assertTrue(!la.box.contains(b.box.centerX(), b.box.centerY()))
        assertTrue(!lb.box.contains(a.box.centerX(), a.box.centerY()))
        // Together the lobes still clean the whole shape.
        val cells = la.mask.count { it } + lb.mask.count { it }
        assertEquals(joined.mask.count { it }, cells)
    }

    @Test
    fun oneBalloonAnsweredInPiecesIsSetBackTogether() {
        val oval = Rect(200, 200, 520, 600)
        val balloon = detection(oval, oval)
        // Two adjacent vertical columns of one balloon, answered separately.
        val right = PageItem(Rect(370, 280, 410, 520), ItemKind.SPEECH, "じゃあ", "Then", who = "Rin", vertical = true)
        val left = PageItem(Rect(320, 280, 360, 520), ItemKind.SPEECH, "どうするの", "what now?", who = "Rin", vertical = true)

        val out = resolver(balloon).resolve(listOf(right, left))
        assertEquals(1, out.size)
        assertEquals("Then what now?", out[0].translated)
        assertTrue(out[0].balloon === balloon)
    }

    @Test
    fun twoSpeakersInOneDetectionNeverShareALine() {
        val left = Rect(60, 200, 400, 520)
        val right = Rect(360, 200, 740, 520)
        val joined = detection(Rect(60, 200, 740, 520), left, right)
        val a = PageItem(Rect(180, 300, 280, 420), ItemKind.SPEECH, "A", "You!", who = "Kai")
        val b = PageItem(Rect(500, 300, 620, 420), ItemKind.SPEECH, "B", "Me?", who = "Mira")

        val out = resolver(joined).resolve(listOf(a, b))
        assertEquals(2, out.size)
        assertTrue(out.none { it.translated.contains("You!") && it.translated.contains("Me?") })
    }

    @Test
    fun soundEffectsNeverClaimABalloon() {
        val oval = Rect(200, 200, 520, 600)
        val balloon = detection(oval, oval)
        val speech = PageItem(Rect(320, 300, 400, 500), ItemKind.SPEECH, "何", "What?!", loud = true)
        val sfx = PageItem(Rect(420, 520, 470, 570), ItemKind.SFX, "ドン", "BAM")

        val out = resolver(balloon).resolve(listOf(speech, sfx))
        val inBalloon = out.filter { it.balloon != null }
        assertEquals(1, inBalloon.size)
        assertEquals("What?!", inBalloon[0].translated)
        assertEquals(LetterStyle.SHOUT, inBalloon[0].style)
    }

    @Test
    fun aHeartTheModelGaveAsASoundDoesNotSpoilItsBalloon() {
        val oval = Rect(200, 200, 520, 600)
        val balloon = detection(oval, oval)
        val inked = page.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(inked)
        val ink = android.graphics.Paint().apply { color = Color.BLACK }
        val speech = PageItem(Rect(320, 300, 400, 480), ItemKind.SPEECH, "好き", "I like you", vertical = true)
        c.drawRect(335f, 310f, 385f, 470f, ink)
        // A bold heart lettered under the line, answered as a sound of its own.
        val heart = PageItem(Rect(330, 500, 390, 560), ItemKind.SFX, "♡", "♡")
        c.drawRect(335f, 505f, 385f, 555f, ink)

        val out = ReadResolver(inked, listOf(balloon), emptyList(), 0, 0, emptyList()).resolve(listOf(speech, heart))
        assertTrue("the balloon is still cleaned and typeset", out.any { it.translated == speech.en && it.balloon === balloon })
    }

    /** A phone-tall page of busy art with a white balloon at [oval], lettered unless [empty]. */
    private fun artWithBalloon(oval: Rect, empty: Boolean = false): Bitmap {
        val bmp = Bitmap.createBitmap(800, 2000, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(150, 150, 150))
        val rnd = java.util.Random(3)
        val p = android.graphics.Paint()
        repeat(3000) {
            p.color = Color.rgb(rnd.nextInt(120), rnd.nextInt(120), rnd.nextInt(120))
            val x = rnd.nextInt(800).toFloat()
            val y = rnd.nextInt(2000).toFloat()
            c.drawRect(x, y, x + 4 + rnd.nextInt(12), y + 2 + rnd.nextInt(6), p)
        }
        c.drawOval(android.graphics.RectF(oval), android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        if (!empty) {
            p.color = Color.BLACK
            for (k in 0 until 4) c.drawRect(oval.centerX() - 15f, oval.top + 60f + k * 50, oval.centerX() + 15f, oval.top + 95f + k * 50, p)
        }
        return bmp
    }

    @Test
    fun aLineWhoseBoxDriftedOntoTheArtGoesBackToItsBalloon() {
        val oval = Rect(300, 500, 500, 820)
        val balloon = detection(oval, oval)
        val page = artWithBalloon(oval)
        // The model's box landed 250 px above the balloon, on the art.
        val line = PageItem(Rect(370, 300, 430, 470), ItemKind.SPEECH, "違うって、それ！", "No, that's not it!", vertical = true)
        val out = ReadResolver(page, listOf(balloon), emptyList(), 0, 0, emptyList()).resolve(listOf(line))
        assertEquals(1, out.size)
        assertTrue("lettered in the balloon it came from", out[0].balloon === balloon)
    }

    @Test
    fun anEmptyBalloonNearbyDoesNotClaimALine() {
        val oval = Rect(300, 500, 500, 820)
        val balloon = detection(oval, oval)
        val page = artWithBalloon(oval, empty = true)
        val line = PageItem(Rect(370, 300, 430, 470), ItemKind.SPEECH, "違うって、それ！", "No, that's not it!", vertical = true)
        val out = ReadResolver(page, listOf(balloon), emptyList(), 0, 0, emptyList()).resolve(listOf(line))
        assertTrue(out.none { it.balloon === balloon })
    }

    @Test
    fun aFaceThatPassedForABalloonNeverTakesADriftedLine() {
        val oval = Rect(300, 500, 500, 820)
        val balloon = detection(oval, oval)
        val page = artWithBalloon(oval, empty = true)
        val line = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE; strokeWidth = 4f; color = Color.BLACK
        }
        Canvas(page).apply {
            drawOval(android.graphics.RectF(330f, 590f, 380f, 620f), line)
            drawOval(android.graphics.RectF(420f, 590f, 470f, 620f), line)
            drawLine(325f, 570f, 385f, 562f, line)
            drawLine(415f, 562f, 475f, 570f, line)
            drawArc(android.graphics.RectF(350f, 720f, 450f, 770f), 0f, 180f, false, line)
        }
        val drifted = PageItem(Rect(370, 300, 430, 470), ItemKind.SPEECH, "違うって、それ！", "No, that's not it!", vertical = true)
        val out = ReadResolver(page, listOf(balloon), emptyList(), 0, 0, emptyList()).resolve(listOf(drifted))
        assertTrue("the face is left as drawn", out.none { it.balloon === balloon })
    }

    @Test
    fun cleanLetteringWhereTheBoxSaysKeepsItThere() {
        // Plain paper with the line drawn right where the box is, and a
        // lettered balloon nearby that no answer claims.
        val oval = Rect(300, 500, 500, 820)
        val balloon = detection(oval, oval)
        val bmp = Bitmap.createBitmap(800, 1000, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val ink = android.graphics.Paint().apply { color = Color.BLACK }
        c.drawOval(android.graphics.RectF(oval), android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.STROKE; strokeWidth = 5f; color = Color.BLACK })
        for (k in 0 until 4) c.drawRect(385f, 560f + k * 50, 415f, 595f + k * 50, ink)
        for (k in 0 until 3) c.drawRect(385f, 310f + k * 50, 415f, 345f + k * 50, ink)
        val line = PageItem(Rect(378, 302, 422, 452), ItemKind.SPEECH, "ねえ", "Hey.", vertical = true)
        val out = ReadResolver(bmp, listOf(balloon), emptyList(), 0, 0, emptyList()).resolve(listOf(line))
        assertTrue(out.none { it.balloon === balloon })
    }

    @Test
    fun aBigSoundEffectAcrossTheArtIsNotedNotErased() {
        // A dramatic sound effect spanning a quarter of the page: erasing it
        // would take the drawing with it.
        val sfx = PageItem(Rect(100, 300, 700, 560), ItemKind.SFX, "ドドン", "BA-DOOM")
        val out = resolver().resolve(listOf(sfx))
        assertEquals(1, out.size)
        assertEquals(LetterStyle.SFX_NOTE, out[0].style)
        assertEquals(null, out[0].patch)
        assertEquals(null, out[0].balloon)
    }
}
