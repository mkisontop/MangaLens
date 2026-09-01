package app.mangalens.translate

import org.json.JSONObject

/**
 * Pulls the entries of a reply's `"bubbles"` array out one at a time as the
 * reply's text streams in.
 *
 * A page's answer is a JSON object the model writes over several seconds,
 * and nothing about the first balloon depends on the last. Waiting for the
 * closing brace before painting anything is the single largest delay the
 * reader feels in AI mode; with the array parsed as it arrives, each
 * balloon can be painted the moment its entry closes, and the page fills
 * in the order the model reads it.
 *
 * Parsing is by brace matching with string and escape awareness, so a `}`
 * inside a translated line never closes an entry early. Anything before
 * the array — a markdown fence, prose — is skipped; anything malformed
 * ends the stream quietly, and the caller's full parse of the finished
 * reply decides what the page finally shows.
 */
class BubbleStream(private val key: String = "bubbles") {

    private val buf = StringBuilder()
    private var arrayFound = false
    private var scan = 0
    private var done = false

    /** Appends [delta] and returns every entry that became complete with it. */
    fun feed(delta: String): List<JSONObject> {
        buf.append(delta)
        return drain()
    }

    private fun drain(): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        if (done) return out
        if (!arrayFound) {
            val k = buf.indexOf("\"$key\"")
            if (k < 0) return out
            val colon = buf.indexOf(":", k + key.length + 2)
            if (colon < 0) return out
            val bracket = buf.indexOf("[", colon)
            if (bracket < 0) return out
            for (i in colon + 1 until bracket) {
                if (!buf[i].isWhitespace()) {
                    done = true
                    return out
                }
            }
            arrayFound = true
            scan = bracket + 1
        }
        while (true) {
            while (scan < buf.length && (buf[scan].isWhitespace() || buf[scan] == ',')) scan++
            if (scan >= buf.length) return out
            val c = buf[scan]
            if (c == ']' || c != '{') {
                done = true
                return out
            }
            val end = matchObject(scan) ?: return out
            val text = buf.substring(scan, end + 1)
            scan = end + 1
            runCatching { JSONObject(text) }.getOrNull()?.let { out.add(it) }
        }
    }

    /** Index of the brace closing the object opening at [start], or null if it is still incomplete. */
    private fun matchObject(start: Int): Int? {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until buf.length) {
            val c = buf[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return null
    }
}
