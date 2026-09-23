package app.mangalens.pipeline

import android.graphics.Bitmap
import app.mangalens.settings.AppSettings

/**
 * The art under lettering, redrawn by an image model.
 *
 * INTERFACE STUB: implementation pending.
 */
class AiCleaner(private val settings: AppSettings) {

    /**
     * The page with its lettering removed by the image model, scaled back to
     * [page]'s exact size, or null when the model failed or declined.
     */
    suspend fun cleanPage(page: Bitmap): Bitmap? {
        TODO("AiCleaner.cleanPage")
    }

    companion object {
        /** Whether AI clean-up is available and switched on. */
        fun supports(settings: AppSettings): Boolean = false

        /**
         * [erasure] with its reconstructed pixels taken from [cleaned] — but
         * only when [cleaned] agrees with [page] in the ring around the
         * lettering, which is how a model that redrew the art (dropped a
         * balloon, moved a line) is caught. Null when it does not agree.
         */
        fun refine(page: Bitmap, cleaned: Bitmap, erasure: Erasure): Erasure? = null
    }
}
