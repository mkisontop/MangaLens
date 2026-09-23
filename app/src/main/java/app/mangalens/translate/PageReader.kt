package app.mangalens.translate

import android.graphics.Bitmap
import app.mangalens.settings.AppSettings
import app.mangalens.settings.LlmProvider
import app.mangalens.settings.SourceLang
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope

/**
 * The AI-first page reader: the raw screen goes to the model the moment it
 * is still, and the model finds every piece of lettering on it — balloons,
 * captions, text on the art, sound effects — and translates it, streaming
 * each piece back as it finishes.
 *
 * INTERFACE STUB: implementation pending.
 */
class PageReader(
    private val settings: AppSettings,
    private val glossary: GlossaryStore? = null,
    private val cast: CastBook? = null,
) {

    val label: String get() = LlmHttp.providerLabel(settings)

    val cacheNamespace: String get() = "Read:" + label + ":" + settings.effectiveModel()

    /**
     * Starts reading [bitmap] in [scope] and returns at once. The page is
     * encoded before this returns, so the caller may recycle [bitmap] as
     * soon as it likes. Cancelling [scope] or the returned read aborts the
     * request.
     */
    fun start(scope: CoroutineScope, bitmap: Bitmap, lang: SourceLang): PendingRead {
        TODO("PageReader.start")
    }

    /** Reads [bitmap] start to finish; [onItem] sees each item as it lands. */
    suspend fun read(
        bitmap: Bitmap,
        lang: SourceLang,
        onItem: (suspend (PageItem) -> Unit)? = null,
    ): List<PageItem> = coroutineScope { start(this, bitmap, lang).collect(onItem) }

    companion object {
        /** Whether the configured provider reads pages AI-first. */
        fun supports(settings: AppSettings): Boolean = settings.provider == LlmProvider.GEMINI
    }
}

/**
 * A page read in flight. Items arrive while nobody is collecting yet — a
 * read started while the reader might still scroll — and are replayed to
 * whoever collects later.
 */
interface PendingRead {
    /** True until the read has finished, failed or been cancelled. */
    val isActive: Boolean

    /**
     * Hands every item already received to [onItem], then each new one as
     * it lands, and returns the complete list once the reply has finished.
     * Throws when the read failed. May be called more than once.
     */
    suspend fun collect(onItem: (suspend (PageItem) -> Unit)? = null): List<PageItem>

    fun cancel()
}
