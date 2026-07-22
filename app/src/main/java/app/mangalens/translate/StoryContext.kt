package app.mangalens.translate

import java.util.ArrayDeque

/**
 * Rolling window of recently translated lines, shared by the text and vision
 * AI engines so the story stays coherent across pages no matter which path
 * translated the previous screen.
 */
object StoryContext {

    private val recent = ArrayDeque<String>()

    @Synchronized
    fun snapshot(): List<String> = recent.toList()

    @Synchronized
    fun remember(src: String, en: String) {
        if (src.isBlank() || en.isBlank()) return
        recent.addLast(src.take(60) + " => " + en.take(80))
        while (recent.size > 24) recent.removeFirst()
    }

    @Synchronized
    fun reset() = recent.clear()
}
