package org.koitharu.kotatsu.favourites.ui.list

import org.koitharu.kotatsu.core.prefs.ProgressIndicatorMode
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.favourites.domain.FavouriteContentType

/** A cached card projection is reusable only within this exact library presentation scope. */
internal data class CardMetadataContext(
    val space: FavouriteSpace,
    val categoryId: Long,
    val selectedCategoryIds: List<Long>?,
    val contentType: FavouriteContentType,
    val progressMode: ProgressIndicatorMode,
    val includeUnread: Boolean,
    val includeDownloaded: Boolean,
    val similarTitleScan: Boolean,
)

/** Revision is deliberately not a presentation eligibility criterion: old valid metadata can display during refresh. */
internal fun reusableCardMetadataIds(
    cachedContext: CardMetadataContext?,
    requestedContext: CardMetadataContext,
    cachedIds: Collection<Long>,
    requestedIds: Collection<Long>,
    cachedNovelIds: Set<Long>,
    requestedNovelIds: Set<Long>,
): Set<Long> {
    if (cachedContext != requestedContext) return emptySet()
    val available = cachedIds.toHashSet()
    return requestedIds.filterTo(HashSet()) { id ->
        id in available && (id in cachedNovelIds) == (id in requestedNovelIds)
    }
}

/**
 * Distinct instances identify distinct requests even when visible IDs/context return to the
 * same metadata key (A -> B -> A). Do not use data-class equality for async ownership.
 */
internal class CardEnrichmentRequest<K>(val key: K)

/** A cancelled or superseded asynchronous query cannot publish, even if it returned data. */
internal fun canPublishCardEnrichment(
    requestedRevision: Long,
    currentRevision: Long,
    stillPending: Boolean,
    jobActive: Boolean,
): Boolean = requestedRevision == currentRevision && stillPending && jobActive
