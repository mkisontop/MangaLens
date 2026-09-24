package app.mangalens.capture

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import app.mangalens.ocr.Balloon
import app.mangalens.ocr.BubbleKind
import app.mangalens.overlay.ArtMap
import app.mangalens.overlay.LetterStyle
import app.mangalens.overlay.RenderBubble
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A scroll clears the screen's cards, and the next stop brings them back
 * where the scroll moved their lettering. Everything a card places on the
 * page must move with it, or its cleaning lands where the balloon used to
 * be; and only cards still wholly on screen come back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CardCarryTest {

    private val w = 720
    private val h = 1600

    private fun card(box: Rect, balloon: Rect? = null, art: ArtMap? = null) = RenderBubble(
        box = box,
        translated = "Where were you?",
        original = "どこにいた",
        bgColor = Color.WHITE,
        textColor = Color.BLACK,
        vertical = false,
        kind = BubbleKind.DIALOGUE,
        balloon = balloon?.let { Balloon(it, 4, 4, BooleanArray(16) { true }, inverted = false) },
        style = LetterStyle.DIALOGUE,
        patchRect = Rect(box).apply { inset(-4, -4) },
        art = art,
        otherPanels = listOf(Rect(0, 0, w, 300)),
        panel = Rect(0, 300, w, 900),
    )

    @Test
    fun aCardMovesWithEverythingItPlaces() {
        val c = card(Rect(100, 500, 300, 560), balloon = Rect(80, 470, 320, 600))
        val m = c.shiftedBy(-200)
        assertEquals(Rect(100, 300, 300, 360), m.box)
        assertEquals(Rect(80, 270, 320, 400), m.balloon!!.box)
        assertEquals(Rect(96, 296, 304, 364), m.patchRect)
        assertEquals(Rect(0, 100, w, 700), m.panel)
        assertEquals(listOf(Rect(0, -200, w, 100)), m.otherPanels)
        assertEquals("the words are the same words", c.translated, m.translated)
    }

    @Test
    fun theArtAroundASoundMovesWithIt() {
        val page = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(page)
        c.drawColor(Color.WHITE)
        // A drawn figure's outline in one band of the page.
        val ink = Paint().apply { color = Color.BLACK; strokeWidth = 6f }
        for (x in 0 until w step 12) c.drawLine(x.toFloat(), 800f, x + 6f, 900f, ink)
        val art = ArtMap.of(page)
        val there = RectF(100f, 800f, 300f, 900f)
        val empty = RectF(100f, 400f, 300f, 500f)
        assertTrue(art.drawnShare(there) > 0.3f)
        assertTrue(art.drawnShare(empty) < 0.05f)
        // Scrolled up by 300 rows: the drawing now sits 300 rows higher.
        val moved = art.shifted(-300)
        assertEquals(art.drawnShare(there), moved.drawnShare(RectF(100f, 500f, 300f, 600f)), 0.001f)
        assertEquals(art.drawnShare(empty), moved.drawnShare(RectF(100f, 100f, 300f, 200f)), 0.001f)
    }

    @Test
    fun onlyCardsStillWhollyOnScreenComeBack() {
        val cards = listOf(
            card(Rect(100, 150, 300, 210)),   // scrolled off the top
            card(Rect(100, 420, 300, 480)),   // into the top band
            card(Rect(100, 800, 300, 860)),   // well on screen
            card(Rect(100, 1500, 300, 1560)), // still on screen
        )
        val back = CardCarry.carried(cards, -400, h, ignoreTop = 48, ignoreBottom = 32)
        assertEquals(listOf(Rect(100, 400, 300, 460), Rect(100, 1100, 300, 1160)), back.map { it.box })
        // A toolbar over the rows one of them moved to: that one stays for the read.
        val covered = CardCarry.carried(cards, -400, h, ignoreTop = 48, ignoreBottom = 32) { it.top > 1000 }
        assertEquals(listOf(Rect(100, 1100, 300, 1160)), covered.map { it.box })
    }
}
