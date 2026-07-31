package app.mangalens.translate

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The regression this guards: the vision cache used to key a page on its
 * regions' OCR text, and on a manhwa OCR routinely reads nothing — so every
 * scroll stop with the same number of unreadable balloons shared one key, and
 * the previous stop's translations were replayed onto whatever balloons now
 * sat at those positions. A page's identity must follow its content: same
 * balloons anywhere on screen → same key; different balloons at the same
 * position → different keys.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PageKeyTest {

    private val width = 1080
    private val height = 1920

    /**
     * A page holding one balloon at [box] whose lettering layout is decided by
     * [seed] — the same seed paints the same lines wherever the balloon sits.
     */
    private fun pageWith(box: Rect, seed: Int): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 6f
            color = Color.BLACK
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        canvas.drawOval(RectF(box), ink)
        val rows = 3 + seed % 3
        val rowH = box.height() / (rows * 2 + 1)
        for (r in 0 until rows) {
            val top = box.top + rowH * (r * 2 + 1)
            val inset = 36 + ((r * 53 + seed * 137) % 110)
            canvas.drawRect(Rect(box.left + inset, top, box.right - inset, top + rowH / 2), fill)
        }
        return bmp
    }

    private fun keyOf(bitmap: Bitmap, boxes: List<Rect>, texts: List<String>): String =
        PageKey.of(
            boxes.mapIndexed { i, box ->
                PageKey.token(texts[i]) { PageKey.regionHash(bitmap, box) }
            }
        )

    @Test
    fun `two unreadable pages with different lettering never share an identity`() {
        val box = Rect(600, 200, 1000, 430)
        val pageA = pageWith(box, seed = 1)
        val pageB = pageWith(box, seed = 2)

        // Same screen position, same (empty) OCR text — the old key collided here.
        val keyA = keyOf(pageA, listOf(box), listOf(""))
        val keyB = keyOf(pageB, listOf(box), listOf(""))
        assertNotEquals("different balloons at one position must not collide", keyA, keyB)
    }

    @Test
    fun `a balloon keeps its identity wherever the scroll leaves it`() {
        val topBox = Rect(600, 200, 1000, 430)
        val movedBox = Rect(600, 1100, 1000, 1330)
        val before = pageWith(topBox, seed = 5)
        val after = pageWith(movedBox, seed = 5)

        val keyBefore = keyOf(before, listOf(topBox), listOf(""))
        val keyAfter = keyOf(after, listOf(movedBox), listOf(""))
        assertEquals("the same content must hit the cache at any offset", keyBefore, keyAfter)
    }

    @Test
    fun `region count changes the identity even when every region is unreadable`() {
        assertNotEquals(
            PageKey.of(List(3) { "px:0" }),
            PageKey.of(List(5) { "px:0" }),
        )
    }

    @Test
    fun `token boundaries cannot blur into each other`() {
        assertNotEquals(PageKey.of(listOf("가나", "다")), PageKey.of(listOf("가", "나다")))
    }

    @Test
    fun `text is the identity when OCR read any and pixels are not consulted`() {
        val token = PageKey.token("안녕하세요") { fail("hash must not run for readable text"); 0L }
        assertEquals("안녕하세요", token)
    }

    @Test
    fun `mixed pages differ when only their unreadable balloon differs`() {
        val readBox = Rect(80, 300, 480, 500)
        val blindBox = Rect(600, 1100, 1000, 1330)
        val pageA = pageWith(blindBox, seed = 7)
        val pageB = pageWith(blindBox, seed = 8)

        val keyA = keyOf(pageA, listOf(readBox, blindBox), listOf("같은 대사", ""))
        val keyB = keyOf(pageB, listOf(readBox, blindBox), listOf("같은 대사", ""))
        assertNotEquals(keyA, keyB)
    }

    @Test
    fun `the same box over the same page always hashes the same`() {
        val box = Rect(600, 200, 1000, 430)
        val page = pageWith(box, seed = 3)
        assertEquals(PageKey.regionHash(page, box), PageKey.regionHash(page, box))
    }

    @Test
    fun `a jittered detection box stays close enough that only a miss can result`() {
        // Balloon detection re-finds the same balloon a few pixels off between
        // stops, and capture noise perturbs the frame besides — bit-exact
        // stability under pure box jitter is not achievable, and is not
        // needed: a flipped bit is a cache miss and a fresh translation,
        // never a replay onto the wrong balloon. The ink-anchored window
        // keeps the drift to a few near-threshold bits, far from the
        // wholesale divergence that separates different lettering.
        val box = Rect(600, 200, 1000, 430)
        val page = pageWith(box, seed = 3)
        val jittered = Rect(box).apply { offset(4, -3) }
        val drift = java.lang.Long.bitCount(
            PageKey.regionHash(page, box) xor PageKey.regionHash(page, jittered)
        )
        val otherLettering = java.lang.Long.bitCount(
            PageKey.regionHash(page, box) xor PageKey.regionHash(pageWith(box, seed = 4), box)
        )
        println("jitter drift $drift bits; different lettering $otherLettering bits")
        assertTrue("jitter must stay within a few bits (was $drift)", drift <= 6)
        assertTrue(
            "different lettering must diverge beyond jitter (jitter $drift, other $otherLettering)",
            otherLettering > drift,
        )
    }
}
