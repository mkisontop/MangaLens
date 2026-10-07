package app.mangalens.gaps

/**
 * The arithmetic of drawing the shade inside the lettering's window.
 *
 * Android draws an overlay that lets touches through at no more than its maximum obscuring
 * opacity (0.8 unless the device says otherwise), and adds up the opacity of all of one app's
 * such windows: past the cap, touches are no longer passed to the app below. So the shade has
 * no window of its own; it is painted in the lettering's, which is drawn at [windowAlpha], and
 * under the veil MangaLens may lay over the whole page. These say how dark it can be there and
 * what to paint so that the glass shows the shade's alpha and the capture, lifted by the veil's
 * level, reads the shade's own grey.
 */
object ShadeWindow {

    /**
     * The darkest the shade can reach the glass from a window drawn at [windowAlpha], leaving
     * room for the veil: under it a gap must hold more black than the veil does, and a window at
     * 0.8 can show no more than 0.75 there and still read back as the same grey. A trusted window
     * is drawn as painted, and any level is possible.
     */
    fun cap(windowAlpha: Float): Float {
        if (windowAlpha >= 0.999f) return 1f
        val w = windowAlpha.coerceIn(0.05f, 1f)
        return maxOf(minOf(w, 2f - 1f / w), 0.5f * w)
    }

    /**
     * The paint alpha, 0..255, that shows [effective] of black on the glass from a window drawn
     * at [windowAlpha] whose veil leaves [screenLevel] of the page everywhere else.
     *
     * Over a gap the window must hold a black of x with `1 - w·x = (1 - e)·L`: the capture,
     * divided by the veil's level L, then reads the gap as `1 - e` of white, the same grey as with
     * no veil. The veil's own black (`(1 - L) / w`) is already there; the shade adds what is
     * missing over it.
     */
    fun paintAlpha(effective: Float, windowAlpha: Float, screenLevel: Float): Int {
        val w = windowAlpha.coerceIn(0.05f, 1f)
        val level = screenLevel.coerceIn(0.05f, 1f)
        val x = (1f - (1f - effective) * level) / w
        val veil = ((1f - level) / w).coerceIn(0f, 0.999f)
        val a = if (veil > 0f) 1f - (1f - x) / (1f - veil) else x
        return (a.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
    }
}
