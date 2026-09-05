package org.koitharu.kotatsu.favourites.ui.container

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
import org.koitharu.kotatsu.favourites.domain.DOWNLOADED_FAVOURITES_CATEGORY_ID
import org.koitharu.kotatsu.favourites.domain.DOWNLOADED_FAVOURITES_CATEGORY_TITLE
import org.koitharu.kotatsu.favourites.domain.DownloadedContentClassifier
import org.koitharu.kotatsu.favourites.domain.FavouriteContentType
import org.koitharu.kotatsu.favourites.domain.FavouriteContentTypeStore
import org.koitharu.kotatsu.favourites.domain.FavouriteDisplayPreferences
import org.koitharu.kotatsu.favourites.domain.FavouritesRepository
import org.koitharu.kotatsu.favourites.domain.FavouritesSearchMatcher
import org.koitharu.kotatsu.favourites.domain.FavouritesSearchRepository
import org.koitharu.kotatsu.favourites.domain.LOCAL_FAVOURITES_CATEGORY_ID
import org.koitharu.kotatsu.favourites.domain.LOCAL_FAVOURITES_CATEGORY_TITLE
import org.koitharu.kotatsu.favourites.domain.debounceFavouritesSearch
import org.koitharu.kotatsu.favourites.ui.list.FavouritesListFragment.Companion.NO_ID
import org.koitharu.kotatsu.local.data.LocalFavouritesRepository
import org.koitharu.kotatsu.parsers.model.Manga
import javax.inject.Inject

@HiltViewModel
class FavouritesContainerViewModel @Inject constructor(
	private val settings: AppSettings,
	private val favouritesRepository: FavouritesRepository,
	private val searchMatcher: FavouritesSearchMatcher,
	private val searchRepository: FavouritesSearchRepository,
	private val contentTypeStore: FavouriteContentTypeStore,
	private val localFavouritesRepository: LocalFavouritesRepository,
	private val displayPreferences: FavouriteDisplayPreferences,
	private val downloadedContentClassifier: DownloadedContentClassifier,
) : BaseViewModel() {
	init {
		// Populate the virtual Local shelf and its badge even when its page has not been created yet.
		launchJob(Dispatchers.IO) {
			localFavouritesRepository.ensureInitialized()
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
		favouritesRepository.observeFavouritesChanges(),
		favouritesRepository.observeDownloadedChanges(),
	)
		.onEach { searchRepository.invalidate() }

	private val categoriesStateFlow = favouritesRepository.observeCategoriesForLibrary()
		.withErrorHandling()
		// A category sort-order change only changes the manga order inside that page. Do not rebuild
		// every tab/count for it; the page ViewModel observes the order itself and refreshes immediately.
		.distinctUntilChanged { old, new ->
			old.size == new.size && old.indices.all { index ->
				old[index].id == new[index].id && old[index].title == new[index].title
			}
		}
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, null)

	private val contentTypeState = combine(
		contentTypeStore.selectedType,
		contentTypeStore.novelCategoryIds,
		localFavouritesRepository.items,
	) { type, _, localManga ->
		ContentTypeState(
			type = type,
			localManga = localManga,
		)
	}

	/**
	 * Category structure is deliberately independent from favourite-count calculation. With very large
	 * libraries the user should see the category tabs as soon as the tiny category query finishes; badge
	 * numbers can arrive afterwards as a count-only update.
	 */
	private val categoryStructure = combine(
		categoriesStateFlow.filterNotNull(),
		observeAllFavouritesVisibility(),
		contentTypeStore.selectedType,
		contentTypeStore.novelCategoryIds,
	) { list, showAll, type, novelCategoryIds ->
		CategoryStructure(
			type = type,
			categories = list.filter { category ->
				val isNovel = category.id in novelCategoryIds
				if (type == FavouriteContentType.NOVEL) isNovel else !isNovel
			},
			showAll = showAll,
			includeLocal = type != FavouriteContentType.NOVEL,
		)
	}.distinctUntilChanged()
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, null)

	/**
	 * Build a cheap request first, then do the expensive scan in mapLatest. A new debounced query or DB
	 * invalidation cancels the old calculation instead of making the user wait for stale 10k-30k scans.
	 */
	private val countRequests = combine(
		categoriesStateFlow.filterNotNull(),
		favouritesChanges,
		contentTypeState,
		searchQuery,
	) { list, _, state, query ->
		CountRequest(
			categories = list.filter { contentTypeStore.isCategoryForType(it.id, state.type) },
			state = state,
			query = query,
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
		)
		val remote = calculateRemoteCounts(typedCategories, state.type, query)
		val downloadedCount = calculateDownloadedCount(state.type, query)
		val localCount = if (state.type == FavouriteContentType.NOVEL) {
			0
		} else if (query.isBlank()) {
			state.localManga.size
		} else {
			searchMatcher.filter(state.localManga, query).size
		}
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
	) { structure, snapshot, query ->
		val expectedKey = CountKey(
			type = structure.type,
			query = query,
			categoryIds = structure.categories.map { it.id },
		)
		val counts = snapshot.takeIf { it.key == expectedKey }
		structure.categories.toUi(
			showAll = structure.showAll,
			allCount = counts?.allCount ?: 0,
			counts = counts?.counts.orEmpty(),
			includeLocal = structure.includeLocal,
			localCount = counts?.localCount ?: 0,
			downloadedCount = counts?.downloadedCount ?: 0,
		)
	}.withErrorHandling()
		// Badge-only updates are handled directly by FavouritesContainerAdapter and no longer cause
		// TabLayoutMediator to rebuild all tabs.
		.distinctUntilChanged()
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, emptyList())

	val isEmpty = categories.map { it.isEmpty() }
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, false)

	private suspend fun calculateRemoteCounts(
		typedCategories: List<FavouriteCategory>,
		type: FavouriteContentType,
		query: String,
	): RemoteCounts {
		val categoryIds = typedCategories.mapTo(HashSet(typedCategories.size)) { it.id }

		if (query.isBlank()) {
			if (categoryIds.isEmpty()) return RemoteCounts(0, emptyMap())
			val counts = favouritesRepository.getCategoryCounts(categoryIds)
			return RemoteCounts(favouritesRepository.getDistinctMangaCount(categoryIds), counts)
		}

		val memberships = searchRepository.getMemberships()
		val counts = HashMap<Long, Int>(typedCategories.size)
		val visibleMatchingIds = HashSet<Long>()
		val wantNovel = type == FavouriteContentType.NOVEL
		// Searching category counts no longer loads every full Manga + tags. A single lightweight
		// projection supplies just id/title/author/source, while memberships supply category ids.
		val sourceTypeCache = HashMap<String, Boolean>()
		val searchable = searchRepository.getEntries().filter { entry ->
			sourceTypeCache.getOrPut(entry.source) { MangaSource(entry.source).isNovelSource } == wantNovel
		}
		val matchingIds = searchMatcher.matchingIds(searchable, query)
		for ((index, membership) in memberships.withIndex()) {
			if ((index and CANCELLATION_CHECK_MASK) == 0) currentCoroutineContext().ensureActive()
			if (membership.mangaId !in matchingIds || membership.categoryId !in categoryIds) continue
			visibleMatchingIds += membership.mangaId
			counts[membership.categoryId] = (counts[membership.categoryId] ?: 0) + 1
		}
		return RemoteCounts(visibleMatchingIds.size, counts)
	}

	private fun List<FavouriteCategory>.toUi(
		showAll: Boolean,
		allCount: Int,
		counts: Map<Long, Int>,
		includeLocal: Boolean,
		localCount: Int,
		downloadedCount: Int,
	): List<FavouriteTabModel> {
		val result = ArrayList<FavouriteTabModel>(
			size + (if (showAll) 1 else 0) + (if (includeLocal) 1 else 0) + 1,
		)
		if (showAll) result.add(FavouriteTabModel(NO_ID, null, allCount))
		result.add(
			FavouriteTabModel(
				DOWNLOADED_FAVOURITES_CATEGORY_ID,
				DOWNLOADED_FAVOURITES_CATEGORY_TITLE,
				downloadedCount,
			),
		)
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
		if (query.isBlank()) {
			val countsBySource = favouritesRepository.getDownloadedCountsBySource()
			var total = 0
			var localTotal = 0
			for (count in countsBySource) {
				val source = MangaSource(count.source)
				if (source.isLocal) {
					localTotal += count.itemCount
				} else if (source.isNovelSource == wantNovel) {
					total += count.itemCount
				}
			}
			if (localTotal == 0) return total

			val localNovelIds = downloadedContentClassifier.getLocalNovelIds()
			val localNovelCount = favouritesRepository.getDownloadedEntries().count { entry ->
				MangaSource(entry.source).isLocal && entry.mangaId in localNovelIds
			}
			total += if (wantNovel) {
				localNovelCount
			} else {
				(localTotal - localNovelCount).coerceAtLeast(0)
			}
			return total
		}

		val localNovelIds = downloadedContentClassifier.getLocalNovelIds()
		val entries = favouritesRepository.getDownloadedEntries().filter { entry ->
			val source = MangaSource(entry.source)
			val isNovel = if (source.isLocal) {
				entry.mangaId in localNovelIds
			} else {
				source.isNovelSource
			}
			isNovel == wantNovel
		}
		return searchMatcher.matchingIds(entries, query).size
	}

	fun hide(categoryId: Long) {
		if (categoryId == LOCAL_FAVOURITES_CATEGORY_ID || categoryId == DOWNLOADED_FAVOURITES_CATEGORY_ID) return
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
		if (categoryId == LOCAL_FAVOURITES_CATEGORY_ID || categoryId == DOWNLOADED_FAVOURITES_CATEGORY_ID) return
		launchJob(Dispatchers.Default) {
			favouritesRepository.removeCategories(setOf(categoryId))
			contentTypeStore.removeCategories(setOf(categoryId))
		}
	}

	private fun observeAllFavouritesVisibility() = settings.observeAsFlow(
		key = AppSettings.KEY_ALL_FAVOURITES_VISIBLE,
		valueProducer = { isAllFavouritesVisible },
	)

	private data class ContentTypeState(
		val type: FavouriteContentType,
		val localManga: List<Manga>,
	)

	private data class CategoryStructure(
		val type: FavouriteContentType,
		val categories: List<FavouriteCategory>,
		val showAll: Boolean,
		val includeLocal: Boolean,
	)

	private data class CountRequest(
		val categories: List<FavouriteCategory>,
		val state: ContentTypeState,
		val query: String,
	)

	private data class CountKey(
		val type: FavouriteContentType,
		val query: String,
		val categoryIds: List<Long>,
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

	private companion object {
		const val CANCELLATION_CHECK_MASK = 0xFF
	}
}
