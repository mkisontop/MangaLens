package app.mangalens.translate

/**
 * Small LRU cache so re-reading, scrolling back, or peeking never re-translates
 * (or re-bills) the same bubble twice.
 */
class TranslationCache(private val maxEntries: Int = 1500) {

    private val map = object : LinkedHashMap<String, String>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > maxEntries
    }

    @Synchronized
    fun get(key: String): String? = map[key]

    @Synchronized
    fun put(key: String, value: String) {
        map[key] = value
    }

    companion object {
        fun key(namespace: String, lang: String, text: String) =
            namespace + "|" + lang + "|" + text.replace(Regex("\\s+"), "")
    }
}
