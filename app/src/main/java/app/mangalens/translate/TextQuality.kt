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
        // --- Japanese: more of the everyday ones ---
        "ドクン" to "BA-DUMP", "ドクドク" to "THUMP THUMP", "ドキン" to "BA-DUMP", "キュン" to "…SQUEEZE…",
        "ドーン" to "BOOM", "ドオン" to "BOOM", "ドッ" to "WHUMP", "ガーン" to "…SHOCK…", "ズーン" to "…GLOOM…",
        "バタバタ" to "STOMP STOMP", "バタ" to "THUD", "ガタン" to "CLUNK", "ガチャン" to "CLANK",
        "ピシッ" to "CRACK", "ピシ" to "CRACK", "パリン" to "SHATTER", "バリ" to "CRUNCH", "ボリ" to "CRUNCH",
        "コンコン" to "KNOCK KNOCK", "コン" to "KNOCK", "トントン" to "TAP TAP", "トン" to "TAP",
        "パチパチ" to "CLAP CLAP", "パチン" to "SNAP", "パチ" to "BLINK", "ポン" to "PAT", "ポカ" to "BONK",
        "ジロジロ" to "…STARE…", "ジロ" to "GLARE", "ボー" to "…DAZED…", "ボーッ" to "…DAZED…",
        "ニヤニヤ" to "SMIRK", "ニコニコ" to "BEAM", "ニッ" to "GRIN", "ムスッ" to "SULK", "ムッ" to "HMPH",
        "イライラ" to "…IRRITATED…", "ワクワク" to "…EXCITED…", "ソワソワ" to "…FIDGET…", "オロオロ" to "…FLUSTER…",
        "ハラハラ" to "FLUTTER", "ポロポロ" to "DRIP DRIP", "ポロ" to "PLOP", "ジワ" to "…WELLING…",
        "ゾワ" to "SHUDDER", "ヒヤ" to "CHILL", "ヒヤヒヤ" to "…NERVOUS…", "ブルッ" to "SHIVER",
        "スヤスヤ" to "ZZZ", "グースカ" to "ZZZ", "クー" to "ZZZ", "ペコ" to "BOW", "ペコペコ" to "BOW BOW",
        "ガブ" to "CHOMP", "パク" to "CHOMP", "モグ" to "MUNCH", "ゴクリ" to "GULP", "ゴクン" to "GULP",
        "チッ" to "TCH", "フン" to "HMPH", "ブー" to "BOO", "シクシク" to "SOB SOB", "シク" to "SOB",
        "ウウ" to "UGH", "うう" to "UGH", "グス" to "SNIFF", "ズビ" to "SNIFF",
        "ゴーン" to "BONG", "ピンポン" to "DING DONG", "プルル" to "RING RING", "ピリリ" to "BEEP BEEP",
        "ブブ" to "BZZT", "ヴヴ" to "BZZT", "ジジ" to "BZZT", "ザザ" to "RUSTLE", "サー" to "SHHH", "ザー" to "POUR",
        "ポツ" to "DRIP", "ポツポツ" to "PITTER PATTER", "ジャー" to "WHOOSH", "ジュー" to "SIZZLE",
        "グツグツ" to "BUBBLE", "ブクブク" to "BUBBLE", "ゴリ" to "GRIND", "ギリ" to "GRIT", "ギリギリ" to "GRIND",
        "ブンブン" to "BUZZ", "ブン" to "WHOOSH", "ヒュン" to "WHOOSH", "ビュン" to "ZOOM", "ビュー" to "WHOOSH",
        "ドロ" to "OOZE", "ヌル" to "SLIP", "ペタ" to "PAT", "ペタン" to "PLOP", "ポフ" to "POOF",
        "フワ" to "FLOAT", "フワフワ" to "FLUFFY", "ユラ" to "SWAY", "ユラユラ" to "SWAY", "クル" to "SPIN",
        "クルクル" to "SPIN SPIN", "ダラ" to "DRIP", "ダラダラ" to "…SWEAT…", "タラ" to "DRIP", "タラー" to "…SWEAT…",
        "ハア" to "HAAH", "ハアハア" to "PANT PANT", "フー" to "PHEW", "フゥ" to "PHEW", "スー" to "…",
        "ガシャーン" to "KRASH", "バーン" to "BAM", "バキッ" to "KRAK", "ボキ" to "SNAP", "ゴン" to "BONK",
        "ゴツン" to "BONK", "ズドン" to "KABOOM", "ズガ" to "KRAKOOM", "ドカーン" to "KABOOM", "チュッ" to "SMOOCH",
        "ジタバタ" to "FLAIL", "テクテク" to "WALK WALK", "トボトボ" to "TRUDGE", "スタスタ" to "STRIDE",
        "ソロ" to "SNEAK", "ソロソロ" to "SNEAK", "コソ" to "WHISPER", "コソコソ" to "SNEAK", "ボソ" to "MUTTER",
        "ボソボソ" to "MUMBLE", "ヒソヒソ" to "WHISPER", "ガヤガヤ" to "HUBBUB", "ワー" to "WAAAH", "キャッ" to "EEK",
        "ゲッ" to "URK", "ギクッ" to "GULP", "ギク" to "GULP", "ドキッ" to "BA-DUMP", "ハッ" to "GASP",
        "ンッ" to "MM", "ウッ" to "URGH", "エッ" to "EH", "ヒッ" to "EEP", "オエ" to "BLERGH",
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
        "쿵쾅" to "THUMP THUMP", "쾅쾅" to "BANG BANG", "콰르릉" to "KRAKOOM", "우르릉" to "RUMBLE",
        "쿨쿨" to "ZZZ", "드르렁" to "SNORE", "덜컹" to "RATTLE", "탁" to "TAP", "털썩" to "FLOP",
        "휘익" to "WHOOSH", "휘청" to "WOBBLE", "휘리릭" to "WHIRL", "번쩍" to "FLASH", "반짝" to "SPARKLE",
        "찰칵" to "CLICK", "딸깍" to "CLICK", "달칵" to "CLICK", "띠링" to "DING", "따르릉" to "RING RING",
        "지잉" to "BZZZ", "지지직" to "BZZT", "삐빅" to "BEEP BEEP", "쏴아" to "SHHH", "주르륵" to "DRIP",
        "뚝뚝" to "DRIP DRIP", "흑흑" to "SOB SOB", "엉엉" to "WAAAH", "히히" to "HEHE", "하하" to "HAHA",
        "크크" to "KEKEKE", "푸훗" to "PFFT", "피식" to "SNORT", "쓰윽" to "SWISH", "스르륵" to "SLIDE",
        "살금살금" to "TIPTOE", "성큼성큼" to "STRIDE", "터벅터벅" to "TRUDGE", "쨍" to "CLANG", "찌릿" to "ZAP",
        "움찔" to "FLINCH", "오들오들" to "SHIVER", "후다닥" to "SCRAMBLE", "허둥지둥" to "…FLUSTER…",
        "멍하니" to "…DAZED…", "멍" to "…BLANK…", "끄덕끄덕" to "NOD NOD", "끄덕" to "NOD", "도리도리" to "SHAKE SHAKE",
        "빠직" to "CRACK", "부글부글" to "…SEETHE…", "화르륵" to "FWOOSH", "치직" to "SIZZLE", "꼬르륵" to "GRRRL",
        "냠냠" to "NOM NOM", "쩝쩝" to "SMACK SMACK", "하암" to "YAWN", "쪽" to "SMOOCH", "토닥토닥" to "PAT PAT",
        "꼬옥" to "SQUEEZE", "꽉" to "GRIP", "쓱싹" to "SCRUB", "웅성웅성" to "MURMUR", "와글와글" to "CHATTER",
        "짝짝" to "CLAP CLAP", "후우" to "PHEW", "휴" to "PHEW", "끼익" to "SCREECH", "부웅" to "VROOM",
        // --- Chinese ---
        "轰隆" to "RUMBLE", "轰" to "BOOM", "砰" to "BANG", "嘭" to "BANG",
        "咚" to "THUD", "哐" to "CLANG", "啪" to "SLAP", "咔嚓" to "CRACK",
        "唰" to "SWISH", "嗖" to "WHOOSH", "哗" to "WHOOSH", "呼" to "WHOOSH",
        "噗" to "PFFT", "嘟" to "BEEP", "滴答" to "DRIP", "沙沙" to "RUSTLE",
        "咕噜" to "GURGLE", "呵呵" to "HEH", "嘻嘻" to "HEHE", "咳" to "COUGH",
        "咚咚" to "THUD THUD", "砰砰" to "BANG BANG", "哗啦" to "CRASH", "哗啦啦" to "WHOOSH", "咔" to "CLICK",
        "嗒嗒" to "TAP TAP", "嗒" to "TAP", "吱呀" to "CREAK", "吱" to "SQUEAK", "叮咚" to "DING DONG", "叮" to "DING",
        "铃铃" to "RING RING", "嗡嗡" to "BUZZ", "嗡" to "BZZZ", "呜呜" to "SOB SOB", "呜" to "SOB", "哈哈" to "HAHA",
        "哼" to "HMPH", "啧" to "TSK", "嘶" to "HISS", "咕" to "GULP", "扑通" to "THUMP", "怦怦" to "BA-DUMP",
        "扑哧" to "PFFT", "唰唰" to "SWISH SWISH", "噼里啪啦" to "CRACKLE", "噼啪" to "CRACK", "啪嗒" to "PLOP",
        "咣当" to "CLANG", "哐当" to "CLANG", "嘎吱" to "CREAK", "嘎" to "CRUNCH", "嚓" to "SHK", "刷" to "SWISH",
        "呼呼" to "WHOOSH", "呼噜" to "SNORE", "嘿嘿" to "HEHE", "哇" to "WAAH", "嘘" to "SHHH",
        // Traditional forms of the common ones, as Taiwanese and Hong Kong editions letter them.
        "轟隆" to "RUMBLE", "轟" to "BOOM", "嘩啦" to "CRASH", "嘩" to "WHOOSH", "噠" to "TAP", "嗚嗚" to "SOB SOB",
        "嗚" to "SOB", "鏘" to "CLANG", "鐺" to "CLANG", "嘰" to "SQUEAK", "撲通" to "THUMP", "撲哧" to "PFFT",
        "噓" to "SHHH", "鈴鈴" to "RING RING", "嘖" to "TSK", "咔嚓" to "CRACK",
    ).map { (key, en) -> normalize(key) to en }

    /**
     * Longest-prefix match, so an elongated form (ゴクゴク) wins over the stem
     * it starts with. Prefix matching also absorbs the tail noise and stretched
     * vowels OCR picks up off hand-drawn lettering (ゴゴゴゴ…).
     *
     * The match is script-blind within Japanese: ぎゅ and ギュ are one sound
     * effect, and artists letter either — hiragana for the soft and small,
     * katakana for the loud — so both are folded onto katakana first, along
     * with the marks OCR mistakes a long-vowel bar for.
     */
    fun lookup(text: String): String? {
        val t = normalize(text)
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

    /**
     * Katakana-folded, with the lookalikes OCR returns for a long-vowel bar
     * (一, ―, －, ｰ) restored to ー and small kana widened to their full
     * forms, so 「ど一ん」 and 「ドーン」 are the same key.
     */
    fun normalize(text: String): String {
        val sb = StringBuilder(text.length)
        for (raw in text.trim()) {
            val c = when (raw) {
                '一', '―', '－', 'ｰ', '—', '｜' -> 'ー'
                '゜', '゛', ' ', '　' -> continue
                else -> raw
            }
            // Hiragana → katakana: the blocks are offset by 0x60.
            val k = if (c.code in 0x3041..0x3096) (c.code + 0x60).toChar() else c
            sb.append(k)
        }
        // A bar can only be a long vowel after a kana; one in front of
        // everything is a stray border stroke.
        while (sb.isNotEmpty() && sb[0] == 'ー') sb.deleteCharAt(0)
        return sb.toString()
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
        // The Latin flavour of the same failure — a Spanish source coming
        // back as the same Spanish. Short identical answers are left alone:
        // "No!" translates to "No!" legitimately.
        if (Script.cjkCount(source) == 0) {
            val a = source.lowercase().filter { it.isLetterOrDigit() }
            if (a.length >= 12 && a == out.lowercase().filter { it.isLetterOrDigit() }) return null
        }
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
