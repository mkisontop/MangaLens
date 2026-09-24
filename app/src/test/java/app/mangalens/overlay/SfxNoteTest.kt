package app.mangalens.overlay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import app.mangalens.ocr.BubbleKind
import app.mangalens.overlay.LetteringFixtures.count
import app.mangalens.overlay.LetteringFixtures.fill
import app.mangalens.overlay.LetteringFixtures.glyphs
import app.mangalens.overlay.LetteringFixtures.near
import app.mangalens.overlay.LetteringFixtures.noiseArt
import app.mangalens.overlay.LetteringFixtures.outline
import app.mangalens.overlay.LetteringFixtures.overlayOnly
import app.mangalens.overlay.LetteringFixtures.pixels
import app.mangalens.overlay.LetteringFixtures.render
import app.mangalens.overlay.LetteringFixtures.uncovered
import app.mangalens.overlay.LetteringFixtures.writePreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A sound effect drawn into detailed art stays in the art; its English is a
 * small outlined note beside it. The note must be small, sit off the sound
 * rather than on it, paint nothing behind itself, and leave every pixel of
 * the sound it does not overlap exactly as the page had it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SfxNoteTest {

    private val pageW = 720
    private val pageH = 900
    private val ink = Color.rgb(20, 20, 24)

    private fun view(): BubbleOverlayView =
        BubbleOverlayView(RuntimeEnvironment.getApplication()).apply { layout(0, 0, pageW, pageH) }

    /** Busy art with a big orange sound drawn across [box]. */
    private fun page(box: Rect): Bitmap {
        val page = noiseArt(pageW, pageH, seed = 31)
        glyphs(Canvas(page), box, 2, 1, fill(Color.rgb(240, 120, 40)), outline(ink, 10f))
        return page
    }

    private fun note(box: Rect, text: String = "*ba-doom*") = RenderBubble(
        box = box,
        translated = text,
        original = "ドドン",
        bgColor = Color.rgb(150, 120, 140),
        textColor = ink,
        vertical = false,
        kind = BubbleKind.SFX,
        style = LetterStyle.SFX_NOTE,
        outlineColor = Color.WHITE,
    )

    private fun area(r: Rect) = r.width().toLong() * r.height()

    private fun overlap(a: Rect, b: Rect): Long {
        val r = Rect()
        return if (r.setIntersect(a, b)) area(r) else 0L
    }

    @Test
    fun `a sound drawn into the art gets a small note beside it, not a redraw`() {
        val box = Rect(180, 260, 480, 510)
        val page = page(box)
        val v = view()
        v.setBubbles(listOf(note(box)))
        val out = render(v, page)
        writePreview("sfx-note.png", out)

        val label = v.placedRects().single()
        assertTrue(
            "the note covers under 15% of the sound (${overlap(label, box)} of ${area(box)})",
            overlap(label, box) * 100 < area(box) * 15,
        )
        assertTrue(
            "it hangs just below the sound ($label under $box)",
            label.top >= box.bottom - 4 && label.top <= box.bottom + 12,
        )
        assertTrue("from its left edge ($label)", Math.abs(label.left - box.left) < 12)
        assertTrue("and stays small (${label.height()} px tall)", label.height() < 40)

        // The sound itself is untouched wherever the note is not.
        val a = pixels(page)
        val b = pixels(out)
        var changed = 0
        for (y in box.top until box.bottom) for (x in box.left until box.right) {
            if (label.contains(x, y)) continue
            if (a[y * pageW + x] != b[y * pageW + x]) changed++
        }
        assertEquals("no pixel of the sound outside the note may change", 0, changed)

        val overlay = overlayOnly(v, pageW, pageH)
        assertTrue("dark lettering", count(overlay, label) { near(it, ink, 20) } > 40)
        assertTrue("in a white outline", count(overlay, label) { near(it, Color.WHITE, 12) } > 40)
        val painted = count(overlay, label) { Color.alpha(it) != 0 }
        assertTrue("no card behind it ($painted of ${area(label)})", painted < area(label) * 0.8f)
        assertEquals(0, uncovered(overlay, v.placedRects()))
    }

    @Test
    fun `a note at the foot of the screen goes above its sound`() {
        val box = Rect(120, 640, 420, 896)
        val page = page(box)
        val v = view()
        v.setBubbles(listOf(note(box, "*thooom*")))
        writePreview("sfx-note-above.png", render(v, page))
        val label = v.placedRects().single()
        assertTrue("above the sound ($label over $box)", label.bottom <= box.top + 4 && label.bottom >= box.top - 12)
        assertTrue(overlap(label, box) * 100 < area(box) * 15)
        assertEquals(0, uncovered(overlayOnly(v, pageW, pageH), v.placedRects()))
    }

    @Test
    fun `a note steps off lettering already there`() {
        val box = Rect(180, 200, 480, 420)
        val page = page(box)
        val below = Rect(box.left, box.bottom + 2, box.left + 260, box.bottom + 40)
        val speech = RenderBubble(
            below, "Hey, over here!", "おーい！", Color.WHITE, ink, false,
            patch = Bitmap.createBitmap(below.width(), below.height(), Bitmap.Config.ARGB_8888),
            patchRect = below,
        )
        val v = view()
        v.setBubbles(listOf(speech, note(box)))
        writePreview("sfx-note-nudged.png", render(v, page))
        val (text, label) = v.placedRects()
        assertTrue(
            "the note does not sit on the speech ($label vs $text)",
            overlap(label, text) * 100 < area(label) * 35,
        )
    }

    @Test
    fun `a note follows the reader's text size, not the sound's`() {
        val small = Rect(300, 300, 360, 340)
        val huge = Rect(60, 60, 660, 560)
        val a = view().apply { setBubbles(listOf(note(small))) }.placedRects().single()
        val b = view().apply { setBubbles(listOf(note(huge))) }.placedRects().single()
        assertEquals("the same size for any sound", a.height(), b.height())
        val big = view().apply { textScale = 1.5f; setBubbles(listOf(note(huge))) }.placedRects().single()
        assertTrue("larger at a larger text scale (${big.height()} vs ${b.height()})", big.height() > b.height() * 1.3f)
    }
}
