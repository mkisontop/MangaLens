package app.mangalens.gaps

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bit-parallel morphology is checked against the obvious pixel-by-pixel version, on
 * widths either side of a word boundary — the carry between words is where such code breaks.
 */
class BitPlaneTest {

    private val widths = intArrayOf(1, 5, 63, 64, 65, 100, 128, 130, 200)

    private fun random(w: Int, h: Int, seed: Int, density: Double): Pair<BitPlane, Array<BooleanArray>> {
        val r = Random(seed)
        val plane = BitPlane(w, h)
        val ref = Array(h) { BooleanArray(w) }
        for (y in 0 until h) for (x in 0 until w) if (r.nextDouble() < density) {
            plane.set(x, y)
            ref[y][x] = true
        }
        return plane to ref
    }

    private fun assertSame(ref: Array<BooleanArray>, plane: BitPlane, what: String) {
        for (y in ref.indices) for (x in ref[y].indices) {
            assertEquals("$what at ($x,$y) w=${plane.w}", ref[y][x], plane.get(x, y))
        }
        // padding bits must stay clear, or count() would lie
        var n = 0L
        for (row in ref) for (v in row) if (v) n++
        assertEquals("$what count w=${plane.w}", n, plane.count())
    }

    private fun naiveErode(src: Array<BooleanArray>, rx: Int, ry: Int, outside: Boolean): Array<BooleanArray> {
        val h = src.size
        val w = src[0].size
        return Array(h) { y ->
            BooleanArray(w) { x ->
                var all = true
                for (dy in -ry..ry) for (dx in -rx..rx) {
                    val yy = y + dy
                    val xx = x + dx
                    val v = if (yy in 0 until h && xx in 0 until w) src[yy][xx] else outside
                    if (!v) all = false
                }
                all
            }
        }
    }

    private fun naiveDilate(src: Array<BooleanArray>, rx: Int, ry: Int): Array<BooleanArray> {
        val h = src.size
        val w = src[0].size
        return Array(h) { y ->
            BooleanArray(w) { x ->
                var any = false
                for (dy in -ry..ry) for (dx in -rx..rx) {
                    val yy = y + dy
                    val xx = x + dx
                    if (yy in 0 until h && xx in 0 until w && src[yy][xx]) any = true
                }
                any
            }
        }
    }

    @Test
    fun `erosion and dilation match the naive window`() {
        for (w in widths) for (r in 1..3) for (outside in listOf(false, true)) {
            val (p, ref) = random(w, 9, w * 31 + r, 0.8)
            assertSame(naiveErode(ref, r, 0, outside), p.copy().erodeH(r, outside), "erodeH r=$r out=$outside")
            assertSame(naiveErode(ref, 0, r, outside), p.copy().erodeV(r, outside), "erodeV r=$r out=$outside")
            assertSame(naiveErode(ref, r, r, outside), p.copy().erode(r, outside), "erode r=$r out=$outside")
            assertSame(naiveDilate(ref, r, 0), p.copy().dilateH(r), "dilateH r=$r")
            assertSame(naiveDilate(ref, 0, r), p.copy().dilateV(r), "dilateV r=$r")
            assertSame(naiveDilate(ref, r, r), p.copy().dilate(r), "dilate r=$r")
        }
    }

    @Test
    fun `setRun and countRow agree with pixel access`() {
        val r = Random(5)
        for (w in widths) {
            val p = BitPlane(w, 3)
            val ref = Array(3) { BooleanArray(w) }
            repeat(12) {
                val y = r.nextInt(3)
                val a = r.nextInt(w + 1)
                val b = r.nextInt(w + 1)
                p.setRun(y, a, b)
                for (x in a until b) if (x in 0 until w) ref[y][x] = true
            }
            assertSame(ref, p, "setRun")
            for (y in 0 until 3) repeat(20) {
                val a = r.nextInt(w + 1)
                val b = r.nextInt(w + 1)
                var n = 0
                for (x in a until b) if (ref[y][x]) n++
                assertEquals(n, p.countRow(y, a, b))
            }
        }
    }

    @Test
    fun `runs iterate exactly the set pixels`() {
        for (w in widths) for (density in listOf(0.1, 0.5, 0.95, 1.0)) {
            val (p, ref) = random(w, 6, w + (density * 100).toInt(), density)
            for (y in 0 until 6) {
                val seen = BooleanArray(w)
                var lastEnd = -1
                p.forEachRun(y) { a, b ->
                    assertTrue("runs must be ordered and separated: $a after $lastEnd", a > lastEnd)
                    assertTrue(b in (a + 1)..w)
                    for (x in a until b) seen[x] = true
                    lastEnd = b
                }
                for (x in 0 until w) assertEquals("w=$w d=$density ($x,$y)", ref[y][x], seen[x])
            }
        }
    }

    @Test
    fun `boolean operations`() {
        val (a, ra) = random(130, 4, 1, 0.5)
        val (b, rb) = random(130, 4, 2, 0.5)
        val and = a.copy().and(b)
        val or = a.copy().or(b)
        val andNot = a.copy().andNot(b)
        for (y in 0 until 4) for (x in 0 until 130) {
            assertEquals(ra[y][x] && rb[y][x], and.get(x, y))
            assertEquals(ra[y][x] || rb[y][x], or.get(x, y))
            assertEquals(ra[y][x] && !rb[y][x], andNot.get(x, y))
        }
    }

    @Test
    fun `a long vertical erosion keeps exactly the pixels whose whole run of rows is set`() {
        val rnd = kotlin.random.Random(9)
        for (trial in 0 until 40) {
            val w = 70 + rnd.nextInt(130)
            val h = 50 + rnd.nextInt(250)
            val p = BitPlane(w, h)
            // bands of rows with holes, as gutters cut by balloons are
            for (y in 0 until h) {
                if (rnd.nextInt(10) < 8) p.setRun(y, rnd.nextInt(w / 3), w - rnd.nextInt(w / 3))
                if (rnd.nextInt(10) < 2) p.clearRun(y, rnd.nextInt(w), rnd.nextInt(w))
            }
            val r = 7 + rnd.nextInt(60)
            val outside = rnd.nextBoolean()
            val got = p.copy().erodeV(r, outside)
            for (y in 0 until h) for (x in 0 until w) {
                var all = true
                for (d in -r..r) {
                    val yy = y + d
                    val set = if (yy < 0 || yy >= h) outside else p.get(x, yy)
                    if (!set) { all = false; break }
                }
                assertEquals("trial $trial r=$r outside=$outside at ($x, $y)", all && p.get(x, y), got.get(x, y))
            }
            assertEquals("no bits past the right edge", got.count(), (0 until h).sumOf { got.countRow(it, 0, w).toLong() })
        }
    }
}
