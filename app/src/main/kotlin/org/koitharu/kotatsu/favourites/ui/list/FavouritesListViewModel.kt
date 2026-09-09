package org.koitharu.kotatsu.favourites.ui.list

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.FavouriteCategory
import org.koitharu.kotatsu.core.model.LocalMangaSource
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.core.model.getLanguageCode
import org.koitharu.kotatsu.core.model.isLocal
import org.koitharu.kotatsu.core.model.isNovelContent
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.ListMode
import org.koitharu.kotatsu.core.prefs.MiyorareDesignStyle
import org.koitharu.kotatsu.core.prefs.observeAsFlow
import org.koitharu.kotatsu.core.ui.util.ReversibleAction
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.core.util.ext.flattenLatest
import org.koitharu.kotatsu.details.data.DetailsNavigationCache
import org.koitharu.kotatsu.favourites.data.EXTRA_FAVOURITE_SPACE
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.favourites.domain.DOWNLOADED_FAVOURITES_CATEGORY_ID
import org.koitharu.kotatsu.favourites.domain.DownloadedFavouritesSortPreferences
import org.koitharu.kotatsu.favourites.domain.FavouriteContentType
import org.koitharu.kotatsu.favourites.domain.FavouriteContentTypeStore
import org.koitharu.kotatsu.favourites.domain.FavouriteDisplayPreferences
import org.koitharu.kotatsu.favourites.domain.FavouriteSourceFilterStore
import org.koitharu.kotatsu.favourites.domain.FavouriteUnreadCounter
import org.koitharu.kotatsu.favourites.domain.FavoritesListQuickFilter
import org.koitharu.kotatsu.favourites.domain.FavouritesRepository
import org.koitharu.kotatsu.favourites.domain.FavouritesSearchMatcher
import org.koitharu.kotatsu.favourites.domain.LOCAL_FAVOURITES_CATEGORY_ID
import org.koitharu.kotatsu.favourites.domain.PRIVATE_COMPLETED_CATEGORY_ID
import org.koitharu.kotatsu.favourites.domain.PRIVATE_IN_PROGRESS_CATEGORY_ID
import org.koitharu.kotatsu.favourites.domain.debounceFavouritesSearch
import org.koitharu.kotatsu.favourites.groups.domain.LibraryGroup
import org.koitharu.kotatsu.favourites.groups.domain.LibraryGroupAddResult
import org.koitharu.kotatsu.favourites.groups.domain.LibraryGroupsRepository
import org.koitharu.kotatsu.favourites.groups.ui.LibraryGroupGridModel
import org.koitharu.kotatsu.favourites.groups.ui.LibraryGroupListModel
import org.koitharu.kotatsu.favourites.groups.ui.LibraryGroupManageItem
import org.koitharu.kotatsu.favourites.ui.container.FavouritesContainerFragment
import org.koitharu.kotatsu.favourites.ui.list.FavouritesListFragment.Companion.NO_ID
import org.koitharu.kotatsu.history.domain.MarkAsReadUseCase
import org.koitharu.kotatsu.list.domain.ListFilterOption
import org.koitharu.kotatsu.list.domain.ListSortOrder
import org.koitharu.kotatsu.list.domain.MangaListMapper
import org.koitharu.kotatsu.list.domain.QuickFilterListener
import org.koitharu.kotatsu.list.ui.MangaListViewModel
import org.koitharu.kotatsu.list.ui.model.EmptyState
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.LoadingState
import org.koitharu.kotatsu.list.ui.model.MangaCompactListModel
import org.koitharu.kotatsu.list.ui.model.MangaDetailedListModel
import org.koitharu.kotatsu.list.ui.model.MangaGridModel
import org.koitharu.kotatsu.list.ui.model.MangaListModel
import org.koitharu.kotatsu.list.ui.model.TIP_UI_SCALING
import org.koitharu.kotatsu.list.ui.model.toErrorState
import org.koitharu.kotatsu.list.ui.model.uiScalingTip
import org.koitharu.kotatsu.local.data.LocalStorageChanges
import org.koitharu.kotatsu.local.data.index.LocalMangaIndex
import org.koitharu.kotatsu.local.domain.model.LocalManga
import org.koitharu.kotatsu.parsers.model.Manga
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

private const val PAGE_SIZE = 16
private const val DATABASE_WINDOW_INITIAL = PAGE_SIZE * 4
private const val DATABASE_WINDOW_MAX = 4096
private const val GROUP_PIN_NAMESPACE = 1L shl 61
private const val PRIVATE_PIN_NAMESPACE = 1L shl 62

private fun mergeSourceFilters(
	localFilters: Set<ListFilterOption>,
	type: FavouriteContentType,
	sourceSelections: Map<FavouriteContentType, Set<String>>,
): Set<ListFilterOption> = buildSet {
	addAll(localFilters.filterNot { it is ListFilterOption.Source })
	sourceSelections[type].orEmpty().mapTo(this) { sourceName ->
		ListFilterOption.Source(MangaSource(sourceName))
	}
}

@HiltViewModel
class FavouritesListViewModel @Inject constructor(
	savedStateHandle: SavedStateHandle,
	private val repository: FavouritesRepository,
	private val mangaListMapper: MangaListMapper,
	private val markAsReadUseCase: MarkAsReadUseCase,
	quickFilterFactory: FavoritesListQuickFilter.Factory,
	private val settings: AppSettings,
	private val mangaDataRepository: MangaDataRepository,
	@LocalStorageChanges localStorageChanges: SharedFlow<LocalManga?>,
	private val searchMatcher: FavouritesSearchMatcher,
	private val contentTypeStore: FavouriteContentTypeStore,
	private val displayPreferences: FavouriteDisplayPreferences,
	private val localMangaIndex: LocalMangaIndex,
	private val unreadCounter: FavouriteUnreadCounter,
	private val sourceFilterStore: FavouriteSourceFilterStore,
	private val detailsNavigationCache: DetailsNavigationCache,
	private val downloadedSortPreferences: DownloadedFavouritesSortPreferences,
	private val libraryGroupsRepository: LibraryGroupsRepository,
) : MangaListViewModel(settings, mangaDataRepository, localStorageChanges), QuickFilterListener {

	val categoryId: Long = savedStateHandle[AppRouter.KEY_ID] ?: NO_ID
	val favouriteSpace: FavouriteSpace = FavouriteSpace.fromArgument(
		savedStateHandle[EXTRA_FAVOURITE_SPACE] ?: FavouriteSpace.NORMAL.dbValue,
	)
	private val isLocalShelf = categoryId == LOCAL_FAVOURITES_CATEGORY_ID
	private val pinnedPreferenceId = if (favouriteSpace == FavouriteSpace.PRIVATE) {
		// Category ids are database Ints (plus two Long virtual ids), so bit 62 is a safe namespace
		// that cannot collide with Normal pin keys. Long.MIN_VALUE was unsuitable because Private Local
		// would map to key 0 and collide with Normal "All".
		categoryId xor PRIVATE_PIN_NAMESPACE
	} else {
		categoryId
	}
	private val groupPinnedPreferenceId = GROUP_PIN_NAMESPACE xor if (favouriteSpace == FavouriteSpace.PRIVATE) {
		PRIVATE_PIN_NAMESPACE
	} else {
		0L
	}
	private val quickFilter = quickFilterFactory.create(categoryId, favouriteSpace)
	private val sourceFilterState = sourceFilterStore.state(favouriteSpace)
	private val refreshTrigger = MutableStateFlow(Any())
	private val limit = MutableStateFlow(PAGE_SIZE)
	private val databaseWindow = MutableStateFlow(DATABASE_WINDOW_INITIAL)
	private val fromBottom = MutableStateFlow(false)
	private val isPaginationReady = AtomicBoolean(false)
	private var detailsPrefetchJob: Job? = null
	private var lastSortOrder: ListSortOrder? = null
	private var lastFilters: Set<ListFilterOption>? = null
	private var lastContentType: FavouriteContentType? = null
	private var lastSearchQuery = FavouritesContainerFragment.searchQuery.value.trim()

	private val libraryGroups = libraryGroupsRepository.observeGroups(favouriteSpace).stateIn(
		viewModelScope + Dispatchers.Default,
		SharingStarted.Eagerly,
		emptyList(),
	)

	init {
		viewModelScope.launch(Dispatchers.Default) {
			libraryGroupsRepository.repairInvalidGroups(favouriteSpace)
		}
	}

	private val searchQuery = FavouritesContainerFragment.searchQuery
		.debounceFavouritesSearch()
		.onEach { query ->
			if (query != lastSearchQuery) {
				lastSearchQuery = query
				limit.value = PAGE_SIZE
				databaseWindow.value = DATABASE_WINDOW_INITIAL
			}
		}
		.stateIn(
			viewModelScope + Dispatchers.Default,
			SharingStarted.Eagerly,
			lastSearchQuery,
		)

	private val activeDisplayOptions = combine(
		contentTypeStore.selectedType,
		displayPreferences.state,
	) { type, state -> state.getValue(type) }.distinctUntilChanged()

	private val displayState = combine(
		searchQuery,
		contentTypeStore.selectedType,
		limit,
		displayPreferences.state,
		fromBottom,
	) { query, type, pageLimit, preferences, bottom ->
		DisplayState(query, type, pageLimit, preferences.getValue(type), bottom)
	}

	private val effectiveFilters = combine(
		quickFilter.appliedOptions,
		contentTypeStore.selectedType,
		sourceFilterState,
	) { localFilters, type, sourceSelections ->
		mergeSourceFilters(
			localFilters = localFilters,
			type = type,
			sourceSelections = if (isLocalShelf) emptyMap() else sourceSelections,
		)
	}.stateIn(
		viewModelScope + Dispatchers.Default,
		SharingStarted.Eagerly,
		mergeSourceFilters(
			localFilters = quickFilter.appliedOptions.value,
			type = contentTypeStore.selectedType.value,
			sourceSelections = if (isLocalShelf) emptyMap() else sourceFilterState.value,
		),
	)

	override val listMode: StateFlow<ListMode> = activeDisplayOptions
		.map { it.listMode }
		.stateIn(
			viewModelScope + Dispatchers.Default,
			SharingStarted.Eagerly,
			displayPreferences.current(contentTypeStore.selectedType.value).listMode,
		)

	override val gridScale: StateFlow<Float> = activeDisplayOptions
		.map { it.gridSize / 100f }
		.stateIn(
			viewModelScope + Dispatchers.Default,
			SharingStarted.Eagerly,
			displayPreferences.current(contentTypeStore.selectedType.value).gridSize / 100f,
		)

	override val gridColumns: StateFlow<Int?> = activeDisplayOptions
		.map { it.gridColumns as Int? }
		.stateIn(
			viewModelScope + Dispatchers.Default,
			SharingStarted.Eagerly,
			displayPreferences.current(contentTypeStore.selectedType.value).gridColumns,
		)

	val sortOrder: StateFlow<ListSortOrder?> = when (categoryId) {
		DOWNLOADED_FAVOURITES_CATEGORY_ID,
		LOCAL_FAVOURITES_CATEGORY_ID,
		-> downloadedSortPreferences.state
		NO_ID, PRIVATE_IN_PROGRESS_CATEGORY_ID, PRIVATE_COMPLETED_CATEGORY_ID ->
			settings.observeAsFlow(AppSettings.KEY_FAVORITES_ORDER) { allFavoritesSortOrder }
		else -> repository.observeCategory(categoryId, favouriteSpace).withErrorHandling().map { it?.order }
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, null)

	val pinnedIds: StateFlow<List<Long>> = settings.observeAsFlow(
		AppSettings.KEY_FAVORITES_PINNED + pinnedPreferenceId,
	) { getPinnedFavourites(pinnedPreferenceId) }.stateIn(
		viewModelScope + Dispatchers.Default,
		SharingStarted.Eagerly,
		settings.getPinnedFavourites(pinnedPreferenceId),
	)

	val pinnedGroupIds: StateFlow<List<Long>> = settings.observeAsFlow(
		AppSettings.KEY_FAVORITES_PINNED + groupPinnedPreferenceId,
	) { getPinnedFavourites(groupPinnedPreferenceId) }.stateIn(
		viewModelScope + Dispatchers.Default,
		SharingStarted.Eagerly,
		settings.getPinnedFavourites(groupPinnedPreferenceId),
	)

	val isLibraryGroupingAvailable: Boolean
		get() = settings.miyorareDesignStyle == MiyorareDesignStyle.MODERN &&
			contentTypeStore.selectedType.value == FavouriteContentType.MANGA &&
			categoryId != DOWNLOADED_FAVOURITES_CATEGORY_ID &&
			categoryId != LOCAL_FAVOURITES_CATEGORY_ID

	val hasLibraryGroups: Boolean
		get() = libraryGroups.value.isNotEmpty()

	override val content = combine(
		combine(observeFavorites(), libraryGroups, pinnedGroupIds) { items, groups, groupPins ->
			Triple(items, groups, groupPins)
		},
		observeListModeWithTriggers(),
		combine(
			refreshTrigger,
			settings.observeAsFlow(AppSettings.KEY_TIPS_CLOSED) { isTipEnabled(TIP_UI_SCALING) },
		) { _, visible -> visible },
		pinnedIds,
		displayState,
	) { listGroupsAndPins, _, scalingTip, pinned, display ->
		val (list, allGroups, groupPins) = listGroupsAndPins
		val filters = effectiveFilters.value
		val wantNovel = display.type == FavouriteContentType.NOVEL
		val categoryGroups = groupsForCurrentCategory(allGroups)
		val activeGroups = if (isLibraryGroupingAvailable) {
			if (ListFilterOption.SFW in filters) categoryGroups.filterNot { it.containsNsfw } else categoryGroups
		} else {
			emptyList()
		}
		val currentWindow = databaseWindow.value
		val windowed = if (currentWindow == Int.MAX_VALUE || list.size <= currentWindow) {
			list
		} else {
			list.take(currentWindow)
		}
		val candidates = if (display.fromBottom) windowed.asReversed() else windowed
		val typed = candidates.filter { manga -> manga.isNovelContent == wantNovel }
		val searched = searchWithLibraryGroups(typed, display.query, activeGroups)
		maybeExpandDatabaseWindow(
			loadedCount = candidates.size,
			matchingCount = searched.size,
			targetCount = display.limit,
		)
		val visible = searched.take(display.limit)
		visible.mapList(
			display.options.listMode,
			filters,
			pinned.takeIfDefaultState(filters),
			scalingTip,
			display.query.isNotBlank(),
			display.options,
			activeGroups,
			groupPins,
		)
	}.distinctUntilChanged().onEach {
		isPaginationReady.set(true)
	}.catch {
		emit(listOf(it.toErrorState(canRetry = false)))
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, listOf(LoadingState))

	override fun onRefresh() {
		refreshTrigger.value = Any()
	}

	override fun onRetry() = Unit

	override fun setFilterOption(option: ListFilterOption, isApplied: Boolean) {
		if (option is ListFilterOption.Source) {
			if (isLocalShelf) return
			sourceFilterStore.set(
				contentTypeStore.selectedType.value,
				option.mangaSource.name,
				isApplied,
				favouriteSpace,
			)
		} else {
			quickFilter.setFilterOption(option, isApplied)
		}
	}

	override fun toggleFilterOption(option: ListFilterOption) {
		if (option is ListFilterOption.Source) {
			if (isLocalShelf) return
			val type = contentTypeStore.selectedType.value
			val isSelected = option.mangaSource.name in sourceFilterState.value[type].orEmpty()
			sourceFilterStore.set(type, option.mangaSource.name, !isSelected, favouriteSpace)
		} else {
			quickFilter.toggleFilterOption(option)
		}
	}

	override fun clearFilter() {
		quickFilter.clearFilter()
		if (!isLocalShelf) sourceFilterStore.clear(contentTypeStore.selectedType.value, favouriteSpace)
	}

	fun dismissScalingTip() {
		settings.closeTip(TIP_UI_SCALING)
	}

	suspend fun createLibraryGroup(title: String, mangaIds: Collection<Long>): Long = withContext(Dispatchers.Default) {
		libraryGroupsRepository.createGroup(title = title, mangaIds = mangaIds, space = favouriteSpace)
	}

	fun getLibraryGroupsForAdd(): List<LibraryGroup> {
		val pinOrder = pinnedGroupIds.value.withIndex().associate { (index, id) -> id to index }
		return libraryGroups.value.sortedWith(
			compareBy<LibraryGroup> { pinOrder[it.id] ?: Int.MAX_VALUE }
				.thenBy { it.title.lowercase() }
				.thenBy { it.id },
		)
	}

	fun getLibraryGroupConflicts(targetGroupId: Long, mangaIds: Set<Long>): List<LibraryGroup> =
		libraryGroups.value.filter { group ->
			group.id != targetGroupId && group.memberIds.any { it in mangaIds }
		}

	suspend fun addToLibraryGroup(
		groupId: Long,
		mangaIds: Collection<Long>,
		moveFromExistingGroups: Boolean,
	): LibraryGroupAddResult = withContext(Dispatchers.Default) {
		libraryGroupsRepository.addMembers(
			groupId = groupId,
			mangaIds = mangaIds,
			moveFromExistingGroups = moveFromExistingGroups,
			space = favouriteSpace,
		)
	}

	suspend fun getLibraryGroupPlacementCategories(): List<FavouriteCategory> = withContext(Dispatchers.Default) {
		repository.observeCategories(favouriteSpace).first()
	}

	suspend fun setLibraryGroupCategories(groupId: Long, categoryIds: Collection<Long>) =
		withContext(Dispatchers.Default) {
			libraryGroupsRepository.replaceCategories(groupId, categoryIds, favouriteSpace)
		}

	suspend fun getLibraryGroupManageItems(groupId: Long): Pair<LibraryGroup, List<LibraryGroupManageItem>>? =
		withContext(Dispatchers.Default) {
			val group = libraryGroupsRepository.getGroup(groupId, favouriteSpace) ?: return@withContext null
			val items = group.members.mapNotNull { member ->
				mangaDataRepository.findMangaById(member.mangaId, withChapters = false)?.let { manga ->
					LibraryGroupManageItem(member, manga)
				}
			}
			group to items
		}

	suspend fun updateLibraryGroup(groupId: Long, title: String, coverUrl: String?) = withContext(Dispatchers.Default) {
		libraryGroupsRepository.updateGroup(groupId, title, coverUrl, favouriteSpace)
	}

	suspend fun deleteLibraryGroup(groupId: Long) = withContext(Dispatchers.Default) {
		libraryGroupsRepository.deleteGroup(groupId, favouriteSpace)
	}

	suspend fun removeLibraryGroupMember(groupId: Long, mangaId: Long) = withContext(Dispatchers.Default) {
		libraryGroupsRepository.removeMember(groupId, mangaId, favouriteSpace)
	}

	suspend fun reorderLibraryGroup(groupId: Long, orderedMangaIds: List<Long>) = withContext(Dispatchers.Default) {
		libraryGroupsRepository.reorder(groupId, orderedMangaIds, favouriteSpace)
	}

	suspend fun getAllSelectableIds(): Set<Long> = withContext(Dispatchers.Default) {
		val order = sortOrder.filterNotNull().first()
		val filters = systemShelfFilters(effectiveFilters.combineWithSettings().first())
		val allItems = when (categoryId) {
			DOWNLOADED_FAVOURITES_CATEGORY_ID -> repository.observeDownloaded(
				order = order,
				filterOptions = filters,
				limit = Int.MAX_VALUE,
				space = favouriteSpace,
			).first()
			LOCAL_FAVOURITES_CATEGORY_ID -> repository.observeAll(
				order = order,
				filterOptions = localShelfFilters(filters),
				limit = Int.MAX_VALUE,
				space = favouriteSpace,
			).first()
			NO_ID, PRIVATE_IN_PROGRESS_CATEGORY_ID, PRIVATE_COMPLETED_CATEGORY_ID -> repository.observeAll(
				order = order,
				filterOptions = filters,
				limit = Int.MAX_VALUE,
				space = favouriteSpace,
			).first()
			else -> repository.observeAll(
				categoryId = categoryId,
				order = order,
				filterOptions = filters,
				limit = Int.MAX_VALUE,
				space = favouriteSpace,
			).first()
		}
		val wantNovel = contentTypeStore.selectedType.value == FavouriteContentType.NOVEL
		val typed = allItems.filter { manga -> isNovelContent(manga) == wantNovel }
		val matched = searchWithLibraryGroups(typed, searchQuery.value, activeGroupsFor(filters))
		val hiddenGroupMembers = activeGroupsFor(filters).flatMapTo(HashSet()) { it.memberIds }
		matched.mapTo(LinkedHashSet(matched.size)) { it.id }.apply {
			removeAll(hiddenGroupMembers)
		}
	}

	fun markAsRead(items: Set<Manga>) {
		launchLoadingJob(Dispatchers.Default) {
			markAsReadUseCase(items)
			onRefresh()
		}
	}

	fun removeFromFavourites(ids: Set<Long>) {
		if (ids.isEmpty()) return
		launchJob(Dispatchers.Default) {
			val handle = if (
				categoryId == NO_ID ||
				categoryId == DOWNLOADED_FAVOURITES_CATEGORY_ID ||
				categoryId == LOCAL_FAVOURITES_CATEGORY_ID ||
				categoryId == PRIVATE_IN_PROGRESS_CATEGORY_ID ||
				categoryId == PRIVATE_COMPLETED_CATEGORY_ID
			) {
				repository.removeFromFavourites(ids, favouriteSpace)
			} else {
				repository.removeFromCategory(categoryId, ids)
			}
			libraryGroupsRepository.repairInvalidGroups(favouriteSpace)
			onActionDone.call(ReversibleAction(R.string.removed_from_favourites, handle))
		}
	}

	fun requestMoreItems() {
		if (!isPaginationReady.compareAndSet(true, false)) return
		val nextLimit = limit.value + PAGE_SIZE
		limit.value = nextLimit
		val preferredWindow = (nextLimit * 4).coerceAtMost(DATABASE_WINDOW_MAX)
		if (databaseWindow.value < preferredWindow) {
			databaseWindow.value = preferredWindow
		}
	}

	fun requestBottomPage(): Boolean {
		if (fromBottom.value) return false
		isPaginationReady.set(false)
		limit.value = PAGE_SIZE
		databaseWindow.value = DATABASE_WINDOW_INITIAL
		fromBottom.value = true
		return true
	}

	fun requestTopPage(): Boolean {
		if (!fromBottom.value) return false
		isPaginationReady.set(false)
		limit.value = PAGE_SIZE
		databaseWindow.value = DATABASE_WINDOW_INITIAL
		fromBottom.value = false
		return true
	}

	private fun prefetchDetailsSnapshots(
		items: List<Manga>,
		cardSnapshot: FavouriteUnreadCounter.Snapshot,
	) {
		detailsNavigationCache.updateHistory(items.map { it.id }, cardSnapshot::getHistory)
		val missing = items.filterNot { detailsNavigationCache.contains(it.id) }
		if (missing.isEmpty()) return
		detailsPrefetchJob?.cancel()
		detailsPrefetchJob = viewModelScope.launch(Dispatchers.Default) {
			val snapshots = if (
				categoryId == DOWNLOADED_FAVOURITES_CATEGORY_ID ||
				categoryId == LOCAL_FAVOURITES_CATEGORY_ID
			) {
				missing.map { item ->
					val localChapters = localMangaIndex.get(item.id, withDetails = true)?.manga?.chapters
					if (localChapters.isNullOrEmpty()) item else item.copy(chapters = localChapters)
				}
			} else {
				mangaDataRepository.attachCachedChapters(missing)
			}
			detailsNavigationCache.putAll(snapshots, cardSnapshot::getHistory)
		}
	}

	private suspend fun searchWithLibraryGroups(
		items: List<Manga>,
		query: String,
		groups: List<LibraryGroup>,
	): List<Manga> {
		val searched = searchMatcher.filter(items, query)
		if (query.isBlank() || groups.isEmpty()) return searched
		val byId = items.associateBy { it.id }
		val seen = searched.mapTo(HashSet(searched.size)) { it.id }
		val result = ArrayList<Manga>(searched.size + groups.size)
		result += searched
		for (group in groups) {
			if (!group.title.contains(query, ignoreCase = true)) continue
			for (member in group.members) {
				val manga = byId[member.mangaId] ?: continue
				if (seen.add(manga.id)) result += manga
				break
			}
		}
		return result
	}

	private fun groupsForCurrentCategory(groups: List<LibraryGroup>): List<LibraryGroup> {
		if (categoryId == NO_ID) return groups
		return groups.filter { group ->
			group.categoryIds.isEmpty() || categoryId in group.categoryIds
		}
	}

	private fun LibraryGroup.isExplicitlyPlacedHere(): Boolean =
		categoryId != NO_ID && categoryIds.isNotEmpty() && categoryId in categoryIds

	private fun explicitGroupsForRender(
		groups: List<LibraryGroup>,
		filters: Set<ListFilterOption>,
		isSearchActive: Boolean,
	): List<LibraryGroup> {
		if (filters.any { it != ListFilterOption.SFW }) return emptyList()
		val query = searchQuery.value.trim()
		return groups.filter { group ->
			group.isExplicitlyPlacedHere() &&
				(!isSearchActive || group.title.contains(query, ignoreCase = true))
		}
	}

	private fun activeGroupsFor(filters: Set<ListFilterOption>): List<LibraryGroup> {
		if (!isLibraryGroupingAvailable) return emptyList()
		val groups = groupsForCurrentCategory(libraryGroups.value)
		return if (ListFilterOption.SFW in filters) {
			groups.filterNot { it.containsNsfw }
		} else {
			groups
		}
	}

	private suspend fun List<Manga>.mapList(
		mode: ListMode,
		filters: Set<ListFilterOption>,
		pinned: List<Long>,
		isScalingTipVisible: Boolean,
		isSearchActive: Boolean,
		display: FavouriteDisplayPreferences.Options,
		groups: List<LibraryGroup>,
		pinnedGroups: List<Long>,
	): List<ListModel> {
		val explicitGroups = explicitGroupsForRender(groups, filters, isSearchActive)
		val pinnedGroupSet = pinnedGroups.toSet()
		if (isEmpty()) {
			if (explicitGroups.isNotEmpty()) {
				val pinOrder = pinnedGroups.withIndex().associate { (index, id) -> id to index }
				val result = ArrayList<ListModel>(explicitGroups.size + 2)
				if (isScalingTipVisible) result += uiScalingTip
				quickFilter.filterItem(filters)?.let(result::add)
				explicitGroups
					.sortedWith(compareBy<LibraryGroup> { pinOrder[it.id] ?: Int.MAX_VALUE }.thenBy { it.title.lowercase() })
					.mapTo(result) { group -> group.toUiModel(mode, group.id in pinnedGroupSet) }
				return result
			}
			if (isSearchActive) {
				return listOfNotNull(
					quickFilter.filterItem(filters),
					EmptyState(
						icon = R.drawable.ic_empty_favourites,
						textPrimary = R.string.nothing_found,
						textSecondary = R.string.text_empty_holder_secondary_filtered,
						actionStringRes = 0,
					),
				)
			}
			return if (filters.isEmpty()) {
				listOf(getEmptyState(false))
			} else {
				listOfNotNull(quickFilter.filterItem(filters), getEmptyState(true))
			}
		}

		val cardSnapshot = unreadCounter.getSnapshot(
			mangaIds = map { it.id },
			includeUnread = display.showUnread,
		)
		prefetchDetailsSnapshots(takeLast(16), cardSnapshot)
		val result = ArrayList<ListModel>(size + 2)
		if (isScalingTipVisible) result += uiScalingTip
		quickFilter.filterItem(filters)?.let(result::add)
		mangaListMapper.toListModelList(
			destination = result,
			manga = this,
			mode = mode,
			flags = MangaListMapper.NO_FAVORITE or MangaListMapper.NO_PROGRESS or MangaListMapper.NO_COUNTER,
		)
		val pinnedSet = pinned.toSet()
		for (i in result.indices) {
			val model = result[i]
			if (model !is MangaListModel) continue
			val mangaId = model.manga.id
			val isPinned = mangaId in pinnedSet
			val source = model.manga.source
			val isSaved = display.showDownloaded && mangaId in localMangaIndex
			val isLocalSource = display.showLocalSource && source.isLocal
			val languageLabel = if (display.showLanguage) source.getLanguageCode() else null
			val unreadCount = if (display.showUnread) cardSnapshot.unreadCounts[mangaId] ?: 0 else 0
			val hasReadingHistory = display.showContinueReading && cardSnapshot.hasHistory(mangaId)
			val progress = cardSnapshot.getProgress(mangaId, settings.progressIndicatorMode)
			result[i] = when (model) {
				is MangaGridModel -> model.copy(
					counter = unreadCount,
					progress = progress,
					isSaved = isSaved,
					isPinned = isPinned,
					isTitleOverCover = display.titleOverCover,
					isGridSpacingIncreased = display.gridSpacingIncreased,
					isLocalSource = isLocalSource,
					languageLabel = languageLabel,
					showContinueReading = hasReadingHistory,
				)
				is MangaDetailedListModel -> model.copy(
					counter = unreadCount,
					progress = progress,
					isSaved = isSaved,
					isPinned = isPinned,
					isLocalSource = isLocalSource,
					languageLabel = languageLabel,
					showContinueReading = hasReadingHistory,
				)
				is MangaCompactListModel -> model.copy(
					counter = unreadCount,
					isPinned = isPinned,
					isSaved = isSaved,
					isLocalSource = isLocalSource,
					languageLabel = languageLabel,
					showContinueReading = hasReadingHistory,
				)
			}
		}
		return collapseLibraryGroups(
			models = result,
			groups = groups,
			explicitGroups = explicitGroups,
			mode = mode,
			pinnedGroupIds = pinnedGroups,
			elevatePinned = !isSearchActive && filters.all { it == ListFilterOption.SFW },
		)
	}

	private fun collapseLibraryGroups(
		models: List<ListModel>,
		groups: List<LibraryGroup>,
		explicitGroups: List<LibraryGroup>,
		mode: ListMode,
		pinnedGroupIds: List<Long>,
		elevatePinned: Boolean,
	): List<ListModel> {
		if (groups.isEmpty()) return models
		val pinnedSet = pinnedGroupIds.toSet()
		val byMember = HashMap<Long, LibraryGroup>()
		for (group in groups) {
			for (member in group.members) byMember[member.mangaId] = group
		}
		val emitted = HashSet<Long>()
		val result = ArrayList<ListModel>(models.size + explicitGroups.size + pinnedGroupIds.size)
		val firstMangaIndex = models.indexOfFirst { it is MangaListModel }.let { if (it < 0) models.size else it }
		for (index in 0 until firstMangaIndex) result += models[index]

		if (elevatePinned) {
			val groupsById = groups.associateBy { it.id }
			for (groupId in pinnedGroupIds) {
				val group = groupsById[groupId] ?: continue
				if (emitted.add(group.id)) result += group.toUiModel(mode, true)
			}
		}

		for (index in firstMangaIndex until models.size) {
			val model = models[index]
			if (model !is MangaListModel) {
				result += model
				continue
			}
			val group = byMember[model.manga.id]
			if (group == null) {
				result += model
			} else if (emitted.add(group.id)) {
				result += group.toUiModel(mode, group.id in pinnedSet)
			}
		}
		for (group in explicitGroups) {
			if (emitted.add(group.id)) result += group.toUiModel(mode, group.id in pinnedSet)
		}
		return result
	}

	private fun LibraryGroup.toUiModel(mode: ListMode, isPinned: Boolean): ListModel = when (mode) {
		ListMode.COVER_ONLY, ListMode.GRID -> LibraryGroupGridModel(this, isPinned)
		ListMode.LIST, ListMode.DETAILED_LIST -> LibraryGroupListModel(this, isPinned)
	}

	fun setPinned(ids: Set<Long>, isPinned: Boolean) {
		val current = settings.getPinnedFavourites(pinnedPreferenceId)
		val updated = if (isPinned) current + (ids - current.toSet()) else current - ids
		settings.setPinnedFavourites(pinnedPreferenceId, updated)
	}

	fun isLibraryGroupPinned(groupId: Long): Boolean = groupId in pinnedGroupIds.value

	fun setLibraryGroupPinned(groupId: Long, isPinned: Boolean) {
		val current = settings.getPinnedFavourites(groupPinnedPreferenceId)
		val updated = if (isPinned) {
			current + listOf(groupId).filterNot { it in current }
		} else {
			current - groupId
		}
		settings.setPinnedFavourites(groupPinnedPreferenceId, updated)
	}

	private fun observeFavorites() = combine(
		sortOrder.filterNotNull(),
		effectiveFilters.combineWithSettings(),
		combine(pinnedIds, fromBottom) { pinned, bottom -> pinned to bottom },
		databaseWindow,
		contentTypeStore.selectedType,
	) { order, filters, pinnedAndBottom, queryLimit, contentType ->
		val (pinned, bottom) = pinnedAndBottom
		val configurationChanged =
			(lastSortOrder != null && lastSortOrder != order) ||
				(lastFilters != null && lastFilters != filters) ||
				(lastContentType != null && lastContentType != contentType)
		lastSortOrder = order
		lastFilters = filters
		lastContentType = contentType

		val effectiveLimit = if (configurationChanged && queryLimit != Int.MAX_VALUE) {
			limit.value = PAGE_SIZE
			databaseWindow.value = DATABASE_WINDOW_INITIAL
			DATABASE_WINDOW_INITIAL
		} else {
			queryLimit
		}
		isPaginationReady.set(false)
		val categoryFilters = systemShelfFilters(filters)
		val effectivePinned = if (bottom) emptyList() else pinned.takeIfDefaultState(categoryFilters)
		val queryOrder = if (bottom) order.type.toSortOrder(!order.isAscending) else order
		when (categoryId) {
			DOWNLOADED_FAVOURITES_CATEGORY_ID -> repository.observeDownloaded(
				queryOrder,
				categoryFilters,
				effectiveLimit,
				effectivePinned,
				favouriteSpace,
			)
			LOCAL_FAVOURITES_CATEGORY_ID -> repository.observeAll(
				queryOrder,
				localShelfFilters(categoryFilters),
				effectiveLimit,
				effectivePinned,
				favouriteSpace,
			)
			NO_ID, PRIVATE_IN_PROGRESS_CATEGORY_ID, PRIVATE_COMPLETED_CATEGORY_ID -> repository.observeAll(
				queryOrder,
				categoryFilters,
				effectiveLimit,
				effectivePinned,
				favouriteSpace,
			)
			else -> repository.observeAll(
				categoryId,
				queryOrder,
				categoryFilters,
				effectiveLimit,
				effectivePinned,
				favouriteSpace,
			)
		}
	}.flattenLatest()

	private fun systemShelfFilters(filters: Set<ListFilterOption>): Set<ListFilterOption> = when (categoryId) {
		PRIVATE_IN_PROGRESS_CATEGORY_ID -> buildSet {
			addAll(filters.filterNot { it is ListFilterOption.ReadingProgress })
			add(ListFilterOption.ReadingProgress.IN_PROGRESS)
		}
		PRIVATE_COMPLETED_CATEGORY_ID -> buildSet {
			addAll(filters.filterNot { it is ListFilterOption.ReadingProgress })
			add(ListFilterOption.ReadingProgress.COMPLETED)
		}
		else -> filters
	}

	private fun localShelfFilters(filters: Set<ListFilterOption>): Set<ListFilterOption> = buildSet {
		addAll(filters.filterNot { it == ListFilterOption.Downloaded || it is ListFilterOption.Source })
		add(ListFilterOption.Source(LocalMangaSource))
	}

	private fun maybeExpandDatabaseWindow(
		loadedCount: Int,
		matchingCount: Int,
		targetCount: Int,
	) {
		if (matchingCount >= targetCount) return
		val current = databaseWindow.value
		if (loadedCount < current || current == Int.MAX_VALUE) return
		val next = if (current >= DATABASE_WINDOW_MAX) {
			Int.MAX_VALUE
		} else {
			(current * 2).coerceAtMost(DATABASE_WINDOW_MAX)
		}
		if (next != current) databaseWindow.value = next
	}

	private fun List<Long>.takeIfDefaultState(filters: Set<ListFilterOption>): List<Long> =
		if (filters.all { it == ListFilterOption.SFW }) this else emptyList()

	private fun getEmptyState(hasFilters: Boolean) = if (hasFilters) {
		EmptyState(
			icon = R.drawable.ic_empty_favourites,
			textPrimary = R.string.nothing_found,
			textSecondary = R.string.text_empty_holder_secondary_filtered,
			actionStringRes = R.string.reset_filter,
		)
	} else {
		EmptyState(
			icon = R.drawable.ic_empty_favourites,
			textPrimary = R.string.text_empty_holder_primary,
			textSecondary = if (categoryId == NO_ID) R.string.you_have_not_favourites_yet else R.string.favourites_category_empty,
			actionStringRes = 0,
		)
	}

	private data class DisplayState(
		val query: String,
		val type: FavouriteContentType,
		val limit: Int,
		val options: FavouriteDisplayPreferences.Options,
		val fromBottom: Boolean,
	)
}
