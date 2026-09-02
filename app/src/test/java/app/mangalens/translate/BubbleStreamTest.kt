package app.mangalens.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The reply is fed in at arbitrary chunk boundaries, as a network stream
 * delivers it, and every entry must surface exactly once, as soon as it is
 * complete, whatever the boundaries were.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BubbleStreamTest {

    private val reply = """```json
{"bubbles":[{"id":0,"who":"Tae-oh","src":"안녕","en":"Hey there.","kind":"dialogue"},
 {"id":1,"who":"Mina","src":"뭐?","en":"What?! A \"joke\" {really}","kind":"dialogue"},
 {"box":[100,200,300,50],"who":"","src":"쿵","en":"THUD","kind":"sfx"}],
 "new_terms":{"강태오":"Kang Tae-oh"},"characters":{}}
```"""

    @Test
    fun `entries surface as their closing brace arrives, whatever the chunking`() {
        for (chunk in listOf(1, 3, 7, 16, 64, 4096)) {
            val stream = BubbleStream()
            val seen = ArrayList<String>()
            var i = 0
            while (i < reply.length) {
                val piece = reply.substring(i, minOf(reply.length, i + chunk))
                i += chunk
                for (o in stream.feed(piece)) seen.add(o.optString("en"))
            }
            assertEquals("chunk size $chunk", listOf("Hey there.", "What?! A \"joke\" {really}", "THUD"), seen)
        }
    }

    @Test
    fun `an entry is never surfaced before it is complete`() {
        val stream = BubbleStream()
        val head = reply.substring(0, reply.indexOf("\"kind\":\"dialogue\"}") + 5)
        assertTrue("nothing complete yet", stream.feed(head).isEmpty())
        val rest = reply.substring(head.length)
        assertEquals(3, stream.feed(rest).size)
    }

    @Test
    fun `the array's end stops the stream and later text is ignored`() {
        val stream = BubbleStream()
        val all = stream.feed(reply)
        assertEquals(3, all.size)
        assertTrue(stream.feed("{\"id\":9,\"en\":\"LATE\"}").isEmpty())
    }

    @Test
    fun `a bare array reply yields nothing here and is left to the full parse`() {
        val stream = BubbleStream()
        assertTrue(stream.feed("[{\"id\":0,\"en\":\"x\"}]").isEmpty())
    }
}
