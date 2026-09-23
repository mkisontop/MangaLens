package app.mangalens.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import app.mangalens.settings.SourceLang
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await

/**
 * On-device OCR built on ML Kit's CJK recognizers.
 *
 * In AUTO mode, all three recognizers race on the same frame and the result with
 * the strongest script signature wins. After the same language wins twice in a
 * row it is "pinned" so subsequent frames only pay for a single recognizer; the
 * pin is dropped again whenever a frame produces almost no text in that script.
 */
open class OcrEngine {

    data class Result(val lines: List<OcrLine>, val lang: SourceLang)

    private val korean: TextRecognizer by lazy {
        TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    }
    private val japanese: TextRecognizer by lazy {
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    }
    private val chinese: TextRecognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    private var pinned: SourceLang? = null
    private var lastWinner: SourceLang? = null
    private var winStreak = 0

    open suspend fun recognize(bitmap: Bitmap, setting: SourceLang): Result {
        if (setting != SourceLang.AUTO) {
            return Result(run(recognizerFor(setting), bitmap), setting)
        }
        pinned?.let { lang ->
            val lines = run(recognizerFor(lang), bitmap)
            val strength = lines.sumOf { Script.cjkCount(it.text) }
            if (strength >= 6) return Result(lines, lang)
            pinned = null
            lastWinner = null
            winStreak = 0
        }
        return race(bitmap)
    }

    fun reset() {
        pinned = null
        lastWinner = null
        winStreak = 0
    }

    /**
     * Reads one balloon's crop, enlarged by the caller so the recognizers
     * see lettering at the size they were trained on. With [lang] known the
     * matching recognizer runs alone; otherwise all three race and the
     * strongest script signature wins, as on a full page — but nothing is
     * pinned from a crop, and Latin lines are kept for the same reason they
     * are kept on a page.
     */
    open suspend fun recognizeRegion(bitmap: Bitmap, lang: SourceLang?): List<OcrLine> {
        val known = lang ?: pinned
        if (known != null && known != SourceLang.AUTO) return run(recognizerFor(known), bitmap)
        return coroutineScope {
            val image = InputImage.fromBitmap(bitmap, 0)
            val ko = async { runCatching { korean.process(image).await() }.getOrNull() }
            val ja = async { runCatching { japanese.process(image).await() }.getOrNull() }
            val zh = async { runCatching { chinese.process(image).await() }.getOrNull() }
            val results = listOf(ko.await(), ja.await(), zh.await())
            val scored = results.map { t ->
                val text = t?.text ?: ""
                maxOf(Script.hangulCount(text) * 3, Script.kanaCount(text) * 3 + Script.hanCount(text), Script.hanCount(text) * 2)
            }
            val best = scored.indices.maxBy { scored[it] }
            if (scored[best] >= 2) {
                toLines(results[best])
            } else {
                toLines(results.maxByOrNull { it?.text?.count { c -> c.isLetter() } ?: 0 })
            }
        }
    }

    private fun recognizerFor(lang: SourceLang): TextRecognizer = when (lang) {
        SourceLang.KO -> korean
        SourceLang.JA -> japanese
        else -> chinese
    }

    private suspend fun race(bitmap: Bitmap): Result = coroutineScope {
        val image = InputImage.fromBitmap(bitmap, 0)
        val ko = async { runCatching { korean.process(image).await() }.getOrNull() }
        val ja = async { runCatching { japanese.process(image).await() }.getOrNull() }
        val zh = async { runCatching { chinese.process(image).await() }.getOrNull() }
        val koText = ko.await()
        val jaText = ja.await()
        val zhText = zh.await()

        val koScore = koText?.text?.let { Script.hangulCount(it) * 3 } ?: 0
        val jaScore = jaText?.text?.let { Script.kanaCount(it) * 3 + Script.hanCount(it) } ?: 0
        val zhScore = zhText?.text?.let { Script.hanCount(it) * 2 - Script.kanaCount(it) * 2 } ?: 0

        val best = listOf(
            SourceLang.KO to koScore,
            SourceLang.JA to jaScore,
            SourceLang.ZH to zhScore,
        ).maxBy { it.second }

        if (best.second < 4) {
            lastWinner = null
            winStreak = 0
            // No CJK signature — but not necessarily no text. Aggregator
            // sites routinely serve raws already translated once (Spanish and
            // English are common), and the recognizers read Latin script
            // fine. Throwing those lines away demoted every such page to
            // blind vision guesses; keeping them gives the pipeline real
            // geometry to anchor cards to. Never pinned: the next page may
            // well be the CJK original again.
            val fallback = listOf(koText, jaText, zhText)
                .maxByOrNull { it?.text?.count { c -> c.isLetter() } ?: 0 }
            return@coroutineScope Result(toLines(fallback), SourceLang.KO)
        }
        if (best.first == lastWinner) winStreak++ else {
            lastWinner = best.first
            winStreak = 1
        }
        if (winStreak >= 2) pinned = best.first

        val winnerText = when (best.first) {
            SourceLang.KO -> koText
            SourceLang.JA -> jaText
            else -> zhText
        }
        Result(toLines(winnerText), best.first)
    }

    private suspend fun run(recognizer: TextRecognizer, bitmap: Bitmap): List<OcrLine> {
        val image = InputImage.fromBitmap(bitmap, 0)
        val text = runCatching { recognizer.process(image).await() }.getOrNull() ?: return emptyList()
        return toLines(text)
    }

    private fun toLines(text: Text?): List<OcrLine> {
        if (text == null) return emptyList()
        val out = ArrayList<OcrLine>()
        for (block in text.textBlocks) {
            for (line in block.lines) {
                val box: Rect = line.boundingBox ?: continue
                val t = line.text.trim()
                if (t.isEmpty()) continue
                val vertical = box.height() > box.width() * 1.4 && t.length > 1
                out.add(OcrLine(t, box, vertical))
            }
        }
        return out
    }
}
