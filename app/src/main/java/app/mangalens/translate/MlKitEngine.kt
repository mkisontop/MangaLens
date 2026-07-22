package app.mangalens.translate

import app.mangalens.settings.SourceLang
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await

/**
 * Fully offline translation via ML Kit. Downloads a ~30 MB model per language
 * on first use, then works with no network at all. Quality is rougher than the
 * online engines but it never fails on a subway.
 */
class MlKitEngine : TranslationEngine {

    override val label = "On-device"

    private val translators = HashMap<String, Translator>()

    override suspend fun translate(items: List<String>, lang: SourceLang): List<String> {
        val effective = if (lang == SourceLang.AUTO) SourceLang.KO else lang
        val translator = translatorFor(effective)
        return items.map { translator.translate(it).await() }
    }

    private suspend fun translatorFor(lang: SourceLang): Translator {
        val code = when (lang) {
            SourceLang.KO -> TranslateLanguage.KOREAN
            SourceLang.JA -> TranslateLanguage.JAPANESE
            else -> TranslateLanguage.CHINESE
        }
        val translator = synchronized(translators) {
            translators.getOrPut(code) {
                Translation.getClient(
                    TranslatorOptions.Builder()
                        .setSourceLanguage(code)
                        .setTargetLanguage(TranslateLanguage.ENGLISH)
                        .build()
                )
            }
        }
        translator.downloadModelIfNeeded().await()
        return translator
    }
}
