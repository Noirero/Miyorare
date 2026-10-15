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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.getLanguageCode
import org.koitharu.kotatsu.core.model.isNovelContent
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.ListMode
import org.koitharu.kotatsu.core.prefs.MiyorareDesignStyle
import org.koitharu.kotatsu.core.prefs.observeAsFlow
import org.koitharu.kotatsu.details.data.DetailsNavigationCache
import org.koitharu.kotatsu.favourites.data.EXTRA_FAVOURITE_SPACE
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.favourites.domain.FavouriteContentTypeStore
import org.koitharu.kotatsu.favourites.domain.FavouriteDisplayPreferences
import org.koitharu.kotatsu.favourites.domain.FavouriteUnreadCounter
import org.koitharu.kotatsu.favourites.domain.FavouritesRepository
import org.koitharu.kotatsu.favourites.domain.FavouritesSearchMatcher
import org.koitharu.kotatsu.favourites.domain.LOCAL_FAVOURITES_CATEGORY_ID
import org.koitharu.kotatsu.favourites.domain.debounceFavouritesSearch
import org.koitharu.kotatsu.favourites.ui.container.FavouritesContainerFragment
import org.koitharu.kotatsu.list.domain.MangaListMapper
import org.koitharu.kotatsu.list.ui.MangaListViewModel
import org.koitharu.kotatsu.list.ui.model.EmptyState
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.LoadingState
import org.koitharu.kotatsu.list.ui.model.MangaCompactListModel
import org.koitharu.kotatsu.list.ui.model.MangaDetailedListModel
import org.koitharu.kotatsu.list.ui.model.MangaGridModel
import org.koitharu.kotatsu.list.ui.model.MangaListModel
import org.koitharu.kotatsu.local.data.LocalFavouritesRepository
import org.koitharu.kotatsu.local.data.LocalMangaRepository
import org.koitharu.kotatsu.local.data.LocalStorageChanges
import org.koitharu.kotatsu.local.domain.model.LocalManga
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import javax.inject.Inject

private const val LOCAL_SEARCH_PAGE_SIZE = 16
private const val PRIVATE_LOCAL_PIN_NAMESPACE = 1L shl 62

@HiltViewModel
class LocalFavouritesListViewModel @Inject constructor(
	savedStateHandle: SavedStateHandle,
	private val settings: AppSettings,
	mangaDataRepository: MangaDataRepository,
	@LocalStorageChanges private val localStorageChanges: SharedFlow<LocalManga?>,
	private val localFavouritesRepository: LocalFavouritesRepository,
	private val localMangaRepository: LocalMangaRepository,
	private val mangaListMapper: MangaListMapper,
	private val searchMatcher: FavouritesSearchMatcher,
	private val detailsNavigationCache: DetailsNavigationCache,
	private val contentTypeStore: FavouriteContentTypeStore,
	private val displayPreferences: FavouriteDisplayPreferences,
	private val unreadCounter: FavouriteUnreadCounter,
	private val favouritesRepository: FavouritesRepository,
) : MangaListViewModel(settings, mangaDataRepository, localStorageChanges) {

	private val favouriteSpace = FavouriteSpace.fromArgument(
		savedStateHandle[EXTRA_FAVOURITE_SPACE] ?: FavouriteSpace.NORMAL.dbValue,
	)
	private val pinnedPreferenceId = if (favouriteSpace == FavouriteSpace.PRIVATE) {
		LOCAL_FAVOURITES_CATEGORY_ID xor PRIVATE_LOCAL_PIN_NAMESPACE
	} else {
		LOCAL_FAVOURITES_CATEGORY_ID
	}
	private val limit = MutableStateFlow(LOCAL_SEARCH_PAGE_SIZE)
	private var detailsPrefetchJob: Job? = null
	private var lastSearchQuery = FavouritesContainerFragment.searchQuery.value.trim()
	private val searchQuery = FavouritesContainerFragment.searchQuery
		.debounceFavouritesSearch()
		.onEach { query ->
			if (query != lastSearchQuery) {
				lastSearchQuery = query
				limit.value = LOCAL_SEARCH_PAGE_SIZE
			}
		}
		.stateIn(
			viewModelScope + Dispatchers.Default,
			SharingStarted.Eagerly,
			lastSearchQuery,
		)

	override val listMode = settings.observeAsFlow(AppSettings.KEY_LIST_MODE_FAVORITES) { favoritesListMode }
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, settings.favoritesListMode)

	val pinnedIds: StateFlow<List<Long>> = settings.observeAsFlow(
		AppSettings.KEY_FAVORITES_PINNED + pinnedPreferenceId,
	) { getPinnedFavourites(pinnedPreferenceId) }.stateIn(
		viewModelScope + Dispatchers.Default,
		SharingStarted.Eagerly,
		settings.getPinnedFavourites(pinnedPreferenceId),
	)

	private val displayOptions = combine(
		contentTypeStore.selectedType,
		displayPreferences.state,
	) { type, state -> state.getValue(type) }.distinctUntilChanged()

	private val pagingState = combine(pinnedIds, limit) { pinned, pageLimit -> pinned to pageLimit }

	override val content = combine(
		localFavouritesRepository.items(favouriteSpace),
		observeListModeWithTriggers(),
		searchQuery,
		pagingState,
		displayOptions,
	) { items, mode, query, paging, display ->
		val (pinned, pageLimit) = paging
		val searched = searchMatcher.filter(items.skipNsfwIfNeeded(), query)
		val pinnedSet = pinned.toHashSet()
		val ordered = if (pinnedSet.isEmpty()) {
			searched
		} else {
			ArrayList<Manga>(searched.size).also { result ->
				for (id in pinned) {
					searched.firstOrNull { it.id == id }?.let(result::add)
				}
				for (manga in searched) {
					if (manga.id !in pinnedSet) result.add(manga)
				}
			}
		}
		if (ordered.isEmpty()) {
			listOf(
				EmptyState(
					icon = R.drawable.ic_empty_favourites,
					textPrimary = if (query.isBlank()) {
						R.string.text_empty_holder_primary
					} else {
						R.string.nothing_found
					},
					textSecondary = if (query.isBlank()) {
						R.string.favourites_category_empty
					} else {
						R.string.text_empty_holder_secondary_filtered
					},
					actionStringRes = 0,
				),
			)
		} else {
			val visible = ordered.take(pageLimit)
			val usePermanentStatus = settings.miyorareDesignStyle == MiyorareDesignStyle.MODERN &&
				favouriteSpace == FavouriteSpace.NORMAL && (mode == ListMode.GRID || mode == ListMode.COVER_ONLY)
			val libraryIds = if (usePermanentStatus) favouritesRepository.getMemberships(favouriteSpace)
				.mapTo(HashSet()) { it.mangaId } else emptySet()
			prefetchDetailsSnapshots(visible)
			val cardSnapshot = unreadCounter.getSnapshot(
				mangaIds = visible.map { it.id },
				includeUnread = display.showUnread,
			)
			ArrayList<ListModel>(visible.size).also { result ->
				mangaListMapper.toListModelList(
					destination = result,
					manga = visible,
					mode = mode,
					flags = MangaListMapper.NO_FAVORITE or MangaListMapper.NO_PROGRESS or MangaListMapper.NO_COUNTER,
				)
				for (i in result.indices) {
					val model = result[i]
					if (model !is MangaListModel) continue
					val mangaId = model.manga.id
					val isPinned = mangaId in pinnedSet
					val unreadCount = if (display.showUnread) cardSnapshot.unreadCounts[mangaId] ?: 0 else 0
					val progress = cardSnapshot.getProgress(mangaId, settings.progressIndicatorMode)
					val hasReadingHistory = display.showContinueReading && cardSnapshot.hasHistory(mangaId)
					val languageLabel = if (display.showLanguage) model.manga.source.getLanguageCode() else null
					result[i] = when (model) {
						is MangaGridModel -> model.copy(
							counter = unreadCount,
							progress = progress,
							isFavorite = if (usePermanentStatus) mangaId in libraryIds else model.isFavorite,
							isSaved = model.isSaved && display.showDownloaded,
							isPinned = isPinned,
							isTitleOverCover = display.titleOverCover,
							isGridSpacingIncreased = display.gridSpacingIncreased,
							isLocalSource = display.showLocalSource,
							languageLabel = languageLabel,
							showContinueReading = hasReadingHistory,
						)
						is MangaDetailedListModel -> model.copy(
							counter = unreadCount,
							progress = progress,
							isSaved = model.isSaved && display.showDownloaded,
							isPinned = isPinned,
							isLocalSource = display.showLocalSource,
							languageLabel = languageLabel,
							showContinueReading = hasReadingHistory,
						)
						is MangaCompactListModel -> model.copy(
							counter = unreadCount,
							isSaved = model.isSaved && display.showDownloaded,
							isPinned = isPinned,
							isLocalSource = display.showLocalSource,
							languageLabel = languageLabel,
							showContinueReading = hasReadingHistory,
						)
					}
				}
			}
		}
	}.stateIn(
		viewModelScope + Dispatchers.Default,
		SharingStarted.Eagerly,
		listOf(LoadingState),
	)

	init {
		viewModelScope.launch(Dispatchers.IO) {
			localFavouritesRepository.ensureInitialized(favouriteSpace)
		}
		viewModelScope.launch {
			localStorageChanges.filter { changed ->
				changed == null || changed.file.isInsideLocalFolder()
			}.distinctUntilChanged { old, new ->
				old != null && new != null &&
					old.manga.id == new.manga.id && old.file.path == new.file.path
			}.collect {
				localFavouritesRepository.refresh(favouriteSpace)
			}
		}
	}

	private fun java.io.File.isInsideLocalFolder(): Boolean = generateSequence(this) { it.parentFile }
		.any { it.name.isLocalFolderName() }

	private fun String.isLocalFolderName(): Boolean =
		equals("local", ignoreCase = true) || equals("lokal", ignoreCase = true)

	override fun onRefresh() {
		launchLoadingJob(Dispatchers.IO) {
			localFavouritesRepository.refresh(favouriteSpace)
		}
	}

	override fun onRetry() = onRefresh()

	fun requestMoreItems() {
		limit.value += LOCAL_SEARCH_PAGE_SIZE
	}

	fun setPinned(ids: Set<Long>, isPinned: Boolean) {
		val current = settings.getPinnedFavourites(pinnedPreferenceId)
		val updated = if (isPinned) current + (ids - current.toSet()) else current - ids
		settings.setPinnedFavourites(pinnedPreferenceId, updated)
	}

	private fun prefetchDetailsSnapshots(items: List<Manga>) {
		val missing = items.filterNot { detailsNavigationCache.containsLocalManga(it.id) }
		if (missing.isEmpty()) return
		detailsPrefetchJob?.cancel()
		detailsPrefetchJob = viewModelScope.launch(Dispatchers.IO) {
			val snapshots = missing.mapNotNull { item ->
				if (item.isNovelContent) {
					// Local EPUB collections can contain hundreds of chapter archives. Keep the lightweight
					// list item in the navigation cache and parse the book only after the user opens it.
					item
				} else {
					runCatchingCancellable { localMangaRepository.getDetails(item) }.getOrNull()
				}
			}
			detailsNavigationCache.putLocalAll(snapshots)
		}
	}
}
