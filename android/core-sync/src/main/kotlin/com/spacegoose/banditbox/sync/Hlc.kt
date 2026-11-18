package com.spacegoose.banditbox.sync

/**
 * Hybrid logical clock: `<13-digit Unix ms>-<5-digit counter>`, always increasing even if the
 * wall clock steps back, so a TV's ops sort in the order they were made.
 */
class Hlc(private val now: () -> Long = System::currentTimeMillis, start: String? = null) {
    private var ms: Long = 0
    private var counter: Int = 0

    init {
        start?.let(::parse)?.let { (m, c) -> ms = m; counter = c }
    }

    @Synchronized
    fun next(): String {
        val t = now()
        if (t > ms) {
            ms = t
            counter = 0
        } else {
            counter++
            if (counter > 99_999) {
                ms++
                counter = 0
            }
        }
        return format(ms, counter)
    }

    /** The last value handed out, to persist and pass back as `start`. */
    @Synchronized
    fun last(): String = format(ms, counter)

    companion object {
        fun format(ms: Long, counter: Int) = "%013d-%05d".format(ms, counter)

        fun parse(s: String): Pair<Long, Int>? {
            val (a, b) = s.split('-').takeIf { it.size == 2 } ?: return null
            return (a.toLongOrNull() ?: return null) to (b.toIntOrNull() ?: return null)
        }
    }
}
