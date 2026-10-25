package org.koitharu.kotatsu.discovery.ui

import org.koitharu.kotatsu.discovery.domain.CatalogSectionType
import org.koitharu.kotatsu.discovery.domain.DiscoveryHubState
import org.koitharu.kotatsu.discovery.domain.TrackerCatalogItem
import org.koitharu.kotatsu.discovery.domain.TrackerCatalogSection

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
		refreshing && hasRows -> DiscoveryHomeStatus.CACHED_REFRESHING
		failure != null && hasRows -> DiscoveryHomeStatus.CACHED_ERROR
		failure != null -> DiscoveryHomeStatus.FULL_ERROR
		sections.any { it.failure != null } -> DiscoveryHomeStatus.PARTIAL
		!hasRows -> DiscoveryHomeStatus.EMPTY
		else -> DiscoveryHomeStatus.FRESH
	}
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
