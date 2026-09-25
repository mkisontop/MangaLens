package app.mangalens.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mangalens.pipeline.AiFailure
import app.mangalens.settings.AiReasoning
import app.mangalens.settings.AiVisionMode
import app.mangalens.settings.AppSettings
import app.mangalens.settings.CaptureMode
import app.mangalens.settings.LlmProvider
import app.mangalens.settings.SettingsRepository
import app.mangalens.settings.SourceLang
import app.mangalens.translate.LlmHttp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch

/**
 * Every settings write the screens make. The screens render from plain
 * state and send their changes here, so a screenshot or a test can drive
 * them without a settings store behind them.
 */
internal interface SettingsSink {
    fun setProvider(v: LlmProvider)
    fun setApiKey(provider: LlmProvider, v: String)
    fun setModel(provider: LlmProvider, v: String)
    fun setCustomUrl(v: String)
    fun setSourceLang(v: SourceLang)
    fun setMode(v: CaptureMode)
    fun setAiVision(v: AiVisionMode)
    fun setAiReasoning(v: AiReasoning)
    fun setDataSaver(v: Boolean)
    fun setAiCleanup(v: Boolean)
    fun setDiagnostics(v: Boolean)
    fun setTextScale(v: Float)
    fun setNoGhosts(v: Boolean)
    fun setIgnoreTopPct(v: Float)
    fun setStabilityMs(v: Int)
    fun setAutoScrollButton(v: Boolean)
    fun setScrollLevel(v: Int)
    fun setSmartScroll(v: Boolean)
}

/**
 * The real sink: each write is launched in order on [scope], so the
 * store receives a burst of keystrokes in the order they were typed.
 */
internal class RepoSink(private val scope: CoroutineScope, private val repo: SettingsRepository) : SettingsSink {
    private fun write(block: suspend SettingsRepository.() -> Unit) {
        scope.launch { repo.block() }
    }

    override fun setProvider(v: LlmProvider) = write { setProvider(v) }
    override fun setApiKey(provider: LlmProvider, v: String) = write { setApiKey(provider, v) }
    override fun setModel(provider: LlmProvider, v: String) = write { setModel(provider, v) }
    override fun setCustomUrl(v: String) = write { setCustomUrl(v) }
    override fun setSourceLang(v: SourceLang) = write { setSourceLang(v) }
    override fun setMode(v: CaptureMode) = write { setMode(v) }
    override fun setAiVision(v: AiVisionMode) = write { setAiVision(v) }
    override fun setAiReasoning(v: AiReasoning) = write { setAiReasoning(v) }
    override fun setDataSaver(v: Boolean) = write { setDataSaver(v) }
    override fun setAiCleanup(v: Boolean) = write { setAiCleanup(v) }
    override fun setDiagnostics(v: Boolean) = write { setDiagnostics(v) }
    override fun setTextScale(v: Float) = write { setTextScale(v) }
    override fun setNoGhosts(v: Boolean) = write { setNoGhosts(v) }
    override fun setIgnoreTopPct(v: Float) = write { setIgnoreTopPct(v) }
    override fun setStabilityMs(v: Int) = write { setStabilityMs(v) }
    override fun setAutoScrollButton(v: Boolean) = write { setAutoScrollButton(v) }
    override fun setScrollLevel(v: Int) = write { setScrollLevel(v) }
    override fun setSmartScroll(v: Boolean) = write { setSmartScroll(v) }
}

/**
 * What the reader is typing into the AI fields, shared by setup and
 * Tweaks. The store echoes every keystroke back a moment later; a field
 * that took each echo would jump its cursor or roll back a character, so
 * once a field is edited it keeps its own text, and only a provider switch
 * hands it the store's value again.
 *
 * Held with plain remember, never saveable: an API key must never end up
 * in saved-instance state, which Android may write to disk.
 */
@Stable
internal class AiDrafts(initial: AppSettings, private val sink: SettingsSink) {
    private var base by mutableStateOf(initial)

    var key by mutableStateOf(initial.apiKey)
        private set
    var model by mutableStateOf(initial.model)
        private set
    var customUrl by mutableStateOf(initial.customUrl)
        private set

    private var keyEdited = false
    private var modelEdited = false
    private var customUrlEdited = false

    /** The stored settings with the drafts in place: what a test line should use right now. */
    val settings: AppSettings
        get() = base.copy(apiKey = key.trim(), model = model.trim(), customUrl = customUrl.trim())

    /** Takes a fresh value from the store, without echoing an older write over typing. */
    fun sync(s: AppSettings) {
        if (s.provider != base.provider) {
            keyEdited = false
            modelEdited = false
        }
        if (!keyEdited) key = s.apiKey
        if (!modelEdited) model = s.model
        if (!customUrlEdited) customUrl = s.customUrl
        base = s
    }

    fun editKey(v: String) {
        key = v
        keyEdited = true
        sink.setApiKey(base.provider, v.trim())
    }

    /** A key that arrived whole (pasted or saved); the caller stores it. */
    fun acceptKey(k: String) {
        key = k
        keyEdited = true
    }

    fun editModel(v: String) {
        model = v
        modelEdited = true
        sink.setModel(base.provider, v.trim())
    }

    fun editCustomUrl(v: String) {
        customUrl = v
        customUrlEdited = true
        sink.setCustomUrl(v.trim())
    }

    /**
     * Checks [raw] as a key for the current provider and, when it passes,
     * saves it and has [sayHi] try it at once, with the new key rather
     * than waiting for the store to echo it back.
     */
    fun submitKey(raw: String, sayHi: SayHi): PasteCheck {
        val provider = base.provider
        val check = pasteCheck(provider, raw)
        if (check != PasteCheck.OK) return check
        val k = raw.trim()
        acceptKey(k)
        sink.setApiKey(provider, k)
        sayHi.runIfReady(settings)
        return check
    }
}

@Composable
internal fun rememberAiDrafts(settings: AppSettings, sink: SettingsSink): AiDrafts {
    val drafts = remember(sink) { AiDrafts(settings, sink) }
    LaunchedEffect(drafts, settings) { drafts.sync(settings) }
    return drafts
}

/** The notice under a Paste button when the clipboard held no key; null when it did. */
internal fun pasteNotice(check: PasteCheck): String? = when (check) {
    PasteCheck.EMPTY -> "Your clipboard is empty. Copy the key first, then tap Paste."
    PasteCheck.NOT_A_KEY ->
        "That doesn't look like a key (keys are one long word, no spaces). Copy it again, then tap Paste."
    PasteCheck.OK -> null
}

/**
 * Fuki's proof that a key works: one Korean line, translated by the AI
 * the reader picked. It runs only when the reader asks — a paste, a save,
 * a retry or the Tweaks test — never on its own when the screen opens,
 * so opening the app never spends a request.
 */
@Stable
internal class SayHi(
    private val scope: CoroutineScope,
    private val attempt: suspend (AppSettings) -> String = ::tryKey,
) {
    sealed interface Phase {
        data object Idle : Phase
        data object Running : Phase
        data class Ok(val text: String) : Phase
        /**
         * The test did not letter the line. [rejected] means the provider
         * turned the key away; [retry] is false when trying the same thing
         * again cannot help, so no "Try again" is offered.
         */
        data class Failed(val message: String, val rejected: Boolean, val retry: Boolean = !rejected) : Phase
    }

    var phase by mutableStateOf<Phase>(Phase.Idle)

    private var job: Job? = null

    /** The provider and key last tried, so a refusal is only held against that same key. */
    private var tried: Pair<LlmProvider, String>? = null

    fun run(s: AppSettings) {
        job?.cancel()
        phase = Phase.Running
        tried = s.provider to s.apiKey.trim()
        val label = LlmHttp.providerLabel(s)
        job = scope.launch {
            phase = try {
                Phase.Ok(attempt(s))
            } catch (e: TimeoutCancellationException) {
                // A timeout is a CancellationException too; it must be
                // told apart before the rethrow below swallows it.
                Phase.Failed(friendlyTestFailure("timeout", label), rejected = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Phase.Failed(friendlyTestFailure(AiFailure.cause(e), label), AiFailure.keyRejected(e))
            }
        }
    }

    /**
     * Fails the test without asking the AI, for a test that could not
     * start: Fuki says what to fill in first. Nothing was tried, so no
     * earlier refusal is held against the key any more.
     */
    fun needs(message: String) {
        job?.cancel()
        tried = null
        phase = Phase.Failed(message, rejected = false, retry = false)
    }

    /**
     * Runs the test when [s] has what it needs, otherwise says what is
     * missing (see [sayHiBlocker]). False when it could not start, so the
     * caller can buzz.
     */
    fun runIfReady(s: AppSettings): Boolean {
        val blocker = sayHiBlocker(s)
        if (blocker != null) {
            needs(blocker)
            return false
        }
        run(s)
        return true
    }

    /**
     * Whether the provider turned away the key [s] holds. A refusal is
     * about one key: once the reader changes the key or the provider, it no
     * longer stands, and setup no longer asks for a new key.
     */
    fun rejects(s: AppSettings): Boolean =
        (phase as? Phase.Failed)?.rejected == true && tried == (s.provider to s.apiKey.trim())
}

/**
 * What the test line did, in balloons: the raw Korean on one side and
 * Fuki's English on the other, so the first thing a new key produces
 * looks like the lettering it will do on real pages. A failure is Fuki
 * speaking too, in a balloon marked with a red "!", so it never reads as
 * one more link. "Try again" shows only when trying again could help: a
 * refused key fails the same way every time.
 */
@Composable
internal fun SayHiResult(phase: SayHi.Phase, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val pop = LocalPop.current
    Column(modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
        when (phase) {
            SayHi.Phase.Idle -> Unit
            SayHi.Phase.Running -> {
                Spacer(Modifier.height(12.dp))
                SpeechBalloon(pop.surface, tail = Tail.Top, tailAt = 0.2f) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Trying your key on a line", style = MaterialTheme.typography.bodyLarge, color = pop.ink)
                        Spacer(Modifier.width(10.dp))
                        WaitingDots(Modifier.padding(top = 6.dp))
                    }
                }
            }
            is SayHi.Phase.Ok -> {
                Spacer(Modifier.height(12.dp))
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SpeechBalloon(pop.surface, tail = Tail.Start, tailAt = 0.35f) {
                        Text(SAMPLE, fontFamily = FontFamily.Default, fontSize = 18.sp, lineHeight = 24.sp, color = pop.ink)
                    }
                    val text = phase.text.ifBlank { "…" }
                    val shown = if (text.length > 80) text.take(79).trimEnd() + "…" else text
                    SpeechBalloon(pop.zap, Modifier.align(Alignment.End), tail = Tail.End, tailAt = 0.65f) {
                        Text(
                            shown,
                            fontFamily = ComicNeue,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            lineHeight = 22.sp,
                            color = pop.onZap,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "That's me: your first lettered line!",
                    style = MaterialTheme.typography.bodyMedium,
                    color = pop.inkSoft,
                )
            }
            is SayHi.Phase.Failed -> {
                Spacer(Modifier.height(12.dp))
                SpeechBalloon(pop.surface, tail = Tail.Top, tailAt = 0.2f) {
                    Row(verticalAlignment = Alignment.Top) {
                        AlertBadge(Modifier.padding(top = 1.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(phase.message, style = MaterialTheme.typography.bodyLarge, color = pop.punchText)
                    }
                }
                if (phase.retry) {
                    Spacer(Modifier.height(12.dp))
                    StickerButton("Try again", onRetry, style = StickerStyle.Surface, height = 48.dp)
                }
            }
        }
    }
}

/** A small red "!" disc: the mark on anything Fuki could not do. */
@Composable
private fun AlertBadge(modifier: Modifier = Modifier) {
    val pop = LocalPop.current
    val glyph = with(LocalDensity.current) { 14.dp.toSp() }
    Box(
        modifier
            .size(20.dp)
            .clearAndSetSemantics { }
            .drawWithCache {
                val sw = 2.dp.toPx()
                onDrawBehind {
                    drawCircle(pop.punch)
                    drawCircle(pop.stroke, size.minDimension / 2f - sw / 2f, style = Stroke(sw))
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text("!", fontFamily = ComicNeue, fontWeight = FontWeight.Bold, fontSize = glyph, lineHeight = glyph, color = pop.onPunch)
    }
}
