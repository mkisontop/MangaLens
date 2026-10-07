package app.mangalens.gaps

/**
 * A black-and-white image, one bit per pixel, packed so that the morphology
 * the gap finder needs runs 64 pixels per machine operation.
 *
 * Bit `x and 63` of word `y * wpr + (x ushr 6)` is pixel x of row y; the least
 * significant bit is the leftmost pixel. Every operation keeps the padding bits
 * past the right edge clear, so [count] and the run iterators never see them.
 *
 * Erosion and dilation are by a square window, applied as repeated one-pixel
 * steps: the radii this code uses are a handful of pixels, and a step is a few
 * word operations per row.
 */
class BitPlane(val w: Int, val h: Int) {

    val wpr: Int = (w + 63) ushr 6
    val bits = LongArray(wpr * h)

    /** Bits of the last word of a row that lie inside the image. */
    private val tail: Long = if (w and 63 == 0) -1L else (1L shl (w and 63)) - 1

    fun get(x: Int, y: Int): Boolean =
        x in 0 until w && y in 0 until h && ((bits[y * wpr + (x ushr 6)] ushr (x and 63)) and 1L) != 0L

    fun set(x: Int, y: Int) {
        if (x in 0 until w && y in 0 until h) bits[y * wpr + (x ushr 6)] = bits[y * wpr + (x ushr 6)] or (1L shl (x and 63))
    }

    /** Sets pixels [x0, x1) of row [y]. */
    fun setRun(y: Int, x0: Int, x1: Int) {
        if (y !in 0 until h) return
        val a = x0.coerceIn(0, w)
        val b = x1.coerceIn(0, w)
        if (a >= b) return
        val row = y * wpr
        val wa = a ushr 6
        val wb = (b - 1) ushr 6
        val first = -1L shl (a and 63)
        val last = if (b and 63 == 0) -1L else (1L shl (b and 63)) - 1
        if (wa == wb) {
            bits[row + wa] = bits[row + wa] or (first and last)
        } else {
            bits[row + wa] = bits[row + wa] or first
            for (k in wa + 1 until wb) bits[row + k] = -1L
            bits[row + wb] = bits[row + wb] or last
        }
    }

    /** Clears pixels [x0, x1) of row [y]. */
    fun clearRun(y: Int, x0: Int, x1: Int) {
        if (y !in 0 until h) return
        val a = x0.coerceIn(0, w)
        val b = x1.coerceIn(0, w)
        if (a >= b) return
        val row = y * wpr
        val wa = a ushr 6
        val wb = (b - 1) ushr 6
        val first = -1L shl (a and 63)
        val last = if (b and 63 == 0) -1L else (1L shl (b and 63)) - 1
        if (wa == wb) {
            bits[row + wa] = bits[row + wa] and (first and last).inv()
        } else {
            bits[row + wa] = bits[row + wa] and first.inv()
            for (k in wa + 1 until wb) bits[row + k] = 0L
            bits[row + wb] = bits[row + wb] and last.inv()
        }
    }

    /** Clears rows [y0, y1). */
    fun clearRows(y0: Int, y1: Int) {
        val a = y0.coerceIn(0, h)
        val b = y1.coerceIn(a, h)
        bits.fill(0L, a * wpr, b * wpr)
    }

    fun copy(): BitPlane = BitPlane(w, h).also { System.arraycopy(bits, 0, it.bits, 0, bits.size) }

    fun clear() = bits.fill(0L)

    fun isEmpty(): Boolean {
        for (v in bits) if (v != 0L) return false
        return true
    }

    /** Number of set pixels. */
    fun count(): Long {
        var n = 0L
        for (v in bits) n += java.lang.Long.bitCount(v)
        return n
    }

    /** Number of set pixels of row [y] in [x0, x1). */
    fun countRow(y: Int, x0: Int, x1: Int): Int {
        val a = x0.coerceIn(0, w)
        val b = x1.coerceIn(0, w)
        if (y !in 0 until h || a >= b) return 0
        val row = y * wpr
        val wa = a ushr 6
        val wb = (b - 1) ushr 6
        val first = -1L shl (a and 63)
        val last = if (b and 63 == 0) -1L else (1L shl (b and 63)) - 1
        if (wa == wb) return java.lang.Long.bitCount(bits[row + wa] and first and last)
        var n = java.lang.Long.bitCount(bits[row + wa] and first)
        for (k in wa + 1 until wb) n += java.lang.Long.bitCount(bits[row + k])
        n += java.lang.Long.bitCount(bits[row + wb] and last)
        return n
    }

    fun and(o: BitPlane): BitPlane {
        require(o.w == w && o.h == h)
        for (i in bits.indices) bits[i] = bits[i] and o.bits[i]
        return this
    }

    fun or(o: BitPlane): BitPlane {
        require(o.w == w && o.h == h)
        for (i in bits.indices) bits[i] = bits[i] or o.bits[i]
        return this
    }

    /** Clears every pixel that is set in [o]. */
    fun andNot(o: BitPlane): BitPlane {
        require(o.w == w && o.h == h)
        for (i in bits.indices) bits[i] = bits[i] and o.bits[i].inv()
        return this
    }

    /**
     * Shrinks every run of set pixels by [r] on each side. [outside] is what lies
     * beyond the left and right edges: true when the edge is not a wall (an
     * erosion used to seal gaps must not carve the frame's own border), false
     * when it is.
     */
    fun erodeH(r: Int, outside: Boolean = false): BitPlane {
        val pad = if (outside) tail.inv() else 0L
        val row = LongArray(wpr)
        repeat(r) {
            for (y in 0 until h) {
                val base = y * wpr
                for (k in 0 until wpr) row[k] = bits[base + k]
                if (outside) row[wpr - 1] = row[wpr - 1] or pad
                for (k in 0 until wpr) {
                    val cur = row[k]
                    val prevBit = if (k > 0) row[k - 1] ushr 63 else if (outside) 1L else 0L
                    val nextBit = if (k < wpr - 1) row[k + 1] shl 63 else if (outside) Long.MIN_VALUE else 0L
                    val left = (cur shl 1) or prevBit
                    val right = (cur ushr 1) or nextBit
                    var v = cur and left and right
                    if (k == wpr - 1) v = v and tail
                    bits[base + k] = v
                }
            }
        }
        return this
    }

    /** Grows every run of set pixels by [r] on each side. */
    fun dilateH(r: Int): BitPlane {
        repeat(r) {
            for (y in 0 until h) {
                val base = y * wpr
                var carry = 0L
                for (k in 0 until wpr) {
                    val cur = bits[base + k]
                    val nextBit = if (k < wpr - 1) bits[base + k + 1] shl 63 else 0L
                    var v = cur or (cur shl 1) or carry or (cur ushr 1) or nextBit
                    carry = cur ushr 63
                    if (k == wpr - 1) v = v and tail
                    bits[base + k] = v
                }
            }
        }
        return this
    }

    /**
     * Shrinks every vertical run of set pixels by [r] at each end. [outside] is
     * what lies above the first row and below the last: false when the frame's
     * edge must count as a wall — the margin that keeps a moving overlay off
     * content which has not scrolled into view yet.
     */
    fun erodeV(r: Int, outside: Boolean = false): BitPlane {
        if (h == 0) return this
        val fill = if (outside) -1L else 0L
        var src = bits
        var dst = LongArray(bits.size)
        repeat(r) {
            for (y in 0 until h) {
                val base = y * wpr
                for (k in 0 until wpr) {
                    val up = if (y > 0) src[base - wpr + k] else if (k == wpr - 1) fill and tail else fill
                    val down = if (y < h - 1) src[base + wpr + k] else if (k == wpr - 1) fill and tail else fill
                    dst[base + k] = src[base + k] and up and down
                }
            }
            val t = src; src = dst; dst = t
        }
        if (src !== bits) System.arraycopy(src, 0, bits, 0, bits.size)
        return this
    }

    /** Grows every vertical run of set pixels by [r] at each end. */
    fun dilateV(r: Int): BitPlane {
        if (h == 0) return this
        var src = bits
        var dst = LongArray(bits.size)
        repeat(r) {
            for (y in 0 until h) {
                val base = y * wpr
                for (k in 0 until wpr) {
                    val up = if (y > 0) src[base - wpr + k] else 0L
                    val down = if (y < h - 1) src[base + wpr + k] else 0L
                    dst[base + k] = src[base + k] or up or down
                }
            }
            val t = src; src = dst; dst = t
        }
        if (src !== bits) System.arraycopy(src, 0, bits, 0, bits.size)
        return this
    }

    /** Dilation by a (2r+1) square. */
    fun dilate(r: Int): BitPlane = dilateH(r).dilateV(r)

    /** Erosion by a (2r+1) square. */
    fun erode(r: Int, outside: Boolean = false): BitPlane = erodeH(r, outside).erodeV(r, outside)

    /** Calls [f] with the [x0, x1) extent of each run of set pixels in row [y], left to right. */
    inline fun forEachRun(y: Int, f: (Int, Int) -> Unit) {
        if (y !in 0 until h) return
        val base = y * wpr
        var runStart = -1
        for (k in 0 until wpr) {
            val word = bits[base + k]
            val origin = k shl 6
            if (word == 0L) {
                if (runStart >= 0) {
                    f(runStart, origin)
                    runStart = -1
                }
                continue
            }
            if (word == -1L) {
                if (runStart < 0) runStart = origin
                continue
            }
            // A mixed word: walk its runs. `pos` stays below 64 throughout, so
            // the shifts below never wrap.
            var pos = 0
            while (pos < 64) {
                if (runStart < 0) {
                    val ahead = word ushr pos
                    if (ahead == 0L) break
                    pos += java.lang.Long.numberOfTrailingZeros(ahead)
                    runStart = origin + pos
                }
                val zeros = word.inv() ushr pos
                // No zero from here to the top of the word: the run goes on into the next.
                if (zeros == 0L) break
                pos += java.lang.Long.numberOfTrailingZeros(zeros)
                f(runStart, origin + pos)
                runStart = -1
            }
        }
        if (runStart >= 0) f(runStart, w)
    }
}
