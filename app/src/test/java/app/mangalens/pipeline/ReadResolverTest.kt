package app.mangalens.pipeline

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import app.mangalens.ocr.Balloon
import app.mangalens.overlay.LetterStyle
import app.mangalens.translate.ItemKind
import app.mangalens.translate.PageItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Where each answer is lettered. Manhwa routinely joins one speaker's two
 * balloons into a single drawn shape, and the detector returns them as one;
 * each line must still be lettered into its own lobe. The model, for its
 * part, sometimes answers one balloon's columns as separate items; those
 * belong back together in one balloon.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReadResolverTest {

    private val page: Bitmap = Bitmap.createBitmap(800, 1000, Bitmap.Config.ARGB_8888).apply {
        Canvas(this).drawColor(Color.WHITE)
    }

    /** A detection whose interior is the union of the given ellipses, on a 4-px mask grid. */
    private fun detection(box: Rect, vararg ellipses: Rect): Balloon {
        val mw = box.width() / 4
        val mh = box.height() / 4
        val mask = BooleanArray(mw * mh) { i ->
            val x = box.left + (i % mw) * 4 + 2
            val y = box.top + (i / mw) * 4 + 2
            ellipses.any { e ->
                val nx = (x - e.exactCenterX()) / (e.width() / 2f)
                val ny = (y - e.exactCenterY()) / (e.height() / 2f)
                nx * nx + ny * ny <= 1f
            }
        }
        return Balloon(box, mw, mh, mask, inverted = false)
    }

    private fun resolver(vararg balloons: Balloon) =
        ReadResolver(page, balloons.toList(), emptyList(), 0, 0, emptyList())

    @Test
    fun joinedBalloonsKeepALineEach() {
        val upper = Rect(80, 100, 480, 300)
        val lower = Rect(360, 260, 760, 460)
        val joined = detection(Rect(80, 100, 760, 460), upper, lower)
        val a = PageItem(Rect(200, 180, 360, 220), ItemKind.SPEECH, "어떻게 된 거야?", "What happened?", who = "Seo-yeon")
        val b = PageItem(Rect(480, 340, 640, 380), ItemKind.SPEECH, "믿을 수 없어.", "I can't believe it.", who = "Seo-yeon")

        val out = resolver(joined).resolve(listOf(a, b))
        assertEquals(2, out.size)
        val ra = out.single { it.translated == a.en }
        val rb = out.single { it.translated == b.en }
        val la = assertNotNull(ra.balloon).let { ra.balloon!! }
        val lb = rb.balloon!!
        assertNotSame(la, lb)
        // Each lobe holds its own line and not the other's.
        assertTrue(la.box.contains(a.box.centerX(), a.box.centerY()))
        assertTrue(lb.box.contains(b.box.centerX(), b.box.centerY()))
        assertTrue(!la.box.contains(b.box.centerX(), b.box.centerY()))
        assertTrue(!lb.box.contains(a.box.centerX(), a.box.centerY()))
        // Together the lobes still clean the whole shape, and they overlap
        // along the join: each is cleaned short of its own edge, and a seam
        // neither covered left the lettering crossing it on the page.
        var covered = 0
        var both = 0
        for (i in joined.mask.indices) {
            if (!joined.mask[i]) continue
            val x = joined.box.left + (i % joined.maskW) * 4 + 2
            val y = joined.box.top + (i / joined.maskW) * 4 + 2
            val inA = onMask(la, x, y)
            val inB = onMask(lb, x, y)
            if (inA || inB) covered++
            if (inA && inB) both++
        }
        assertEquals(joined.mask.count { it }, covered)
        assertTrue("the lobes overlap along the join, got $both cells", both > 0)
        assertTrue("and only along it, got $both cells", both < joined.mask.count { it } / 10)
    }

    /** True when page point (x, y) falls on [b]'s mask. */
    private fun onMask(b: Balloon, x: Int, y: Int): Boolean {
        if (!b.box.contains(x, y)) return false
        val mx = ((x - b.box.left) * b.maskW / b.box.width()).coerceIn(0, b.maskW - 1)
        val my = ((y - b.box.top) * b.maskH / b.box.height()).coerceIn(0, b.maskH - 1)
        return b.mask[my * b.maskW + mx]
    }

    /**
     * A line of two columns and a line of four, a column apart in the two
     * lobes of one joined balloon — one speaker's two balloons. A box's
     * short side is two lines thick round the two-column line, and taken
     * for one line it made the gap between them look like the space
     * between one balloon's columns: both lines went into one block
     * across the join.
     */
    @Test
    fun multiColumnLinesAColumnApartKeepALobeEach() {
        val small = Rect(262, 396, 372, 540)
        val big = Rect(156, 430, 346, 624)
        val joined = detection(Rect(156, 396, 372, 624), small, big)
        val a = PageItem(Rect(298, 418, 339, 518), ItemKind.SPEECH, "いやあ\n悪いなあ…", "Aw, she shouldn't have...", who = "Boss", vertical = true)
        val b = PageItem(
            Rect(180, 460, 274, 601), ItemKind.SPEECH, "そうそう\nメルが来てから\nお客さんが\n少し増えたんだ",
            "Actually, we've had more customers since you got here, Mel.", who = "Boss", vertical = true,
        )

        val out = resolver(joined).resolve(listOf(a, b))
        assertEquals(2, out.size)
        assertTrue(out.all { it.balloon != null && it.balloon !== joined })
    }

    @Test
    fun oneBalloonAnsweredInPiecesIsSetBackTogether() {
        val oval = Rect(200, 200, 520, 600)
        val balloon = detection(oval, oval)
        // Two adjacent vertical columns of one balloon, answered separately.
        val right = PageItem(Rect(370, 280, 410, 520), ItemKind.SPEECH, "じゃあ", "Then", who = "Rin", vertical = true)
        val left = PageItem(Rect(320, 280, 360, 520), ItemKind.SPEECH, "どうするの", "what now?", who = "Rin", vertical = true)

        val out = resolver(balloon).resolve(listOf(right, left))
        assertEquals(1, out.size)
        assertEquals("Then what now?", out[0].translated)
        assertTrue(out[0].balloon === balloon)
    }

    @Test
    fun twoSpeakersInOneDetectionNeverShareALine() {
        val left = Rect(60, 200, 400, 520)
        val right = Rect(360, 200, 740, 520)
        val joined = detection(Rect(60, 200, 740, 520), left, right)
        val a = PageItem(Rect(180, 300, 280, 420), ItemKind.SPEECH, "A", "You!", who = "Kai")
        val b = PageItem(Rect(500, 300, 620, 420), ItemKind.SPEECH, "B", "Me?", who = "Mira")

        val out = resolver(joined).resolve(listOf(a, b))
        assertEquals(2, out.size)
        assertTrue(out.none { it.translated.contains("You!") && it.translated.contains("Me?") })
    }

    @Test
    fun soundEffectsNeverClaimABalloon() {
        val oval = Rect(200, 200, 520, 600)
        val balloon = detection(oval, oval)
        val speech = PageItem(Rect(320, 300, 400, 500), ItemKind.SPEECH, "何", "What?!", loud = true)
        val sfx = PageItem(Rect(420, 520, 470, 570), ItemKind.SFX, "ドン", "BAM")

        val out = resolver(balloon).resolve(listOf(speech, sfx))
        val inBalloon = out.filter { it.balloon != null }
        assertEquals(1, inBalloon.size)
        assertEquals("What?!", inBalloon[0].translated)
        assertEquals(LetterStyle.SHOUT, inBalloon[0].style)
    }

    @Test
    fun aHeartTheModelGaveAsASoundDoesNotSpoilItsBalloon() {
        val oval = Rect(200, 200, 520, 600)
        val balloon = detection(oval, oval)
        val inked = page.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(inked)
        val ink = android.graphics.Paint().apply { color = Color.BLACK }
        val speech = PageItem(Rect(320, 300, 400, 480), ItemKind.SPEECH, "好き", "I like you", vertical = true)
        c.drawRect(335f, 310f, 385f, 470f, ink)
        // A bold heart lettered under the line, answered as a sound of its own.
        val heart = PageItem(Rect(330, 500, 390, 560), ItemKind.SFX, "♡", "♡")
        c.drawRect(335f, 505f, 385f, 555f, ink)

        val out = ReadResolver(inked, listOf(balloon), emptyList(), 0, 0, emptyList()).resolve(listOf(speech, heart))
        assertTrue("the balloon is still cleaned and typeset", out.any { it.translated == speech.en && it.balloon === balloon })
    }

    /** A phone-tall page of busy art with a white balloon at [oval], lettered unless [empty]. */
    private fun artWithBalloon(oval: Rect, empty: Boolean = false): Bitmap {
        val bmp = Bitmap.createBitmap(800, 2000, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(150, 150, 150))
        val rnd = java.util.Random(3)
        val p = android.graphics.Paint()
        repeat(3000) {
            p.color = Color.rgb(rnd.nextInt(120), rnd.nextInt(120), rnd.nextInt(120))
            val x = rnd.nextInt(800).toFloat()
            val y = rnd.nextInt(2000).toFloat()
            c.drawRect(x, y, x + 4 + rnd.nextInt(12), y + 2 + rnd.nextInt(6), p)
        }
        c.drawOval(android.graphics.RectF(oval), android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        if (!empty) {
            p.color = Color.BLACK
            for (k in 0 until 4) c.drawRect(oval.centerX() - 15f, oval.top + 60f + k * 50, oval.centerX() + 15f, oval.top + 95f + k * 50, p)
        }
        return bmp
    }

    @Test
    fun aLineWhoseBoxDriftedOntoTheArtGoesBackToItsBalloon() {
        val oval = Rect(300, 500, 500, 820)
        val balloon = detection(oval, oval)
        val page = artWithBalloon(oval)
        // The model's box landed 250 px above the balloon, on the art.
        val line = PageItem(Rect(370, 300, 430, 470), ItemKind.SPEECH, "違うって、それ！", "No, that's not it!", vertical = true)
        val out = ReadResolver(page, listOf(balloon), emptyList(), 0, 0, emptyList()).resolve(listOf(line))
        assertEquals(1, out.size)
        assertTrue("lettered in the balloon it came from", out[0].balloon === balloon)
    }

    /**
     * A line drifts into a balloon holding two columns of lettering; the
     * next item streamed in is a sign on one column, and takes the balloon
     * from it. The line's put-back must not vouch for the balloon now: the
     * page re-resolved one item longer comes out as it does resolved whole,
     * as it is on a scroll-back.
     */
    @Test
    fun aBalloonALineHasLeftIsJudgedAsIfItNeverDriftedThere() {
        val oval = Rect(300, 500, 500, 820)
        val balloon = detection(oval, oval)
        val page = artWithBalloon(oval, empty = true)
        val ink = android.graphics.Paint().apply { color = Color.BLACK }
        for (x in listOf(340f, 430f)) for (k in 0 until 4) Canvas(page).drawRect(x, 560f + k * 50, x + 30, 595f + k * 50, ink)
        val line = PageItem(Rect(370, 300, 430, 470), ItemKind.SPEECH, "違うって、それ！", "No, that's not it!", vertical = true)
        val sign = PageItem(Rect(335, 555, 375, 750), ItemKind.ART_TEXT, "営業中", "Open", vertical = true)

        val live = ReadResolver(page, listOf(balloon), emptyList(), 0, 0, emptyList())
        assertTrue("the line went back to the balloon", live.resolve(listOf(line)).single().balloon === balloon)
        val streamed = live.resolve(listOf(line, sign)).single { it.translated == sign.en }
        val whole = ReadResolver(page, listOf(balloon), emptyList(), 0, 0, emptyList())
            .resolve(listOf(line, sign)).single { it.translated == sign.en }
        assertTrue("the sign's balloon, resolved whole, is not trusted", whole.balloon == null)
        assertTrue("streamed in, the sign's balloon is judged the same", streamed.balloon === whole.balloon)
    }

    @Test
    fun anEmptyBalloonNearbyDoesNotClaimALine() {
        val oval = Rect(300, 500, 500, 820)
        val balloon = detection(oval, oval)
        val page = artWithBalloon(oval, empty = true)
        val line = PageItem(Rect(370, 300, 430, 470), ItemKind.SPEECH, "違うって、それ！", "No, that's not it!", vertical = true)
        val out = ReadResolver(page, listOf(balloon), emptyList(), 0, 0, emptyList()).resolve(listOf(line))
        assertTrue(out.none { it.balloon === balloon })
    }

    /**
     * The model's box on the last glyphs of the columns it read, the rest
     * of the columns above it. Counted as stray ink, they made a balloon
     * holding nothing but that line look like art: it was left uncleaned,
     * and its line went untranslated or was set over its own lettering.
     */
    @Test
    fun aBoxThatSlidDownItsLinesStillCleansTheBalloon() {
        val oval = Rect(240, 120, 560, 620)
        val balloon = detection(oval, oval)
        val bmp = Bitmap.createBitmap(800, 1000, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        c.drawOval(
            android.graphics.RectF(oval),
            android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.STROKE; strokeWidth = 5f; color = Color.BLACK },
        )
        val ink = android.graphics.Paint().apply { color = Color.BLACK }
        for (col in 0 until 3) for (glyph in 0 until 12) for (stroke in 0 until 3) {
            val x = 330f + col * 50
            val y = 220f + glyph * 28 + stroke * 8
            c.drawRect(x, y, x + 24, y + 4, ink)
        }
        val item = PageItem(Rect(325, 480, 435, 560), ItemKind.SPEECH, "部長の\n園山みずき\nです！", "I'm the club president, Mizuki Sonoyama!", vertical = true)

        val out = ReadResolver(bmp, listOf(balloon), emptyList(), 0, 0, emptyList()).resolve(listOf(item))
        assertTrue("the whole balloon is cleaned and lettered", out.single().balloon === balloon)
    }

    @Test
    fun aFaceThatPassedForABalloonNeverTakesADriftedLine() {
        val oval = Rect(300, 500, 500, 820)
        val balloon = detection(oval, oval)
        val page = artWithBalloon(oval, empty = true)
        val line = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE; strokeWidth = 4f; color = Color.BLACK
        }
        Canvas(page).apply {
            drawOval(android.graphics.RectF(330f, 590f, 380f, 620f), line)
            drawOval(android.graphics.RectF(420f, 590f, 470f, 620f), line)
            drawLine(325f, 570f, 385f, 562f, line)
            drawLine(415f, 562f, 475f, 570f, line)
            drawArc(android.graphics.RectF(350f, 720f, 450f, 770f), 0f, 180f, false, line)
        }
        val drifted = PageItem(Rect(370, 300, 430, 470), ItemKind.SPEECH, "違うって、それ！", "No, that's not it!", vertical = true)
        val out = ReadResolver(page, listOf(balloon), emptyList(), 0, 0, emptyList()).resolve(listOf(drifted))
        assertTrue("the face is left as drawn", out.none { it.balloon === balloon })
    }

    /**
     * The model's boxes for a whole panel slid 250 px down together. Two
     * land on bare art and go back to their balloons; the third lands on a
     * face drawn in line art, which passes for a balloon with its features
     * under the box, and was wiped. It slides back with the others.
     */
    @Test
    fun aLineWhosePanelSlidLeavesTheFaceItLandedOn() {
        val bmp = artWithBalloon(Rect(60, 200, 220, 520))
        val c = Canvas(bmp)
        val white = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val ink = android.graphics.Paint().apply { color = Color.BLACK }
        val second = Rect(300, 200, 460, 520)
        val third = Rect(540, 200, 700, 520)
        val face = Rect(540, 540, 700, 800)
        for (o in listOf(second, third, face)) c.drawOval(android.graphics.RectF(o), white)
        for (k in 0 until 4) c.drawRect(365f, 260f + k * 50, 395f, 295f + k * 50, ink)
        for (col in 0 until 2) for (k in 0 until 4) c.drawRect(595f + col * 40, 260f + k * 50, 625f + col * 40, 295f + k * 50, ink)
        val line = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE; strokeWidth = 4f; color = Color.BLACK
        }
        c.drawOval(android.graphics.RectF(580f, 580f, 610f, 605f), line)
        c.drawOval(android.graphics.RectF(650f, 580f, 680f, 605f), line)
        c.drawArc(android.graphics.RectF(605f, 735f, 655f, 760f), 0f, 180f, false, line)
        val balloons = listOf(Rect(60, 200, 220, 520), second, third, face).map { detection(it, it) }
        val items = listOf(
            PageItem(Rect(125, 510, 155, 695), ItemKind.SPEECH, "どこ行くの", "Where are you going?", vertical = true),
            PageItem(Rect(365, 510, 395, 695), ItemKind.SPEECH, "ちょっと外", "Just outside.", vertical = true),
            PageItem(Rect(595, 510, 665, 695), ItemKind.SPEECH, "待ってよ\n一緒に行く", "Wait, I'm coming too!", vertical = true),
        )
        val out = ReadResolver(bmp, balloons, emptyList(), 0, 0, emptyList()).resolve(items)
        assertTrue("the face is left as drawn", out.none { it.balloon === balloons[3] })
        for ((k, item) in items.withIndex()) {
            assertTrue("${item.en} is lettered in its own balloon", out.single { it.translated == item.en }.balloon === balloons[k])
        }
    }

    /**
     * A box drifted onto a face drawn in line art on white skin: to the
     * eraser that is clean lettering on paper, so the line was lettered
     * over the face and its balloon left raw. Two eyes and a mouth are far
     * too few marks for a twelve-character line, and the balloon below
     * holds lettering of the line's size.
     */
    @Test
    fun aFaceUnderADriftedBoxIsNotTakenForItsLettering() {
        val oval = Rect(300, 500, 500, 820)
        val balloon = detection(oval, oval)
        val bmp = Bitmap.createBitmap(800, 1400, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val stroke = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE; strokeWidth = 4f; color = Color.BLACK
        }
        c.drawOval(android.graphics.RectF(oval), stroke.apply { strokeWidth = 5f })
        val ink = android.graphics.Paint().apply { color = Color.BLACK }
        // Two columns of six glyphs, two strokes each.
        for (col in 0 until 2) for (g in 0 until 6) {
            val x = 365f + col * 40
            val y = 560f + g * 36
            c.drawRect(x, y, x + 26, y + 5, ink)
            c.drawRect(x + 10, y + 8, x + 16, y + 28, ink)
        }
        // The face the box landed on, 250 px higher.
        stroke.strokeWidth = 4f
        c.drawOval(android.graphics.RectF(362f, 330f, 388f, 350f), stroke)
        c.drawOval(android.graphics.RectF(404f, 330f, 430f, 350f), stroke)
        c.drawArc(android.graphics.RectF(380f, 430f, 412f, 450f), 0f, 180f, false, stroke)
        val line = PageItem(Rect(360, 305, 436, 520), ItemKind.SPEECH, "確かに原理的\nには可能かも", "In theory it might be possible.", vertical = true)
        val out = ReadResolver(bmp, listOf(balloon), emptyList(), 0, 0, emptyList()).resolve(listOf(line))
        assertTrue("lettered in its balloon, not over the face", out.single().balloon === balloon)
    }

    /**
     * The model's box slid down its own column: over the last two glyphs
     * in the balloon and on out over a brick wall below. Clean lettering
     * on paper where the box says, it was erased on its own there, the
     * wall scuffed with it, and the four glyphs above left standing over
     * the English. The box holds some of the balloon's lettering, and the
     * balloon is the line's.
     */
    @Test
    fun aBoxThatSlidDownOutOfItsBalloonStillCleansTheBalloon() {
        val oval = Rect(300, 300, 500, 700)
        val balloon = detection(oval, oval)
        val bmp = Bitmap.createBitmap(800, 1400, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val edge = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE; strokeWidth = 5f; color = Color.BLACK
        }
        c.drawOval(android.graphics.RectF(oval), edge)
        val ink = android.graphics.Paint().apply { color = Color.BLACK }
        // Six glyphs of three strokes each down the balloon's middle.
        for (g in 0 until 6) for (k in 0 until 3) {
            val y = 350f + g * 50 + k * 12
            c.drawRect(385f, y, 415f, y + 4, ink)
        }
        // The wall below: courses of brick.
        for (row in 0 until 5) {
            val y = 730f + row * 40
            c.drawRect(250f, y, 550f, y + 2, ink)
            for (x in 0 until 4) c.drawRect(260f + x * 80 + (row % 2) * 40, y, 262f + x * 80 + (row % 2) * 40, y + 40, ink)
        }
        val line = PageItem(Rect(380, 560, 420, 830), ItemKind.SPEECH, "真不可思議啊", "It's really bizarre.", vertical = true)
        val out = ReadResolver(bmp, listOf(balloon), emptyList(), 0, 0, emptyList()).resolve(listOf(line))
        assertTrue("the whole balloon is cleaned and lettered", out.single().balloon === balloon)
    }

    @Test
    fun cleanLetteringWhereTheBoxSaysKeepsItThere() {
        // Plain paper with the line drawn right where the box is, and a
        // lettered balloon nearby that no answer claims.
        val oval = Rect(300, 500, 500, 820)
        val balloon = detection(oval, oval)
        val bmp = Bitmap.createBitmap(800, 1000, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val ink = android.graphics.Paint().apply { color = Color.BLACK }
        c.drawOval(android.graphics.RectF(oval), android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.STROKE; strokeWidth = 5f; color = Color.BLACK })
        for (k in 0 until 4) c.drawRect(385f, 560f + k * 50, 415f, 595f + k * 50, ink)
        for (k in 0 until 3) c.drawRect(385f, 310f + k * 50, 415f, 345f + k * 50, ink)
        val line = PageItem(Rect(378, 302, 422, 452), ItemKind.SPEECH, "ねえ", "Hey.", vertical = true)
        val out = ReadResolver(bmp, listOf(balloon), emptyList(), 0, 0, emptyList()).resolve(listOf(line))
        assertTrue(out.none { it.balloon === balloon })
    }

    @Test
    fun aBigSoundEffectAcrossTheArtIsNotedNotErased() {
        // A dramatic sound effect spanning a quarter of the page: erasing it
        // would take the drawing with it.
        val sfx = PageItem(Rect(100, 300, 700, 560), ItemKind.SFX, "ドドン", "BA-DOOM")
        val out = resolver().resolve(listOf(sfx))
        assertEquals(1, out.size)
        assertEquals(LetterStyle.SFX_NOTE, out[0].style)
        assertEquals(null, out[0].patch)
        assertEquals(null, out[0].balloon)
    }
}
