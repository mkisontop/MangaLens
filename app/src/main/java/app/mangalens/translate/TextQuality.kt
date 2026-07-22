package app.mangalens.translate

import app.mangalens.ocr.Script
import app.mangalens.settings.SourceLang
import kotlin.math.max
import kotlin.math.min

/**
 * Onomatopoeia lookup for sound effects so the machine engines never render
 * `死 → "death"` over a ドカッ. Prefix match tolerates OCR tail noise and the
 * elongated forms manga loves (ゴゴゴゴ…).
 */
object SfxDict {

    private val entries = listOf(
        // Japanese
        "ドカ" to "WHAM", "ドゴ" to "WHAM", "ドン" to "THUD", "ドド" to "DUDUDU",
        "ゴゴ" to "RUMBLE", "ゴロ" to "ROLL", "ガタ" to "CLATTER", "ガシ" to "GRAB",
        "ガチャ" to "CLICK", "バン" to "BAM", "バタン" to "SLAM", "バキ" to "KRAK",
        "ビク" to "FLINCH", "ピキ" to "TWITCH", "ザッ" to "SHFF", "ザワ" to "MURMUR",
        "シーン" to "…SILENCE…", "キラ" to "SPARKLE", "ドキ" to "BA-DUMP",
        "ニヤ" to "SMIRK", "ゴク" to "GULP", "ハァ" to "HAAH", "はぁ" to "HAAH",
        "フッ" to "HEH", "ギュ" to "SQUEEZE", "スッ" to "SWISH", "ガバ" to "LUNGE",
        "カチ" to "TICK", "ガサ" to "RUSTLE", "ガガ" to "GRRIND", "ズズ" to "SLURP",
        // Korean
        "쿵" to "THUD", "쾅" to "BANG", "두근" to "BA-DUMP", "휙" to "WHOOSH",
        "짝" to "CLAP", "헉" to "GASP", "하아" to "HAAH", "부들" to "TREMBLE",
        "덜컥" to "CLUNK", "스윽" to "SWISH", "펑" to "POOF", "촤악" to "SPLASH",
        // Chinese
        "轰" to "BOOM", "砰" to "BANG", "咚" to "THUD", "唰" to "SWISH", "哗" to "WHOOSH",
    )

    fun lookup(text: String): String? {
        val t = text.trim()
        if (t.isEmpty()) return null
        for ((key, en) in entries) {
            if (t.startsWith(key)) return en
        }
        return null
    }
}

/**
 * Rejects translations that would read as garbage on the page: empty results,
 * source echoed back untranslated, and Google's habit of romanizing Japanese it
 * cannot parse ("|Yakoru Shiretsuta"). Better an untouched bubble than junk.
 */
object JunkFilter {

    /** Returns null when the translation should not be rendered at all. */
    fun accept(source: String, translated: String, lang: SourceLang, fromAi: Boolean = false): String? {
        val out = translated.trim()
        if (out.isEmpty()) return null
        // Untranslated echo: painting the original text over itself helps nobody.
        if (Script.cjkCount(out) > out.length * 0.4f) return null
        val letters = out.count { it.isLetter() }
        if (letters < 2) return null
        // The AI engines are instructed to skip rather than romanize, and a
        // shouted name (カナタ! -> "Kanata!") IS legitimate romaji — only the
        // machine engines get this gate, and only for long echoes.
        if (!fromAi && lang == SourceLang.JA && looksLikeRomajiEcho(source, out)) return null
        return out
    }

    /**
     * Google romanizes unparseable kana instead of translating it. Detected by
     * comparing the output against a Hepburn romanization of the source kana:
     * near-match means no translation happened. Short matches are left alone —
     * a romanized name bubble is a correct translation, a romanized sentence
     * is not.
     */
    fun looksLikeRomajiEcho(source: String, out: String): Boolean {
        val romaji = romanizeKana(source)
        if (romaji.length < 12) return false
        val cleanOut = out.lowercase().filter { it in 'a'..'z' }
        if (cleanOut.length < 8) return false
        val dist = levenshtein(romaji, cleanOut)
        return dist <= max(romaji.length, cleanOut.length) * 0.34
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a.length > 64 || b.length > 64) return max(a.length, b.length)
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    private val KANA_BASE = mapOf(
        'あ' to "a", 'い' to "i", 'う' to "u", 'え' to "e", 'お' to "o",
        'か' to "ka", 'き' to "ki", 'く' to "ku", 'け' to "ke", 'こ' to "ko",
        'が' to "ga", 'ぎ' to "gi", 'ぐ' to "gu", 'げ' to "ge", 'ご' to "go",
        'さ' to "sa", 'し' to "shi", 'す' to "su", 'せ' to "se", 'そ' to "so",
        'ざ' to "za", 'じ' to "ji", 'ず' to "zu", 'ぜ' to "ze", 'ぞ' to "zo",
        'た' to "ta", 'ち' to "chi", 'つ' to "tsu", 'て' to "te", 'と' to "to",
        'だ' to "da", 'ぢ' to "ji", 'づ' to "zu", 'で' to "de", 'ど' to "do",
        'な' to "na", 'に' to "ni", 'ぬ' to "nu", 'ね' to "ne", 'の' to "no",
        'は' to "ha", 'ひ' to "hi", 'ふ' to "fu", 'へ' to "he", 'ほ' to "ho",
        'ば' to "ba", 'び' to "bi", 'ぶ' to "bu", 'べ' to "be", 'ぼ' to "bo",
        'ぱ' to "pa", 'ぴ' to "pi", 'ぷ' to "pu", 'ぺ' to "pe", 'ぽ' to "po",
        'ま' to "ma", 'み' to "mi", 'む' to "mu", 'め' to "me", 'も' to "mo",
        'や' to "ya", 'ゆ' to "yu", 'よ' to "yo",
        'ら' to "ra", 'り' to "ri", 'る' to "ru", 'れ' to "re", 'ろ' to "ro",
        'わ' to "wa", 'を' to "o", 'ん' to "n",
        'ゃ' to "ya", 'ゅ' to "yu", 'ょ' to "yo", 'っ' to "", 'ー' to "",
        'ぁ' to "a", 'ぃ' to "i", 'ぅ' to "u", 'ぇ' to "e", 'ぉ' to "o",
    )

    private fun romanizeKana(s: String): String {
        val sb = StringBuilder()
        for (c in s) {
            // Fold katakana onto hiragana (blocks are offset by 0x60).
            val h = if (c.code in 0x30A1..0x30F6) (c.code - 0x60).toChar() else c
            KANA_BASE[h]?.let { sb.append(it) }
        }
        return sb.toString()
    }
}
