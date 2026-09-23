package app.mangalens.pipeline

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import app.mangalens.ocr.Balloon
import app.mangalens.ocr.BubbleKind
import app.mangalens.ocr.OcrLine
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
            val item = usable[i]
            val balloon = home[i]
            val group = balloon?.let { byBalloon[it] }
            // A balloon holds one character's line. When the model split
            // it into pieces the pieces are set back together, in the order
            // it read them; when two speakers landed in one detection — two
            // balloons the detector saw as one — neither may wipe the other,
            // and both are lettered in place instead.
            if (balloon != null && group != null && (group.size == 1 || oneVoice(group.map { usable[it] }))) {
                val members = group.map { usable[it] }
                out.add(inBalloon(item, balloon, members))
                for (k in group) done[k] = true
                continue
            }
            done[i] = true
            free(item)?.let {
                out.add(it)
                free.add(item)
            }
        }
        freeItems = free
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

    private fun oneVoice(members: List<PageItem>): Boolean {
        val voices = members.map { it.who.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        return voices.size <= 1
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
        val erasure = if (erasures.containsKey(item)) erasures[item] else {
            runCatching {
                TextEraser.erase(bitmap, item.box, item.kind, item.textColor, item.outlineColor)
            }.getOrNull().also { erasures[item] = it }
        }
        val kind = if (item.kind == ItemKind.SFX) BubbleKind.SFX else BubbleKind.DIALOGUE
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

    companion object {
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
