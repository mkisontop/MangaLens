package app.mangalens.gaps

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Random strips, built from every ingredient a manhwa page is made of, with two invariants
 * that must hold whatever the arrangement: the shade lands on paper and only on paper, and
 * the finder reads its own shaded output as the page it was drawn on — otherwise the shade
 * would flicker, or take itself down, on exactly the pages that confuse it.
 */
class GapFinderFuzzTest {

    private val w = 720
    private val h = 1400

    private fun randomStrip(seed: Long): Strip {
        val r = Random(seed)
        val s = Strip(w, h, seed)
        var y = 0
        // A strip is art and gutters, alternating, from the top.
        while (y < h) {
            val kind = r.nextInt(10)
            val height = 120 + r.nextInt(260)
            val end = minOf(h, y + height)
            when {
                kind <= 3 -> {
                    // art: full width, or inset from one side, sometimes with black rules, sometimes faded into the white
                    val inset = if (r.nextInt(3) == 0) 40 + r.nextInt(160) else 0
                    val left = if (r.nextBoolean()) inset else 0
                    val right = if (left == 0) w - inset else w
                    s.art(left, y, right, end, r.nextInt(360))
                    if (r.nextInt(3) == 0) s.rules(left, y, right, end, 4 + r.nextInt(5))
                    if (r.nextInt(4) == 0) fade(s, left, right, end, 20 + r.nextInt(60))
                }
                kind <= 5 -> {
                    // a plain gutter with a balloon, perhaps leaking, perhaps two
                    val rx = 100 + r.nextInt(180)
                    val ry = minOf(30 + r.nextInt(70), (end - y) / 2 - 8)
                    if (ry > 20) {
                        s.balloon(rx + 40 + r.nextInt(w - 2 * rx - 80), (y + end) / 2, rx, ry, 3 + r.nextInt(5), if (r.nextInt(3) == 0) 3 + r.nextInt(8) else 0)
                    }
                }
                kind == 6 -> s.text(40 + r.nextInt(80), y + 10, w - 40 - r.nextInt(80), end - 10)
                kind == 7 -> {
                    // coloured lettering on the white
                    s.text(80, y + 15, w - 120, end - 15, Strip.rgb(200 + r.nextInt(55), r.nextInt(80), r.nextInt(80)))
                }
                kind == 8 -> {
                    // pale band the gutter runs into, a thin rule, or a thin white line inside art
                    when (r.nextInt(3)) {
                        0 -> s.solid(0, y, w, y + 8 + r.nextInt(20), Strip.rgb(232 + r.nextInt(8), 232 + r.nextInt(8), 232 + r.nextInt(8)))
                        1 -> s.solid(60, y + 20, w - 60, y + 24, Strip.BLACK)
                        else -> { s.art(0, y, w, end); s.solid(0, y + 40, w, y + 43, Strip.WHITE) }
                    }
                }
                else -> { /* bare gutter */ }
            }
            y = end
        }
        if (r.nextInt(3) == 0) {
            // black bars either side
            s.solid(0, 0, 30, h, Strip.rgb(8, 8, 8))
            s.solid(w - 30, 0, w, h, Strip.rgb(8, 8, 8))
        }
        return s
    }

    private fun fade(s: Strip, left: Int, right: Int, end: Int, rows: Int) {
        for (i in 0 until rows) {
            val y = end - rows + i
            if (y !in 0 until h) continue
            val t = (i + 1).toFloat() / rows
            for (x in left until right) {
                val p = s.px[y * w + x]
                fun mix(c: Int) = (c + (255 - c) * t).toInt().coerceIn(0, 255)
                s.px[y * w + x] = Strip.rgb(mix((p ushr 16) and 0xFF), mix((p ushr 8) and 0xFF), mix(p and 0xFF))
            }
        }
    }

    @Test
    fun `on any arrangement the shade lands on paper only and reads back as the same page`() {
        val style = ShadeStyle(ShadeLevel.DARK)
        var scenesWithShade = 0
        for (seed in 1L..60L) {
            val s = randomStrip(seed)
            val page = s.px
            val first = GapFinder.find(PlaneBuilder.build(ArrayPixels(w, h, page), 1, style), 0, GapParams(), true)
            val cov = coverage(first.rects, first.rectCount, w, h)
            assertEquals("seed $seed: shade covers art", 0, artDamage(page, cov, w, h))

            if (first.rectCount > 0) scenesWithShade++
            // and the finder, reading its own shaded output, finds the same shade
            val shaded = composite(page, w, h, first.rects, first.rectCount, style)
            val second = GapFinder.find(PlaneBuilder.build(ArrayPixels(w, h, shaded), 1, style), 0, GapParams(), true)
            val cov2 = coverage(second.rects, second.rectCount, w, h)
            var differ = 0
            var total = 0
            for (i in cov.indices) {
                if (cov[i] || cov2[i]) total++
                if (cov[i] != cov2[i]) differ++
            }
            assertTrue("seed $seed: shaded frame read differently from the clean one: $differ of $total", differ <= 0.01 * total + 50)

            // and with a margin, still paper only
            val moving = GapFinder.find(PlaneBuilder.build(ArrayPixels(w, h, shaded), 2, style), 12, GapParams(), true)
            val cov3 = coverage(moving.rects, moving.rectCount, w, h)
            assertEquals("seed $seed: half-resolution shade covers art", 0, artDamage(page, cov3, w, h, reach = 4))
        }
        assertTrue("the random scenes should mostly contain a gutter: $scenesWithShade of 60", scenesWithShade >= 40)
    }
}
