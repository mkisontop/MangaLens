package app.mangalens.pipeline

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import app.mangalens.ocr.Balloon
import app.mangalens.ocr.BubbleKind
import app.mangalens.ocr.OcrLine
import app.mangalens.overlay.ArtMap
import app.mangalens.overlay.LetterStyle
import app.mangalens.overlay.RenderBubble
import app.mangalens.translate.ItemKind
import app.mangalens.translate.PageItem
import java.util.IdentityHashMap

/**
 * Decides where the English for each piece of lettering the model found
 * goes, and how the original is taken off the page.
 *
 * The model finds text and says what it reads; on-device analysis owns the
 * shape. An item inside a detected balloon is typeset into that balloon
 * through its mask, exactly as before. Anything else — narration on the
 * art, a side comment on screentone, a sound effect, a balloon the detector
 * missed — is erased stroke by stroke ([TextEraser]) and re-lettered in
 * place, never hidden under a card.
 *
 * Items stream in one at a time and [resolve] is called with the whole list
 * each time, so everything expensive is remembered per item and per
 * balloon: re-resolving a page one item longer costs one erasure.
 */
internal class ReadResolver(
    private val bitmap: Bitmap,
    private val detected: List<Balloon>,
    private var anchorLines: List<OcrLine>,
    private val ignoreTop: Int,
    private val ignoreBottom: Int,
    private val exclusions: List<Rect>,
    /** Panels read off the page; a note for a sound never crosses into another one. */
    private val panels: List<Rect> = emptyList(),
) {

    /**
     * Balloons found whole, and ones the frame edge cuts through. A cut
     * one can be a fragment of its balloon, or a panel's corner passing
     * for one: lettered into, it left the rest of the line on the page.
     * It is used only for a line nothing else placed, or placed in a
     * balloon that does not hold up (see [resolve]).
     */
    private val whole = detected.filter { !it.partial }
    private val cut = detected.filter { it.partial }

    private val erasures = HashMap<PageItem, Erasure?>()
    private val fills = IdentityHashMap<Balloon, Bitmap?>()
    private val interiors = IdentityHashMap<Balloon, Int>()

    /**
     * OCR's lines, once read: they back up a line the model reported where
     * the eraser found no lettering. Lines already resolved keep their
     * place; only one that had nothing to show can gain a card.
     */
    fun anchor(lines: List<OcrLine>) {
        anchorLines = lines
    }

    /** The erasure made for [item] so far, if it was placed on the art. */
    fun erasureOf(item: PageItem): Erasure? = erasures[item]

    /** Replaces [item]'s local erasure, typically with an AI-redrawn one. */
    fun upgrade(item: PageItem, erasure: Erasure) {
        erasures[item] = erasure
    }

    /** Items lettered on the art rather than into a balloon, as of the last [resolve]. */
    var freeItems: List<PageItem> = emptyList()
        private set

    fun resolve(items: List<PageItem>): List<RenderBubble> {
        val usable = items.filter(::usable)
        val home = usable.map(::balloonFor).toMutableList()
        val claimed = java.util.Collections.newSetFromMap(IdentityHashMap<Balloon, Boolean>())
        home.forEach { if (it != null) claimed.add(it) }
        val slid = ArrayList<Slide>()
        for (i in usable.indices) {
            if (home[i] != null) continue
            drifted(usable[i], claimed)?.let {
                home[i] = it
                claimed.add(it)
                drift[it]?.let { block -> slid.add(Slide(i, block.centerX() - usable[i].box.centerX(), block.centerY() - usable[i].box.centerY())) }
            }
        }
        slideAlong(usable, home, claimed, slid)
        // Balloons page-wide detection missed — see-through ones, ones
        // breaking a border or cut by the page edge — found from their
        // lettering outward.
        val seededHere = ArrayList<Balloon>()
        for (i in usable.indices) {
            if (home[i] != null) continue
            seeded(usable[i], seededHere, claimed)?.let {
                home[i] = it
                claimed.add(it)
                if (it !in seededHere) seededHere.add(it)
            }
        }
        // A balloon the frame edge cuts through, for a line nothing else
        // placed, or placed only in a balloon that does not hold up. A
        // placement that holds up is kept: a cut detection of the same
        // balloon can stop short of the lettering, where one found whole,
        // or from the lettering outward, covers all of it. A cut balloon
        // taken up for one line takes the lines seeding placed inside it
        // too, when it holds up with all of them: two balloons drawn joined
        // are one detection, and neither line alone looks like its only
        // lettering.
        val placed = IdentityHashMap<Balloon, MutableList<Int>>()
        home.forEachIndexed { i, b -> if (b != null) placed.getOrPut(b) { mutableListOf() }.add(i) }
        val needs = BooleanArray(usable.size) { i -> home[i]?.let { !trusted(it, placed.getValue(it), usable, home) } ?: true }
        val moved = BooleanArray(usable.size)
        for (c in cut.sortedBy { it.box.width().toLong() * it.box.height() }) {
            val held = usable.indices.filter { i ->
                !moved[i] && holding(listOf(c), usable[i]) != null &&
                    (needs[i] || home[i].let { b -> b != null && whole.none { it === b } })
            }
            if (held.none { needs[it] }) continue
            val takers = if (trusted(c, held, usable, home)) held else held.filter { needs[it] }
            for (i in takers) {
                home[i] = c
                moved[i] = true
                claimed.add(c)
            }
        }
        val byBalloon = IdentityHashMap<Balloon, MutableList<Int>>()
        home.forEachIndexed { i, b -> if (b != null) byBalloon.getOrPut(b) { mutableListOf() }.add(i) }

        val out = ArrayList<RenderBubble>(usable.size)
        val free = ArrayList<PageItem>()
        val done = BooleanArray(usable.size)
        for (i in usable.indices) {
            if (done[i]) continue
            val balloon = home[i]
            val group = balloon?.let { byBalloon[it] }
            if (balloon == null || group == null) {
                trace?.invoke("${usable[i].src.take(8).replace('\n', ' ')}: no balloon (${usable[i].kind})")
                done[i] = true
                free(usable[i])?.let {
                    out.add(it)
                    free.add(usable[i])
                }
                continue
            }
            for (k in group) done[k] = true
            val trusted = trusted(balloon, group, usable, home)
            trace?.invoke(
                "${group.joinToString(" + ") { usable[it].src.take(8).replace('\n', ' ') }}: " +
                    (if (balloon in seedCache.values) "seeded" else "detected") + " ${balloon.box} trusted=$trusted",
            )
            if (!trusted) {
                for (k in group) {
                    free(usable[k])?.let {
                        out.add(it)
                        free.add(usable[k])
                    }
                }
                continue
            }
            // A detection holding several items is one of two things. The
            // model split one balloon's lines into pieces — adjacent, one
            // voice — and they are set back together, in the order it read
            // them. Or two balloons drawn joined were detected as one, and
            // each line keeps its own lobe: the interior is shared out
            // between them and each is lettered into its share, so neither
            // wipes the other and no line straddles the join.
            val clusters = clusters(group.map { usable[it] })
            if (clusters.size == 1) {
                out.add(inBalloon(clusters[0][0], balloon, clusters[0]))
            } else {
                val lobes = lobes(balloon, clusters)
                for ((k, members) in clusters.withIndex()) {
                    val lobe = lobes[k]
                    if (lobe != null) {
                        out.add(inBalloon(members[0], lobe, members))
                    } else {
                        for (m in members) {
                            free(m)?.let {
                                out.add(it)
                                free.add(m)
                            }
                        }
                    }
                }
            }
        }
        freeItems = free
        return out
    }

    /**
     * Whether [balloon] can be wiped for the items of [group]. A detection
     * whose interior shows anything but this lettering is art that passed
     * for a balloon — a face, a highlight, the inside of a big glyph — and
     * wiping it would paint over the drawing; its lettering is erased on
     * its own instead. A "!!" or heart the model gave as a sound of its own
     * is lettering too, not stray ink, though it never claims the balloon.
     */
    private fun trusted(balloon: Balloon, group: List<Int>, usable: List<PageItem>, home: List<Balloon?>): Boolean {
        val lettering = group.map { usable[it].box } + listOfNotNull(drift[balloon]) + usable.indices
            .filter { home[it] == null && usable[it].kind == ItemKind.SFX && containedShare(usable[it].box, balloon.box) > 0.8f }
            .map { usable[it].box }
        return trust.getOrPut(trustKey(balloon, lettering)) {
            BalloonTrust.holdsOnly(bitmap, balloon, lettering) ||
                // A see-through balloon: the art shows faintly through its
                // wash and reads as texture. Found again from its own
                // lettering outward — walled, convex, paper round the text
                // — it is a balloon, and the wash is cleaned with its tone.
                (BalloonTrust.holdsOnly(bitmap, balloon, lettering, BalloonTrust.SEEN_THROUGH_TEXTURE) &&
                    vouched(balloon, group.map { usable[it] })) ||
                stoppedShort(balloon, group.map { usable[it] })
        }
    }

    private val lobeCache = HashMap<String, List<Balloon?>>()
    private val trust = HashMap<String, Boolean>()

    private fun trustKey(balloon: Balloon, boxes: List<Rect>): String =
        System.identityHashCode(balloon).toString() + boxes.joinToString("|") { it.flattenToString() }

    /**
     * Groups the items of one detection into balloons' worth of lettering:
     * items one voice speaks whose boxes nearly touch — closer than about
     * a line's height — are the pieces of one balloon.
     */
    private fun clusters(members: List<PageItem>): List<List<PageItem>> {
        val parent = IntArray(members.size) { it }
        fun root(i: Int): Int {
            var r = i
            while (parent[r] != r) r = parent[r]
            return r
        }
        for (a in members.indices) {
            for (b in a + 1 until members.size) {
                val x = members[a]
                val y = members[b]
                val sameVoice = x.who.isBlank() || y.who.isBlank() || x.who.trim().equals(y.who.trim(), ignoreCase = true)
                if (!sameVoice) continue
                val line = minOf(lineOf(x), lineOf(y))
                if (gap(x.box, y.box) <= line * 0.8f) parent[root(b)] = root(a)
            }
        }
        val byRoot = LinkedHashMap<Int, MutableList<PageItem>>()
        for (i in members.indices) byRoot.getOrPut(root(i)) { mutableListOf() }.add(members[i])
        return byRoot.values.toList()
    }

    /**
     * How thick one of [item]'s lines is: its box across the lines, shared
     * between the lines it holds — a column's width in vertical text, a
     * row's height across. A box's short side alone is two lines thick
     * round a two-column line, and two joined balloons' lines, a column
     * apart, then read as one balloon's.
     */
    private fun lineOf(item: PageItem): Int {
        val lines = item.src.lines().count { it.isNotBlank() }.coerceAtLeast(1)
        val across = if (item.vertical) item.box.width() else item.box.height()
        return minOf(across / lines, minOf(item.box.width(), item.box.height()))
    }

    /** Distance between two boxes, 0 when they touch or overlap. */
    private fun gap(a: Rect, b: Rect): Float {
        val dx = maxOf(0, maxOf(a.left, b.left) - minOf(a.right, b.right))
        val dy = maxOf(0, maxOf(a.top, b.top) - minOf(a.bottom, b.bottom))
        return kotlin.math.sqrt((dx * dx + dy * dy).toFloat())
    }

    /**
     * [balloon]'s interior shared out between [clusters]: every mask cell
     * goes to the cluster whose lettering is nearest, and each share
     * becomes a balloon of its own. Null for a cluster left with too little
     * interior to letter into.
     */
    private fun lobes(balloon: Balloon, clusters: List<List<PageItem>>): List<Balloon?> {
        val key = System.identityHashCode(balloon).toString() + clusters.joinToString("|") { c ->
            c.joinToString(",") { it.box.flattenToString() }
        }
        lobeCache[key]?.let { return it }
        val mw = balloon.maskW
        val mh = balloon.maskH
        val box = balloon.box
        val areas = clusters.map { c ->
            Rect(c[0].box).apply { for (m in c) union(m.box) }
        }
        val owner = IntArray(mw * mh) { -1 }
        for (cy in 0 until mh) {
            val py = box.top + (cy + 0.5f) * box.height() / mh
            for (cx in 0 until mw) {
                val i = cy * mw + cx
                if (!balloon.mask[i]) continue
                val px = box.left + (cx + 0.5f) * box.width() / mw
                var best = -1
                var bestD = Float.MAX_VALUE
                for ((k, a) in areas.withIndex()) {
                    val dx = maxOf(0f, a.left - px, px - a.right)
                    val dy = maxOf(0f, a.top - py, py - a.bottom)
                    val d = dx * dx + dy * dy
                    if (d < bestD) {
                        bestD = d
                        best = k
                    }
                }
                owner[i] = best
            }
        }
        val out = clusters.indices.map { k ->
            var ownX0 = mw
            var ownY0 = mh
            var ownX1 = -1
            var ownY1 = -1
            for (i in owner.indices) {
                if (owner[i] != k) continue
                val cx = i % mw
                val cy = i / mw
                if (cx < ownX0) ownX0 = cx
                if (cx > ownX1) ownX1 = cx
                if (cy < ownY0) ownY0 = cy
                if (cy > ownY1) ownY1 = cy
            }
            if (ownX1 - ownX0 < 3 || ownY1 - ownY0 < 3) return@map null
            // Each share reaches a little way into its neighbours': a lobe
            // is cleaned short of its own edge, and two shares meeting edge
            // to edge left a seam neither cleaned, with whatever lettering
            // crossed it still on the page.
            var share = BooleanArray(mw * mh) { owner[it] == k }
            repeat(SEAM_CELLS) {
                val next = share.copyOf()
                for (i in share.indices) {
                    if (share[i] || owner[i] < 0) continue
                    val cx = i % mw
                    if ((cx > 0 && share[i - 1]) || (cx < mw - 1 && share[i + 1]) ||
                        (i >= mw && share[i - mw]) || (i + mw < share.size && share[i + mw])
                    ) {
                        next[i] = true
                    }
                }
                share = next
            }
            var minX = mw
            var minY = mh
            var maxX = -1
            var maxY = -1
            for (cy in 0 until mh) for (cx in 0 until mw) {
                if (!share[cy * mw + cx]) continue
                if (cx < minX) minX = cx
                if (cx > maxX) maxX = cx
                if (cy < minY) minY = cy
                if (cy > maxY) maxY = cy
            }
            val w = maxX - minX + 1
            val h = maxY - minY + 1
            val mask = BooleanArray(w * h) { j -> share[(minY + j / w) * mw + minX + j % w] }
            Balloon(
                Rect(
                    box.left + minX * box.width() / mw,
                    box.top + minY * box.height() / mh,
                    box.left + (maxX + 1) * box.width() / mw,
                    box.top + (maxY + 1) * box.height() / mh,
                ),
                w, h, mask, balloon.inverted, balloon.partial,
            )
        }
        lobeCache[key] = out
        return out
    }

    private fun usable(item: PageItem): Boolean {
        val b = item.box
        if (item.en.isBlank() || b.width() < 4 || b.height() < 4) return false
        if (b.bottom <= ignoreTop || b.top >= bitmap.height - ignoreBottom) return false
        return exclusions.none { Rect.intersects(it, b) }
    }

    /**
     * The detected balloon holding [item]: its box mostly inside the
     * balloon's, its centre on the balloon's interior. Sound effects never
     * claim a balloon — a stray "…" or "!!" lettered inside one must not
     * take over the speech it sits beside.
     */
    private fun balloonFor(item: PageItem): Balloon? = holding(whole, item)

    /** The smallest of [balloons] holding [item], by the rule [balloonFor] states. */
    private fun holding(balloons: List<Balloon>, item: PageItem): Balloon? =
        if (item.kind == ItemKind.SFX) null else holding(balloons, item.box)

    private fun holding(balloons: List<Balloon>, box: Rect): Balloon? {
        return balloons
            .filter { b -> containedShare(box, b.box) >= 0.6f && onInterior(b, box.centerX(), box.centerY()) }
            .minByOrNull { it.box.width().toLong() * it.box.height() }
    }

    /**
     * The balloon a line of dialogue was really in, when the model's box
     * for it drifted off: now and then a box lands a few hundred pixels
     * from its lettering, the line is lettered over whatever art is there,
     * and its own balloon is left untranslated. Such a box holds no clean
     * lettering, and close by is a balloon that holds lettering but that no
     * line claims. Null unless both are true and one balloon is nearest.
     */
    private fun drifted(item: PageItem, claimed: Set<Balloon>): Balloon? {
        if (item.kind != ItemKind.SPEECH && item.kind != ItemKind.THOUGHT) return null
        val box = item.box
        val reach = bitmap.height * MAX_DRIFT
        val near = whole.filter { b ->
            b !in claimed && !b.inverted &&
                b.box.width() >= box.width() * 0.6f && b.box.height() >= box.height() * 0.6f &&
                kotlin.math.hypot((b.box.exactCenterX() - box.exactCenterX()).toDouble(), (b.box.exactCenterY() - box.exactCenterY()).toDouble()) <= reach
        }
        if (near.isEmpty()) return null
        val blocks = near.mapNotNull { b -> BalloonTrust.letteringBlock(bitmap, b)?.let { b to it } }
        // A box that slid along its own column, from the last glyphs of a
        // balloon no line claims on out over the art, holds some of that
        // balloon's lettering: the line is that balloon's however little of
        // the box is left inside it. Taken for lettering on the art, the
        // glyphs it covered were erased, the art beyond with them, and the
        // rest of the column left standing over the English.
        blocks
            .filter { (_, block) -> Rect.intersects(block, box) }
            .maxByOrNull { (_, block) -> overlapArea(block, box) }
            ?.let { (b, block) ->
                drift[b] = block
                return b
            }
        val here = if (erasures.containsKey(item)) erasures[item] else {
            runCatching { TextEraser.erase(bitmap, item.box, item.kind, item.textColor, item.outlineColor) }
                .getOrNull().also { erasures[item] = it }
        }
        // Clean lettering where the box says: the box was right after all —
        // unless it is far too little of it. A face in line art on white
        // skin reads as clean lettering too, but as a handful of marks
        // where the line has a character for each.
        val clean = here != null && here.flat && here.busy < 0.3f
        if (clean && !scant(here!!, item)) return null
        // The nearest, counting lettering of another size as further off:
        // a box drifts with its line's size, and when a whole panel's boxes
        // slid, the balloon nearest a line's box can be its neighbour's.
        val found = blocks
            .minByOrNull { (b, block) ->
                kotlin.math.hypot((b.box.exactCenterX() - box.exactCenterX()).toDouble(), (b.box.exactCenterY() - box.exactCenterY()).toDouble()) *
                    kotlin.math.exp(sizeGap(block, box))
            }
            ?: return null
        if (clean && sizeGap(found.second, box) > SAME_SIZE) return null
        drift[found.first] = found.second
        return found.first
    }

    /** Area [a] and [b] share. */
    private fun overlapArea(a: Rect, b: Rect): Long {
        val ix = minOf(a.right, b.right) - maxOf(a.left, b.left)
        val iy = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        return if (ix <= 0 || iy <= 0) 0L else ix.toLong() * iy
    }

    /** Characters in [item]'s source: what a line of its lettering is made of. */
    private fun glyphs(item: PageItem): Int = item.src.count { !it.isWhitespace() }

    /** True when [e] found under [item]'s box under half as many separate marks as the line has characters. */
    private fun scant(e: Erasure, item: PageItem): Boolean = marks(e, item.box) * 2 < glyphs(item)

    /**
     * Separate marks in [e]'s mask in and just round [box]: its strokes'
     * connected pieces, 8-neighbour, of a few pixels or more.
     */
    private fun marks(e: Erasure, box: Rect): Int {
        val w = e.rect.width()
        val h = e.rect.height()
        if (w <= 0 || h <= 0) return 0
        val m = (minOf(box.width(), box.height()) * INK_REACH).toInt() + 2
        val x0 = (box.left - m - e.rect.left).coerceIn(0, w)
        val y0 = (box.top - m - e.rect.top).coerceIn(0, h)
        val x1 = (box.right + m - e.rect.left).coerceIn(0, w)
        val y1 = (box.bottom + m - e.rect.top).coerceIn(0, h)
        val seen = BooleanArray(w * h)
        val stack = IntArray(w * h)
        var count = 0
        for (y in y0 until y1) for (x in x0 until x1) {
            val start = y * w + x
            if (!e.mask[start] || seen[start]) continue
            var top = 0
            var size = 0
            stack[top++] = start
            seen[start] = true
            while (top > 0) {
                val p = stack[--top]
                size++
                val px = p % w
                val py = p / w
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = px + dx
                    val ny = py + dy
                    if (nx < x0 || ny < y0 || nx >= x1 || ny >= y1) continue
                    val q = ny * w + nx
                    if (e.mask[q] && !seen[q]) {
                        seen[q] = true
                        stack[top++] = q
                    }
                }
            }
            if (size >= MIN_MARK_PX) count++
        }
        return count
    }

    /** A line [drifted] put back into its balloon, and how far its box had slid from the lettering there. */
    private class Slide(val item: Int, val dx: Int, val dy: Int)

    /**
     * Now and then the model's boxes for a whole panel slide the same way
     * together. [drifted] puts back the lines whose boxes landed on bare
     * art, but a box that landed on art passing for a balloon — a face,
     * features and all under the box — is held there, and the face was
     * wiped. When two lines within a panel's reach of one were put back
     * by the same shift, a line held
     * by a detection that holds no block of lettering moves by that shift
     * too, into a balloon no line claims whose lettering is its line's
     * size.
     */
    private fun slideAlong(usable: List<PageItem>, home: MutableList<Balloon?>, claimed: MutableSet<Balloon>, slid: List<Slide>) {
        if (slid.size < 2) return
        val reach = bitmap.height * PANEL_REACH
        for (i in usable.indices) {
            val held = home[i] ?: continue
            if (slid.any { it.item == i }) continue
            val item = usable[i]
            if (item.kind != ItemKind.SPEECH && item.kind != ItemKind.THOUGHT) continue
            val box = item.box
            val near = slid.filter { s ->
                val b = usable[s.item].box
                kotlin.math.hypot((b.exactCenterX() - box.exactCenterX()).toDouble(), (b.exactCenterY() - box.exactCenterY()).toDouble()) <= reach
            }
            val shift = agreed(near) ?: continue
            if (BalloonTrust.letteringBlock(bitmap, held) != null) continue
            val moved = Rect(box).apply { offset(shift.first, shift.second) }
            val to = holding(whole, moved) ?: continue
            if (to in claimed || to.inverted) continue
            val block = BalloonTrust.letteringBlock(bitmap, to) ?: continue
            if (sizeGap(block, box) > SAME_SIZE) continue
            home[i] = to
            claimed.add(to)
            drift[to] = block
            trace?.invoke("${item.src.take(8).replace('\n', ' ')}: slid with its panel to ${to.box}")
        }
    }

    /** The shift two of [slides] agree on, averaged; null when no two agree. */
    private fun agreed(slides: List<Slide>): Pair<Int, Int>? {
        for (a in slides.indices) for (b in a + 1 until slides.size) {
            val s = slides[a]
            val t = slides[b]
            val tol = maxOf(SLIDE_TOLERANCE_PX.toDouble(), 0.2 * kotlin.math.hypot(s.dx.toDouble(), s.dy.toDouble()))
            if (kotlin.math.abs(s.dx - t.dx) <= tol && kotlin.math.abs(s.dy - t.dy) <= tol) return (s.dx + t.dx) / 2 to (s.dy + t.dy) / 2
        }
        return null
    }

    /** How far apart two boxes' sizes are: 0 for the same, ln 2 for one twice the other along its length. */
    private fun sizeGap(a: Rect, b: Rect): Double {
        fun gap(x: Int, y: Int) = kotlin.math.abs(kotlin.math.ln(x.coerceAtLeast(1).toDouble() / y.coerceAtLeast(1)))
        val (aLong, aShort) = if (a.height() >= a.width()) a.height() to a.width() else a.width() to a.height()
        val (bLong, bShort) = if (b.height() >= b.width()) b.height() to b.width() else b.width() to b.height()
        return gap(aLong, bLong) + 0.5 * gap(aShort, bShort)
    }

    /**
     * The balloon [item] is lettered in when page-wide detection found
     * none: one already found for another line of this page when the
     * item sits in it, a detected balloon that turns out to be the same
     * one, or a fresh search from its lettering outward. Only dialogue and
     * thoughts have balloons to find.
     */
    private fun seeded(item: PageItem, found: List<Balloon>, claimed: Set<Balloon>): Balloon? {
        if (item.kind != ItemKind.SPEECH && item.kind != ItemKind.THOUGHT) return null
        val cx = item.box.centerX()
        val cy = item.box.centerY()
        found.firstOrNull { onMask(it, cx, cy) }?.let { return it }
        val fresh = seedFor(item.box) ?: return null
        // The same balloon a detection already holds, found again from inside.
        (claimed + whole).firstOrNull { d -> overlap(d.box, fresh.box) > SAME_BALLOON }?.let { return it }
        return fresh
    }

    /**
     * True when all of [balloon]'s ink is one compact block of lettering
     * and [items]' boxes lie on part of it: the model's box slid along the
     * lines it read or stopped short of them, and the rest, counted as
     * stray ink, made a balloon holding nothing but those lines look like
     * art. The balloon was left uncleaned, with the lettering outside the
     * box still on the page.
     */
    private fun stoppedShort(balloon: Balloon, items: List<PageItem>): Boolean {
        if (items.any { it.kind == ItemKind.SFX || it.kind == ItemKind.ART_TEXT }) return false
        val block = BalloonTrust.letteringBlock(bitmap, balloon) ?: return false
        return items.any { Rect.intersects(it.box, block) }
    }

    /**
     * True when a search from [items]' own lettering outward finds
     * [balloon] too: a detection that holds up as a balloon however it is
     * looked for.
     */
    private fun vouched(balloon: Balloon, items: List<PageItem>): Boolean {
        if (balloon in seedCache.values) return true
        return items.any { item ->
            if (item.kind != ItemKind.SPEECH && item.kind != ItemKind.THOUGHT) return@any false
            val found = seedFor(item.box)
            found != null && overlap(found.box, balloon.box) > SAME_BALLOON
        }
    }

    /** Searches from lettering outward, by the lettering's box: a page re-resolved one item longer searches once. */
    private val seedCache = HashMap<Rect, Balloon?>()

    /**
     * [seedCache]'s search from [box]. A search that found nothing is kept
     * too: getOrPut takes a stored null for none and searched again on
     * every re-resolve, 25-90 ms (server) per streamed item on 2 pages in 5.
     */
    private fun seedFor(box: Rect): Balloon? {
        if (seedCache.containsKey(box)) return seedCache[box]
        val found = runCatching { BalloonSeed.find(bitmap, box) }.getOrNull()
        seedCache[Rect(box)] = found
        return found
    }

    /** True when (x, y) lies on [b]'s interior mask. */
    private fun onMask(b: Balloon, x: Int, y: Int): Boolean {
        if (!b.box.contains(x, y) || b.maskW < 1 || b.maskH < 1) return false
        val mx = ((x - b.box.left).toLong() * b.maskW / b.box.width().coerceAtLeast(1)).toInt().coerceIn(0, b.maskW - 1)
        val my = ((y - b.box.top).toLong() * b.maskH / b.box.height().coerceAtLeast(1)).toInt().coerceIn(0, b.maskH - 1)
        return b.mask[my * b.maskW + mx]
    }

    /**
     * Intersection over union: the same balloon found twice covers the same
     * ground. Over the smaller area alone, the white counter of a big glyph
     * that detection passed for a balloon matched the whole burst around it,
     * and the burst was lettered into the counter.
     */
    private fun overlap(a: Rect, b: Rect): Float {
        val ix = minOf(a.right, b.right) - maxOf(a.left, b.left)
        val iy = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        if (ix <= 0 || iy <= 0) return 0f
        val inter = ix.toLong() * iy
        val union = a.width().toLong() * a.height() + b.width().toLong() * b.height() - inter
        return if (union <= 0L) 0f else inter.toFloat() / union
    }

    /** The lettering found in a balloon a drifted line was put back into, for its trust check. */
    private val drift = IdentityHashMap<Balloon, Rect>()

    /** True when (x, y) falls on or right beside the balloon's interior mask. */
    private fun onInterior(b: Balloon, x: Int, y: Int): Boolean {
        if (!b.box.contains(x, y) || b.maskW < 1 || b.maskH < 1) return false
        val cx = ((x - b.box.left).toLong() * b.maskW / b.box.width().coerceAtLeast(1)).toInt()
        val cy = ((y - b.box.top).toLong() * b.maskH / b.box.height().coerceAtLeast(1)).toInt()
        for (dy in -2..2) {
            for (dx in -2..2) {
                val mx = cx + dx
                val my = cy + dy
                if (mx in 0 until b.maskW && my in 0 until b.maskH && b.mask[my * b.maskW + mx]) return true
            }
        }
        return false
    }

    private fun inBalloon(first: PageItem, balloon: Balloon, members: List<PageItem>): RenderBubble {
        val bg = interiors.getOrPut(balloon) { PageColors.interiorColor(bitmap, balloon) }
        val textColor = when {
            balloon.inverted -> 0xFFF2F3F7.toInt()
            PageColors.luminance(bg) < 140 -> Color.WHITE
            else -> 0xFF17181C.toInt()
        }
        // A gradient or textured balloon is cleaned with its own paper
        // continued under the lettering, not with a flat patch of the average.
        val fill = if (fills.containsKey(balloon)) fills[balloon] else {
            BalloonFill.build(bitmap, balloon).also { fills[balloon] = it }
        }
        return RenderBubble(
            box = Rect(balloon.box),
            translated = members.joinToString(" ") { it.en.trim() },
            original = members.joinToString(" ") { it.src.trim() },
            bgColor = bg,
            textColor = textColor,
            vertical = first.vertical,
            kind = BubbleKind.DIALOGUE,
            balloon = balloon,
            fill = fill,
            style = if (members.any { it.loud }) LetterStyle.SHOUT else styleOf(first),
        )
    }

    private fun free(item: PageItem): RenderBubble? {
        // A sound effect drawn big across the art is part of the drawing:
        // erasing it would ruin the panel, so it keeps its place and the
        // English is noted beside it. Only a small one on plain ground is
        // taken off and re-lettered.
        if (item.kind == ItemKind.SFX && !smallSfx(item)) return sfxNote(item)
        val erasure = if (erasures.containsKey(item)) erasures[item] else {
            runCatching {
                TextEraser.erase(bitmap, item.box, item.kind, item.textColor, item.outlineColor)
            }.getOrNull().also { erasures[item] = it }
        }
        val kind = if (item.kind == ItemKind.SFX) BubbleKind.SFX else BubbleKind.DIALOGUE
        if (item.kind == ItemKind.SFX && (erasure == null || !erasure.flat)) return sfxNote(item)
        if (erasure != null) {
            return RenderBubble(
                box = inkBox(erasure, item.box) ?: Rect(item.box),
                translated = item.en.trim(),
                original = item.src,
                bgColor = erasure.background,
                textColor = erasure.textColor,
                vertical = item.vertical,
                kind = kind,
                style = styleOf(item),
                patch = erasure.patch,
                patchRect = Rect(erasure.rect),
                outlineColor = erasure.outlineColor,
                panel = panelOf(item.box),
            )
        }
        // Nothing letter-like under the model's box. Only on-device OCR
        // having read text there as well earns it a card: English painted
        // where the page shows no lettering is read as true, and a missing
        // line is the lesser failure.
        val supported = anchorLines.any { l ->
            Rect.intersects(l.box, item.box) && containedShare(l.box, item.box) > 0.4f
        }
        if (!supported) return null
        val bg = PageColors.sampleBackground(bitmap, item.box)
        return RenderBubble(
            box = Rect(item.box),
            translated = item.en.trim(),
            original = item.src,
            bgColor = bg,
            textColor = if (PageColors.luminance(bg) < 140) Color.WHITE else 0xFF17181C.toInt(),
            vertical = item.vertical,
            kind = kind,
            style = styleOf(item),
        )
    }

    /**
     * Where the erased lettering actually was: the model's box around text
     * in a balloon the detector missed is often the whole balloon, and
     * English sized and centred on that is set far larger than the line it
     * replaces. Only the erased pixels in and just round the model's [box]
     * count: a stroke of art the erasure also took — a sparkle above the
     * text, a strand of hair — once dragged the English a line or more
     * off the text it replaces. Null when nothing was masked there.
     */
    private fun inkBox(e: Erasure, box: Rect): Rect? {
        val w = e.rect.width()
        val m = (minOf(box.width(), box.height()) * INK_REACH).toInt() + 2
        val near = Rect(box.left - m, box.top - m, box.right + m, box.bottom + m)
        var l = Int.MAX_VALUE
        var t = Int.MAX_VALUE
        var r = -1
        var b = -1
        for (i in e.mask.indices) {
            if (!e.mask[i]) continue
            val x = i % w
            val y = i / w
            if (!near.contains(e.rect.left + x, e.rect.top + y)) continue
            if (x < l) l = x
            if (x > r) r = x
            if (y < t) t = y
            if (y > b) b = y
        }
        if (r < 0) return null
        return Rect(e.rect.left + l, e.rect.top + t, e.rect.left + r + 1, e.rect.top + b + 1)
    }

    /** The panel [box]'s lettering is drawn in, when the page's panels were read. */
    private fun panelOf(box: Rect): Rect? =
        panels.filter { it.contains(box.centerX(), box.centerY()) }.minByOrNull { it.width().toLong() * it.height() }

    /** Small enough that erasing it could only ever take a sliver of art with it. */
    private fun smallSfx(item: PageItem): Boolean =
        item.box.height() <= bitmap.height * SMALL_SFX_HEIGHT && item.box.width() <= bitmap.width * SMALL_SFX_WIDTH

    /** The English for a sound effect left in the art, noted beside it. */
    private fun sfxNote(item: PageItem): RenderBubble {
        val bg = PageColors.sampleBackground(bitmap, item.box)
        val dark = PageColors.luminance(bg) < 140
        return RenderBubble(
            box = Rect(item.box),
            translated = item.en.trim(),
            original = item.src,
            bgColor = bg,
            textColor = if (dark) Color.WHITE else 0xFF17181C.toInt(),
            vertical = item.vertical,
            kind = BubbleKind.SFX,
            style = LetterStyle.SFX_NOTE,
            outlineColor = if (dark) 0xFF17181C.toInt() else Color.WHITE,
            art = art,
            otherPanels = panels.filterNot { it.contains(item.box.centerX(), item.box.centerY()) },
        )
    }

    /** Where the page is drawn, for placing notes off the art; measured once, when a note first needs it. */
    private val art: ArtMap by lazy { ArtMap.of(bitmap) }

    companion object {
        /** How each line was placed, for the page harness; null in the app. */
        @Volatile
        internal var trace: ((String) -> Unit)? = null

        /** Shares of the page a sound effect may span and still count as small. */
        private const val SMALL_SFX_HEIGHT = 0.07f

        /** Furthest a line's box may have drifted from its balloon, as a share of the page's height. */
        private const val MAX_DRIFT = 0.2f
        private const val SMALL_SFX_WIDTH = 0.25f

        /** Mask cells a joined balloon's lobe reaches into its neighbour's, so the two cleanings overlap. */
        private const val SEAM_CELLS = 2

        /** How far, as a share of the page's height, lines may be and still be one panel's for [slideAlong]. */
        private const val PANEL_REACH = 0.3f

        /** Pixels two slides may differ by, at least, and still be the one shift of a panel. */
        private const val SLIDE_TOLERANCE_PX = 24

        /** Pixels a piece of a mask needs to count as a mark of lettering rather than a speck. */
        private const val MIN_MARK_PX = 6

        /** [sizeGap] within which a balloon's lettering is a line's own size. */
        private const val SAME_SIZE = 0.5

        /** Intersection over union past which a balloon found from its lettering is one already detected. */
        private const val SAME_BALLOON = 0.5f

        /** How far past the model's box, in its narrow dimension, erased ink still counts as the lettering's. */
        private const val INK_REACH = 0.35f

        fun styleOf(item: PageItem): LetterStyle = when (item.kind) {
            ItemKind.SFX -> LetterStyle.SFX
            ItemKind.THOUGHT -> LetterStyle.THOUGHT
            ItemKind.NARRATION -> LetterStyle.NARRATION
            ItemKind.ART_TEXT -> LetterStyle.ART
            ItemKind.SPEECH -> if (item.loud) LetterStyle.SHOUT else LetterStyle.DIALOGUE
        }

        /** Fraction of [box] inside [within]. */
        fun containedShare(box: Rect, within: Rect): Float {
            val ix = minOf(box.right, within.right) - maxOf(box.left, within.left)
            val iy = minOf(box.bottom, within.bottom) - maxOf(box.top, within.top)
            if (ix <= 0 || iy <= 0) return 0f
            val area = box.width().toLong() * box.height()
            if (area <= 0L) return 0f
            return (ix.toLong() * iy).toFloat() / area
        }
    }
}
