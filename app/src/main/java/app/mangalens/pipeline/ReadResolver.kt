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
    private val anchorLines: List<OcrLine>,
    private val ignoreTop: Int,
    private val ignoreBottom: Int,
    private val exclusions: List<Rect>,
) {

    private val erasures = HashMap<PageItem, Erasure?>()
    private val fills = IdentityHashMap<Balloon, Bitmap?>()
    private val interiors = IdentityHashMap<Balloon, Int>()

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
        val home = usable.map(::balloonFor)
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
                done[i] = true
                free(usable[i])?.let {
                    out.add(it)
                    free.add(usable[i])
                }
                continue
            }
            for (k in group) done[k] = true
            // A detection whose interior shows anything but this lettering
            // is art that passed for a balloon — a face, a highlight, the
            // inside of a big glyph — and wiping it would paint over the
            // drawing. Its lettering is erased on its own instead. A "!!"
            // or heart the model gave as a sound of its own is lettering
            // too, not stray ink, though it never claims the balloon.
            val lettering = group.map { usable[it].box } + usable.indices
                .filter { home[it] == null && usable[it].kind == ItemKind.SFX && containedShare(usable[it].box, balloon.box) > 0.8f }
                .map { usable[it].box }
            val trusted = trust.getOrPut(trustKey(balloon, lettering)) {
                BalloonTrust.holdsOnly(bitmap, balloon, lettering)
            }
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
                val line = minOf(minOf(x.box.width(), x.box.height()), minOf(y.box.width(), y.box.height()))
                if (gap(x.box, y.box) <= line * 0.8f) parent[root(b)] = root(a)
            }
        }
        val byRoot = LinkedHashMap<Int, MutableList<PageItem>>()
        for (i in members.indices) byRoot.getOrPut(root(i)) { mutableListOf() }.add(members[i])
        return byRoot.values.toList()
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
            var minX = mw
            var minY = mh
            var maxX = -1
            var maxY = -1
            for (cy in 0 until mh) for (cx in 0 until mw) {
                if (owner[cy * mw + cx] != k) continue
                if (cx < minX) minX = cx
                if (cx > maxX) maxX = cx
                if (cy < minY) minY = cy
                if (cy > maxY) maxY = cy
            }
            if (maxX - minX < 3 || maxY - minY < 3) return@map null
            val w = maxX - minX + 1
            val h = maxY - minY + 1
            val mask = BooleanArray(w * h) { j -> owner[(minY + j / w) * mw + minX + j % w] == k }
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
    private fun balloonFor(item: PageItem): Balloon? {
        if (item.kind == ItemKind.SFX) return null
        val box = item.box
        return detected
            .filter { b -> containedShare(box, b.box) >= 0.6f && onInterior(b, box.centerX(), box.centerY()) }
            .minByOrNull { it.box.width().toLong() * it.box.height() }
    }

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
                box = Rect(item.box),
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
        )
    }

    /** Where the page is drawn, for placing notes off the art; measured once, when a note first needs it. */
    private val art: ArtMap by lazy { ArtMap.of(bitmap) }

    companion object {
        /** Shares of the page a sound effect may span and still count as small. */
        private const val SMALL_SFX_HEIGHT = 0.07f
        private const val SMALL_SFX_WIDTH = 0.25f

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
