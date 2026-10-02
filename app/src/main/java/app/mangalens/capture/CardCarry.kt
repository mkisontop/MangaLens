package app.mangalens.capture

import android.graphics.Rect
import app.mangalens.overlay.RenderBubble

/**
 * The cards the last stop showed, moved to where a measured scroll put the
 * lettering under them. A scroll clears the screen's cards; at the next
 * stop they come back at once, before the stop's own read has recalled and
 * re-lettered them, and that read replaces them as its lines land. Only a
 * card wholly on screen, clear of the bands at its top and bottom, and
 * whose rows [keeps] says still show what they showed is carried: a
 * toolbar that slid over a line is not lettered over. The read letters the
 * rest.
 */
internal object CardCarry {

    fun carried(
        cards: List<RenderBubble>,
        dy: Int,
        height: Int,
        ignoreTop: Int,
        ignoreBottom: Int,
        keeps: (Rect) -> Boolean = { true },
    ): List<RenderBubble> =
        cards.mapNotNull { card ->
            val moved = card.shiftedBy(dy)
            moved.takeIf { it.box.top >= ignoreTop && it.box.bottom <= height - ignoreBottom && keeps(it.box) }
        }
}
