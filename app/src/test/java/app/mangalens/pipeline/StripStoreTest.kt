package app.mangalens.pipeline

import android.graphics.Color
import android.graphics.Rect
import app.mangalens.ocr.Balloon
import app.mangalens.ocr.BubbleKind
import app.mangalens.overlay.RenderBubble
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The store's promise is that a chapter, once translated, stays translated:
 * cards captured at one offset land back on their balloons at every other
 * offset, a balloon seen twice keeps its first card, claimed balloons are
 * excluded from further work, hash landmarks straighten out a drifted offset,
 * and an overflowing store forgets the far ends of the chapter first.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StripStoreTest {

    private val screenH = 1920

    private fun bubble(box: Rect, text: String = "line", balloon: Balloon? = null) = RenderBubble(
        box = box,
        translated = text,
        original = "src",
        bgColor = Color.WHITE,
        textColor = Color.BLACK,
        vertical = false,
        kind = BubbleKind.DIALOGUE,
        balloon = balloon,
    )

    private fun balloonAt(box: Rect) = Balloon(box, 2, 2, BooleanArray(4) { true }, inverted = false)

    @Test
    fun `cards ride the strip across offsets`() {
        val store = StripStore()
        val a = bubble(Rect(100, 200, 500, 400))
        val b = bubble(Rect(200, 1500, 600, 1700))
        store.add(listOf(a, b), listOf(11L, 22L), offset = 0)
        assertEquals(2, store.size())

        val atStart = store.visible(0, screenH)
        assertEquals(
            listOf(Rect(100, 200, 500, 400), Rect(200, 1500, 600, 1700)),
            atStart.map { it.box },
        )

        val scrolled = store.visible(1400, screenH)
        assertEquals(listOf(Rect(200, 100, 600, 300)), scrolled.map { it.box })
        assertEquals("line", scrolled[0].translated)

        assertTrue(store.visible(4000, screenH).isEmpty())
        // The caller's rect was copied on the way in, not adopted.
        assertEquals(Rect(100, 200, 500, 400), a.box)
    }

    @Test
    fun `handed out copies never expose stored geometry`() {
        val store = StripStore()
        val balloon = balloonAt(Rect(80, 180, 520, 420))
        val source = bubble(Rect(100, 200, 500, 400), balloon = balloon)
        store.add(listOf(source), listOf(7L), offset = 300)

        val seen = store.visible(450, screenH).single()
        assertEquals(Rect(100, 50, 500, 250), seen.box)
        assertEquals(Rect(80, 30, 520, 270), seen.balloon!!.box)

        // Overlay code mutates Rects freely; the store must not care.
        seen.box.offset(37, 91)
        seen.balloon!!.box.offset(37, 91)
        val again = store.visible(450, screenH).single()
        assertEquals(Rect(100, 50, 500, 250), again.box)
        assertEquals(Rect(80, 30, 520, 270), again.balloon!!.box)
        assertEquals(Rect(80, 180, 520, 420), balloon.box)
    }

    @Test
    fun `re-adding the same balloon keeps the first entry`() {
        val store = StripStore()
        store.add(listOf(bubble(Rect(100, 1500, 500, 1700), text = "the first line")), listOf(42L), offset = 0)
        // The reader scrolled back to it; detection re-found it 8 px off.
        store.add(listOf(bubble(Rect(104, 42, 504, 242), text = "a re-translation")), listOf(42L), offset = 1450)

        assertEquals(1, store.size())
        assertEquals("the first line", store.visible(1450, screenH).single().translated)
    }

    @Test
    fun `the same lettering far down the strip is a new balloon`() {
        val store = StripStore()
        store.add(listOf(bubble(Rect(100, 1500, 500, 1700))), listOf(42L), offset = 0)
        store.add(listOf(bubble(Rect(100, 100, 500, 300))), listOf(42L), offset = 2600)
        assertEquals(2, store.size())
    }

    @Test
    fun `a different balloon sharing the span stays alongside`() {
        val store = StripStore()
        store.add(listOf(bubble(Rect(100, 1500, 500, 1700))), listOf(42L), offset = 0)
        store.add(listOf(bubble(Rect(560, 1510, 940, 1690))), listOf(77L), offset = 0)
        assertEquals(2, store.size())
    }

    @Test
    fun `claimed rects agree with the visible cards`() {
        val store = StripStore()
        store.add(
            listOf(
                bubble(Rect(100, 200, 500, 400)),
                bubble(Rect(150, 1250, 550, 1450), balloon = balloonAt(Rect(120, 1220, 580, 1480))),
            ),
            listOf(1L, 2L),
            offset = 0,
        )

        // With a detected balloon the claim is the balloon, not the tighter card.
        assertEquals(
            listOf(Rect(100, 200, 500, 400), Rect(120, 1220, 580, 1480)),
            store.claimedRects(0, screenH),
        )

        val cards = store.visible(600, screenH)
        val claims = store.claimedRects(600, screenH)
        assertEquals(cards.size, claims.size)
        assertEquals(listOf(Rect(120, 620, 580, 880)), claims)
    }

    @Test
    fun `reground answers the median correction and refuses under two matches`() {
        val store = StripStore()
        store.add(
            listOf(
                bubble(Rect(100, 1000, 500, 1200)),
                bubble(Rect(120, 1400, 520, 1600)),
                bubble(Rect(140, 2000, 540, 2200)),
            ),
            listOf(1L, 2L, 3L),
            offset = 0,
        )

        // Believed 900, truly 950; each detection carries its own jitter.
        val detected = listOf(
            Rect(100, 50, 500, 250) to 1L,
            Rect(120, 453, 520, 653) to 2L,
            Rect(140, 1048, 540, 1248) to 3L,
        )
        assertEquals(950, store.reground(detected, 900)!!)

        assertNull(store.reground(detected.take(1), 900))
        assertNull(
            store.reground(
                listOf(Rect(0, 0, 10, 10) to 98L, Rect(0, 500, 10, 510) to 99L),
                900,
            ),
        )
    }

    @Test
    fun `layout alignment relocates the strip when every hash jitters`() {
        // The field failure: content hashes are exact-match and the same
        // balloon re-detected on a later pass crops a little differently, so
        // a revisit can produce zero hash votes — and a session must not be
        // wiped over a fingerprint quirk. The balloons' spacing pattern is
        // the landmark hashes cannot lose.
        val store = StripStore()
        store.add(
            listOf(
                bubble(Rect(100, 1000, 500, 1200)),
                bubble(Rect(120, 1400, 520, 1600)),
                bubble(Rect(140, 2000, 540, 2200)),
            ),
            listOf(1L, 2L, 3L),
            offset = 0,
        )

        // Truly at 950; every hash is unrecognizable.
        val detected = listOf(
            Rect(100, 50, 500, 250) to 111L,
            Rect(120, 450, 520, 650) to 222L,
            Rect(140, 1050, 540, 1250) to 333L,
        )
        assertEquals(950, store.reground(detected, 400)!!)

        // A page whose balloons land at unrelated spacings gathers no two
        // agreeing proposals, however many balloons it shows.
        val swapped = listOf(
            Rect(100, 120, 500, 320) to 444L,
            Rect(120, 700, 520, 900) to 555L,
            Rect(140, 1500, 540, 1700) to 666L,
        )
        assertNull(store.reground(swapped, 400))
    }

    @Test
    fun `an ambiguous hash votes with the entry nearest the belief`() {
        val store = StripStore()
        store.add(
            listOf(
                bubble(Rect(0, 1000, 400, 1200)),
                bubble(Rect(0, 2400, 400, 2600)),
                bubble(Rect(0, 1500, 400, 1700)),
            ),
            listOf(5L, 5L, 6L),
            offset = 0,
        )

        val detected = listOf(
            Rect(0, 60, 400, 260) to 5L,
            Rect(0, 565, 400, 765) to 6L,
        )
        assertEquals(940, store.reground(detected, 930)!!)
    }

    @Test
    fun `eviction keeps the entries nearest the reader`() {
        val store = StripStore(maxEntries = 3)
        store.add(
            listOf(
                bubble(Rect(0, 100, 400, 300)),
                bubble(Rect(0, 900, 400, 1100)),
                bubble(Rect(0, 1700, 400, 1900)),
                bubble(Rect(0, 2500, 400, 2700)),
            ),
            listOf(1L, 2L, 3L, 4L),
            offset = 0,
        )
        assertEquals(3, store.size())

        // Reading on: the new entry lands at strip 2900, and the top of the
        // chapter is now the farthest thing from the reader.
        store.add(listOf(bubble(Rect(0, 500, 400, 700))), listOf(5L), offset = 2400)
        assertEquals(3, store.size())
        assertEquals(listOf(900, 1700, 2900), store.visible(0, 10_000).map { it.box.top })
    }

    @Test
    fun `clear forgets the chapter`() {
        val store = StripStore()
        store.add(listOf(bubble(Rect(100, 200, 500, 400))), listOf(1L), offset = 0)
        store.clear()
        assertEquals(0, store.size())
        assertTrue(store.visible(0, screenH).isEmpty())
    }
}
