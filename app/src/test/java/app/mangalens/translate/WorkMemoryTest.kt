package app.mangalens.translate

import android.content.Context
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Reading several series in a sitting must not pool their memory.
 *
 * The glossary is obeyed exactly and a pronoun is held once set, which is what
 * keeps one story consistent — and exactly what corrupts the next one if the
 * two share a scope. These cases follow a reader through three series and back
 * again, checking that nothing crosses over and that returning to a series
 * still finds what it established.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkMemoryTest {

    private lateinit var glossary: GlossaryStore
    private lateinit var cast: CastBook
    private lateinit var works: WorkMemory

    /**
     * The gap that ends a work is measured from the last *translated* page, so
     * the clock has to be threaded through rather than each call inventing its
     * own timestamp — measuring from the wrong page silently under-counts the
     * gap and the work never ends.
     */
    private var clock = 1_000_000L

    /** Built as the capture service builds them, with nothing reset by hand. */
    @Before
    fun setUp() {
        val ctx = RuntimeEnvironment.getApplication()
        glossary = GlossaryStore(ctx)
        cast = CastBook(ctx)
        works = WorkMemory(ctx, glossary, cast)
    }

    /** Establishes names and reports the pass, as a translated page does. */
    private fun readPage(terms: Map<String, String>) {
        glossary.learn(terms)
        works.noteTranslated(clock, glossary.snapshot().keys)
    }

    /** Moves time on and starts the next pass, as the capture loop does. */
    private fun after(ms: Long) {
        clock += ms
        works.beginPass(clock)
    }

    @Test
    fun `a second series does not inherit the first one's glossary`() {
        readPage(mapOf("草太" to "Souta", "夕" to "Yuu"))
        val first = works.currentScope()
        assertNotEquals("the session should have been named", WorkMemory.PENDING, first)

        after(1_000)
        readPage(mapOf("先生" to "Doctor"))
        assertEquals("Doctor", glossary.snapshot()["先生"])

        // The reader closes one series and opens another.
        after(WorkMemory.GAP_MS + 1)
        assertEquals(WorkMemory.PENDING, works.currentScope())

        readPage(mapOf("玲奈" to "Reina", "健" to "Ken"))
        val second = works.currentScope()

        assertNotEquals("the two series must not share a scope", first, second)
        assertNull(
            "先生 was fixed as \"Doctor\" by another series and must not carry over",
            glossary.snapshot()["先生"],
        )
        assertEquals("Reina", glossary.snapshot()["玲奈"])
    }

    @Test
    fun `returning to a series resumes what it established`() {
        readPage(mapOf("草太" to "Souta", "夕" to "Yuu"))
        val first = works.currentScope()
        after(1_000)
        readPage(mapOf("先生" to "Doctor"))

        after(WorkMemory.GAP_MS + 1)
        readPage(mapOf("玲奈" to "Reina", "健" to "Ken"))
        assertNotEquals(first, works.currentScope())

        // Back to the first series the next day.
        after(WorkMemory.GAP_MS * 20)
        readPage(mapOf("草太" to "Souta", "夕" to "Yuu"))

        assertEquals("the series should have been recognised", first, works.currentScope())
        assertEquals(
            "its glossary should come back with it",
            "Doctor",
            glossary.snapshot()["先生"],
        )
    }

    @Test
    fun `a character's pronoun does not follow their name into another series`() {
        readPage(mapOf("草太" to "Souta", "夕" to "Yuu"))
        cast.learn(mapOf("Yuu" to CastBook.Member("he", "shy, informal", "the younger one")))
        assertEquals("he", cast.snapshot()["Yuu"]?.pronoun)

        after(WorkMemory.GAP_MS + 1)
        readPage(mapOf("玲奈" to "Reina", "健" to "Ken"))

        assertNull(
            "an unrelated Yuu must start without a pronoun",
            cast.snapshot()["Yuu"],
        )
    }

    @Test
    fun `story context does not carry into the next series`() {
        readPage(mapOf("草太" to "Souta", "夕" to "Yuu"))
        StoryContext.remember("I'm not going back.", "Souta")
        assertTrue(StoryContext.snapshot().isNotEmpty())

        after(WorkMemory.GAP_MS + 1)

        assertTrue(
            "the previous story's lines must not be context for the next one",
            StoryContext.snapshot().isEmpty(),
        )
    }

    @Test
    fun `a run of pages with no dialogue ends the series`() {
        readPage(mapOf("草太" to "Souta", "夕" to "Yuu"))
        val first = works.currentScope()

        // Leaving a series always crosses pages with nothing to translate —
        // an index, a cover, a thumbnail grid.
        repeat(WorkMemory.QUIET_PASSES) { works.noteQuietPass() }

        assertEquals(WorkMemory.PENDING, works.currentScope())
        assertNotEquals(first, works.currentScope())
    }

    @Test
    fun `scrolling within one series keeps its memory`() {
        readPage(mapOf("草太" to "Souta", "夕" to "Yuu"))
        val first = works.currentScope()

        // Page after page, well inside the gap, with the odd wordless spread.
        for (i in 1..6) {
            after(10_000)
            if (i == 3) works.noteQuietPass() else readPage(mapOf("兄ちゃん" to "big bro"))
        }

        assertEquals("a normal read must not be split into works", first, works.currentScope())
        assertEquals("big bro", glossary.snapshot()["兄ちゃん"])
    }

    @Test
    fun `an unnamed session is discarded rather than leaking into the next`() {
        // A page or two with no proper nouns at all never names its work.
        glossary.learn(mapOf("うん" to "Yeah"))
        works.noteTranslated(clock, glossary.snapshot().keys)
        assertEquals(WorkMemory.PENDING, works.currentScope())

        after(WorkMemory.GAP_MS + 1)
        readPage(mapOf("玲奈" to "Reina", "健" to "Ken"))

        assertNull(
            "terms from an unattributed session belong to no work",
            glossary.snapshot()["うん"],
        )
    }

    @Test
    fun `a capture session starts clean, not on the glossary every series once shared`() {
        val ctx = RuntimeEnvironment.getApplication()
        // What an older install kept as its one glossary and cast for
        // everything, and what an earlier run left pending when it stopped.
        val glossaryPrefs = ctx.getSharedPreferences("mangalens_glossary", Context.MODE_PRIVATE)
        glossaryPrefs.edit()
            .putString("terms", JSONObject().put("先生", "Doctor").put("院長", "Director").toString())
            .putString("terms:pending", JSONObject().put("うん", "Yeah").toString())
            .commit()
        ctx.getSharedPreferences("mangalens_cast", Context.MODE_PRIVATE).edit()
            .putString("cast", JSONObject().put("Yuu", JSONObject().put("pronoun", "he")).toString())
            .commit()

        glossary = GlossaryStore(ctx)
        cast = CastBook(ctx)
        works = WorkMemory(ctx, glossary, cast)
        assertNull("先生 was fixed by some other series", glossary.snapshot()["先生"])
        assertNull("terms from an unattributed run belong to no work", glossary.snapshot()["うん"])
        assertNull("an unrelated Yuu must start without a pronoun", cast.snapshot()["Yuu"])

        readPage(mapOf("草太" to "Souta", "夕" to "Yuu"))
        assertNotEquals(WorkMemory.PENDING, works.currentScope())
        assertEquals("the first page's names belong to the work they named", "Souta", glossary.snapshot()["草太"])
        assertNull(glossary.snapshot()["先生"])
        assertNull(glossary.snapshot()["うん"])
        assertFalse("the pooled glossary is gone for good", glossaryPrefs.contains("terms"))
    }

    @Test
    fun `a long read is still recognised by the names it opened with`() {
        readPage(mapOf("草太" to "Souta", "夕" to "Yuu"))
        val first = works.currentScope()
        // Chapter after chapter, every page adding a few names and terms of
        // its own, until the glossary holds far more than a work keeps.
        for (page in 0 until 20) {
            after(10_000)
            readPage((0 until 3).associate { "語${page}_$it" to "Term $page-$it" })
        }
        assertTrue(glossary.size() > 48)

        // Back the next day. The first page also teaches 先輩, which sorts
        // ahead of both names, so a work not recognised would be minted anew.
        after(WorkMemory.GAP_MS * 20)
        readPage(mapOf("草太" to "Souta", "夕" to "Yuu", "先輩" to "senpai"))

        assertEquals("the series should have been recognised", first, works.currentScope())
        assertEquals("its glossary should come back with it", "Term 0-0", glossary.snapshot()["語0_0"])
    }
}
