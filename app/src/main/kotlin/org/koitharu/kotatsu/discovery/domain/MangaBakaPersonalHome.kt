package org.koitharu.kotatsu.discovery.domain

enum class MangaBakaExploreType(val apiValue: String) {
	MANGA("manga"),
	NOVEL("novel"),
	MANHWA("manhwa"),
	MANHUA("manhua"),
	OEL("oel"),
	OTHER("other"),
	;

	fun isVisibleFor(filter: CatalogFilter): Boolean = when (filter) {
		CatalogFilter.ALL -> true
		CatalogFilter.MANGA -> this != NOVEL
		CatalogFilter.NOVEL -> this == NOVEL
	}
}

data class MangaBakaLibraryItem(
	val item: TrackerCatalogItem,
	val tracking: CatalogTrackingEntry,
)

data class MangaBakaLibraryPage(
	val items: List<MangaBakaLibraryItem>,
	val nextPage: Int? = null,
)

data class MangaBakaPersonalHomeSnapshot(
	val library: List<MangaBakaLibraryItem> = emptyList(),
	val recommendations: List<TrackerCatalogItem> = emptyList(),
	val partial: Boolean = false,
)

data class MangaBakaPersonalHomeState(
	val loading: Boolean = false,
	val library: List<MangaBakaLibraryItem> = emptyList(),
	val recommendations: List<TrackerCatalogItem> = emptyList(),
	val partial: Boolean = false,
	val failed: Boolean = false,
	val authExpired: Boolean = false,
	val selectedType: MangaBakaExploreType? = null,
	val exploreItems: List<TrackerCatalogItem> = emptyList(),
	val exploreLoading: Boolean = false,
	val exploreFailed: Boolean = false,
)
