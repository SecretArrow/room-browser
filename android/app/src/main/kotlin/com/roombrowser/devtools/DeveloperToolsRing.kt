package com.roombrowser.devtools

/**
 * A bounded buffer that drops its oldest entry — the console and network feeds.
 *
 * The cap is a property of the panel, not a preference: it is the number the
 * panel shows, and [dropped] is what lets the panel say that older entries were
 * dropped rather than implying the list is the whole story. A page can log
 * without limit, so an unbounded list is not an option on a phone.
 *
 * Not thread-safe. Both the sink that writes and the panel that reads run on
 * the main thread.
 */
internal class DeveloperToolsRing<T>(val capacity: Int) {

    init {
        require(capacity > 0) { "a ring has to hold something" }
    }

    private val items = ArrayDeque<T>()

    var dropped: Int = 0
        private set

    fun add(item: T) {
        if (items.size == capacity) {
            items.removeFirst()
            dropped++
        }
        items.addLast(item)
    }

    fun snapshot(): List<T> = items.toList()

    fun clear() {
        items.clear()
        dropped = 0
    }
}
