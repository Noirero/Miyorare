package org.koitharu.kotatsu.details.ui.pager.pages

/** Presentation only: chapter identities and page order remain owned by ChaptersLoader. */
internal fun reconcileExpandedChapters(expanded: Set<Long>, valid: Set<Long>, current: Long?, previous: Long?): Set<Long> =
    expanded.intersect(valid).let { retained ->
        if (current != null && current in valid && current != previous) retained + current else retained
    }
