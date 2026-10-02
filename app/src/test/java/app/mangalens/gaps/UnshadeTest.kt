package app.mangalens.gaps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnshadeTest {

    private val w = 720
    private val h = 1400

    @Test
    fun `restoring a shaded frame gives back the page it was drawn on`() {
        for (level in ShadeLevel.values()) {
            val style = ShadeStyle(level)
            val s = scrollStrip(w)
            val page = s.frame(2300, h)
            val r = GapFinder.find(PlaneBuilder.build(ArrayPixels(w, h, page), 1, style))
            val shaded = composite(page, w, h, r.rects, r.rectCount, style)
            val restored = shaded.copyOf()
            Unshade.restore(restored, w, w, h, r.rects, r.rectCount, 0, style)
            var bad = 0
            for (i in page.indices) {
                val a = page[i] and 0xFFFFFF
                val b = restored[i] and 0xFFFFFF
                // paper comes back as paper; everything else is identical
                if (Strip.isPaper(page[i])) { if (!Strip.isPaper(restored[i])) bad++ } else if (a != b) bad++
            }
            assertEquals("level $level", 0, bad)
        }
    }

    @Test
    fun `an overlay that was not where we thought is not whitened over art`() {
        val style = ShadeStyle()
        val s = scrollStrip(w)
        val page = s.frame(2300, h)
        val r = GapFinder.find(PlaneBuilder.build(ArrayPixels(w, h, page), 1, style))
        // the shade is on the glass 30 rows lower than we believe
        val shaded = composite(page, w, h, r.rects, r.rectCount, style, dy = 30)
        val restored = shaded.copyOf()
        Unshade.restore(restored, w, w, h, r.rects, r.rectCount, 0, style)
        for (i in page.indices) {
            if (!Strip.isPaper(page[i])) {
                // art stays what the capture showed (dimmed or not): never turned white
                assertTrue("art whitened at $i", !Strip.isPaper(restored[i]))
            }
        }
    }

    @Test
    fun `a corrected thumbnail matches the thumbnail of the unshaded page`() {
        val style = ShadeStyle()
        val s = scrollStrip(w)
        val page = s.frame(2300, h)
        val r = GapFinder.find(PlaneBuilder.build(ArrayPixels(w, h, page), 1, style))
        val shaded = composite(page, w, h, r.rects, r.rectCount, style)
        val size = 96
        fun thumb(f: IntArray): IntArray {
            val out = IntArray(size * size)
            for (cy in 0 until size) for (cx in 0 until size) {
                var sum = 0L
                var n = 0
                val y0 = cy * h / size
                val y1 = (cy + 1) * h / size
                val x0 = cx * w / size
                val x1 = (cx + 1) * w / size
                for (y in y0 until y1) for (x in x0 until x1) {
                    val p = f[y * w + x]
                    sum += (((p ushr 16) and 0xFF) * 299 + ((p ushr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
                    n++
                }
                out[cy * size + cx] = (sum / n).toInt()
            }
            return out
        }
        val clean = thumb(page)
        val dark = thumb(shaded)
        val corrected = dark.copyOf()
        Unshade.correctThumb(corrected, Unshade.thumbCoverage(r.rects, r.rectCount, 0, w, h, size), style)
        var worst = 0
        var rawWorst = 0
        for (i in clean.indices) {
            worst = maxOf(worst, kotlin.math.abs(clean[i] - corrected[i]))
            rawWorst = maxOf(rawWorst, kotlin.math.abs(clean[i] - dark[i]))
        }
        assertTrue("the shade changes thumbnails a great deal: $rawWorst", rawWorst > 150)
        assertTrue("and the correction takes it back to within a few levels: $worst", worst <= 6)
    }

    @Test
    fun `coverage of a rectangle is its area share of each cell`() {
        val cov = Unshade.thumbCoverage(intArrayOf(0, 0, 100, 50), 1, 0, 200, 200, 4)
        // cells are 50x50: the rectangle fills cells (0,0) and (1,0) entirely
        assertEquals(1f, cov[0], 1e-6f)
        assertEquals(1f, cov[1], 1e-6f)
        assertEquals(0f, cov[2], 1e-6f)
        assertEquals(0f, cov[4], 1e-6f)
        val half = Unshade.thumbCoverage(intArrayOf(25, 0, 75, 50), 1, 0, 200, 200, 4)
        assertEquals(0.5f, half[0], 1e-6f)
        assertEquals(0.5f, half[1], 1e-6f)
    }
}
