package app.mangalens.overlay

import android.content.Context
import android.hardware.input.InputManager
import android.os.Build

/**
 * How strongly Android draws an overlay that lets touches through to the
 * app under it. Android 12 (API 31) and later hold such a window to the
 * maximum obscuring opacity — 0.8 unless the device is set otherwise — so
 * that some of the app beneath always shows; earlier versions draw it as
 * painted.
 */
object OverlayStrength {

    /** Android's own default for the cap. */
    const val DEFAULT = 0.8f

    fun of(context: Context): Float {
        if (Build.VERSION.SDK_INT < 31) return 1f
        val im = context.getSystemService(InputManager::class.java) ?: return DEFAULT
        return runCatching { im.maximumObscuringOpacityForTouch }.getOrDefault(DEFAULT).coerceIn(0f, 1f)
    }
}
