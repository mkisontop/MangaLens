package app.mangalens.ocr

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Measures reading order against a corpus of real comics page layouts rather
 * than against cases picked to suit the algorithm.
 *
 * Each layout is drawn from a standard panel arrangement and labelled by the
 * rule a human reads by — tier by tier down the page, right to left across a
 * tier on a manga page, left to right in a webtoon — and *then* both the old
 * ordering and the new one are scored against it. Balloons sit where balloons
 * actually sit, near the top of their panel at roughly half its width, because
 * the algorithm only ever sees balloon boxes and never the panel borders.
 *
 * The page is 1000x1500 with 20px gutters throughout.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReadingOrderBenchmarkTest {

    private class Layout(
        val name: String,
        val rtl: Boolean,
        val bubbles: List<Pair<String, Rect>>,
        /** Correct order, by label. */
        val expected: List<String>,
        /**
         * Names an ambiguity group when this layout's balloon geometry is
         * indistinguishable from another layout in the corpus that reads in a
         * different order. Only panel borders could tell them apart, and the
         * grouper never sees those — so exactly one member of a group can be
         * right, and which one is a coin flip rather than a fixable defect.
         */
        val ambiguousWith: String? = null,
    )

    /** What the grouper did before: top-to-bottom, left-to-right. */
    private fun legacyOrder(bubbles: List<Bubble>): List<Bubble> =
        bubbles.sortedWith(compareBy({ it.box.top }, { it.box.left }))

    private fun box(l: Int, t: Int, r: Int, b: Int) = Rect(l, t, r, b)

    private val corpus = listOf(
        Layout(
            // The single most common manga page: two tiers of two panels.
            name = "2x2 grid",
            rtl = true,
            bubbles = listOf(
                "TL" to box(90, 90, 430, 260),
                "TR" to box(560, 90, 900, 260),
                "BL" to box(90, 820, 430, 990),
                "BR" to box(560, 820, 900, 990),
            ),
            expected = listOf("TR", "TL", "BR", "BL"),
        ),
        Layout(
            name = "three tiers of two",
            rtl = true,
            bubbles = listOf(
                "1L" to box(90, 80, 430, 220),
                "1R" to box(560, 80, 900, 220),
                "2L" to box(90, 560, 430, 700),
                "2R" to box(560, 560, 900, 700),
                "3L" to box(90, 1040, 430, 1180),
                "3R" to box(560, 1040, 900, 1180),
            ),
            expected = listOf("1R", "1L", "2R", "2L", "3R", "3L"),
        ),
        Layout(
            // No horizontal gutter crosses this page at all.
            name = "full-height right panel, two stacked left",
            rtl = true,
            bubbles = listOf(
                "Ltop" to box(90, 90, 430, 260),
                "Lbot" to box(90, 820, 430, 990),
                "R" to box(560, 90, 900, 300),
            ),
            expected = listOf("R", "Ltop", "Lbot"),
        ),
        // These two are the same three boxes. In the first the left balloon
        // belongs to a panel running the full height of the page, so its line
        // is read last; in the second it belongs to a top-tier panel and is
        // read second. Nothing in the balloon geometry distinguishes them.
        Layout(
            name = "full-height left panel, two stacked right",
            rtl = true,
            bubbles = listOf(
                "L" to box(90, 90, 430, 300),
                "Rtop" to box(560, 90, 900, 260),
                "Rbot" to box(560, 820, 900, 990),
            ),
            expected = listOf("Rtop", "Rbot", "L"),
            ambiguousWith = "tall-side-panel",
        ),
        Layout(
            name = "two-panel tier over a single right panel",
            rtl = true,
            bubbles = listOf(
                "L" to box(90, 90, 430, 300),
                "Rtop" to box(560, 90, 900, 260),
                "Rbot" to box(560, 820, 900, 990),
            ),
            expected = listOf("Rtop", "L", "Rbot"),
            ambiguousWith = "tall-side-panel",
        ),
        Layout(
            name = "establishing shot over two panels",
            rtl = true,
            bubbles = listOf(
                "TOP" to box(300, 80, 700, 220),
                "BL" to box(90, 590, 430, 730),
                "BR" to box(560, 590, 900, 730),
            ),
            expected = listOf("TOP", "BR", "BL"),
        ),
        Layout(
            name = "T-layout: wide top over three",
            rtl = true,
            bubbles = listOf(
                "TOP" to box(300, 80, 700, 220),
                "bL" to box(80, 590, 290, 730),
                "bM" to box(390, 590, 600, 730),
                "bR" to box(700, 590, 920, 730),
            ),
            expected = listOf("TOP", "bR", "bM", "bL"),
        ),
        Layout(
            name = "irregular: two over three",
            rtl = true,
            bubbles = listOf(
                "1L" to box(90, 90, 430, 260),
                "1R" to box(560, 90, 900, 260),
                "2L" to box(80, 590, 320, 730),
                "2M" to box(390, 590, 620, 730),
                "2R" to box(680, 590, 920, 730),
            ),
            expected = listOf("1R", "1L", "2R", "2M", "2L"),
        ),
        Layout(
            name = "yonkoma: four stacked full-width",
            rtl = true,
            bubbles = listOf(
                "p1" to box(300, 80, 700, 200),
                "p2" to box(300, 440, 700, 560),
                "p3" to box(300, 800, 700, 920),
                "p4" to box(300, 1160, 700, 1280),
            ),
            expected = listOf("p1", "p2", "p3", "p4"),
        ),
        Layout(
            // Two balloons in one panel, the left one drawn higher — the case
            // that silently reverses an exchange under a top-first sort.
            name = "two balloons in one panel, left drawn higher",
            rtl = true,
            bubbles = listOf(
                "left" to box(100, 100, 480, 260),
                "right" to box(520, 140, 900, 300),
            ),
            expected = listOf("right", "left"),
        ),
        Layout(
            name = "conversation stacked in one panel",
            rtl = true,
            bubbles = listOf(
                "first" to box(300, 100, 700, 260),
                "second" to box(300, 320, 700, 480),
            ),
            expected = listOf("first", "second"),
        ),
        Layout(
            name = "splash page with inset",
            rtl = true,
            bubbles = listOf(
                "main" to box(200, 200, 800, 400),
                "inset" to box(620, 1150, 920, 1330),
            ),
            expected = listOf("main", "inset"),
        ),
        Layout(
            name = "webtoon strip, offset balloons",
            rtl = false,
            bubbles = listOf(
                "w1" to box(120, 100, 520, 240),
                "w2" to box(480, 400, 880, 540),
                "w3" to box(100, 700, 500, 840),
                "w4" to box(400, 1000, 800, 1140),
            ),
            expected = listOf("w1", "w2", "w3", "w4"),
        ),
        Layout(
            name = "webtoon, two balloons side by side",
            rtl = false,
            bubbles = listOf(
                "left" to box(100, 300, 460, 440),
                "right" to box(520, 340, 880, 480),
            ),
            expected = listOf("left", "right"),
        ),
        Layout(
            name = "single balloon",
            rtl = true,
            bubbles = listOf("only" to box(300, 300, 700, 460)),
            expected = listOf("only"),
        ),
    )

    @Test
    fun `panel-aware ordering beats the previous top-left sort across the corpus`() {
        val decidable = corpus.filter { it.ambiguousWith == null }
        var legacyCorrect = 0
        var currentCorrect = 0
        val wrong = ArrayList<String>()
        val regressions = ArrayList<String>()
        val report = StringBuilder("\nreading order — layout corpus\n")

        for (layout in corpus) {
            val bubbles = layout.bubbles.map { (label, r) -> Bubble(label, r, layout.rtl) }

            val legacy = legacyOrder(bubbles).map { it.text }
            val current = ReadingOrder.order(bubbles, layout.rtl).map { it.text }

            val legacyOk = legacy == layout.expected
            val currentOk = current == layout.expected
            if (layout.ambiguousWith == null) {
                if (legacyOk) legacyCorrect++
                if (currentOk) currentCorrect++ else wrong += layout.name
                if (legacyOk && !currentOk) regressions += layout.name
            }

            report.append(
                "  %-44s was:%s now:%s%s%s\n".format(
                    layout.name,
                    if (legacyOk) "ok " else "BAD",
                    if (currentOk) "ok " else "BAD",
                    if (layout.ambiguousWith != null) "  [ambiguous: ${layout.ambiguousWith}]" else "",
                    if (currentOk || layout.ambiguousWith != null) "" else "  got $current, want ${layout.expected}",
                )
            )
        }
        report.append("  ${"-".repeat(78)}\n")
        report.append(
            "  decidable layouts — previous: $legacyCorrect/${decidable.size}" +
                "   current: $currentCorrect/${decidable.size}\n"
        )
        println(report)

        assertEquals("layouts the new ordering regressed: $regressions", emptyList<String>(), regressions)
        assertEquals("$report\nstill wrong: $wrong", decidable.size, currentCorrect)
    }

    /**
     * Pins the ambiguity itself. Both members of a group are handed identical
     * geometry, so the ordering must satisfy exactly one of them — never
     * neither, which would mean it had invented a third reading no layout
     * calls for. Closing this properly needs panel borders detected from the
     * frame; until then one of the two readings is simply taken.
     */
    @Test
    fun `an ambiguous geometry still yields one of the two valid readings`() {
        val groups = corpus.filter { it.ambiguousWith != null }.groupBy { it.ambiguousWith }
        for ((group, layouts) in groups) {
            val bubbles = layouts.first().bubbles.map { (label, r) -> Bubble(label, r, layouts.first().rtl) }
            val current = ReadingOrder.order(bubbles, layouts.first().rtl).map { it.text }
            val matched = layouts.count { it.expected == current }
            assertEquals(
                "group $group produced $current, which is not a reading any layout in the group calls for",
                1,
                matched,
            )
        }
    }

    @Test
    fun `ordering never drops or duplicates a balloon`() {
        for (layout in corpus) {
            val bubbles = layout.bubbles.map { (label, r) -> Bubble(label, r, layout.rtl) }
            val ordered = ReadingOrder.order(bubbles, layout.rtl)
            assertEquals(
                "balloon count changed for ${layout.name}",
                bubbles.size,
                ordered.size,
            )
            assertEquals(
                "balloon set changed for ${layout.name}",
                bubbles.map { it.text }.toSet(),
                ordered.map { it.text }.toSet(),
            )
        }
    }
}
