package org.koitharu.kotatsu.reader.ui.epub.translation

/**
 * Page translation is anchored to immutable source offsets, never to a rendered page index.
 * Only a short boundary extension is allowed: reflow and font changes must not cause a whole
 * paragraph (or chapter) to be submitted for a single-page request.
 */
internal object NovelTranslationPageScope {
    private const val WORD_BOUNDARY_LOOKAROUND = 96

    fun expandToWordBoundaries(source: CharSequence, visible: IntRange): IntRange? {
        if (source.isEmpty()) return null
        var start = visible.first.coerceIn(0, source.lastIndex)
        var end = visible.last.coerceIn(start, source.lastIndex)
        var moved = 0
        while (start > 0 && moved < WORD_BOUNDARY_LOOKAROUND &&
            !source[start - 1].isWhitespace() && !source[start].isWhitespace()
        ) {
            start--
            moved++
        }
        moved = 0
        while (end < source.lastIndex && moved < WORD_BOUNDARY_LOOKAROUND &&
            !source[end].isWhitespace() && !source[end + 1].isWhitespace()
        ) {
            end++
            moved++
        }
        return start..end
    }

    /** Keep the first newly translated readable content in view after pagination changes. */
    fun readingAnchor(source: CharSequence, uncovered: List<IntRange>, fallback: Int): Int {
        if (source.isEmpty()) return 0
        var firstNonWhitespace: Int? = null
        for (range in uncovered) {
            val start = range.first.coerceIn(0, source.lastIndex)
            val end = range.last.coerceIn(start, source.lastIndex)
            for (offset in start..end) {
                val char = source[offset]
                if (char.isLetterOrDigit()) return offset
                if (firstNonWhitespace == null && !char.isWhitespace()) firstNonWhitespace = offset
            }
        }
        return firstNonWhitespace ?: fallback.coerceIn(0, source.lastIndex)
    }

    /** Return the exact source spans not already translated, without evicting older page results. */
    fun uncovered(requested: IntRange, translated: List<IntRange>): List<IntRange> {
        if (requested.isEmpty()) return emptyList()
        val result = ArrayList<IntRange>()
        var cursor = requested.first
        translated.asSequence()
            .filterNot { it.isEmpty() || it.last < requested.first || it.first > requested.last }
            .sortedBy { it.first }
            .forEach { covered ->
                if (cursor > requested.last) return@forEach
                val beforeEnd = minOf(requested.last, covered.first - 1)
                if (cursor <= beforeEnd) result += cursor..beforeEnd
                cursor = maxOf(cursor, covered.last + 1)
            }
        if (cursor <= requested.last) result += cursor..requested.last
        return result
    }
}
