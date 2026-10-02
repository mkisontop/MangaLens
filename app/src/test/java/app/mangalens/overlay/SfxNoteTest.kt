package app.mangalens.overlay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import app.mangalens.ocr.BubbleKind
import app.mangalens.overlay.LetteringFixtures.blank
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
 * small outlined note. The note sits on empty ground beside the sound, or
 * over the sound itself where everything around it is drawn — never on the
 * picture around it. It paints nothing behind itself, and leaves every pixel
 * of the page it does not cover exactly as the page had it.
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

    /** Plain paper with the same sound on it. */
    private fun paper(box: Rect): Bitmap {
        val page = blank(pageW, pageH, Color.WHITE)
        glyphs(Canvas(page), box, 2, 1, fill(Color.rgb(240, 120, 40)), outline(ink, 10f))
        return page
    }

    private fun note(box: Rect, text: String = "*ba-doom*", on: Bitmap? = null) = RenderBubble(
        box = box,
        translated = text,
        original = "ドドン",
        bgColor = Color.rgb(150, 120, 140),
        textColor = ink,
        vertical = false,
        kind = BubbleKind.SFX,
        style = LetterStyle.SFX_NOTE,
        outlineColor = Color.WHITE,
        art = on?.let(ArtMap::of),
    )

    private fun area(r: Rect) = r.width().toLong() * r.height()

    private fun overlap(a: Rect, b: Rect): Long {
        val r = Rect()
        return if (r.setIntersect(a, b)) area(r) else 0L
    }

    /** Pixels of [out] that differ from [page] outside [label]. */
    private fun changedOutside(page: Bitmap, out: Bitmap, label: Rect): Int {
        val a = pixels(page)
        val b = pixels(out)
        var changed = 0
        for (y in 0 until pageH) for (x in 0 until pageW) {
            if (label.contains(x, y)) continue
            if (a[y * pageW + x] != b[y * pageW + x]) changed++
        }
        return changed
    }

    @Test
    fun `on empty paper the note hangs just below its sound`() {
        val box = Rect(180, 260, 480, 510)
        val page = paper(box)
        val v = view()
        v.setBubbles(listOf(note(box, on = page)))
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
        assertEquals("no pixel of the page outside the note may change", 0, changedOutside(page, out, label))

        val overlay = overlayOnly(v, pageW, pageH)
        assertTrue("dark lettering", count(overlay, label) { near(it, ink, 20) } > 40)
        assertTrue("in a white outline", count(overlay, label) { near(it, Color.WHITE, 12) } > 40)
        val painted = count(overlay, label) { Color.alpha(it) != 0 }
        assertTrue("no card behind it ($painted of ${area(label)})", painted < area(label) * 0.8f)
        assertEquals(0, uncovered(overlay, v.placedRects()))
    }

    @Test
    fun `where the art around a sound is drawn, the note sits on the sound, not the picture`() {
        val box = Rect(180, 260, 480, 510)
        val page = page(box)
        val v = view()
        v.setBubbles(listOf(note(box, on = page)))
        val out = render(v, page)
        writePreview("sfx-note-on-art.png", out)
        val label = v.placedRects().single()
        assertTrue("inside the sound ($label in $box)", Rect(box).apply { inset(-2, -2) }.contains(label))
        assertTrue(
            "centred on it ($label in $box)",
            Math.abs(label.centerX() - box.centerX()) <= 2 && Math.abs(label.centerY() - box.centerY()) <= 2,
        )
        assertEquals("no pixel of the page outside the note may change", 0, changedOutside(page, out, label))
        assertEquals(0, uncovered(overlayOnly(v, pageW, pageH), v.placedRects()))
    }

    @Test
    fun `a note at the foot of the screen goes above its sound`() {
        val box = Rect(120, 640, 420, 896)
        val page = paper(box)
        val v = view()
        v.setBubbles(listOf(note(box, "*thooom*", on = page)))
        writePreview("sfx-note-above.png", render(v, page))
        val label = v.placedRects().single()
        assertTrue("above the sound ($label over $box)", label.bottom <= box.top + 4 && label.bottom >= box.top - 12)
        assertTrue(overlap(label, box) * 100 < area(box) * 15)
        assertEquals(0, uncovered(overlayOnly(v, pageW, pageH), v.placedRects()))
    }

    @Test
    fun `a note steps off lettering already there`() {
        val box = Rect(180, 200, 480, 420)
        val page = paper(box)
        val below = Rect(box.left, box.bottom + 2, box.left + 260, box.bottom + 40)
        val speech = RenderBubble(
            below, "Hey, over here!", "おーい！", Color.WHITE, ink, false,
            patch = Bitmap.createBitmap(below.width(), below.height(), Bitmap.Config.ARGB_8888),
            patchRect = below,
        )
        val v = view()
        v.setBubbles(listOf(speech, note(box, on = page)))
        writePreview("sfx-note-nudged.png", render(v, page))
        val (text, label) = v.placedRects()
        assertEquals("the note does not sit on the speech ($label vs $text)", 0L, overlap(label, text))
        assertTrue(overlap(label, box) * 100 < area(box) * 15)
    }

    @Test
    fun `a sound repeated down a column is said twice, on one line, running down the column`() {
        val column = Rect(560, 120, 620, 520)
        val page = page(column)
        val v = view()
        v.setBubbles(listOf(note(column, "*ba-dump* *ba-dump* *ba-dump* *ba-dump*", on = page)))
        val out = render(v, page)
        writePreview("sfx-note-column.png", out)
        val label = v.placedRects().single()
        val one = view().apply { setBubbles(listOf(note(Rect(100, 100, 400, 300), "*ba-dump*"))) }.placedRects().single()
        assertEquals("a single line, turned (${label.width()} vs ${one.height()})", one.height(), label.width())
        assertTrue("running down the column ($label)", label.height() > label.width() * 3)
        assertTrue("on the column ($label on $column)", Math.abs(label.centerX() - column.centerX()) <= 2)
        assertTrue("within its length ($label on $column)", label.top >= column.top && label.bottom <= column.bottom)
        assertEquals("no pixel of the page outside the note may change", 0, changedOutside(page, out, label))
        assertEquals(0, uncovered(overlayOnly(v, pageW, pageH), v.placedRects()))
    }

    @Test
    fun `beside a column on paper, the note stands by its first characters`() {
        val column = Rect(300, 120, 360, 520)
        val page = paper(column)
        val v = view()
        v.setBubbles(listOf(note(column, "*ba-dump* *ba-dump*", on = page)))
        writePreview("sfx-note-column-paper.png", render(v, page))
        val label = v.placedRects().single()
        assertEquals("nothing of the column is covered ($label)", 0L, overlap(label, column))
        assertTrue("beside its top ($label by $column)", label.top in column.top - 12..column.top + 12)
    }

    @Test
    fun `the same sound close by is noted once, a distant one again`() {
        val left = Rect(100, 150, 150, 450)
        val near = Rect(250, 200, 300, 500)
        val far = Rect(560, 150, 610, 450)
        val v = view()
        v.setBubbles(listOf(note(left, "*ba-dump ba-dump*"), note(near, "*ba-dump ba-dump*"), note(far, "*ba-dump ba-dump*")))
        val labels = v.placedRects()
        assertEquals("the neighbour's heartbeat shares the first note ($labels)", 2, labels.size)
    }

    @Test
    fun `a note never lands on lettering listed after it, nor on another sound`() {
        val box = Rect(180, 200, 480, 420)
        val other = Rect(160, 60, 500, 196)
        val below = Rect(box.left, box.bottom + 2, box.left + 260, box.bottom + 40)
        val speech = RenderBubble(
            below, "Hey, over here!", "おーい！", Color.WHITE, ink, false,
            patch = Bitmap.createBitmap(below.width(), below.height(), Bitmap.Config.ARGB_8888),
            patchRect = below,
        )
        val page = paper(box).also { glyphs(Canvas(it), other, 3, 1, fill(Color.rgb(240, 120, 40)), outline(ink, 10f)) }
        val v = view()
        v.setBubbles(listOf(note(box, on = page), note(other, "*creak*", on = page), speech))
        val rects = v.placedRects()
        val text = rects.first()
        val labels = rects.drop(1)
        assertEquals(2, labels.size)
        for (label in labels) {
            assertEquals("the note keeps off the speech ($label vs $text)", 0L, overlap(label, text))
        }
        assertEquals("the first note keeps off the other sound", 0L, overlap(labels[0], other))
        assertEquals("and the second off the first sound", 0L, overlap(labels[1], box))
    }

    @Test
    fun `a note never crosses into the next panel`() {
        // The sound fills the foot of the screen, so the note cannot go
        // below it, and the panel above starts just over its top: on empty
        // paper "above" would otherwise be taken.
        val box = Rect(180, 640, 480, 896)
        val page = paper(box)
        val above = Rect(20, 20, 700, 632)
        val v = view()
        v.setBubbles(listOf(note(box, on = page).copy(otherPanels = listOf(above))))
        val label = v.placedRects().single()
        assertEquals("the note stays out of the panel above ($label)", 0L, overlap(label, above))
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
