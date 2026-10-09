package org.koitharu.kotatsu.local.library

/** Reference-counted pins for seekable Smart Local cache files. */
internal class MaterializedCachePins {
    private val counts = HashMap<String, Int>()

    fun acquire(name: String) {
        counts[name] = (counts[name] ?: 0) + 1
    }

    fun release(name: String) {
        val count = counts[name] ?: return
        if (count <= 1) counts.remove(name) else counts[name] = count - 1
    }

    fun isPinned(name: String): Boolean = (counts[name] ?: 0) > 0
}
