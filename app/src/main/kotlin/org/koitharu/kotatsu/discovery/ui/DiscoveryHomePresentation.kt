package org.koitharu.kotatsu.discovery.ui

import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.discovery.domain.CatalogCachePolicy
import org.koitharu.kotatsu.discovery.domain.CatalogMediaKind
import org.koitharu.kotatsu.discovery.domain.CatalogSectionKey
import org.koitharu.kotatsu.discovery.domain.CatalogSectionType
import org.koitharu.kotatsu.discovery.domain.DiscoveryHubState
import org.koitharu.kotatsu.discovery.domain.TrackerCatalogItem
import org.koitharu.kotatsu.discovery.domain.TrackerCatalogSection
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService

enum class DiscoveryHomeStatus {
	LOADING,
	CACHED_REFRESHING,
	CACHED_ERROR,
	PARTIAL,
	FULL_ERROR,
	EMPTY,
	FRESH,
}

data class DiscoverySpotlight(
	val section: TrackerCatalogSection,
	val item: TrackerCatalogItem,
)

fun DiscoveryHubState.homeStatus(): DiscoveryHomeStatus {
	val sections = home?.sections.orEmpty()
	val hasRows = sections.any { it.items.isNotEmpty() }
	return when {
		loading && !hasRows -> DiscoveryHomeStatus.LOADING
		refreshing && hasRows && stale -> DiscoveryHomeStatus.CACHED_REFRESHING
		failure != null && hasRows -> DiscoveryHomeStatus.CACHED_ERROR
		failure != null -> DiscoveryHomeStatus.FULL_ERROR
		sections.any { it.failure != null } -> DiscoveryHomeStatus.PARTIAL
		!hasRows -> DiscoveryHomeStatus.EMPTY
		else -> DiscoveryHomeStatus.FRESH
	}
}

/** A single timestamp is truthful only for a completely successful Home snapshot. */
fun DiscoveryHubState.confirmedHomeSnapshotAt(now: Long): Long? =
    home?.savedAt?.takeIf {
        homeStatus() == DiscoveryHomeStatus.FRESH &&
            CatalogCachePolicy.isReadable(it, now) &&
            home?.sections?.all { section -> section.failure == null } == true
    }

/** Filtered provider samples are not evidence that their entire catalogues are empty. */
fun catalogEmptyMessageId(key: CatalogSectionKey): Int = when {
    key.service == ScrobblerService.KITSU &&
        key.kind == CatalogMediaKind.NOVEL &&
        key.type == CatalogSectionType.TRENDING -> R.string.hub_kitsu_trending_novel_empty
    key.service == ScrobblerService.MAL &&
        key.kind == CatalogMediaKind.NOVEL &&
        key.type in setOf(CatalogSectionType.POPULAR, CatalogSectionType.MOST_FAVORITED) ->
        R.string.hub_mal_novel_ranking_empty
    else -> R.string.hub_empty
}

fun selectDiscoverySpotlight(sections: List<TrackerCatalogSection>): DiscoverySpotlight? {
	val section = sections.firstOrNull {
		it.key.type == CatalogSectionType.TRENDING && it.items.isNotEmpty()
	} ?: sections.firstOrNull { it.items.isNotEmpty() }
	return section?.items?.firstOrNull()?.let { DiscoverySpotlight(section, it) }
}

/**
 * Presentation-only Spotlight dedupe. The provider/cache/section data remains untouched and See All
 * continues to receive the complete repository section. Keep the item when removal would leave a
 * one-card rail, which is more useful than an artificially sparse presentation.
 */
fun visibleDiscoveryRailItems(
	section: TrackerCatalogSection,
	spotlight: DiscoverySpotlight?,
): List<TrackerCatalogItem> {
	if (spotlight == null || spotlight.section.key != section.key || spotlight.item.key !in section.items.map { it.key }) {
		return section.items
	}
	if (section.items.size <= 2) return section.items
	return section.items.filterNot { it.key == spotlight.item.key }
}

/** Keeps truthful ordinal identity after presentation-only Spotlight dedupe. */
fun discoverySectionOrdinal(section: TrackerCatalogSection, item: TrackerCatalogItem): Int? {
	if (section.key.type != CatalogSectionType.TOP_RANKED) return null
	val index = section.items.indexOfFirst { it.key == item.key }
	return if (index >= 0) index + 1 else null
}
