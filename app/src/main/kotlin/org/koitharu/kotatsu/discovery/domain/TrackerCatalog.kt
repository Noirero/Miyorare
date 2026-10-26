package org.koitharu.kotatsu.discovery.domain

import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerRelationType
import java.io.IOException

enum class CatalogMediaKind { MANGA, NOVEL, MIXED }
enum class CatalogFilter {
	ALL, MANGA, NOVEL;
	val kinds: List<CatalogMediaKind> get() = when (this) {
		ALL -> listOf(CatalogMediaKind.MANGA, CatalogMediaKind.NOVEL)
		MANGA -> listOf(CatalogMediaKind.MANGA)
		NOVEL -> listOf(CatalogMediaKind.NOVEL)
	}
}

/** Public work identity. It never represents a parser/source manga or a user's tracker entry. */
data class TrackerCatalogKey(val service: ScrobblerService, val kind: CatalogMediaKind, val remoteId: String) {
	init {
		require(kind != CatalogMediaKind.MIXED)
		require(remoteId.toLongOrNull()?.let { it > 0 && it.toString() == remoteId } == true)
	}
	val stableId: String get() = "${service.name}_${kind.name}_$remoteId"
}

data class TrackerCatalogItem(
	val key: TrackerCatalogKey,
	val title: String,
	val coverUrl: String? = null,
	val providerUrl: String? = null,
	val score: Double? = null,
	val adult: Boolean? = null,
	/** Provider-native format/taxonomy, e.g. MangaBaka manga/manhwa/manhua/novel/oel/other. */
	val providerType: String? = null,
) {
	init { require(title.isNotBlank()) }
	fun forPolicy(allowAdult: Boolean): TrackerCatalogItem? = when {
		allowAdult -> this
		adult == true -> null
		adult == null -> copy(coverUrl = null)
		else -> this
	}
}

data class TrackerCatalogPerson(
	val id: String? = null,
	val name: String,
	val imageUrl: String? = null,
	val roles: List<String> = emptyList(),
	val providerUrl: String? = null,
) {
	init { require(name.isNotBlank()) }
}

enum class CatalogRelatedMediaKind { MANGA, NOVEL, ANIME, OTHER }

data class TrackerCatalogRelatedWork(
	val service: ScrobblerService,
	val remoteId: String,
	val title: String,
	val coverUrl: String? = null,
	val providerUrl: String? = null,
	val relation: TrackerRelationType = TrackerRelationType.OTHER,
	val mediaKind: CatalogRelatedMediaKind = CatalogRelatedMediaKind.OTHER,
	val format: String? = null,
	val providerRelation: String? = null,
) {
	init {
		require(remoteId.toLongOrNull()?.let { it > 0 && it.toString() == remoteId } == true)
		require(title.isNotBlank())
	}

	val catalogKey: TrackerCatalogKey?
		get() = when (mediaKind) {
			CatalogRelatedMediaKind.MANGA -> TrackerCatalogKey(service, CatalogMediaKind.MANGA, remoteId)
			CatalogRelatedMediaKind.NOVEL -> TrackerCatalogKey(service, CatalogMediaKind.NOVEL, remoteId)
			CatalogRelatedMediaKind.ANIME, CatalogRelatedMediaKind.OTHER -> null
		}
}

enum class CatalogSectionType {
	RECENT,
	TRENDING,
	POPULAR,
	TOP_RATED,
	TOP_RANKED,
	MOST_FAVORITED,
	RISING,
	HIDDEN_GEMS,
	NEW_RELEASES,
}
data class CatalogSectionKey(val service: ScrobblerService, val kind: CatalogMediaKind, val type: CatalogSectionType)
data class TrackerCatalogPage(val items: List<TrackerCatalogItem>, val nextPage: Int? = null)
data class TrackerCatalogSection(
	val key: CatalogSectionKey,
	val items: List<TrackerCatalogItem>,
	val nextPage: Int? = null,
	val failure: CatalogFailure? = null,
)
data class TrackerCatalogHome(val sections: List<TrackerCatalogSection>, val savedAt: Long = 0)
data class TrackerCatalogDetails(
	val item: TrackerCatalogItem,
	val alternativeTitles: List<String> = emptyList(),
	val description: String? = null,
	val bannerUrl: String? = null,
	val genres: List<String> = emptyList(),
	val staff: List<String> = emptyList(),
	val status: String? = null,
	val startDate: String? = null,
	val rank: Int? = null,
	val popularity: Int? = null,
	val recommendations: List<TrackerCatalogItem> = emptyList(),
	val partial: Boolean = false,
	val characters: List<TrackerCatalogPerson> = emptyList(),
	val staffPeople: List<TrackerCatalogPerson> = emptyList(),
	val relatedWorks: List<TrackerCatalogRelatedWork> = emptyList(),
)

enum class CatalogCapability {
	HOME, SEARCH, DETAILS, SECTION_PAGING, MANGA, NOVEL, STAFF, RECOMMENDATIONS, CHARACTERS, RELATIONS,
}
enum class CatalogFailureKind { OFFLINE, RATE_LIMITED, PROVIDER, INVALID_DATA, UNSUPPORTED }
data class CatalogFailure(val kind: CatalogFailureKind, val retryAt: Long? = null)
class CatalogException(val failure: CatalogFailure, cause: Throwable? = null) : IOException("Catalogue ${failure.kind}", cause)

fun Throwable.catalogFailure(): CatalogFailure = when (this) {
	is CatalogException -> failure
	is IOException -> CatalogFailure(CatalogFailureKind.OFFLINE)
	else -> CatalogFailure(CatalogFailureKind.PROVIDER)
}

/** Read-only catalogue operations, independent of authentication and tracker writes. */
interface TrackerCatalogProvider {
	val catalogService: ScrobblerService
	val catalogCapabilities: Set<CatalogCapability>
	suspend fun home(filter: CatalogFilter, allowAdult: Boolean): TrackerCatalogHome
	suspend fun search(query: String, filter: CatalogFilter, allowAdult: Boolean, page: Int): TrackerCatalogPage
	suspend fun details(key: TrackerCatalogKey, allowAdult: Boolean): TrackerCatalogDetails
	suspend fun browseSection(key: CatalogSectionKey, allowAdult: Boolean, page: Int): TrackerCatalogPage
}

data class CatalogHomeRequest(val service: ScrobblerService, val filter: CatalogFilter, val allowAdult: Boolean)

object CatalogCachePolicy {
	const val FRESH_MS = 15 * 60 * 1000L
	const val MAX_STALE_MS = 24 * 60 * 60 * 1000L
	const val MAX_SNAPSHOTS = 18
	const val MAX_SECTIONS = 8
	const val HOME_PAGE_SIZE = 12
	const val PAGE_SIZE = 20
	const val MAX_PAGE = 100
	const val MAX_CANDIDATES = 100
	fun isFresh(savedAt: Long, now: Long) = savedAt in (now - FRESH_MS)..now
	fun isReadable(savedAt: Long, now: Long) = savedAt in (now - MAX_STALE_MS)..now
}
