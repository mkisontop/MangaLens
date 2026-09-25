package app.mangalens.ocr

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Point
import android.graphics.Rect
import android.media.Image
import app.mangalens.settings.SourceLang
import com.google.android.gms.common.Feature
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import com.google.android.odml.image.MlImage
import com.google.mlkit.common.sdkinternal.MlKitContext
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.interfaces.Detector
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognizer
import java.nio.ByteBuffer
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The engine's own bookkeeping around ML Kit — the language pin, and the
 * recognizers it holds — with each recognizer a stand-in that answers
 * whatever the case needs, or never answers at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OcrEngineTest {

    /** A recognizer that hands every read to [answer] and counts them. */
    private class Recognizer(var answer: () -> Task<Text>) : TextRecognizer {
        var reads = 0
        var closed = false

        override fun process(image: InputImage): Task<Text> {
            reads++
            return answer()
        }

        override fun process(image: MlImage): Task<Text> = error("unused")
        override fun process(bitmap: Bitmap, rotation: Int): Task<Text> = error("unused")
        override fun process(image: Image, rotation: Int): Task<Text> = error("unused")
        override fun process(image: Image, rotation: Int, matrix: Matrix): Task<Text> = error("unused")
        override fun process(buffer: ByteBuffer, width: Int, height: Int, rotation: Int, format: Int): Task<Text> = error("unused")
        override fun getDetectorType(): Int = Detector.TYPE_TEXT_RECOGNITION
        override fun getOptionalFeatures(): Array<Feature> = emptyArray()
        override fun close() {
            closed = true
        }
    }

    /** A recognizer's answer: [text] as one line, or nothing when it is blank. */
    private fun reads(text: String): () -> Task<Text> = {
        val box = Rect(0, 0, 20, 20 * text.length)
        val lines = listOf(Text.Line(text, box, emptyList<Point>(), "und", null, emptyList<Any>(), 0f, 1f))
        val blocks = if (text.isBlank()) emptyList() else listOf(Text.TextBlock(text, box, emptyList<Point>(), "und", null, lines))
        Tasks.forResult(Text(text, blocks))
    }

    private val page = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)

    /** The engine wraps each frame in an [InputImage], and that needs ML Kit up. */
    @Before
    fun startMlKit() {
        MlKitContext.initializeIfNeeded(RuntimeEnvironment.getApplication())
    }

    /**
     * A scroll cancels the pass while the pinned recognizer is still
     * reading. That read was cut short, not empty: taken for a page with no
     * Japanese on it, it dropped the pin, and the next stop raced all three
     * recognizers again and needed two more wins to pin.
     */
    @Test
    fun `a read cancelled under the pinned recognizer keeps the pin`() = runBlocking {
        val ko = Recognizer(reads(""))
        val ja = Recognizer(reads("あいつが来たのか"))
        val zh = Recognizer(reads(""))
        val engine = OcrEngine { lang ->
            when (lang) {
                SourceLang.KO -> ko
                SourceLang.JA -> ja
                else -> zh
            }
        }
        // Japanese wins twice and is pinned.
        repeat(2) { assertEquals(SourceLang.JA, engine.recognize(page, SourceLang.AUTO).lang) }

        // The next read never answers, and the pass is cancelled under it.
        ja.answer = { TaskCompletionSource<Text>().task }
        val cut = launch { engine.recognize(page, SourceLang.AUTO) }
        yield()
        assertEquals("the pinned recognizer is reading alone", 3, ja.reads)
        cut.cancelAndJoin()

        ja.answer = reads("あいつが来たのか")
        val raced = ko.reads
        assertEquals(SourceLang.JA, engine.recognize(page, SourceLang.AUTO).lang)
        assertEquals("the next stop still reads with the pinned recognizer alone", raced, ko.reads)
    }

    /**
     * ML Kit keeps one model per language loaded while any client of it is
     * open, so the engine closes what it made. A recognizer it never needed
     * was never made, and closing must not make it.
     */
    @Test
    fun `close releases the recognizers the engine made and makes no others`() = runBlocking {
        val made = ArrayList<SourceLang>()
        val recognizers = SourceLang.entries.associateWith { Recognizer(reads("あいつが来たのか")) }
        val engine = OcrEngine { lang ->
            made += lang
            recognizers.getValue(lang)
        }
        engine.recognize(page, SourceLang.JA)
        engine.close()

        assertEquals(listOf(SourceLang.JA), made)
        assertTrue("the Japanese recognizer is closed", recognizers.getValue(SourceLang.JA).closed)
        assertFalse(recognizers.getValue(SourceLang.KO).closed)
    }
}
