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

    /**
     * Many entries render a *state* rather than a noise — シーン is silence
     * loud enough to hear, ジー is the sound of being stared at, ドキドキ is
     * nervousness. Translating those as noises is the classic tell of machine
     * output, so they are given the reading a letterer would use.
     *
     * Order does not matter: [lookup] takes the longest matching prefix, so
     * ゴクゴク beats ゴク without the table having to be hand-sorted.
     */
    private val entries = listOf(
        // --- Japanese: impacts ---
        "ドカ" to "WHAM", "ドゴ" to "WHAM", "ドン" to "THUD", "ドドド" to "RUMBLE",
        "ドド" to "DUDUDU", "ドサ" to "FLOP", "ボコ" to "BONK", "ゴツ" to "BONK",
        "バン" to "BAM", "バタン" to "SLAM", "バキ" to "KRAK", "メキ" to "CRACK",
        "グサ" to "STAB", "ザシュ" to "SHK", "ジャキ" to "SHINK", "バチ" to "ZAP",
        "ガシャン" to "CRASH", "ガシャ" to "CLANK", "ガン" to "CLANG",
        // --- Japanese: movement ---
        "ダッ" to "DASH", "タッ" to "TMP", "ヒュー" to "WHOOSH", "シュッ" to "SHOOM",
        "スッ" to "SWISH", "サッ" to "SWIP", "パッ" to "FLASH", "ガバ" to "LUNGE",
        "バサ" to "FWSH", "ヒラ" to "FLUTTER", "パラパラ" to "FLUTTER",
        "ズルズル" to "DRAAAG", "コツコツ" to "CLACK", "カツカツ" to "CLACK",
        "コツ" to "TAP", "ギシ" to "CREAK", "ミシ" to "CREAK",
        // --- Japanese: ambience ---
        "ゴゴ" to "RUMBLE", "ゴロ" to "ROLL", "ガタ" to "CLATTER", "ガサ" to "RUSTLE",
        "ガガ" to "GRRIND", "ザーザー" to "POURING", "ザワ" to "MURMUR",
        "ガヤ" to "HUBBUB", "ワイワイ" to "CHATTER", "シーン" to "…SILENCE…",
        "キーン" to "RIIING", "ピー" to "BEEP", "チュン" to "CHIRP", "カチ" to "TICK",
        "ピカ" to "FLASH", "キラ" to "SPARKLE",
        // --- Japanese: body and feeling (states, not noises) ---
        "ドキドキ" to "BA-DUMP BA-DUMP", "ドキ" to "BA-DUMP", "ズキ" to "THROB",
        "ビク" to "FLINCH", "ピク" to "TWITCH", "ピキ" to "TWITCH",
        "プルプル" to "TREMBLE", "ブルブル" to "SHIVER", "ゾク" to "SHIVER",
        "ジー" to "…STARE…", "チラ" to "GLANCE", "ニヤ" to "SMIRK", "ニコ" to "SMILE",
        "ドヤ" to "SMUG", "ムカ" to "GRRR", "シュン" to "…WILT…",
        "ゴクゴク" to "GULP GULP", "ゴク" to "GULP", "モグモグ" to "MUNCH",
        "ギュ" to "SQUEEZE", "ポカポカ" to "WARM",
        // --- Japanese: voice ---
        "ハァ" to "HAAH", "はぁ" to "HAAH", "ぜぇ" to "WHEEZE", "フッ" to "HEH",
        "クスクス" to "GIGGLE", "あはは" to "AHAHA", "えへへ" to "EHEHE",
        "ハッ" to "GASP", "キャー" to "EEEK", "ウワ" to "WAAAH", "ゲホ" to "COUGH",
        "ゴホ" to "COUGH", "ぐすん" to "SNIFF", "ぐぅ" to "GRRRN", "ザッ" to "SHFF",
        "ズズ" to "SLURP", "ガチャ" to "CLICK", "ガシ" to "GRAB",
        // --- Korean ---
        "쿵쿵" to "THUD THUD", "쿵" to "THUD", "쾅" to "BANG", "콰광" to "KABOOM",
        "우당탕" to "CRASH", "쨍그랑" to "SHATTER", "철컥" to "CLICK", "덜컥" to "CLUNK",
        "삐걱" to "CREAK", "두근두근" to "BA-DUMP BA-DUMP", "두근" to "BA-DUMP",
        "두둥" to "DA-DUM", "휙" to "WHOOSH", "화악" to "WHOOSH", "후욱" to "WHOOSH",
        "스윽" to "SWISH", "슥" to "SWISH", "툭" to "TAP", "딱" to "SNAP",
        "짝" to "CLAP", "펑" to "POOF", "촤악" to "SPLASH", "촥" to "SPLASH",
        "헉" to "GASP", "헐" to "WHAAT", "하아" to "HAAH", "우와" to "WOW",
        "꿀꺽" to "GULP", "흠칫" to "FLINCH", "부들" to "TREMBLE", "덜덜" to "TREMBLE",
        "부르르" to "SHIVER", "킥킥" to "SNICKER", "콜록" to "COUGH", "훌쩍" to "SNIFF",
        "씨익" to "SMIRK", "뚝" to "DRIP", "사각" to "SCRTCH", "벌컥" to "FLING",
        // --- Chinese ---
        "轰隆" to "RUMBLE", "轰" to "BOOM", "砰" to "BANG", "嘭" to "BANG",
        "咚" to "THUD", "哐" to "CLANG", "啪" to "SLAP", "咔嚓" to "CRACK",
        "唰" to "SWISH", "嗖" to "WHOOSH", "哗" to "WHOOSH", "呼" to "WHOOSH",
        "噗" to "PFFT", "嘟" to "BEEP", "滴答" to "DRIP", "沙沙" to "RUSTLE",
        "咕噜" to "GURGLE", "呵呵" to "HEH", "嘻嘻" to "HEHE", "咳" to "COUGH",
    )

    /**
     * Longest-prefix match, so an elongated form (ゴクゴク) wins over the stem
     * it starts with. Prefix matching also absorbs the tail noise and stretched
     * vowels OCR picks up off hand-drawn lettering (ゴゴゴゴ…).
     */
    fun lookup(text: String): String? {
        val t = text.trim()
        if (t.isEmpty()) return null
        var best: String? = null
        var bestLen = 0
        for ((key, en) in entries) {
            if (key.length > bestLen && t.startsWith(key)) {
                best = en
                bestLen = key.length
            }
        }
        return best
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
