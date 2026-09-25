package app.mangalens.scroll

import android.graphics.Rect

/**
 * Where on the screen the finger drags: a vertical line from [bottom] up
 * to [top] at [x], in screen pixels.
 *
 * The finger must come down on the page itself. MangaLens's own floating
 * controls, pill and menu are windows that take touches: a drag started on
 * one of them never reaches the app underneath, and the page does not move.
 * The lane also keeps off the screen's edges, where a drag pulls down the
 * notification shade or swipes the app away.
 */
internal data class ScrollLane(val x: Float, val top: Float, val bottom: Float) {

    val length: Float get() = bottom - top

    companion object {
        /** Highest and lowest the finger goes, as shares of the screen's height. */
        const val TOP = 0.2f
        const val BOTTOM = 0.8f

        /** Positions tried across the screen, in order of preference. */
        private val ACROSS = floatArrayOf(0.5f, 0.64f, 0.36f, 0.78f, 0.22f)

        /** The shortest lane worth dragging along, as a share of the screen's height. */
        private const val MIN_LENGTH = 0.25f

        /** Room kept between the finger and anything it must not touch, in pixels. */
        private const val CLEARANCE = 24

        /**
         * The best lane on a [width] x [height] screen that touches none of
         * [obstacles]: the full height at the first position across that is
         * free, or else the longest stretch free at any of them. Null when
         * nothing is left long enough to drag along.
         */
        fun pick(width: Int, height: Int, obstacles: List<Rect>): ScrollLane? {
            if (width <= 0 || height <= 0) return null
            val top = height * TOP
            val bottom = height * BOTTOM
            var best: ScrollLane? = null
            for (share in ACROSS) {
                val x = width * share
                val blocks = obstacles
                    .filter { x >= it.left - CLEARANCE && x <= it.right + CLEARANCE }
                    .map { (it.top - CLEARANCE).toFloat() to (it.bottom + CLEARANCE).toFloat() }
                    .sortedBy { it.first }
                // The free stretches between the obstacles on this line.
                var from = top
                for ((t, b) in blocks + (bottom to bottom)) {
                    val to = minOf(t, bottom)
                    if (to - from > (best?.length ?: 0f)) best = ScrollLane(x, from, to)
                    from = maxOf(from, b)
                    if (from >= bottom) break
                }
                if (best != null && best.x == x && best.top == top && best.bottom == bottom) return best
            }
            return best?.takeIf { it.length >= height * MIN_LENGTH }
        }
    }
}
