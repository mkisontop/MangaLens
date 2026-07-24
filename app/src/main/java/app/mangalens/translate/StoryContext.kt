package app.mangalens.translate

import java.util.ArrayDeque

/**
 * Rolling window of recently translated lines, shared by the text and vision
 * AI engines so the story stays coherent across pages no matter which path
 * translated the previous screen.
 *
 * Lines are kept as a script — `Tae-oh: I'm not going back.` — rather than as
 * source/target pairs. Continuity is what the next page actually needs: who
 * was in the conversation, what was just asked, whose turn it is. Attributed
 * English answers that; a list of source strings does not, and costs more of
 * the request to say less. Keeping the speaker also lets a dangling reply on
 * the next screen ("……分かってる") resolve to the right person.
 */
object StoryContext {

    /**
     * Roughly the last two screens of dialogue. Deliberately short: page-level
     * context is where translation quality peaks, and piling on more history
     * measurably degrades it rather than helping.
     */
    private const val MAX_LINES = 30

    private val recent = ArrayDeque<String>()

    @Synchronized
    fun snapshot(): List<String> = recent.toList()

    /**
     * @param speaker character the line belongs to, blank when unattributed.
     */
    @Synchronized
    fun remember(en: String, speaker: String = "") {
        val line = en.trim()
        if (line.isBlank()) return
        val who = speaker.trim()
        recent.addLast(if (who.isEmpty()) "— ${line.take(110)}" else "${who.take(24)}: ${line.take(110)}")
        while (recent.size > MAX_LINES) recent.removeFirst()
    }

    @Synchronized
    fun reset() = recent.clear()
}
