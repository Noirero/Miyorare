package org.koitharu.kotatsu.favourites.ui.container

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.FavouriteCategory
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.core.model.isLocal
import org.koitharu.kotatsu.core.model.isNovelSource
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.observeAsFlow
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.ui.util.ReversibleAction
import org.koitharu.kotatsu.core.ui.util.ReversibleHandle
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.favourites.data.EXTRA_FAVOURITE_SPACE
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.favourites.domain.DOWNLOADED_FAVOURITES_CATEGORY_ID
import org.koitharu.kotatsu.favourites.domain.DOWNLOADED_FAVOURITES_CATEGORY_TITLE
import org.koitharu.kotatsu.favourites.domain.DownloadedContentClassifier
import org.koitharu.kotatsu.favourites.domain.FavouriteContentType
import org.koitharu.kotatsu.favourites.domain.FavouriteContentTypeStore
import org.koitharu.kotatsu.favourites.domain.FavouriteDisplayPreferences
import org.koitharu.kotatsu.favourites.domain.FavouriteQuickFilterStore
import org.koitharu.kotatsu.favourites.domain.FavouritesRepository
import org.koitharu.kotatsu.favourites.domain.FavouritesSearchMatcher
import org.koitharu.kotatsu.favourites.domain.FavouritesSearchRepository
import org.koitharu.kotatsu.favourites.domain.LOCAL_FAVOURITES_CATEGORY_ID
import org.koitharu.kotatsu.favourites.domain.LOCAL_FAVOURITES_CATEGORY_TITLE
import org.koitharu.kotatsu.favourites.domain.PRIVATE_COMPLETED_CATEGORY_ID
import org.koitharu.kotatsu.favourites.domain.PRIVATE_COMPLETED_CATEGORY_TITLE
import org.koitharu.kotatsu.favourites.domain.PRIVATE_IN_PROGRESS_CATEGORY_ID
import org.koitharu.kotatsu.favourites.domain.PRIVATE_IN_PROGRESS_CATEGORY_TITLE
import org.koitharu.kotatsu.favourites.domain.debounceFavouritesSearch
import org.koitharu.kotatsu.favourites.ui.list.FavouritesListFragment.Companion.NO_ID
import org.koitharu.kotatsu.list.domain.ListFilterOption
import org.koitharu.kotatsu.local.data.LocalFavouritesRepository
import org.koitharu.kotatsu.local.data.index.LocalMangaIndex
import org.koitharu.kotatsu.parsers.model.Manga
import javax.inject.Inject

@HiltViewModel
class FavouritesContainerViewModel @Inject constructor(
	savedStateHandle: SavedStateHandle,
	private val settings: AppSettings,
	private val favouritesRepository: FavouritesRepository,
	private val searchMatcher: FavouritesSearchMatcher,
	private val searchRepository: FavouritesSearchRepository,
	private val contentTypeStore: FavouriteContentTypeStore,
	private val localFavouritesRepository: LocalFavouritesRepository,
	private val displayPreferences: FavouriteDisplayPreferences,
	private val downloadedContentClassifier: DownloadedContentClassifier,
	private val filterStore: FavouriteQuickFilterStore,
) : BaseViewModel() {

	val favouriteSpace: FavouriteSpace = FavouriteSpace.fromArgument(
		savedStateHandle[EXTRA_FAVOURITE_SPACE] ?: FavouriteSpace.NORMAL.dbValue,
	)

	init {
		launchJob(Dispatchers.IO) {
			localFavouritesRepository.ensureInitialized(favouriteSpace)
		}
	}

	val onActionDone = MutableEventFlow<ReversibleAction>()

	private val searchQuery = FavouritesContainerFragment.searchQuery
		.debounceFavouritesSearch()
		.stateIn(
			viewModelScope + Dispatchers.Default,
			SharingStarted.Eagerly,
			FavouritesContainerFragment.searchQuery.value.trim(),
		)

	private val favouritesChanges = merge(
		favouritesRepository.observeFavouritesChanges(favouriteSpace),
		favouritesRepository.observeDownloadedChanges(),
		LocalMangaIndex.rebuildEvents,
	)
		.onEach { searchRepository.invalidate(favouriteSpace) }

	private val categoriesStateFlow = favouritesRepository.observeCategoriesForLibrary(favouriteSpace)
		.withErrorHandling()
		.distinctUntilChanged { old, new ->
			old.size == new.size && old.indices.all { index ->
				old[index].id == new[index].id && old[index].title == new[index].title
			}
		}
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, null)

	private val localItemsForCounts: Flow<List<Manga>> = localFavouritesRepository.items(favouriteSpace)

	private val contentTypeState = combine(
		contentTypeStore.selectedType(favouriteSpace),
		contentTypeStore.novelCategoryIds,
		localItemsForCounts,
	) { type, _, localManga ->
		ContentTypeState(
			type = type,
			localManga = localManga,
		)
	}

	private val categoryStructure = combine(
		categoriesStateFlow.filterNotNull(),
		observeAllFavouritesVisibility(),
		contentTypeStore.selectedType(favouriteSpace),
		contentTypeStore.novelCategoryIds,
		displayPreferences.observeHiddenVirtualCategoryIds(favouriteSpace),
	) { list, showAll, type, novelCategoryIds, hiddenVirtualCategoryIds ->
		CategoryStructure(
			type = type,
			categories = list.filter { category ->
				val isNovel = category.id in novelCategoryIds
				if (type == FavouriteContentType.NOVEL) isNovel else !isNovel
			},
			showAll = showAll,
			includeDownloaded = DOWNLOADED_FAVOURITES_CATEGORY_ID !in hiddenVirtualCategoryIds,
			includeLocal = type != FavouriteContentType.NOVEL &&
				LOCAL_FAVOURITES_CATEGORY_ID !in hiddenVirtualCategoryIds,
			includePrivateInProgress = favouriteSpace == FavouriteSpace.PRIVATE &&
				PRIVATE_IN_PROGRESS_CATEGORY_ID !in hiddenVirtualCategoryIds,
			includePrivateCompleted = favouriteSpace == FavouriteSpace.PRIVATE &&
				PRIVATE_COMPLETED_CATEGORY_ID !in hiddenVirtualCategoryIds,
		)
	}.distinctUntilChanged()
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, null)

	private val countRequests = combine(
		categoriesStateFlow.filterNotNull(),
		favouritesChanges,
		contentTypeState,
		searchQuery,
		filterStore.state(favouriteSpace),
	) { list, _, state, query, filterSnapshot ->
		CountRequest(
			categories = list.filter { contentTypeStore.isCategoryForType(it.id, state.type) },
			state = state,
			query = query,
			downloadStatus = filterSnapshot.filtersFor(state.type).downloadStatus(),
		)
	}

	private val countState = countRequests.mapLatest { request ->
		val typedCategories = request.categories
		val state = request.state
		val query = request.query
		val key = CountKey(
			type = state.type,
			query = query,
			categoryIds = typedCategories.map { it.id },
			downloadStatus = request.downloadStatus,
		)
		val remote = calculateRemoteCounts(typedCategories, state.type, query, request.downloadStatus)
		val downloadedCount = calculateDownloadedCount(state.type, query)
		val localCount = calculateLocalCount(state, query)
		CountSnapshot(
			key = key,
			allCount = remote.allCount,
			counts = remote.counts,
			localCount = localCount,
			downloadedCount = downloadedCount,
		)
	}.withErrorHandling()
		.distinctUntilChanged()
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, CountSnapshot.EMPTY)

	val categories = combine(
		categoryStructure.filterNotNull(),
		countState,
		searchQuery,
		filterStore.state(favouriteSpace),
	) { structure, snapshot, query, filterSnapshot ->
		val expectedKey = CountKey(
			type = structure.type,
			query = query,
			categoryIds = structure.categories.map { it.id },
			downloadStatus = filterSnapshot.filtersFor(structure.type).downloadStatus(),
		)
		val counts = snapshot.takeIf { it.key == expectedKey }
		structure.categories.toUi(
			showAll = structure.showAll,
			allCount = counts?.allCount ?: 0,
			counts = counts?.counts.orEmpty(),
			includeDownloaded = structure.includeDownloaded,
			includeLocal = structure.includeLocal,
			includePrivateInProgress = structure.includePrivateInProgress,
			includePrivateCompleted = structure.includePrivateCompleted,
			localCount = counts?.localCount ?: 0,
			downloadedCount = counts?.downloadedCount ?: 0,
		)
	}.withErrorHandling()
		.distinctUntilChanged()
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, emptyList())

	val isEmpty = categories.map { it.isEmpty() }
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, false)

	private suspend fun calculateRemoteCounts(
		typedCategories: List<FavouriteCategory>,
		type: FavouriteContentType,
		query: String,
		downloadStatus: DownloadStatus?,
	): RemoteCounts {
		val categoryIds = typedCategories.mapTo(HashSet(typedCategories.size)) { it.id }
		if (categoryIds.isEmpty()) return RemoteCounts(0, emptyMap())

		val memberships = searchRepository.getMemberships(favouriteSpace)
		val sourceCache = HashMap<String, MangaSource>()
		val remoteMemberships = memberships.filter { membership ->
			!sourceCache.getOrPut(membership.source) { MangaSource(membership.source) }.isLocal
		}

		// Keep the existing cheap SQL count only when there are no Local rows to exclude.
		if (downloadStatus == null && query.isBlank() && remoteMemberships.size == memberships.size) {
			val counts = favouritesRepository.getCategoryCounts(categoryIds, favouriteSpace)
			return RemoteCounts(favouritesRepository.getDistinctMangaCount(categoryIds, favouriteSpace), counts)
		}

		val matchingIds = if (query.isBlank()) {
			null
		} else {
			val wantNovel = type == FavouriteContentType.NOVEL
			val searchable = searchRepository.getEntries(favouriteSpace).filter { entry ->
				val source = sourceCache.getOrPut(entry.source) { MangaSource(entry.source) }
				!source.isLocal && source.isNovelSource == wantNovel
			}
			searchMatcher.matchingIds(searchable, query)
		}
		val candidateIds = remoteMemberships.asSequence()
			.filter { it.categoryId in categoryIds }
			.map { it.mangaId }
			.filter { matchingIds == null || it in matchingIds }
			.toCollection(LinkedHashSet())
		val downloadedIds = if (downloadStatus != null) {
			downloadedContentClassifier.getKnownDownloadedIds(favouriteSpace, candidateIds)
		} else {
			emptySet()
		}

		val counts = HashMap<Long, Int>(typedCategories.size)
		val visibleMatchingIds = HashSet<Long>()
		val countedMemberships = HashSet<Pair<Long, Long>>()
		for ((index, membership) in remoteMemberships.withIndex()) {
			if ((index and CANCELLATION_CHECK_MASK) == 0) currentCoroutineContext().ensureActive()
			if (membership.categoryId !in categoryIds) continue
			val mangaId = membership.mangaId
			if (matchingIds != null && mangaId !in matchingIds) continue
			if (downloadStatus != null) {
				val isDownloaded = mangaId in downloadedIds
				if ((downloadStatus == DownloadStatus.DOWNLOADED) != isDownloaded) continue
			}
			visibleMatchingIds += mangaId
			if (countedMemberships.add(membership.categoryId to mangaId)) {
				counts[membership.categoryId] = (counts[membership.categoryId] ?: 0) + 1
			}
		}
		return RemoteCounts(visibleMatchingIds.size, counts)
	}

	private suspend fun calculateLocalCount(state: ContentTypeState, query: String): Int {
		if (state.type == FavouriteContentType.NOVEL) return 0
		// Local is the filesystem view. A source-backed download remains visible here even after its
		// canonical remote identity has been proven; only the ordinary favourite shelves hide LOCAL rows.
		return if (query.isBlank()) {
			state.localManga.size
		} else {
			searchMatcher.filter(state.localManga, query).size
		}
	}

	private fun List<FavouriteCategory>.toUi(
		showAll: Boolean,
		allCount: Int,
		counts: Map<Long, Int>,
		includeDownloaded: Boolean,
		includeLocal: Boolean,
		includePrivateInProgress: Boolean,
		includePrivateCompleted: Boolean,
		localCount: Int,
		downloadedCount: Int,
	): List<FavouriteTabModel> {
		val result = ArrayList<FavouriteTabModel>(
			size + (if (showAll) 1 else 0) + (if (includeDownloaded) 1 else 0) +
				(if (includeLocal) 1 else 0) + (if (includePrivateInProgress) 1 else 0) +
				(if (includePrivateCompleted) 1 else 0),
		)
		if (showAll) result.add(FavouriteTabModel(NO_ID, null, allCount))
		if (includePrivateInProgress) {
			result.add(FavouriteTabModel(PRIVATE_IN_PROGRESS_CATEGORY_ID, PRIVATE_IN_PROGRESS_CATEGORY_TITLE, 0))
		}
		if (includePrivateCompleted) {
			result.add(FavouriteTabModel(PRIVATE_COMPLETED_CATEGORY_ID, PRIVATE_COMPLETED_CATEGORY_TITLE, 0))
		}
		if (includeDownloaded) {
			result.add(
				FavouriteTabModel(
					DOWNLOADED_FAVOURITES_CATEGORY_ID,
					DOWNLOADED_FAVOURITES_CATEGORY_TITLE,
					downloadedCount,
				),
			)
		}
		if (includeLocal) {
			result.add(
				FavouriteTabModel(
					LOCAL_FAVOURITES_CATEGORY_ID,
					LOCAL_FAVOURITES_CATEGORY_TITLE,
					localCount,
				),
			)
		}
		mapTo(result) { FavouriteTabModel(it.id, it.title, counts[it.id] ?: 0) }
		return result
	}

	private suspend fun calculateDownloadedCount(type: FavouriteContentType, query: String): Int {
		val wantNovel = type == FavouriteContentType.NOVEL
		if (favouriteSpace == FavouriteSpace.PRIVATE) {
			val hasCandidate = searchRepository.getEntries(FavouriteSpace.PRIVATE).any { entry ->
				val source = MangaSource(entry.source)
				!source.isLocal && source.isNovelSource == wantNovel
			}
			if (!hasCandidate) return 0
		}
		if (query.isBlank()) {
			return favouritesRepository.getDownloadedCountsBySource(favouriteSpace).sumOf { count ->
				val source = MangaSource(count.source)
				if (!source.isLocal && source.isNovelSource == wantNovel) count.itemCount else 0
			}
		}

		val entries = favouritesRepository.getDownloadedEntries(favouriteSpace).filter { entry ->
			val source = MangaSource(entry.source)
			!source.isLocal && source.isNovelSource == wantNovel
		}
		return searchMatcher.matchingIds(entries, query).size
	}

	fun hide(categoryId: Long) {
		if (categoryId == LOCAL_FAVOURITES_CATEGORY_ID ||
			categoryId == DOWNLOADED_FAVOURITES_CATEGORY_ID ||
			categoryId == PRIVATE_IN_PROGRESS_CATEGORY_ID ||
			categoryId == PRIVATE_COMPLETED_CATEGORY_ID
		) {
			launchJob(Dispatchers.Default) {
				displayPreferences.setVirtualCategoryVisible(favouriteSpace, categoryId, false)
				val reverse = ReversibleHandle {
					displayPreferences.setVirtualCategoryVisible(favouriteSpace, categoryId, true)
				}
				onActionDone.call(ReversibleAction(R.string.category_hidden_done, reverse))
			}
			return
		}
		launchJob(Dispatchers.Default) {
			if (categoryId == NO_ID) {
				settings.isAllFavouritesVisible = false
			} else {
				favouritesRepository.updateCategory(categoryId, isVisibleInLibrary = false)
				val reverse = ReversibleHandle {
					favouritesRepository.updateCategory(categoryId, isVisibleInLibrary = true)
				}
				onActionDone.call(ReversibleAction(R.string.category_hidden_done, reverse))
			}
		}
	}

	fun deleteCategory(categoryId: Long) {
		if (categoryId == LOCAL_FAVOURITES_CATEGORY_ID ||
			categoryId == DOWNLOADED_FAVOURITES_CATEGORY_ID ||
			categoryId == PRIVATE_IN_PROGRESS_CATEGORY_ID ||
			categoryId == PRIVATE_COMPLETED_CATEGORY_ID
		) return
		launchJob(Dispatchers.Default) {
			favouritesRepository.removeCategories(setOf(categoryId))
			contentTypeStore.removeCategories(setOf(categoryId))
		}
	}

	private fun observeAllFavouritesVisibility() = settings.observeAsFlow(
		key = AppSettings.KEY_ALL_FAVOURITES_VISIBLE,
		valueProducer = { isAllFavouritesVisible },
	)

	private fun Set<ListFilterOption>.downloadStatus(): DownloadStatus? = when {
		ListFilterOption.Downloaded in this -> DownloadStatus.DOWNLOADED
		ListFilterOption.NOT_DOWNLOADED in this -> DownloadStatus.NOT_DOWNLOADED
		else -> null
	}

	private data class ContentTypeState(
		val type: FavouriteContentType,
		val localManga: List<Manga>,
	)

	private data class CategoryStructure(
		val type: FavouriteContentType,
		val categories: List<FavouriteCategory>,
		val showAll: Boolean,
		val includeDownloaded: Boolean,
		val includeLocal: Boolean,
		val includePrivateInProgress: Boolean,
		val includePrivateCompleted: Boolean,
	)

	private data class CountRequest(
		val categories: List<FavouriteCategory>,
		val state: ContentTypeState,
		val query: String,
		val downloadStatus: DownloadStatus?,
	)

	private data class CountKey(
		val type: FavouriteContentType,
		val query: String,
		val categoryIds: List<Long>,
		val downloadStatus: DownloadStatus?,
	)

	private data class CountSnapshot(
		val key: CountKey?,
		val allCount: Int,
		val counts: Map<Long, Int>,
		val localCount: Int,
		val downloadedCount: Int,
	) {
		companion object {
			val EMPTY = CountSnapshot(null, 0, emptyMap(), 0, 0)
		}
	}

	private data class RemoteCounts(
		val allCount: Int,
		val counts: Map<Long, Int>,
	)

	private enum class DownloadStatus {
		DOWNLOADED,
		NOT_DOWNLOADED,
	}

	private companion object {
		const val CANCELLATION_CHECK_MASK = 0xFF
	}
}
