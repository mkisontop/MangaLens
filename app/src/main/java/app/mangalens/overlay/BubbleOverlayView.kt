package app.mangalens.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.TypedValue
import android.view.View
import android.view.animation.AnimationUtils
import androidx.core.content.res.ResourcesCompat
import app.mangalens.R
import app.mangalens.ocr.Balloon
import app.mangalens.ocr.BubbleKind
import java.util.IdentityHashMap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * How a piece of English is lettered: the face, weight and treatment a
 * scanlation letterer would pick for that kind of text.
 */
enum class LetterStyle {
    DIALOGUE,

    /** Shouted: heavier and larger. */
    SHOUT,

    /** Inner voice: lighter, italic. */
    THOUGHT,

    /** Narration and captions. */
    NARRATION,

    /** A sound effect, lettered as one. */
    SFX,

    /**
     * A sound effect drawn into detailed art, which stays: the English is
     * a small outlined label on empty ground beside it, or over the sound
     * itself, the way a scanlation notes the sound effects it would ruin
     * the art to redraw.
     */
    SFX_NOTE,

    /** Lettering drawn straight onto the art, set in its own colours and outline. */
    ART,
}

data class RenderBubble(
    val box: Rect,
    val translated: String,
    val original: String,
    val bgColor: Int,
    val textColor: Int,
    val vertical: Boolean,
    val kind: BubbleKind = BubbleKind.DIALOGUE,
    /**
     * The balloon this region was detected inside, when the page pixels
     * yielded one. Present, the overlay wipes the balloon's interior and
     * typesets into it; absent, the English is lettered over the erased
     * original when there is a [patch], and floats on a rounded card when
     * there is not. Anything that translates [box] must translate
     * [Balloon.box] with it, or the cleaning lands where the balloon used
     * to be.
     */
    val balloon: Balloon? = null,
    /**
     * An inpainted fill for the balloon at its mask's resolution, when its
     * paper is not one flat colour; null means fill with [bgColor].
     */
    val fill: Bitmap? = null,
    val style: LetterStyle = if (kind == BubbleKind.SFX) LetterStyle.SFX else LetterStyle.DIALOGUE,
    /**
     * Reconstructed background for lettering no balloon holds, drawn at
     * [patchRect] before the English: opaque where the original strokes
     * were, transparent elsewhere, so the art around them is untouched.
     */
    val patch: Bitmap? = null,
    val patchRect: Rect? = null,
    /** Stroke around the English, matching the original lettering's outline; null for none. */
    val outlineColor: Int? = null,
    /**
     * Where the page around a [LetterStyle.SFX_NOTE] is drawn and where it
     * is empty; without it a note is set over its own sound, the one place
     * sure to hold no art the reader needs.
     */
    val art: ArtMap? = null,
    /** Panels other than the one a [LetterStyle.SFX_NOTE]'s sound is in: its note never crosses into one. */
    val otherPanels: List<Rect> = emptyList(),
    /**
     * The panel free lettering stands in: its English stays inside it, as a
     * letterer keeps a line to its own panel, rather than running on over
     * the border into the next one. Null when the page's panels were not read.
     */
    val panel: Rect? = null,
)

/**
 * Full-screen, untouchable layer that puts the English on the page.
 *
 * Every kind of lettering is replaced the way a scanlation replaces it,
 * not covered up:
 *
 * - A bubble that carries its detected [Balloon] has the balloon interior
 *   wiped to the sampled fill through the balloon's own mask, and the
 *   translation typeset into the balloon's shape.
 * - Lettering no balloon holds — narration on the art, side comments,
 *   signs, sound effects — arrives with a [RenderBubble.patch] that erases
 *   the original strokes, and the English is lettered over the same spot in
 *   the original's own colours and outline, at the original's size. The
 *   rounded card this replaces was the loudest "this is an AI overlay"
 *   signal left on a translated page.
 * - Without a patch (providers that do not erase) dialogue keeps
 *   the card — the original is still there, and a card is better than two
 *   languages interleaved — while a sound effect is lettered as one, big
 *   and outlined over its own spot, since a caption box on the art reads
 *   as a sticker rather than a sound.
 *
 * Each [LetterStyle] gets its own face and treatment: shouts heavier and
 * larger, thoughts italic, narration in the calmer regular weight, sound
 * effects in outlined bold italic capitals.
 *
 * Typesetting is the expensive part and a page streams in item by item,
 * each time as the whole list, so [setBubbles] keeps what it set for every
 * bubble and re-setting an unchanged one is a map lookup. [onDraw] only
 * stamps what was prepared: blits and two passes of text at most.
 */
class BubbleOverlayView(context: Context) : View(context) {

    /**
     * What typesetting decided for one bubble, independent of its
     * neighbours: kept across [setBubbles] calls for as long as the bubble
     * stays on the page.
     */
    private class Lettering(
        val layout: StaticLayout,
        /** Where the layout's top-left sits before any nudge. */
        val x: Float,
        val y: Float,
        val ink: Int,
        /** Stroke drawn under the fill: the original's outline, or a halo. 0 for none. */
        val edge: Int,
        val edgeWidth: Float,
        /** Stroke in the fill's own colour that thickens the face; 0 for none. */
        val weight: Float,
        /** Every pixel the text can touch, stroke and italic overhang included, before any nudge. */
        val inkRect: RectF,
        /** [inkRect] relative to the layout origin, for the fade layer. */
        val layer: RectF,
        /** May move off lettering already placed; a balloon's text never leaves its balloon. */
        val movable: Boolean,
        val card: RectF? = null,
        val cardColor: Int = 0,
        /** Rectangle wiped to [cardColor] under a card: an on-art vertical column. */
        val wipe: RectF? = null,
        /** Tint for a flat balloon stamp; null draws the stamp's own colours. */
        val tint: PorterDuffColorFilter? = null,
        /** Set a quarter turn clockwise, reading down, along a column of lettering. */
        val turned: Boolean = false,
    )

    private class Placed(
        /** The bubble this was placed for. */
        val source: RenderBubble,
        val lettering: Lettering,
        /** Vertical nudge off earlier lettering. */
        val dy: Float,
        val bounds: RectF,
        val stamp: Bitmap?,
        val stampDst: RectF?,
        val patch: Bitmap?,
        val patchDst: Rect?,
        /** When the lettering first showed, for the fade-in; 0 when it shows at once. */
        val since: Long,
    )

    /**
     * Everything [Lettering] depends on. Bitmaps are left out on purpose:
     * the pipeline may rebuild a patch or fill for the same text, and the
     * layout does not care — only [busy], judged from the patch, does.
     */
    private data class Key(
        val box: Rect,
        val translated: String,
        val original: String,
        val style: LetterStyle,
        val kind: BubbleKind,
        val vertical: Boolean,
        val bgColor: Int,
        val textColor: Int,
        val outlineColor: Int?,
        val balloon: Balloon?,
        val inpainted: Boolean,
        val patched: Boolean,
        val busy: Boolean,
    )

    /** Identity of a balloon's mask and fill: a stamp is derived from exactly these objects. */
    private class StampKey(val mask: BooleanArray, val fill: Bitmap?) {
        override fun equals(other: Any?) = other is StampKey && other.mask === mask && other.fill === fill
        override fun hashCode() = System.identityHashCode(mask) * 31 + System.identityHashCode(fill)
    }

    /** One piece of text at one spot; a new one — or new words at the old spot — fades in. */
    private data class Shown(val box: Rect, val translated: String)

    private var placed: List<Placed> = emptyList()

    private var letterings = HashMap<Key, Lettering?>()
    private var stamps = HashMap<StampKey, Bitmap?>()
    private var busyPatches = IdentityHashMap<Bitmap, Boolean>()
    private var shownSince = HashMap<Shown, Long>()

    /** The conditions [letterings] were set under; any change invalidates all of them. */
    private var setW = -1f
    private var setH = -1f
    private var setScale = Float.NaN
    private var setOpacity = Float.NaN

    private companion object {
        /** Smallest type, in dp, the text shrinks to. */
        const val MIN_TYPE_SIZE = 9f

        /**
         * Smallest type, in dp, inside a balloon. A dense vertical balloon's
         * own lettering is often smaller than [MIN_TYPE_SIZE], and English
         * needs more letters than it: a step smaller keeps the line inside
         * the balloon rather than running out over its outline.
         */
        const val BALLOON_MIN_TYPE_SIZE = 7.5f

        /** Margin, in dp, free lettering keeps from its panel's border. */
        const val PANEL_MARGIN = 3f
        const val LINE_SPACING = 1.06f

        /** Capitals have no descenders to clear; sound effects stack tight. */
        const val SFX_LINE_SPACING = 0.9f

        /** Share of a row's interior a line may use; the rest is the margin a letterer keeps. */
        const val SHAPE_MARGIN = 0.88f

        /** Line counts tried beyond the fewest the words allow. */
        const val EXTRA_LINES = 3

        /** Vertical positions sampled for a block, besides the centred one. */
        const val TOP_SAMPLES = 12

        /** Weight of the block's distance from the body centre against its fill. */
        const val CENTER_WEIGHT = 4f

        /**
         * English type size per pixel of the original CJK glyph. A CJK glyph
         * fills its em square; Latin lower case fills about half of it, so
         * the same size already reads a touch smaller.
         */
        const val EN_PER_GLYPH = 0.95f

        /** Largest free lettering, in dp, however big the original was. */
        const val MAX_FREE_SIZE = 44f

        /** Sound-effect size bounds, in dp. */
        const val MIN_SFX_SIZE = 11f
        const val MAX_SFX_SIZE = 160f

        /** Stroke widths as a share of the type size (half of each shows outside the glyph). */
        const val OUTLINE_SHARE = 0.18f
        const val SFX_OUTLINE_SHARE = 0.24f
        const val HALO_SHARE = 0.13f

        /** Shouts start larger and are thickened with a stroke of their own ink. */
        const val SHOUT_SCALE = 1.15f
        const val SHOUT_WEIGHT = 0.05f
        const val SFX_WEIGHT = 0.03f

        /** Width budgets tried per type size between the narrowest and widest allowed. */
        const val WIDTH_STEPS = 6

        /** Type size step, as a factor, for free lettering. */
        const val SHRINK = 0.93f

        /** Hyphenated pieces aim below the widest row: the rows above and below are narrower. */
        const val HYPHEN_SHARE = 0.7f

        /** Contrast ratio below which lettering cannot carry itself against its background. */
        const val MIN_CONTRAST = 3.0

        const val FADE_MS = 120L

        /** A sound-effect note's type size, in sp, whatever the size of the sound. */
        const val NOTE_SIZE = 13f

        /** A note's measure, in multiples of its type size, when the sound is narrower: a sound said twice fits one line. */
        const val NOTE_MEASURE = 12f

        /** Times in a row a note repeats its sound, however often the art draws it. */
        const val NOTE_REPEATS = 2

        /** The same sound this close to one already noted (a share of the screen's width) is not noted again. */
        const val NOTE_ONCE = 0.25f

        /** Height to width past which a sound is a column, and its note goes beside it. */
        const val TALL_SOUND = 1.5f

        /** Share of the note, or of its sound if smaller, the note may cover of its own sound. */
        const val NOTE_OVER_SOUND = 0.15f

        /** Share of a note that may lie on other lettering before the sound is left un-noted. */
        const val NOTE_CROWDED = 0.2f

        /** Share of a spot beside a sound that may hold line work and still take its note. */
        const val NOTE_CALM = 0.12f

        /** A column turns its note to run along it once it is this many note lines tall. */
        const val TURN_ROOM = 2f

        /** Height to width past which erased lettering is a long column its English may run down; a short one stays upright. */
        const val TURN_COLUMN = 2.5f

        /** Upright English this much wider than the column it replaces is turned to run down it. */
        const val TURN_SPILL = 1.35f

        /** ...unless turning would shrink the type below this share of the upright size. */
        const val TURN_MIN_SIZE = 0.7f

        val WHITESPACE = Regex("\\s+")

        /** Type size widths are measured at before scaling ([scaledMeasure]). */
        const val MEASURE_REF = 100f

        /** Space before a token with no letters or digits in it; replaced by a no-break space. */
        val STRAY_SYMBOLS = Regex("[ \\t]+(?=[^\\p{L}\\p{N}\\s]+(\\s|$))")

        /** An ellipsis with a letter straight after it. */
        val ELLIPSIS_IN_WORD = Regex("(\\.\\.\\.|…)(?=\\p{L})")

        /** Type may shrink this far to keep a long word whole; past it, the word is hyphenated. */
        const val HYPHEN_FLOOR = 0.65f

        /** Width to height a caption block set from a column aims for. */
        const val CAPTION_ASPECT = 1.2f

        /** Cost of a line holding only a scrap of the block's width. */
        const val ORPHAN_COST = 1f

        /** How far type may shrink, as a factor, to save a block from a stranded scrap. */
        const val STRANDED_SHRINK = 0.86f

        /** Cost per share of growth past the original's measure. */
        const val GROWTH_COST = 0.8f

        /**
         * Lowest window strength a veil makes up for: below it the veil's
         * black would have to be more than opaque.
         */
        const val MIN_VEILED_ALPHA = 0.5f

        /** [color] less [k] of [under], channel by channel, down to black at most; alpha kept. */
        fun lessPage(color: Int, under: Int, k: Float): Int {
            val share = (k * 65536f).toInt()
            return (color and -0x1000000) or
                (less(color shr 16 and 0xFF, under shr 16 and 0xFF, share) shl 16) or
                (less(color shr 8 and 0xFF, under shr 8 and 0xFF, share) shl 8) or
                less(color and 0xFF, under and 0xFF, share)
        }

        /** [lessPage] over every painted pixel of [px], against the page pixels in [under]. */
        fun lessPage(px: IntArray, under: IntArray, k: Float) {
            val share = (k * 65536f).toInt()
            for (i in px.indices) {
                val p = px[i]
                if (p ushr 24 == 0) continue
                val u = under[i]
                px[i] = (p and -0x1000000) or
                    (less(p shr 16 and 0xFF, u shr 16 and 0xFF, share) shl 16) or
                    (less(p shr 8 and 0xFF, u shr 8 and 0xFF, share) shl 8) or
                    less(p and 0xFF, u and 0xFF, share)
            }
        }

        private fun less(c: Int, u: Int, share: Int): Int = max(0, c - ((u * share + 32768) shr 16))

        /** Patch samples across and down when judging how busy the art under it is. */
        const val BUSY_GRID_X = 12
        const val BUSY_GRID_Y = 8
    }

    /**
     * Kept so bubbles can be laid out again once the view knows its real
     * size. Placement clamps lettering inside the screen, and the first
     * pass often runs before layout, when the only width available is the
     * display metric — on a multi-window or letterboxed reader that is wider
     * than the view, and lettering against the right edge is clipped.
     */
    private var source: List<RenderBubble> = emptyList()

    @Volatile var textScale = 1f
    @Volatile var bgOpacity = 1f

    /**
     * How strongly the system draws this layer: 1 when as painted, less
     * where it caps an overlay that lets touches through. Android 12 and
     * later hold one to 80% (see [OverlayController]), so a fifth of the
     * page shows through everything painted here: a grey ghost of the
     * lettering each cleaned balloon replaced, and English a shade off black.
     */
    var windowAlpha = 1f
        set(value) {
            if (field == value) return
            field = value
            invalidate()
            onVeilChanged?.invoke()
        }

    /**
     * Whether the page is veiled so that nothing of the original shows
     * through: black at [veil] over the whole screen takes everything this
     * layer does not paint down to [windowAlpha] of itself, and what it does
     * paint is painted less the page's share ([groundOf]), so the two meet
     * at that same level. Switched by the capture loop, which then reads the
     * screen through the veil.
     */
    var veiled = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
            onVeilChanged?.invoke()
        }

    /** Draws the cleanings alone, veiled or not: what erasure leaves of the page, for the page harness to measure. */
    internal var cleaningsOnly = false

    /** Grounds painted so far ([groundOf]), for tests to see what streaming costs. */
    internal var groundsPainted = 0

    /** Told whenever [veil] may have changed, [screenLevel] with it. Main thread. */
    var onVeilChanged: (() -> Unit)? = null

    /** Opacity of the veil's black; 0 when there is none, or when no veil could make up for [windowAlpha]. */
    val veil: Float
        get() = if (veiled && windowAlpha >= MIN_VEILED_ALPHA && windowAlpha < 1f) (1f - windowAlpha) / windowAlpha else 0f

    /**
     * The share of itself the page shows at wherever this layer paints
     * nothing: under the veil, [windowAlpha] (as near as eight bits of veil
     * come to it), otherwise all of it. A capture of the screen is divided
     * by this to read the page as it is.
     */
    val screenLevel: Float
        get() {
            val k = veil
            return if (k > 0f) 1f - windowAlpha * veilAlpha(k) / 255f else 1f
        }

    private fun veilAlpha(k: Float) = (k * 255f + 0.5f).toInt().coerceIn(0, 255)

    /**
     * The page under this layer as it is with none of MangaLens on it, in
     * screen pixels: the frame the lettering was set for. Only a veil needs
     * it, to take the page's share off each pixel painted over it.
     */
    private var backdrop: Bitmap? = null

    /**
     * One piece of painting as it goes on screen under a veil: a bubble's
     * cleaning, or its card's ground, drawn by itself at [left], [top] and
     * taken the page's share off ([groundOf]).
     */
    private class Ground(val bitmap: Bitmap, val left: Float, val top: Float)

    /**
     * What a [Ground] is drawn from. The view keeps its letterings, stamps
     * and patches as the same objects for as long as their bubble stays
     * unchanged, so a bubble streamed in before keeps its grounds, and a
     * line streaming in costs its own area and no more.
     */
    private data class GroundKey(
        val card: Boolean,
        val lettering: Lettering,
        val stamp: Bitmap?,
        val stampDst: RectF?,
        val patch: Bitmap?,
        val patchDst: Rect?,
        val dy: Float,
    )

    private var grounds = HashMap<GroundKey, Ground?>()

    /** The page and veil [grounds] were painted against, and the placements they were last matched to. */
    private var groundsPage: Bitmap? = null
    private var groundsVeil = 0f
    private var groundsList: List<Placed>? = null

    /** [grounds] in [placed]'s order: each bubble's cleaning, then each bubble's card. */
    private var cleaningGrounds: Array<Ground?> = emptyArray()
    private var cardGrounds: Array<Ground?> = emptyArray()

    /**
     * Whether new lettering fades in. A view with no window has no frames to
     * animate — it is drawn only on demand, into a screenshot or a test —
     * and must show its final state on the first draw.
     */
    internal var animates: () -> Boolean = { isAttachedToWindow }
    internal var clock: () -> Long = { AnimationUtils.currentAnimationTimeMillis() }

    /**
     * Screen areas left bare: MangaLens's own floating controls, while this
     * layer is drawn over them. An accessibility overlay sits above every
     * app overlay, so solid lettering would otherwise cover the button, a
     * status the reader has to read, or the menu they just opened. Kept up
     * to date by [OverlayController].
     */
    var keepClear: List<Rect> = emptyList()
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /**
     * Comic Neue is the lettering hand; the platform faces stand in when a
     * resource fails to inflate, so a broken font asset costs the page its
     * look but never its text. Loaded once — font inflation parses the file
     * on every call.
     */
    private val boldFace: Typeface =
        font(R.font.comic_neue_bold) ?: Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val regularFace: Typeface =
        font(R.font.comic_neue_regular) ?: Typeface.create("sans-serif", Typeface.NORMAL)
    private val boldItalicFace: Typeface =
        font(R.font.comic_neue_bold_italic) ?: Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC)

    private fun font(id: Int): Typeface? =
        runCatching { ResourcesCompat.getFont(context, id) }.getOrNull()

    /**
     * Thoughts lean but keep dialogue's weight, as a scanlation sets them:
     * a thin italic beside bold speech read as faint, almost unlettered.
     */
    private fun faceFor(style: LetterStyle): Typeface = when (style) {
        LetterStyle.NARRATION -> regularFace
        LetterStyle.THOUGHT, LetterStyle.SFX, LetterStyle.SFX_NOTE -> boldItalicFace
        else -> boldFace
    }

    private fun italic(style: LetterStyle) =
        style == LetterStyle.THOUGHT || style == LetterStyle.SFX || style == LetterStyle.SFX_NOTE

    private val veilPaint = Paint().apply { color = Color.BLACK }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val patchPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = 0x2E000000
    }
    private val debugPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        color = 0xCCE6008C.toInt()
    }

    /**
     * Balloons the page detector found, outlined when diagnostics are on. An
     * untranslated balloon means something different depending on whether it
     * was outlined: a detection failure if not, a translation failure if so.
     */
    private var debugBalloons: List<Rect> = emptyList()

    /** The panel grid read off the page, outlined in blue when diagnostics are on. */
    private var debugPanels: List<Rect> = emptyList()

    private val debugPanelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        color = 0xB0008CE6.toInt()
    }

    /**
     * Lays out [bubbles] and shows them. [page] is the frame they were set
     * for, as the screen shows it with nothing of MangaLens on it; while the
     * page is veiled the cleanings are painted against it (see [veiled]).
     * The view keeps the reference and never alters or frees it.
     */
    fun setBubbles(bubbles: List<RenderBubble>, page: Bitmap? = null) {
        source = bubbles
        placed = placeAll(bubbles)
        backdrop = page
        invalidate()
    }

    /**
     * Places bubbles in order, letting each see the space already claimed:
     * on a dense page, lettering centred on neighbouring columns lands on
     * the same spot, and a stack of text reads as one unreadable slab.
     *
     * Only the nudging depends on the neighbours; everything else about a
     * bubble is looked up from the previous call when the bubble is
     * unchanged. The caches are rebuilt from this list alone, so they never
     * hold more than the page on screen.
     */
    private fun placeAll(bubbles: List<RenderBubble>): List<Placed> {
        val screenW = screenWidth()
        val screenH = screenHeight()
        if (screenW != setW || screenH != setH || textScale != setScale || bgOpacity != setOpacity) {
            letterings.clear()
            setW = screenW
            setH = screenH
            setScale = textScale
            setOpacity = bgOpacity
        }
        val now = if (animates()) clock().coerceAtLeast(1L) else 0L
        val nextLetterings = HashMap<Key, Lettering?>(bubbles.size * 2)
        val nextStamps = HashMap<StampKey, Bitmap?>()
        val nextBusy = IdentityHashMap<Bitmap, Boolean>()
        val nextShown = HashMap<Shown, Long>(bubbles.size * 2)

        val out = ArrayList<Placed>(bubbles.size)
        val occupied = ArrayList<RectF>(bubbles.size)
        // Notes go last: a note may sit anywhere around its sound, so it is
        // the one to step around everything else.
        val (notes, lettered) = bubbles.partition { it.style == LetterStyle.SFX_NOTE }
        for (b in lettered) {
            if (b.translated.isBlank()) continue
            val balloon = b.balloon
            val stamp = balloon?.let { stampFor(it, b.fill, nextStamps) }
            val patch = if (stamp == null) b.patch else null
            val busy = patch != null && busyFor(patch, b.bgColor, nextBusy)
            val key = Key(
                box = Rect(b.box),
                translated = b.translated,
                original = b.original,
                style = b.style,
                kind = b.kind,
                vertical = b.vertical,
                bgColor = b.bgColor,
                textColor = b.textColor,
                outlineColor = b.outlineColor,
                balloon = if (stamp != null) balloon else null,
                inpainted = stamp != null && b.fill != null,
                patched = patch != null,
                busy = busy,
            )
            val l = if (letterings.containsKey(key)) letterings[key] else letter(b, stamp != null, patch != null, busy)
            nextLetterings[key] = l
            if (l == null) continue

            var dy = 0f
            val claim = RectF(l.card ?: l.inkRect)
            if (l.movable) {
                val before = RectF(claim)
                nudgeClear(claim, occupied, screenH)
                // Off other lettering, but not out of its own panel.
                val panel = b.panel
                if (panel != null && before.top >= panel.top && before.bottom <= panel.bottom &&
                    (claim.top < panel.top || claim.bottom > panel.bottom)
                ) {
                    claim.set(before)
                }
                dy = claim.top - (l.card ?: l.inkRect).top
            }
            val bounds = RectF(l.inkRect).apply { offset(0f, dy) }
            l.card?.let { bounds.union(claim) }
            l.wipe?.let { bounds.union(it) }
            val stampDst = if (stamp != null) RectF(balloon!!.box) else null
            stampDst?.let { bounds.union(it) }
            val patchDst = if (patch != null) Rect(b.patchRect ?: b.box) else null
            patchDst?.let { bounds.union(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat()) }

            val shown = Shown(key.box, b.translated)
            val since = if (now == 0L) 0L else shownSince[shown] ?: shownNear(shown) ?: now
            nextShown[shown] = since

            out.add(Placed(b, l, dy, bounds, stamp, stampDst, patch, patchDst, since))
            // A cleaned balloon claims all of itself; free lettering only its text,
            // since a patch is background anyone may letter over.
            occupied.add(if (stamp != null) bounds else claim)
        }
        // A noted sound effect stays part of the art: nothing under it is cleaned.
        val sounds = notes.map { RectF(it.box) }
        val noted = ArrayList<Pair<String, Rect>>()
        for (b in notes) {
            val text = noteText(b)
            if (text.isEmpty()) continue
            // The same sound again close by — the other heart of a pounding
            // pair, a column repeated down the panel — is noted once.
            val said = text.filter(Char::isLetterOrDigit)
            if (noted.any { (t, box) -> t == said && gap(box, b.box) < screenW * NOTE_ONCE }) continue
            val l = placeNote(b, text, occupied, sounds) ?: continue
            noted.add(said to b.box)
            val shown = Shown(Rect(b.box), b.translated)
            val since = if (now == 0L) 0L else shownSince[shown] ?: shownNear(shown) ?: now
            nextShown[shown] = since
            out.add(Placed(b, l, 0f, RectF(l.inkRect), null, null, null, null, since))
            occupied.add(RectF(l.inkRect))
        }
        letterings = nextLetterings
        stamps = nextStamps
        busyPatches = nextBusy
        shownSince = nextShown
        return out
    }

    /**
     * When a line already on screen started showing, found by its words
     * and nearly its place: the model's reading of a line recalled from
     * memory lands a few pixels from the recalled box, and the same words
     * fading in again from nothing reads as a flicker in the line being read.
     */
    private fun shownNear(shown: Shown): Long? {
        var best: Long? = null
        for ((old, since) in shownSince) {
            if (old.translated != shown.translated) continue
            val r = Rect()
            if (!r.setIntersect(old.box, shown.box)) continue
            val inter = r.width().toLong() * r.height()
            val union = old.box.width().toLong() * old.box.height() + shown.box.width().toLong() * shown.box.height() - inter
            if (union > 0 && inter * 2 >= union) best = since
        }
        return best
    }

    private fun screenWidth() = (if (width > 0) width else resources.displayMetrics.widthPixels).toFloat()
    private fun screenHeight() = (if (height > 0) height else resources.displayMetrics.heightPixels).toFloat()

    private fun stampFor(balloon: Balloon, fill: Bitmap?, next: HashMap<StampKey, Bitmap?>): Bitmap? {
        val key = StampKey(balloon.mask, fill)
        if (next.containsKey(key)) return next[key]
        val stamp = if (stamps.containsKey(key)) stamps[key] else erodedStamp(balloon, fill)
        next[key] = stamp
        return stamp
    }

    private fun busyFor(patch: Bitmap, bg: Int, next: IdentityHashMap<Bitmap, Boolean>): Boolean =
        next[patch] ?: (busyPatches[patch] ?: busyUnder(patch, bg)).also { next[patch] = it }

    /**
     * Whether the art under erased lettering is busy enough that plain
     * English on it would drown — judged from a sparse grid of the patch's
     * reconstructed pixels, never a scan of the whole bitmap. On flat paper
     * the reconstruction is the paper colour throughout; on art it varies.
     */
    private fun busyUnder(patch: Bitmap, bg: Int): Boolean {
        val w = patch.width
        val h = patch.height
        if (w < 2 || h < 2) return false
        var n = 0
        var far = 0
        for (gy in 0 until BUSY_GRID_Y) {
            val y = ((gy + 0.5f) * h / BUSY_GRID_Y).toInt()
            for (gx in 0 until BUSY_GRID_X) {
                val c = patch.getPixel(((gx + 0.5f) * w / BUSY_GRID_X).toInt(), y)
                if (Color.alpha(c) < 128) continue
                n++
                val d = kotlin.math.abs(Color.red(c) - Color.red(bg)) +
                    kotlin.math.abs(Color.green(c) - Color.green(bg)) +
                    kotlin.math.abs(Color.blue(c) - Color.blue(bg))
                if (d > 90) far++
            }
        }
        return n >= 6 && far * 4 > n
    }

    private fun letter(b: RenderBubble, clean: Boolean, patched: Boolean, busy: Boolean): Lettering? = when {
        clean -> placeClean(b, b.balloon!!, b.fill != null)
        patched || b.style == LetterStyle.SFX -> placeFree(b, busy)
        else -> placeCard(b)
    }

    fun setDebugBalloons(rects: List<Rect>, panels: List<Rect> = emptyList()) {
        debugBalloons = rects
        debugPanels = panels
        invalidate()
    }

    /**
     * The strips the diagnostic outlines are drawn on, for the capture loop
     * to mask out of its comparisons: outlines around every panel and
     * balloon would otherwise read as the page having changed the moment
     * they are drawn, and diagnostics would restart the very pass they
     * were meant to explain.
     */
    fun debugStrokeRects(): List<Rect> {
        val n = debugPanels.size + debugBalloons.size
        if (n == 0) return emptyList()
        val out = ArrayList<Rect>(n * 4)
        // Half the stroke each side of the edge, plus anti-aliasing.
        val w = dp(2f).toInt().coerceAtLeast(2)
        for (r in debugPanels) strokeStrips(r, w, out)
        for (r in debugBalloons) strokeStrips(r, w, out)
        return out
    }

    private fun strokeStrips(r: Rect, w: Int, out: MutableList<Rect>) {
        out.add(Rect(r.left - w, r.top - w, r.right + w, r.top + w))
        out.add(Rect(r.left - w, r.bottom - w, r.right + w, r.bottom + w))
        out.add(Rect(r.left - w, r.top - w, r.left + w, r.bottom + w))
        out.add(Rect(r.right - w, r.top - w, r.right + w, r.bottom + w))
    }

    fun clear() {
        source = emptyList()
        placed = emptyList()
        backdrop = null
        grounds = HashMap()
        groundsPage = null
        groundsList = null
        cleaningGrounds = emptyArray()
        cardGrounds = emptyArray()
        letterings.clear()
        stamps.clear()
        busyPatches.clear()
        shownSince.clear()
        debugBalloons = emptyList()
        debugPanels = emptyList()
        invalidate()
    }

    /** Ends any fade in progress: everything shows at full strength from the next draw. */
    fun finishFades() {
        if (placed.none { it.since != 0L }) return
        placed = placed.map { Placed(it.source, it.lettering, it.dy, it.bounds, it.stamp, it.stampDst, it.patch, it.patchDst, 0L) }
        for (k in shownSince.keys) shownSince[k] = 0L
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (source.isEmpty()) return
        placed = placeAll(source)
        invalidate()
    }

    fun hasBubbles() = placed.isNotEmpty()

    /**
     * Screen rectangles the overlay currently paints. The capture pipeline
     * masks these out when watching for a page change and excludes them from
     * detection — they are captured along with the page, so a rect that
     * understates the painting hides a change beneath it. Each covers the
     * whole of one bubble's painting: a cleaned balloon's box, a patch, a
     * card and its wipe, and the text with its outline and overhang.
     */
    fun placedRects(): List<Rect> = placed.map {
        Rect(
            kotlin.math.floor(it.bounds.left).toInt(),
            kotlin.math.floor(it.bounds.top).toInt(),
            kotlin.math.ceil(it.bounds.right).toInt(),
            kotlin.math.ceil(it.bounds.bottom).toInt(),
        )
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    /**
     * The text color a fill can actually carry. The pipeline's colors are
     * sampled from the page, and a card whose sampled fill is near-black can
     * arrive paired with near-black text — invisible on the very panels
     * (night scenes, flashbacks) where cards appear most. This is the last
     * gate: whatever upstream decided, dark fills get light lettering and
     * light fills get dark, keeping the preferred color when it already
     * contrasts.
     */
    private fun readableText(bg: Int, preferred: Int): Int {
        val bgLum = (Color.red(bg) * 299 + Color.green(bg) * 587 + Color.blue(bg) * 114) / 1000
        val prefLum = (Color.red(preferred) * 299 + Color.green(preferred) * 587 + Color.blue(preferred) * 114) / 1000
        return if (bgLum < 140) {
            if (prefLum > 170) preferred else Color.rgb(244, 245, 248)
        } else {
            if (prefLum < 100) preferred else 0xFF17181C.toInt()
        }
    }

    /** WCAG relative luminance, 0 (black) to 1 (white). */
    private fun relLum(c: Int): Double {
        fun ch(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * ch(Color.red(c)) + 0.7152 * ch(Color.green(c)) + 0.0722 * ch(Color.blue(c))
    }

    private fun contrast(a: Int, b: Int): Double {
        val la = relLum(a)
        val lb = relLum(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /** Black or white, whichever stands off [c] more: the outline an unoutlined colour needs. */
    private fun contrasting(c: Int): Int = if (relLum(c) > 0.3) 0xFF17181C.toInt() else Color.WHITE

    private fun opaque(c: Int) = c or (0xFF shl 24)

    /**
     * What gets lettered: sound effects in capitals, without the
     * translator's *asterisks*; a stray ♡, "!!" or "..." bound to the word
     * before it, since a line that holds nothing but a heart reads as a
     * typesetting accident; and a trailing-off "waiting...aah!?" given room
     * to break after its ellipsis, where a letterer would break it, rather
     * than standing as one unbreakable word.
     */
    private fun letterText(b: RenderBubble): String {
        val text = if (b.style == LetterStyle.SFX || b.style == LetterStyle.SFX_NOTE) {
            b.translated.replace("*", " ").trim().uppercase()
        } else {
            b.translated.trim()
        }
        return text.replace(STRAY_SYMBOLS, "\u00A0").replace(ELLIPSIS_IN_WORD, "$1 ")
    }

    /**
     * A note's words: the sound said at most twice in a row. A heartbeat
     * drawn eight times down a column is "BA-DUMP BA-DUMP" in its caption,
     * not eight lines of it standing over the art.
     */
    private fun noteText(b: RenderBubble): String {
        val out = ArrayList<String>()
        var run = 0
        for (token in letterText(b).split(WHITESPACE)) {
            if (token.isEmpty()) continue
            val word = token.filter(Char::isLetterOrDigit)
            val same = out.isNotEmpty() && word.isNotEmpty() && out.last().filter(Char::isLetterOrDigit) == word
            run = if (same) run + 1 else 1
            if (run <= NOTE_REPEATS) out.add(token)
        }
        return out.joinToString(" ")
    }

    /** Distance between two rectangles' edges; 0 when they touch or overlap. */
    private fun gap(a: Rect, b: Rect): Float {
        val dx = max(0, max(a.left - b.right, b.left - a.right)).toFloat()
        val dy = max(0, max(a.top - b.bottom, b.top - a.bottom)).toFloat()
        return kotlin.math.hypot(dx, dy)
    }

    /**
     * The balloon interior as a tintable stamp, shrunk by one mask cell: a
     * cell survives only when all four neighbours are interior too, and the
     * mask border always dies. The ring this gives up is what keeps the
     * balloon's own outline stroke visible around the fill — a fill that
     * erases the outline reads as a hole punched in the page rather than a
     * cleaned balloon. Null when nothing survives (a sliver of a mask); that
     * bubble falls back to free lettering or the card instead of stamping
     * nothing.
     */
    private fun erodedStamp(balloon: Balloon, fill: Bitmap?): Bitmap? {
        val w = balloon.maskW
        val h = balloon.maskH
        val mask = balloon.mask
        if (w < 3 || h < 3 || mask.size < w * h) return null
        // An inpainted fill carries the balloon's own colours cell for
        // cell; a flat one is white and tinted at draw time.
        val colors = fill?.takeIf { it.width == w && it.height == h }?.let { f ->
            IntArray(w * h).also { f.getPixels(it, 0, w, 0, 0, w, h) }
        }
        val px = IntArray(w * h)
        var any = false
        for (y in 1 until h - 1) {
            var i = y * w + 1
            for (x in 1 until w - 1) {
                if (mask[i] && mask[i - 1] && mask[i + 1] && mask[i - w] && mask[i + w]) {
                    px[i] = if (colors != null) colors[i] or (0xFF shl 24) else Color.WHITE
                    any = true
                }
                i++
            }
        }
        if (!any) return null
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /**
     * [tp]'s measure at whatever size it is set to, from widths taken once
     * at a reference size. Advances scale with the size, and the fitting
     * loops ask for the same runs of words at every size they try; measured
     * once, a size step is arithmetic. The block that is finally chosen is
     * measured at its real size ([blockOf]), so hinting drift can never
     * make a line wrap.
     */
    private fun scaledMeasure(tp: TextPaint): (String) -> Float {
        val ref = TextPaint(tp).apply { textSize = MEASURE_REF }
        val widths = HashMap<String, Float>()
        return { s -> widths.getOrPut(s) { ref.measureText(s) } * tp.textSize / MEASURE_REF }
    }

    private fun paintFor(style: LetterStyle) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = faceFor(style)
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private fun blockOf(lines: List<String>, tp: TextPaint, spacing: Float): StaticLayout {
        val block = lines.joinToString("\n")
        var widest = 0f
        for (line in lines) widest = max(widest, tp.measureText(line))
        return StaticLayout.Builder
            .obtain(block, 0, block.length, tp, (widest + 2f).toInt().coerceAtLeast(16))
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, spacing)
            .setIncludePad(false)
            .build()
    }

    /** How far ink can reach past a layout's line boxes: half of each stroke, and an italic's lean. */
    private fun inkPad(size: Float, style: LetterStyle, edgeWidth: Float, weight: Float) =
        edgeWidth / 2f + weight / 2f + size * (if (italic(style)) 0.22f else 0.1f) + 1f

    /**
     * Packs a finished layout at ([x], [y]) with its colours and strokes.
     * The ink rect is grown by half of every stroke and by the overhang of
     * an italic's last letter, which leans past its advance: the capture
     * loop masks exactly this rect, and an understated one hides a page
     * change under the lettering.
     */
    private fun lettering(
        layout: StaticLayout,
        x: Float,
        y: Float,
        style: LetterStyle,
        ink: Int,
        edge: Int,
        edgeWidth: Float,
        weight: Float,
        movable: Boolean,
        card: RectF? = null,
        cardColor: Int = 0,
        wipe: RectF? = null,
        tint: PorterDuffColorFilter? = null,
        turned: Boolean = false,
    ): Lettering {
        val pad = inkPad(layout.paint.textSize, style, if (edge != 0) edgeWidth else 0f, weight)
        // Lines are centred in the layout, which may be wider than any of
        // them. Their own extents, not getLineWidth, which counts the
        // trailing newline's advance.
        var left = layout.width.toFloat()
        var right = 0f
        for (i in 0 until layout.lineCount) {
            left = min(left, layout.getLineLeft(i))
            right = max(right, layout.getLineRight(i))
        }
        val layer = RectF(min(left, right) - pad, -pad, right + pad, layout.height + pad)
        // Turned a quarter clockwise about (x, y), the layout's (u, v) lands at (x - v, y + u).
        val inkRect = if (turned) {
            RectF(x - layer.bottom, y + layer.left, x - layer.top, y + layer.right)
        } else {
            RectF(layer).apply { offset(x, y) }
        }
        return Lettering(layout, x, y, ink, edge, edgeWidth, weight, inkRect, layer, movable, card, cardColor, wipe, tint, turned)
    }

    /**
     * Clean-and-typeset: fill through the mask, then set the translation the
     * way a letterer would — inside the balloon's actual shape. The mask is
     * measured row by row ([BalloonShape]); the type starts generous and,
     * at each size, the block is tried at a few line counts and vertical
     * positions, each line capped by the room the balloon has at the rows
     * it would sit on. The first size at which the words fit wins, with
     * the placement that fills the shape best and sits nearest the body's
     * centre. A balloon too irregular to measure falls back to fitting an
     * elliptical taper into the box. When a single word is wider than the
     * balloon even at the smallest size, the search runs again with that
     * word hyphenated, rather than letting it run over the outline.
     *
     * The fill is opaque; the whole point is that the original lettering
     * must not ghost through the English.
     */
    private fun placeClean(b: RenderBubble, balloon: Balloon, inpainted: Boolean): Lettering? {
        val style = b.style
        val text = letterText(b)
        if (text.isEmpty()) return null
        val box = balloon.box
        // A cleaning is a replacement, not a patch: whatever the card-opacity
        // slider says — and devices upgraded from the patch era carry low
        // values in their saved settings — the original lettering must be
        // fully gone, or the balloon shows both languages interleaved.
        val fill = opaque(b.bgColor)
        // Coloured lettering keeps its colour when the paper can carry it.
        val ink = if (contrast(opaque(b.textColor), fill) >= MIN_CONTRAST) {
            opaque(b.textColor)
        } else {
            readableText(fill, b.textColor)
        }
        val tp = paintFor(style).apply { color = ink }
        val spacing = if (style == LetterStyle.SFX) SFX_LINE_SPACING else LINE_SPACING
        var startSize = (box.height() * 0.24f / resources.displayMetrics.density)
            .coerceIn(15f, 34f) * textScale
        if (style == LetterStyle.SHOUT) startSize *= SHOUT_SCALE
        val measure = scaledMeasure(tp)

        var layout: StaticLayout? = null
        var textX = 0f
        var textY = 0f

        val shape = BalloonShape.of(balloon.mask, balloon.maskW, balloon.maskH)
        if (shape != null && shape.maxSpan > 0f) {
            val cellW = box.width().toFloat() / balloon.maskW
            val cellH = box.height().toFloat() / balloon.maskH
            // First whole words, down to the hyphen floor; then from the top
            // again with any long word too wide for the balloon hyphenated;
            // then, only if nothing fits even the smallest type, with short
            // words and names hyphenated too.
            val hyphenFloor = max(MIN_TYPE_SIZE, startSize * HYPHEN_FLOOR)
            val hyphenWidth = shape.maxSpan * cellW * SHAPE_MARGIN * HYPHEN_SHARE
            search@ for (pass in 0..2) {
                var size = startSize
                while (size >= (if (pass == 0) hyphenFloor else BALLOON_MIN_TYPE_SIZE)) {
                    tp.textSize = dp(size)
                    val words = when (pass) {
                        0 -> text
                        1 -> TypeSet.hyphenate(text, measure, hyphenWidth)
                        else -> TypeSet.hyphenate(text, measure, hyphenWidth, eager = true)
                    }
                    val tried = when (pass) {
                        1 -> words == text && size >= hyphenFloor
                        2 -> words == TypeSet.hyphenate(text, measure, hyphenWidth)
                        else -> false
                    }
                    val fit = if (tried) {
                        null
                    } else {
                        val shaper = TypeSet.Shaper(words, measure)
                        val lineH = (tp.descent() - tp.ascent()) * spacing
                        fitShape(shaper, shape, lineH / cellH, cellW)?.let { it to lineH }
                    }
                    if (fit != null) {
                        val (lines, topRow) = fit.first
                        val candidate = blockOf(lines, tp, spacing)
                        layout = candidate
                        textX = box.left + shape.centerX * cellW - candidate.width / 2f
                        val blockCenterRow = topRow + lines.size * (fit.second / cellH) / 2f
                        textY = box.top + blockCenterRow * cellH - candidate.height / 2f
                        break@search
                    }
                    size = (size - 1.25f).coerceAtLeast(if (size > BALLOON_MIN_TYPE_SIZE) BALLOON_MIN_TYPE_SIZE else 0f)
                }
            }
        }

        // Words that fit no size of the balloon's shape spill over its
        // outline; they are then haloed in its paper so they stay legible
        // where they cross the outline and the art beyond.
        var spills = false
        if (layout == null) {
            // Elliptical taper into the box: the shape could not be read, or
            // the words will not fit it at any size.
            val maxTextW = box.width() * 0.78f
            val maxTextH = box.height() * 0.80f
            var size = startSize
            while (true) {
                tp.textSize = dp(size)
                val last = size <= BALLOON_MIN_TYPE_SIZE
                val words = if (last) TypeSet.hyphenate(text, measure, maxTextW, eager = true) else text
                val lines = TypeSet.breakLines(words, measure, maxTextW)
                var widest = 0f
                for (line in lines) widest = max(widest, tp.measureText(line))
                val candidate = blockOf(lines, tp, spacing)
                layout = candidate
                if (widest <= maxTextW && candidate.height <= maxTextH) break
                if (last) {
                    spills = true
                    break
                }
                size = (size - 1.25f).coerceAtLeast(BALLOON_MIN_TYPE_SIZE)
            }
            val chosen = layout ?: return null
            // On the balloon's body, not the middle of a box a tail stretches.
            val cx = shape?.let { box.left + it.centerX * box.width() / balloon.maskW } ?: box.exactCenterX()
            val cy = shape?.let { box.top + it.centerY * box.height() / balloon.maskH } ?: box.exactCenterY()
            textX = cx - chosen.width / 2f
            textY = cy - chosen.height / 2f
        }
        val chosen = layout ?: return null
        val size = tp.textSize
        val edge = b.outlineColor?.let(::opaque) ?: if (style == LetterStyle.ART || spills) fill else 0
        val edgeWidth = size * (if (b.outlineColor != null) OUTLINE_SHARE else HALO_SHARE)
        return lettering(
            layout = chosen,
            x = textX,
            y = textY,
            style = style,
            ink = ink,
            edge = edge,
            edgeWidth = edgeWidth,
            weight = weightFor(style, size),
            movable = false,
            tint = if (inpainted) null else PorterDuffColorFilter(fill, PorterDuff.Mode.SRC_IN),
        )
    }

    private fun weightFor(style: LetterStyle, size: Float) = when (style) {
        LetterStyle.SHOUT -> size * SHOUT_WEIGHT
        LetterStyle.SFX -> size * SFX_WEIGHT
        else -> 0f
    }

    /**
     * The best shaped break of [shaper]'s words into the balloon at one
     * type size, as the lines and the mask row the block starts on, or
     * null when the words do not fit the shape at this size.
     *
     * @param lineRows one line's height in mask rows.
     * @param cellW one mask cell's width in pixels.
     */
    private fun fitShape(
        shaper: TypeSet.Shaper,
        shape: BalloonShape,
        lineRows: Float,
        cellW: Float,
    ): Pair<List<String>, Float>? {
        val widest = shape.maxSpan * cellW * SHAPE_MARGIN
        if (shaper.words.isEmpty() || shaper.widestWord > widest) return null
        val minLines = shaper.greedyLines(widest)
        var best: TypeSet.Fit? = null
        var bestTop = 0f
        var bestScore = Float.MAX_VALUE
        for (k in minLines..minLines + EXTRA_LINES) {
            if (k > shaper.words.size) break
            val blockRows = k * lineRows
            if (blockRows > shape.rows) break
            for (top in candidateTops(shape, blockRows)) {
                val caps = FloatArray(k) { i ->
                    shape.capOver(top + i * lineRows, top + (i + 1) * lineRows) * cellW * SHAPE_MARGIN
                }
                if (caps.any { it < shaper.widestWord }) continue
                val fit = shaper.fit(caps) ?: continue
                // Fill the shape, and sit on the body: a block pushed to
                // one end of the balloon reads as misplaced even when its
                // lines fill their rows.
                val off = (top + blockRows / 2f - shape.centerY) / shape.rows
                val score = fit.cost + CENTER_WEIGHT * k * off * off
                if (score < bestScore) {
                    bestScore = score
                    best = fit
                    bestTop = top
                }
            }
        }
        val chosen = best ?: return null
        return chosen.lines to bestTop
    }

    /** Block start rows to try: centred on the body, plus a spread over the balloon. */
    private fun candidateTops(shape: BalloonShape, blockRows: Float): List<Float> {
        val room = shape.rows - blockRows
        if (room < 0f) return emptyList()
        val tops = ArrayList<Float>(TOP_SAMPLES + 1)
        tops.add((shape.centerY - blockRows / 2f).coerceIn(0f, room))
        for (i in 0..TOP_SAMPLES) tops.add(room * i / TOP_SAMPLES)
        return tops
    }

    /**
     * The size of the original lettering's glyphs, in pixels, read off the
     * box and the source text: the box holds the source's glyphs in lines
     * (columns, when vertical) a little further apart than the glyphs are
     * tall, so the glyph is what makes that area hold that many. Explicit
     * line breaks in the source pin the line count; a box one line tall
     * pins the size outright. Spaces count half — Korean spaces its words,
     * Japanese does not.
     */
    private fun glyphSize(b: RenderBubble): Float {
        val w = b.box.width().toFloat()
        val h = b.box.height().toFloat()
        val across = if (b.vertical) w else h
        var glyphs = 0f
        var lines = 1
        for (c in b.original) {
            when {
                c == '\n' -> lines++
                c.isWhitespace() -> glyphs += 0.5f
                else -> glyphs += 1f
            }
        }
        if (glyphs < 1f) return min(across, dp(22f))
        val byArea = sqrt(w * h / (glyphs * 1.15f))
        val byLines = across / (lines * 1.15f - 0.15f)
        return minOf(byArea, byLines, across)
    }

    /**
     * Free lettering: the English set over the spot the original occupied,
     * in its colours, at its size — no card, no border.
     *
     * The patch has already erased the original strokes; what remains is to
     * letter as the artist would have. The fill is the original's
     * [RenderBubble.textColor]; its outline, when it had one, is drawn as a
     * stroke under the fill. Without one, a halo in the background colour
     * keeps the words legible where the art under them is busy, where the
     * style demands it (lettering on the art), or where the colour cannot
     * carry itself against the background — the halo then contrasting with
     * the ink instead.
     *
     * The block is centred on the original and sized from it ([fitFree]);
     * sound effects are sized to fill their box instead ([fitSfx]).
     */
    private fun placeFree(b: RenderBubble, busy: Boolean): Lettering? {
        val style = b.style
        val text = letterText(b)
        if (text.isEmpty()) return null
        val sfx = style == LetterStyle.SFX
        val box = b.box
        val screenW = screenWidth()
        val screenH = screenHeight()

        val ink = opaque(b.textColor)
        val bg = opaque(b.bgColor)
        val original = b.outlineColor?.let(::opaque)
        val edge: Int
        val edgeShare: Float
        when {
            sfx -> {
                edge = original ?: contrasting(ink)
                edgeShare = SFX_OUTLINE_SHARE
            }
            original != null -> {
                edge = original
                edgeShare = OUTLINE_SHARE
            }
            contrast(ink, bg) < MIN_CONTRAST -> {
                edge = contrasting(ink)
                edgeShare = HALO_SHARE
            }
            busy || style == LetterStyle.ART -> {
                edge = bg
                edgeShare = HALO_SHARE
            }
            else -> {
                edge = 0
                edgeShare = 0f
            }
        }

        val tp = paintFor(style).apply { color = ink }
        val spacing = if (sfx) SFX_LINE_SPACING else LINE_SPACING
        // The panel the lettering stands in bounds the block: a line runs
        // on over the border into the next panel no more in English than it
        // did in the original.
        val panel = b.panel?.let { RectF(it).apply { inset(dp(PANEL_MARGIN), dp(PANEL_MARGIN)) } }
            ?.takeIf { it.width() > dp(MIN_TYPE_SIZE) * 3 && it.height() > dp(MIN_TYPE_SIZE) * 2 }
        val lines = if (sfx) {
            fitSfx(text, tp, box, edgeShare, spacing)
        } else {
            fitFree(
                b, text, tp, spacing,
                room = min(screenW - dp(4f), panel?.width() ?: Float.MAX_VALUE),
                maxH = panel?.height() ?: Float.MAX_VALUE,
            )
        }
        val layout = blockOf(lines, tp, spacing)

        // A sound or art lettering drawn down a narrow column: English set
        // across it would spill over the art either side of the erased
        // strip, so it runs down the column instead, as a letterer sets it.
        val column = b.vertical && box.height() > box.width() * TURN_COLUMN && (sfx || style == LetterStyle.ART)
        if (column && layout.width > box.width() * TURN_SPILL) {
            val along = Rect(0, 0, box.height(), box.width())
            val turnedTp = paintFor(style).apply { color = ink }
            val turnedLines = if (sfx) {
                fitSfx(text, turnedTp, along, edgeShare, spacing)
            } else {
                fitFree(b.copy(box = along, vertical = false), text, turnedTp, spacing, box.height().toFloat())
            }
            if (turnedTp.textSize >= tp.textSize * TURN_MIN_SIZE) {
                val turned = blockOf(turnedLines, turnedTp, spacing)
                val size = turnedTp.textSize
                val edgeWidth = size * edgeShare
                val weight = weightFor(style, size)
                val probe = lettering(turned, 0f, 0f, style, ink, edge, edgeWidth, weight, movable = true, turned = true).inkRect
                val x = (box.exactCenterX() - probe.centerX())
                    .coerceIn(-probe.left, max(-probe.left, screenW - probe.right))
                val y = (box.exactCenterY() - probe.centerY())
                    .coerceIn(-probe.top, max(-probe.top, screenH - probe.bottom))
                return lettering(turned, x, y, style, ink, edge, edgeWidth, weight, movable = true, turned = true)
            }
        }
        val size = tp.textSize

        var x = box.exactCenterX() - layout.width / 2f
        // Capitals have no descenders: centre a sound effect's ink, not its
        // line boxes, or it floats above the spot it replaces.
        val inkMid = if (sfx) {
            val cap = capHeight(tp)
            (layout.getLineBaseline(0) - cap + layout.getLineBaseline(layout.lineCount - 1)) / 2f
        } else {
            layout.height / 2f
        }
        var y = box.exactCenterY() - inkMid
        val pad = inkPad(size, style, if (edge != 0) size * edgeShare else 0f, weightFor(style, size))
        // Inside the panel when the block fits it, then on the screen.
        if (panel != null && !sfx) {
            if (layout.width + pad * 2 <= panel.width()) {
                x = x.coerceAtMost(panel.right - layout.width - pad).coerceAtLeast(panel.left + pad)
            }
            if (layout.height + pad * 2 <= panel.height()) {
                y = y.coerceAtMost(panel.bottom - layout.height - pad).coerceAtLeast(panel.top + pad)
            }
        }
        x = x.coerceAtMost(screenW - layout.width - pad).coerceAtLeast(pad)
        y = y.coerceAtMost(screenH - layout.height - pad).coerceAtLeast(pad)
        return lettering(
            layout = layout,
            x = x,
            y = y,
            style = style,
            ink = ink,
            edge = edge,
            edgeWidth = size * edgeShare,
            weight = weightFor(style, size),
            movable = true,
        )
    }

    /**
     * A note for a sound effect left in the art: a small outlined caption,
     * sized for the reader rather than the sound — a note scaled to a sound
     * drawn across half the panel would bury the art it exists to spare.
     *
     * It goes where a letterer would put it without touching the drawing:
     * on empty ground beside the sound — paper, flat colour, an even tone,
     * as [RenderBubble.art] shows it — just below or above the sound from
     * its left edge, or for a tall column beside its first characters.
     * Where everything around the sound is drawn (a face, hair, a hand) the
     * note is set over the sound itself, centred on it, and along a column
     * a quarter turn, reading down it: the sound is what it translates, and
     * covering it hides nothing of the picture. It never crosses into
     * another panel, never lands on other lettering in [taken] or on
     * another sound in [sounds]; when even its own sound is covered by
     * someone's words, the sound is left as drawn.
     */
    private fun placeNote(b: RenderBubble, text: String, taken: List<RectF>, sounds: List<RectF>): Lettering? {
        val style = LetterStyle.SFX_NOTE
        val box = RectF(b.box)
        val screenW = screenWidth()
        val screenH = screenHeight()
        val size = sp(NOTE_SIZE) * textScale
        val ink = opaque(b.textColor)
        val edge = b.outlineColor?.let(::opaque)?.takeIf { contrast(it, ink) >= 1.5 } ?: contrasting(ink)
        val edgeWidth = size * OUTLINE_SHARE
        val tp = paintFor(style).apply {
            color = ink
            textSize = size
        }
        val weight = weightFor(style, size)
        fun block(measure: Float) =
            blockOf(TypeSet.breakLines(text, scaledMeasure(tp), measure.coerceAtLeast(size)), tp, SFX_LINE_SPACING)
        fun at(layout: StaticLayout, ink0: RectF, r: RectF, turned: Boolean) = lettering(
            layout = layout,
            x = if (turned) r.left + ink0.bottom else r.left - ink0.left,
            y = if (turned) r.top - ink0.left else r.top - ink0.top,
            style = style,
            ink = ink,
            edge = edge,
            edgeWidth = edgeWidth,
            weight = weight,
            movable = false,
            turned = turned,
        )
        fun onScreen(r: RectF) = r.apply {
            offset(
                (-left).coerceAtLeast(0f) - (right - screenW).coerceAtLeast(0f),
                (-top).coerceAtLeast(0f) - (bottom - screenH).coerceAtLeast(0f),
            )
        }
        fun crowding(r: RectF) = taken.sumOf { overlapArea(r, it).toDouble() }.toFloat() / area(r)

        val upright = block(max(box.width(), size * NOTE_MEASURE).coerceAtMost(screenW - dp(4f)))
        val ink0 = lettering(upright, 0f, 0f, style, ink, edge, edgeWidth, weight, movable = false).layer
        val w = ink0.width()
        val h = ink0.height()
        val tall = box.height() > box.width() * TALL_SOUND
        b.art?.let { art ->
            val gap = dp(2f)
            val below = box.bottom + gap
            val above = box.top - gap - h
            val beside = listOf(box.right + gap to box.top, box.left - gap - w to box.top)
            val around = listOf(box.left to below, box.left to above, box.right - w to below, box.right - w to above)
            for ((sx, sy) in if (tall) beside + around else around + beside) {
                val r = onScreen(RectF(sx, sy, sx + w, sy + h))
                if (overlapArea(r, box) > min(area(r), area(box)) * NOTE_OVER_SOUND) continue
                if (crowding(r) > 0f) continue
                if (sounds.any { it != box && overlapArea(r, it) > 0f }) continue
                if (b.otherPanels.any { overlapArea(r, RectF(it)) > 0f }) continue
                if (art.drawnShare(r) > NOTE_CALM) continue
                return at(upright, ink0, r, turned = false)
            }
        }
        // Over the sound, centred on it; along a column, turned to run down it.
        val turned = tall && box.height() >= h * TURN_ROOM
        val layout = if (turned) block(box.height()) else upright
        val layer = if (turned) lettering(layout, 0f, 0f, style, ink, edge, edgeWidth, weight, movable = false).layer else ink0
        val across = if (turned) layer.height() else layer.width()
        val down = if (turned) layer.width() else layer.height()
        val r = onScreen(RectF(box.centerX() - across / 2, box.centerY() - down / 2, box.centerX() + across / 2, box.centerY() + down / 2))
        if (crowding(r) > NOTE_CROWDED) return null
        return at(layout, layer, r, turned)
    }

    private fun area(r: RectF) = (r.width() * r.height()).coerceAtLeast(1f)

    private fun overlapArea(a: RectF, b: RectF): Float {
        val ix = minOf(a.right, b.right) - maxOf(a.left, b.left)
        val iy = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        return if (ix <= 0f || iy <= 0f) 0f else ix * iy
    }

    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private fun capHeight(tp: TextPaint): Float {
        val r = Rect()
        tp.getTextBounds("H", 0, 1, r)
        return r.height().toFloat()
    }

    /**
     * The measures a block may take at one type size, the height it must
     * stay within, and the shape it should have: [natural] is the width the
     * original occupied, and [aspect] the width-to-height ratio a block
     * standing in for it should come close to.
     */
    private class Budget(
        val narrowest: Float,
        val widest: Float,
        val tallest: Float,
        val natural: Float,
        val aspect: Float,
    )

    /**
     * The lines for free lettering, with [tp] left at the chosen size.
     *
     * Type starts at the original's size ([glyphSize]) and shrinks only when
     * the words will not fit the budget at any allowed measure. The budget
     * depends on the source's direction:
     *
     * - Horizontal: about the box's measure, growing a little, and about
     *   its height — the block should look like the line it replaces.
     * - A vertical column of dialogue: a horizontal block not much wider
     *   than the column — the balloon around a column is only a couple of
     *   glyphs wider than the column itself, and text past it runs onto
     *   the art.
     * - A vertical column of narration or art lettering: a readable measure
     *   across the column — a caption set two words to the line reads as a
     *   list. A short column usually sits in a caption box a few glyphs
     *   wider than itself and is held to about that; a long one, whose
     *   English at that measure would be a tower, is let out until the
     *   block is a little wider than tall.
     *
     * A word too long for the widest measure is kept whole down to
     * [HYPHEN_FLOOR] of the starting size and hyphenated past that. Text
     * that cannot fit at all is set at the smallest size and allowed to run
     * long, since clipped words are worse than a tall block.
     */
    private fun fitFree(
        b: RenderBubble,
        text: String,
        tp: TextPaint,
        spacing: Float,
        room: Float,
        maxH: Float = Float.MAX_VALUE,
    ): List<String> {
        val box = b.box
        val w = box.width().toFloat()
        val h = box.height().toFloat()
        val column = b.vertical && h > w * 1.2f
        val caption = b.style == LetterStyle.NARRATION || b.style == LetterStyle.ART
        val glyph = glyphSize(b)
        var start = glyph * EN_PER_GLYPH * textScale
        if (b.style == LetterStyle.SHOUT) start *= SHOUT_SCALE
        val floor = dp(MIN_TYPE_SIZE)
        start = start.coerceIn(floor, max(floor, dp(MAX_FREE_SIZE) * textScale))
        val measure = scaledMeasure(tp)
        val budget = { size: Float, lineH: Float ->
            when {
                column && caption -> {
                    val narrowest = min(max(w * 1.05f, size * 5f), room)
                    val squarish = sqrt(CAPTION_ASPECT * measure(text) * lineH)
                    val widest = min(maxOf(narrowest, min(h * 0.85f, max(w + glyph * 3f, squarish))), room)
                    Budget(narrowest, widest, min(max(h * 1.08f, lineH), maxH), narrowest, CAPTION_ASPECT)
                }
                column -> {
                    val widest = min(max(w * 1.5f, w + glyph * 1.5f), room)
                    val narrowest = min(max(w * 1.05f, size * 3.5f), widest)
                    Budget(narrowest, widest, min(max(h * 1.08f, lineH), maxH), w * 1.3f, w * 1.3f / h)
                }
                else -> {
                    val narrowest = min(max(w, size * 2.5f), room)
                    val widest = min(max(narrowest, w * 1.3f), room)
                    Budget(narrowest, widest, min(max(h * 1.3f + lineH * 0.5f, lineH), maxH), w, w / h)
                }
            }
        }
        val hyphenFloor = max(floor, start * HYPHEN_FLOOR)
        // A block with a stranded scrap of a line is kept only if a step or
        // two smaller type cannot set the words without one.
        var stranded: Block? = null
        var strandedSize = 0f
        for (pass in 0..2) {
            var size = start
            while (true) {
                tp.textSize = size
                val lineH = (tp.descent() - tp.ascent()) * spacing
                val bd = budget(size, lineH)
                val words = when (pass) {
                    0 -> text
                    1 -> TypeSet.hyphenate(text, measure, bd.widest * HYPHEN_SHARE)
                    else -> TypeSet.hyphenate(text, measure, bd.widest * HYPHEN_SHARE, eager = true)
                }
                val tried = when (pass) {
                    1 -> words == text && size >= hyphenFloor
                    2 -> words == TypeSet.hyphenate(text, measure, bd.widest * HYPHEN_SHARE)
                    else -> false
                }
                if (!tried) {
                    val block = linesWithin(words, measure, bd, lineH)
                    if (block != null && !block.stranded) return block.lines
                    if (block != null && stranded == null) {
                        stranded = block
                        strandedSize = size
                    }
                }
                val kept = stranded
                if (kept != null && size * SHRINK < strandedSize * STRANDED_SHRINK) {
                    tp.textSize = strandedSize
                    return kept.lines
                }
                val bottom = if (pass == 0) hyphenFloor else floor
                if (size <= bottom) break
                size = max(bottom, size * SHRINK)
            }
        }
        stranded?.let {
            tp.textSize = strandedSize
            return it.lines
        }
        tp.textSize = floor
        val lineH = (tp.descent() - tp.ascent()) * spacing
        val widest = budget(floor, lineH).widest
        return TypeSet.breakLines(TypeSet.hyphenate(text, measure, widest, eager = true), measure, widest)
    }

    /** Lines for a block, and whether one of them is a stranded scrap. */
    private class Block(val lines: List<String>, val stranded: Boolean)

    /**
     * The best break of [text] within [bd], or null when none fits. Each
     * measure in the budget gives the fewest lines it allows, evened out —
     * at a fixed line count the break closest to every line's cap is the
     * one with the most even lines, the block a letterer centres. Among
     * those, the block whose shape is nearest the budget's wins, with a
     * price on growing past the original's measure and on a line that
     * holds only a scrap ("I see, / that's / a / relief.").
     */
    private fun linesWithin(text: String, measure: (String) -> Float, bd: Budget, lineH: Float): Block? {
        val maxLines = (bd.tallest / lineH).toInt()
        if (maxLines < 1) return null
        val shaper = TypeSet.Shaper(text, measure)
        if (shaper.words.isEmpty()) return null
        var best: Block? = null
        var bestCost = Float.MAX_VALUE
        var lastK = -1
        for (i in 0..WIDTH_STEPS) {
            val cap = bd.narrowest + (bd.widest - bd.narrowest) * i / WIDTH_STEPS
            val lines = if (shaper.shapeable) {
                if (shaper.widestWord > cap) continue
                val k = shaper.greedyLines(cap)
                if (k > maxLines || k == lastK) continue
                lastK = k
                shaper.fit(FloatArray(k) { cap })?.lines ?: TypeSet.breakLines(text, measure, cap)
            } else {
                TypeSet.breakLines(text, measure, cap).takeIf { l -> l.size <= maxLines && l.all { measure(it) <= cap } }
                    ?: continue
            }
            var widest = 0f
            val widths = FloatArray(lines.size) { measure(lines[it]).also { lw -> widest = max(widest, lw) } }
            var cost = kotlin.math.abs(kotlin.math.ln(widest / (lines.size * lineH) / bd.aspect))
            cost += GROWTH_COST * max(0f, widest - bd.natural) / bd.natural
            var stranded = false
            if (lines.size > 1) {
                for (lw in widths) {
                    if (lw < widest * 0.35f) {
                        cost += ORPHAN_COST
                        stranded = true
                    }
                }
            }
            if (cost < bestCost) {
                bestCost = cost
                best = Block(lines, stranded)
            }
        }
        return best
    }

    /**
     * Sound-effect lines, with [tp] left at the size that fills [box]. A
     * sound effect is drawn to its space, not to a reading size: one, two
     * or three lines are tried, each at the size where the capitals —
     * outline included — just fill the box, and the biggest wins, with a
     * margin in favour of fewer lines. A tall narrow box lends a little
     * width, since a vertical sound effect's English is still set across.
     */
    private fun fitSfx(text: String, tp: TextPaint, box: Rect, edgeShare: Float, spacing: Float): List<String> {
        val ref = 100f
        tp.textSize = ref
        val measure = scaledMeasure(tp)
        val shaper = TypeSet.Shaper(text, measure)
        val lineRef = (tp.descent() - tp.ascent()) * spacing
        val capRef = capHeight(tp)
        val availW = max(box.width() * 1.1f, box.height() * 0.5f)
        val availH = box.height().toFloat()
        var best = listOf(text)
        var bestSize = 0f
        for (k in 1..min(shaper.words.size, 3)) {
            val lines = if (k == 1) listOf(shaper.words.joinToString(" ")) else narrowest(shaper, k) ?: continue
            if (lines.size != k) continue
            var widest = 0f
            for (line in lines) widest = max(widest, measure(line))
            val size = min(
                availW / (widest / ref + edgeShare),
                availH / ((capRef + (k - 1) * lineRef) / ref + edgeShare),
            )
            if (size > bestSize * 1.12f) {
                best = lines
                bestSize = size
            }
        }
        tp.textSize = (bestSize * textScale).coerceIn(dp(MIN_SFX_SIZE), dp(MAX_SFX_SIZE) * textScale)
        return best
    }

    /** The break of [shaper]'s words into [k] lines with the narrowest widest line. */
    private fun narrowest(shaper: TypeSet.Shaper, k: Int): List<String>? {
        if (!shaper.shapeable || k > shaper.words.size) return null
        var lo = shaper.widestWord
        var hi = lo * shaper.words.size * 2f
        repeat(14) {
            val mid = (lo + hi) / 2f
            if (shaper.greedyLines(mid) <= k) hi = mid else lo = mid
        }
        val lines = shaper.greedyLines(hi)
        return shaper.fit(FloatArray(lines) { hi })?.lines
    }

    private fun placeCard(b: RenderBubble): Lettering? {
        val screenW = screenWidth()
        val screenH = screenHeight()
        val pad = dp(7f)
        val text = letterText(b)
        if (text.isEmpty()) return null

        // A tall on-art column gets the column treatment: the narrow column
        // itself is wiped to the sampled page color, and the English sits in
        // a compact horizontal card over it. The old rule — widen the card to
        // the column's height and grow it to bury the column — turned every
        // narration column into a card the size of the panel; a dense page
        // disappeared under its own translations.
        val column = b.vertical && b.box.height() > b.box.width() * 2.2f && b.box.height() > dp(120f)

        var boxW = b.box.width().toFloat()
        if (column) {
            boxW = screenW * 0.56f
        } else if (b.vertical) {
            boxW = maxOf(boxW, b.box.height() * 0.85f)
        }
        boxW = boxW.coerceAtLeast(dp(88f)).coerceAtMost(screenW * 0.92f)
        val maxH = if (column) screenH * 0.38f else maxOf(b.box.height() + dp(26f), dp(64f))

        // Colors first: the text color depends on the fill it will sit on.
        // Cards paint effectively solid whatever the legacy opacity slider
        // saved — a see-through card over black art is how both languages
        // vanish at once.
        val alpha = (255 * bgOpacity).toInt().coerceIn(235, 255)
        val bg = Color.argb(alpha, Color.red(b.bgColor), Color.green(b.bgColor), Color.blue(b.bgColor))
        val ink = readableText(bg, b.textColor)

        val tp = paintFor(b.style).apply { color = ink }
        var layout: StaticLayout? = null
        var size = 18f * textScale
        while (size >= 9f) {
            tp.textSize = dp(size)
            val candidate = StaticLayout.Builder
                .obtain(text, 0, text.length, tp, (boxW - pad * 2).toInt().coerceAtLeast(40))
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(0f, LINE_SPACING)
                .setIncludePad(false)
                .build()
            layout = candidate
            if (candidate.height <= maxH - pad * 2) break
            size -= 1.25f
        }
        val chosen = layout ?: return null

        var maxLine = 0f
        for (i in 0 until chosen.lineCount) maxLine = maxOf(maxLine, chosen.getLineWidth(i))
        val w = (maxLine + pad * 2).coerceAtLeast(dp(40f)).coerceAtMost(boxW + pad * 2)
        // A dialogue card must bury the original block it floats on — CJK
        // runs taller than its translation, and a card sized to the text
        // leaves source lines peeking out. But burying is capped at half the
        // screen: past that the card is no longer covering a block, it is
        // covering the page. Columns don't bury at all — their wipe does it.
        val h = if (column) {
            chosen.height + pad * 2
        } else {
            maxOf(chosen.height + pad * 2, minOf(b.box.height() + dp(6f), screenH * 0.5f))
        }

        var left = b.box.centerX() - w / 2f
        var top = b.box.centerY() - h / 2f
        left = left.coerceAtMost(screenW - w - dp(2f)).coerceAtLeast(dp(2f))
        top = top.coerceAtMost(screenH - h - dp(2f)).coerceAtLeast(dp(2f))
        val rect = RectF(left, top, left + w, top + h)

        val wipe = if (column) RectF(b.box).apply { inset(-dp(2f), -dp(2f)) } else null
        return lettering(
            layout = chosen,
            x = rect.centerX() - chosen.width / 2f,
            y = rect.centerY() - chosen.height / 2f,
            style = b.style,
            ink = ink,
            edge = 0,
            edgeWidth = 0f,
            weight = weightFor(b.style, tp.textSize),
            movable = true,
            card = rect,
            cardColor = bg,
            wipe = wipe,
        )
    }

    /**
     * Shifts lettering off lettering already placed. Only meaningful overlap
     * moves it (over a third of its own area) — text on a comic page brushes
     * against its neighbours constantly, and jittering every block for a
     * grazing corner would tear placements away from their originals for
     * nothing.
     */
    private fun nudgeClear(rect: RectF, occupied: List<RectF>, screenH: Float) {
        for (attempt in 0 until 3) {
            val hit = occupied.firstOrNull { other ->
                RectF.intersects(other, rect) && overlapShare(other, rect) > 0.35f
            } ?: return
            val below = hit.bottom + dp(4f)
            val above = hit.top - rect.height() - dp(4f)
            val top = when {
                rect.centerY() >= hit.centerY() && below + rect.height() <= screenH - dp(2f) -> below
                above >= dp(2f) -> above
                below + rect.height() <= screenH - dp(2f) -> below
                else -> return
            }
            rect.offsetTo(rect.left, top)
        }
    }

    /** Intersection area as a share of [self]'s area. */
    private fun overlapShare(other: RectF, self: RectF): Float {
        val ix = minOf(other.right, self.right) - maxOf(other.left, self.left)
        val iy = minOf(other.bottom, self.bottom) - maxOf(other.top, self.top)
        if (ix <= 0f || iy <= 0f) return 0f
        val area = self.width() * self.height()
        return if (area <= 0f) 0f else (ix * iy) / area
    }

    /** Everything, cleaning and debug outlines included, stays off [keepClear]. */
    override fun draw(canvas: Canvas) {
        val bare = keepClear
        if (bare.isEmpty()) {
            super.draw(canvas)
            return
        }
        val saved = canvas.save()
        for (r in bare) canvas.clipOutRect(r)
        super.draw(canvas)
        canvas.restoreToCount(saved)
    }

    /**
     * Two passes. Every cleaning — balloon stamps, patches, column wipes —
     * goes down first, opaque from the first frame, so no original lettering
     * ever shows; then the cards and the English over them, in order. One
     * pass would let a neighbour's patch erase text that ran onto it.
     *
     * New lettering fades in over [FADE_MS]: the reader's eye is on the page
     * while items stream in, and text popping in one piece at a time reads
     * as flicker. The fade is plain alpha, and only while something is
     * still fading does the view ask for another frame.
     */
    override fun onDraw(canvas: Canvas) {
        val k = veil
        if (k > 0f) {
            veilPaint.alpha = veilAlpha(k)
            canvas.drawPaint(veilPaint)
        }
        val panels = debugPanels
        for (i in 0 until panels.size) canvas.drawRect(panels[i], debugPanelPaint)
        val debug = debugBalloons
        for (i in 0 until debug.size) canvas.drawRect(debug[i], debugPaint)
        val list = placed
        if (list.isEmpty()) return
        val page = backdrop
        if (k > 0f && page != null && !page.isRecycled) {
            matchGrounds(list, k, page)
            for (g in cleaningGrounds) if (g != null) canvas.drawBitmap(g.bitmap, g.left, g.top, null)
            if (!cleaningsOnly) drawLettering(canvas, list, cards = cardGrounds, veil = k)
        } else {
            drawCleanings(canvas, list)
            if (!cleaningsOnly) drawLettering(canvas, list)
        }
    }

    /**
     * Brings [cleaningGrounds] and [cardGrounds] up to [list], painted
     * against [page] under a veil of [k]: pieces already painted for the
     * same bubble, page and veil are kept, the rest painted anew.
     */
    private fun matchGrounds(list: List<Placed>, k: Float, page: Bitmap) {
        if (page !== groundsPage || k != groundsVeil) {
            grounds = HashMap()
            groundsPage = page
            groundsVeil = k
            groundsList = null
        }
        if (list === groundsList) return
        val next = HashMap<GroundKey, Ground?>()
        fun ground(p: Placed, card: Boolean): Ground? {
            val key = GroundKey(card, p.lettering, p.stamp, p.stampDst, p.patch, p.patchDst, if (card) p.dy else 0f)
            val g = if (grounds.containsKey(key)) grounds[key] else groundOf(p, card, k, page)
            next[key] = g
            return g
        }
        cleaningGrounds = Array(list.size) { ground(list[it], card = false) }
        cardGrounds = Array(list.size) { ground(list[it], card = true) }
        grounds = next
        groundsList = list
    }

    /**
     * One bubble's cleaning, or its card's ground ([card]), as it is painted
     * under a veil of [k]: each pixel less [k] of [page] under it. On screen
     * this layer shows at [windowAlpha] and the page at the rest, so a pixel
     * painted I - k·u shows as windowAlpha·I whatever u was: level with the
     * veiled page around it, with no trace of the lettering it replaced. A
     * pixel too dark to take the page's share off goes to black, the darkest
     * the layer can show. Null when the bubble has nothing of that kind.
     */
    private fun groundOf(p: Placed, card: Boolean, k: Float, page: Bitmap): Ground? {
        val area = RectF()
        if (card) {
            val c = p.lettering.card ?: return null
            area.set(c)
            area.offset(0f, p.dy)
        } else {
            if (p.stamp != null && p.stampDst != null) area.union(p.stampDst)
            if (p.patch != null && p.patchDst != null) area.union(RectF(p.patchDst))
            p.lettering.wipe?.let { area.union(it) }
            if (area.isEmpty) return null
        }
        // Anti-aliased edges and the card's hairline reach a pixel past the shape.
        val r = Rect(
            kotlin.math.floor(area.left).toInt() - 2, kotlin.math.floor(area.top).toInt() - 2,
            kotlin.math.ceil(area.right).toInt() + 2, kotlin.math.ceil(area.bottom).toInt() + 2,
        )
        if (!r.intersect(0, 0, page.width, page.height)) return null
        val w = r.width()
        val h = r.height()
        groundsPainted++
        val layer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(layer)
        c.translate(-r.left.toFloat(), -r.top.toFloat())
        if (card) drawGround(c, p) else drawCleanings(c, listOf(p))
        val px = IntArray(w * h)
        layer.getPixels(px, 0, w, 0, 0, w, h)
        val under = IntArray(w * h)
        page.getPixels(under, 0, w, r.left, r.top, w, h)
        lessPage(px, under, k)
        layer.setPixels(px, 0, w, 0, 0, w, h)
        return Ground(layer, r.left.toFloat(), r.top.toFloat())
    }

    private fun drawCleanings(canvas: Canvas, list: List<Placed>) {
        for (i in 0 until list.size) {
            val p = list[i]
            if (p.stamp != null && p.stampDst != null) {
                maskPaint.colorFilter = p.lettering.tint
                canvas.drawBitmap(p.stamp, null, p.stampDst, maskPaint)
            }
            if (p.patch != null && p.patchDst != null) {
                canvas.drawBitmap(p.patch, null, p.patchDst, patchPaint)
            }
            p.lettering.wipe?.let { wipe ->
                val c = p.lettering.cardColor
                bgPaint.color = Color.argb(255, Color.red(c), Color.green(c), Color.blue(c))
                canvas.drawRoundRect(wipe, dp(3f), dp(3f), bgPaint)
            }
        }
    }

    /** The cleanings alone, no English: what erasure left of the page, for the page harness to measure. */
    internal fun drawCleanings(canvas: Canvas) = drawCleanings(canvas, placed)

    /** Each placed bubble with the rectangle its lettering's ink covers, nudge included. */
    internal fun placements(): List<Pair<RenderBubble, RectF>> =
        placed.map { it.source to RectF(it.lettering.inkRect).apply { offset(0f, it.dy) } }

    /**
     * The cards and the English over them, in order. Under a veil of [veil]
     * each card's ground comes painted already ([cards], in [list]'s
     * order), and the text is painted less the page's share too, reckoned
     * against the bubble's own background: the page right under a letter
     * is that background, or the lettering it replaced, which the letter
     * covers anyway. The text keeps its fade that way, which a ground
     * painted again for every frame of it could not afford.
     */
    private fun drawLettering(canvas: Canvas, list: List<Placed>, cards: Array<Ground?>? = null, veil: Float = 0f) {
        val now = if (list.any { it.since != 0L }) clock() else 0L
        var fading = false
        for (i in 0 until list.size) {
            val p = list[i]
            val l = p.lettering
            if (cards == null) drawGround(canvas, p) else cards.getOrNull(i)?.let { canvas.drawBitmap(it.bitmap, it.left, it.top, null) }
            var alpha = 255
            if (p.since != 0L) {
                val t = (now - p.since).toFloat() / FADE_MS
                if (t < 1f) {
                    alpha = (255 * t.coerceAtLeast(0f)).toInt()
                    fading = true
                }
            }
            if (alpha > 0) {
                if (veil > 0f) {
                    val under = p.source.bgColor
                    drawText(canvas, l, p.dy, alpha, lessPage(l.ink, under, veil), if (l.edge != 0) lessPage(l.edge, under, veil) else 0)
                } else {
                    drawText(canvas, l, p.dy, alpha, l.ink, l.edge)
                }
            }
        }
        if (fading) postInvalidateOnAnimation()
    }

    /** A card's rounded ground and its hairline, if the lettering has a card. */
    private fun drawGround(canvas: Canvas, p: Placed) {
        val l = p.lettering
        val card = l.card ?: return
        val radius = dp(9f)
        canvas.save()
        canvas.translate(0f, p.dy)
        bgPaint.color = l.cardColor
        canvas.drawRoundRect(card, radius, radius, bgPaint)
        // The hairline must contrast with the fill it outlines, or a
        // page-black card on a black panel has no edge at all.
        val bg = l.cardColor
        val lum = (Color.red(bg) * 299 + Color.green(bg) * 587 + Color.blue(bg) * 114) / 1000
        strokePaint.color = if (lum < 140) 0x59FFFFFF else 0x2E000000
        canvas.drawRoundRect(card, radius, radius, strokePaint)
        canvas.restore()
    }

    /**
     * Outline first, fill over it: the stroke is centred on the glyph edge,
     * so drawn second it would eat half of every letter. Mid-fade an outlined
     * block is composited in a layer, or the see-through fill would show the
     * stroke beneath it.
     */
    private fun drawText(canvas: Canvas, l: Lettering, dy: Float, alpha: Int, ink: Int, edge: Int) {
        val tp = l.layout.paint
        canvas.save()
        canvas.translate(l.x, l.y + dy)
        if (l.turned) canvas.rotate(90f)
        val layered = alpha < 255 && l.edge != 0
        if (layered) canvas.saveLayerAlpha(l.layer, alpha)
        val a = if (layered) 255 else alpha
        if (l.edge != 0) {
            tp.style = Paint.Style.STROKE
            tp.strokeWidth = l.edgeWidth
            tp.color = edge
            tp.alpha = Color.alpha(edge) * a / 255
            l.layout.draw(canvas)
        }
        tp.style = if (l.weight > 0f) Paint.Style.FILL_AND_STROKE else Paint.Style.FILL
        tp.strokeWidth = l.weight
        tp.color = ink
        tp.alpha = Color.alpha(ink) * a / 255
        l.layout.draw(canvas)
        if (layered) canvas.restore()
        canvas.restore()
    }
}
