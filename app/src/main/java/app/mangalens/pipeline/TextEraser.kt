package app.mangalens.pipeline

import android.graphics.Bitmap
import android.graphics.Rect
import app.mangalens.translate.ItemKind

/**
 * The lettering of one free-standing piece of text, erased.
 *
 * [patch] is a [rect]-sized ARGB bitmap: opaque where lettering was replaced
 * with reconstructed background, transparent everywhere else, so drawn over
 * the page at [rect] it removes the text and leaves the art untouched.
 * [mask] is [rect]-sized, row-major, true where the patch replaces a pixel.
 */
class Erasure(
    val rect: Rect,
    val patch: Bitmap,
    val mask: BooleanArray,
    /** True when the lettering sat on a flat background, where the erasure is exact. */
    val flat: Boolean,
    /** Dominant background colour around the lettering (opaque ARGB). */
    val background: Int,
    /** Fill colour of the erased lettering (opaque ARGB). */
    val textColor: Int,
    /** Colour of the erased lettering's outline, or null when it had none. */
    val outlineColor: Int?,
    /**
     * How busy the art under the lettering is, 0 (flat paper) to 1 (dense
     * texture): how far a local reconstruction is from what an artist would
     * have drawn there, and so how much an AI clean-up would add.
     */
    val busy: Float,
)

/**
 * Erases lettering that no balloon holds — narration on the art, side
 * comments, signs, sound effects — pixel by pixel, instead of hiding it
 * under a card.
 *
 * INTERFACE STUB: implementation pending.
 */
object TextEraser {

    /**
     * Erases the lettering inside [box] on [page]. [textColor] and
     * [outlineColor] are the model's report of the lettering's colours, when
     * it gave one; they are verified against the pixels, never trusted
     * blindly. Returns null when there is nothing there to erase.
     */
    fun erase(
        page: Bitmap,
        box: Rect,
        kind: ItemKind,
        textColor: Int? = null,
        outlineColor: Int? = null,
    ): Erasure? {
        TODO("TextEraser.erase")
    }
}
