package org.koitharu.kotatsu.favourites.ui.list

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
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.observeAsFlow
import org.koitharu.kotatsu.details.data.DetailsNavigationCache
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

@HiltViewModel
class LocalFavouritesListViewModel @Inject constructor(
	private val settings: AppSettings,
	mangaDataRepository: MangaDataRepository,
	@LocalStorageChanges private val localStorageChanges: SharedFlow<LocalManga?>,
	private val localFavouritesRepository: LocalFavouritesRepository,
	private val localMangaRepository: LocalMangaRepository,
	private val mangaListMapper: MangaListMapper,
	private val searchMatcher: FavouritesSearchMatcher,
	private val detailsNavigationCache: DetailsNavigationCache,
) : MangaListViewModel(settings, mangaDataRepository, localStorageChanges) {

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
		AppSettings.KEY_FAVORITES_PINNED + LOCAL_FAVOURITES_CATEGORY_ID,
	) { getPinnedFavourites(LOCAL_FAVOURITES_CATEGORY_ID) }.stateIn(
		viewModelScope + Dispatchers.Default,
		SharingStarted.Eagerly,
		settings.getPinnedFavourites(LOCAL_FAVOURITES_CATEGORY_ID),
	)

	override val content = combine(
		localFavouritesRepository.items,
		observeListModeWithTriggers(),
		searchQuery,
		pinnedIds,
		limit,
	) { items, mode, query, pinned, pageLimit ->
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
			prefetchDetailsSnapshots(visible)
			ArrayList<ListModel>(visible.size).also { result ->
				mangaListMapper.toListModelList(
					destination = result,
					manga = visible,
					mode = mode,
					flags = MangaListMapper.NO_FAVORITE,
				)
				if (pinnedSet.isNotEmpty()) {
					for (i in result.indices) {
						val model = result[i]
						if (model !is MangaListModel || model.manga.id !in pinnedSet) continue
						result[i] = when (model) {
							is MangaGridModel -> model.copy(isPinned = true)
							is MangaDetailedListModel -> model.copy(isPinned = true)
							is MangaCompactListModel -> model.copy(isPinned = true)
						}
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
		viewModelScope.launch {
			localStorageChanges.filter { changed ->
				changed == null || changed.file.isInsideLocalFolder()
			}.distinctUntilChanged { old, new ->
				old != null && new != null &&
					old.manga.id == new.manga.id && old.file.path == new.file.path
			}.collect {
				localFavouritesRepository.refresh()
			}
		}
	}

	private fun java.io.File.isInsideLocalFolder(): Boolean = generateSequence(this) { it.parentFile }
		.any { it.name.equals("local", ignoreCase = true) }

	override fun onRefresh() {
		launchLoadingJob(Dispatchers.IO) {
			localFavouritesRepository.refresh()
		}
	}

	override fun onRetry() = onRefresh()

	fun requestMoreItems() {
		limit.value += LOCAL_SEARCH_PAGE_SIZE
	}

	fun setPinned(ids: Set<Long>, isPinned: Boolean) {
		val current = settings.getPinnedFavourites(LOCAL_FAVOURITES_CATEGORY_ID)
		val updated = if (isPinned) current + (ids - current.toSet()) else current - ids
		settings.setPinnedFavourites(LOCAL_FAVOURITES_CATEGORY_ID, updated)
	}

	private fun prefetchDetailsSnapshots(items: List<Manga>) {
		val missing = items.filterNot { detailsNavigationCache.contains(it.id) }
		if (missing.isEmpty()) return
		detailsPrefetchJob?.cancel()
		detailsPrefetchJob = viewModelScope.launch(Dispatchers.Default) {
			val snapshots = missing.mapNotNull { item ->
				runCatchingCancellable { localMangaRepository.getDetails(item) }.getOrNull()
			}
			detailsNavigationCache.putAll(snapshots) { null }
		}
	}
}
