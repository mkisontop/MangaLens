package app.mangalens.capture

import app.mangalens.overlay.RenderBubble

/**
 * The cards the last stop showed, moved to where a measured scroll put the
 * lettering under them. A scroll clears the screen's cards; at the next
 * stop they come back at once, before the stop's own read has recalled and
 * re-lettered them, and that read replaces them as its lines land. Only a
 * card wholly on screen and clear of the bands at its top and bottom is
 * carried: the read letters the rest.
 */
internal object CardCarry {

    fun carried(cards: List<RenderBubble>, dy: Int, height: Int, ignoreTop: Int, ignoreBottom: Int): List<RenderBubble> =
        cards.mapNotNull { card ->
            val moved = card.shiftedBy(dy)
            moved.takeIf { it.box.top >= ignoreTop && it.box.bottom <= height - ignoreBottom }
        }
}
