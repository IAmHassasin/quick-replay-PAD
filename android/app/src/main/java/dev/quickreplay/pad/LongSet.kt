package dev.quickreplay.pad

/**
 * Set of longs without boxing (open addressing, linear probing): about 16 bytes per entry instead
 * of ~50 for a HashSet<Long>, which matters for the solver's millions of state hashes.
 */
internal class LongSet(initialCapacity: Int = 1 shl 16) {
    private var keys = LongArray(Integer.highestOneBit(maxOf(4, initialCapacity) * 2 - 1))
    private var used = BooleanArray(keys.size)
    var size = 0
        private set

    operator fun contains(key: Long): Boolean {
        var i = slot(key)
        while (used[i]) {
            if (keys[i] == key) return true
            i = (i + 1) and (keys.size - 1)
        }
        return false
    }

    /** Adds [key]; returns false if it was already there. */
    fun add(key: Long): Boolean {
        if ((size + 1) * 2 > keys.size) grow()
        var i = slot(key)
        while (used[i]) {
            if (keys[i] == key) return false
            i = (i + 1) and (keys.size - 1)
        }
        used[i] = true
        keys[i] = key
        size++
        return true
    }

    fun clear() {
        used.fill(false)
        size = 0
    }

    private fun slot(key: Long): Int {
        // Mix the bits (the keys are hashes already, but their low bits may not be).
        var h = key * -0x61c8864680b583ebL
        h = h xor (h ushr 32)
        return h.toInt() and (keys.size - 1)
    }

    private fun grow() {
        val oldKeys = keys
        val oldUsed = used
        keys = LongArray(oldKeys.size * 2)
        used = BooleanArray(keys.size)
        size = 0
        for (i in oldKeys.indices) if (oldUsed[i]) add(oldKeys[i])
    }
}
