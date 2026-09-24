package app.mangalens.ui

import app.mangalens.settings.AppSettings
import app.mangalens.settings.CaptureMode
import app.mangalens.settings.LlmProvider
import app.mangalens.settings.SourceLang
import app.mangalens.translate.LlmEngine
import app.mangalens.translate.LlmHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/*
 * The home screen's decisions, kept free of Compose so every branch is a
 * plain unit test: which face the screen shows, whether a paste looks like
 * a key, and what Fuki says when a test line fails.
 */

internal enum class Stage { LOADING, SETUP, READY, RUNNING, PAUSED }

/**
 * The home screen shows one of five faces; running wins so a reader can
 * always stop. [setupOpen] is the latch that keeps the checklist up after
 * its last step completes, so the finished ticket and the "All set"
 * button are seen instead of the screen jumping away mid-tap.
 */
internal fun homeStage(
    loaded: Boolean,
    running: Boolean,
    paused: Boolean,
    overlayGranted: Boolean,
    aiReady: Boolean,
    setupOpen: Boolean,
): Stage = when {
    !loaded -> Stage.LOADING
    running -> if (paused) Stage.PAUSED else Stage.RUNNING
    setupOpen || !overlayGranted || !aiReady -> Stage.SETUP
    else -> Stage.READY
}

internal enum class PasteCheck { EMPTY, NOT_A_KEY, OK }

/**
 * Whether [raw] clipboard text could be an API key. It catches the usual
 * wrong copies — a sentence, a page URL, a few characters — without
 * guessing at any provider's format. A custom endpoint's token can be any
 * length, so only the shape checks apply to it.
 */
internal fun pasteCheck(provider: LlmProvider, raw: String): PasteCheck {
    val k = raw.trim()
    if (k.isEmpty()) return PasteCheck.EMPTY
    if (k.any { it.isWhitespace() }) return PasteCheck.NOT_A_KEY
    if (k.startsWith("http", ignoreCase = true)) return PasteCheck.NOT_A_KEY
    if (provider != LlmProvider.CUSTOM && k.length !in 20..300) return PasteCheck.NOT_A_KEY
    return PasteCheck.OK
}

/**
 * A soft hint only: Gemini keys have looked like this for years, but a
 * new format must never be refused, so a miss shows a note and the key is
 * still tried.
 */
internal fun looksLikeGeminiKey(k: String): Boolean =
    k.startsWith("AIza") && k.length in 35..45 && k.all { it.isLetterOrDigit() || it == '-' || it == '_' }

/**
 * What Fuki says when the test line fails, from the [cause] the pipeline
 * reports (see AiFailure) or "timeout". Each message names what the
 * reader can do about it, in [provider]'s name.
 */
internal fun friendlyTestFailure(cause: String, provider: String): String = when {
    cause == "key rejected" -> "$provider said no to that key. Copy the whole key again, then tap Paste."
    cause == "network" -> "I can't reach the internet. Check your connection and try again."
    cause == "rate limited" ->
        "$provider says slow down: it's busy, or today's free quota is used up. Try again in a minute."
    cause == "model unavailable" || cause == "HTTP 404" ->
        "That model isn't on your key. Clear the Model box in Tweaks → AI brain."
    cause == "declined" -> "$provider declined my test line. Odd! Try again."
    cause.startsWith("HTTP 5") -> "$provider is having a moment. Try again shortly."
    cause == "timeout" -> "$provider took too long to answer. Try again?"
    else -> "Something went wrong ($cause). Try again?"
}

/**
 * Why the Tweaks test cannot ask the AI yet, in Fuki's words, or null
 * when it can. A missing key or endpoint is caught here rather than sent:
 * a blank key comes back as a refusal and a blank URL as an odd error,
 * and neither tells the reader the real fix, which is to fill the field in.
 */
internal fun sayHiBlocker(s: AppSettings): String? = when {
    LlmHttp.setupNeeded(s) == null -> null
    s.provider == LlmProvider.CUSTOM -> "Add your endpoint URL above first, then say hi."
    else -> "Paste your ${LlmHttp.providerLabel(s)} key above first, then say hi."
}

/** The one-line summary on the home screen's Tweaks sticker. */
internal fun tweaksSummary(s: AppSettings): String {
    val lang = when (s.sourceLang) {
        SourceLang.AUTO -> "Auto language"
        SourceLang.KO -> "Korean"
        SourceLang.JA -> "Japanese"
        SourceLang.ZH -> "Chinese"
    }
    val mode = if (s.mode == CaptureMode.AUTO) "Hands-free" else "Tap to translate"
    return lang + " · " + mode + " · " + LlmHttp.providerLabel(s)
}

internal fun apiKeyLabel(provider: LlmProvider): String = when (provider) {
    LlmProvider.ANTHROPIC -> "Anthropic API key"
    LlmProvider.OPENAI -> "OpenAI API key"
    LlmProvider.GEMINI -> "Gemini API key"
    LlmProvider.OPENROUTER -> "OpenRouter API key"
    LlmProvider.CUSTOM -> "Bearer token (optional)"
}

internal data class ProviderKeyHelp(val message: String, val linkLabel: String = "", val url: String? = null)

/** Where each provider's key comes from. Each key is saved for its own provider only. */
internal fun providerKeyHelp(provider: LlmProvider): ProviderKeyHelp = when (provider) {
    LlmProvider.GEMINI -> ProviderKeyHelp(
        "Recommended: fastest here, free tier, no card needed. Saved only for Gemini.",
        "Get a free Gemini key ↗",
        "https://aistudio.google.com/apikey",
    )
    LlmProvider.ANTHROPIC -> ProviderKeyHelp(
        "Saved only for Claude.",
        "Create an Anthropic key ↗",
        "https://console.anthropic.com/settings/keys",
    )
    LlmProvider.OPENAI -> ProviderKeyHelp(
        "Saved only for OpenAI.",
        "Create an OpenAI key ↗",
        "https://platform.openai.com/api-keys",
    )
    LlmProvider.OPENROUTER -> ProviderKeyHelp(
        "Saved only for OpenRouter.",
        "Create an OpenRouter key ↗",
        "https://openrouter.ai/settings/keys",
    )
    LlmProvider.CUSTOM -> ProviderKeyHelp(
        "Optional bearer token for this endpoint. Old shared keys are never sent here; enter the one meant for this URL.",
    )
}

/** Where a new Gemini key is made; step 2 of setup sends the reader straight there. */
internal const val GEMINI_KEY_URL = "https://aistudio.google.com/apikey"

/**
 * The floating button's mark as copy writes it. A word joiner holds the
 * two characters together: a line may otherwise break between an
 * ideograph and a Latin letter, leaving "文" at the end of one line and
 * "A" at the start of the next.
 */
internal const val MARK = "文⁠A"

/** Fuki's tips, cycled one at a time on the home screen; the first explains hands-free mode. */
internal val TIPS = listOf(
    "Hands-free: scroll and I hide; stop and I translate. That's the whole trick.",
    "Hold the $MARK bubble for the quick menu: translate now, peek at the original, and more.",
    "Brave private tabs hide the page from me (they go black). Use a normal tab.",
    "My lettering never blocks touches. Scroll right through it.",
    "Lines pop in one by one, straight from the AI.",
    "I remember names as you read, so characters stay who they are.",
    "Starting another series? Hold $MARK → New series, and I'll forget the old names.",
    "Pages go only to the AI you picked, nowhere else.",
    "Loving a raw? Support the official release when it comes out.",
)

/**
 * The tips for [mode]. In tap-to-translate mode the hands-free tip would
 * contradict the caption right above it, so that mode opens with its own.
 */
internal fun tipsFor(mode: CaptureMode): List<String> =
    if (mode == CaptureMode.AUTO) TIPS
    else listOf("Tap mode: open a page, tap $MARK, and I letter it. Hands-free lives in Tweaks.") + TIPS.drop(1)

/** The line Fuki letters to prove a key works: short, warm, and unmistakably Korean. */
internal const val SAMPLE = "괜찮아. 내가 지켜줄게."

/** Asks the AI in [s] to translate [SAMPLE]; the answer is the first proof a key works. */
internal suspend fun tryKey(s: AppSettings): String = withContext(Dispatchers.IO) {
    withTimeout(30_000) { LlmEngine(s).translate(listOf(SAMPLE), SourceLang.KO).first().trim() }
}
