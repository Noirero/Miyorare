package org.koitharu.kotatsu.reader.ui.epub

internal fun mapEpubContentsOffsets(markers: List<Pair<String, Int>>, trimmedStart: Int, textLength: Int): List<Pair<String, Int>> =
    markers.map { (title, offset) -> title to (offset - trimmedStart).coerceIn(0, (textLength - 1).coerceAtLeast(0)) }
        .distinct().sortedBy { it.second }
