package org.koitharu.kotatsu.search.ui.multi

import androidx.collection.ArraySet
import androidx.collection.LongSet
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.LocalMangaSource
import org.koitharu.kotatsu.core.model.UnknownMangaSource
import org.koitharu.kotatsu.core.model.isNovelSource
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.ListMode
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.explore.data.MangaSourcesRepository
import org.koitharu.kotatsu.favourites.domain.FavouritesRepository
import org.koitharu.kotatsu.history.data.HistoryRepository
import org.koitharu.kotatsu.list.domain.MangaListMapper
import org.koitharu.kotatsu.list.ui.model.EmptyState
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.LoadingFooter
import org.koitharu.kotatsu.list.ui.model.LoadingState
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.util.levenshteinDistance
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.search.domain.LANGUAGE_LOCAL
import org.koitharu.kotatsu.search.domain.SearchKind
import org.koitharu.kotatsu.search.domain.SearchSourceMode
import org.koitharu.kotatsu.search.domain.SearchSourcePreferences
import org.koitharu.kotatsu.search.domain.SearchV2Helper
import org.koitharu.kotatsu.search.domain.matchesPreferredLanguage
import org.koitharu.kotatsu.search.domain.parseSearchQuery
import org.koitharu.kotatsu.search.domain.searchLanguageCode
import javax.inject.Inject

private const val MAX_PARALLELISM = 5
private const val POPULAR_SOURCE_LIMIT = 100

data class GlobalSearchProgress(val completed: Int = 0, val total: Int = 0)

@HiltViewModel
class SearchViewModel @Inject constructor(
	savedStateHandle: SavedStateHandle,
	private val mangaListMapper: MangaListMapper,
	private val searchHelperFactory: SearchV2Helper.Factory,
	private val sourcesRepository: MangaSourcesRepository,
	private val historyRepository: HistoryRepository,
	private val favouritesRepository: FavouritesRepository,
	private val settings: AppSettings,
	private val searchPreferences: SearchSourcePreferences,
) : BaseViewModel() {

	val query = savedStateHandle.get<String>(AppRouter.KEY_QUERY).orEmpty()
	val kind = savedStateHandle.get<SearchKind>(AppRouter.KEY_KIND) ?: SearchKind.SIMPLE
	private val isNovelScope = settings.isGlobalSearchNovelScope

	private val sourceModeState = MutableStateFlow(
		if (settings.isSearchPinnedOnly) SearchSourceMode.PINNED_ONLY else searchPreferences.globalMode,
	)
	val sourceMode: StateFlow<SearchSourceMode> = sourceModeState

	private val preferredLanguagesState = MutableStateFlow(searchPreferences.preferredLanguages)
	val preferredLanguages: StateFlow<Set<String>> = preferredLanguagesState

	private val hasResultsOnlyState = MutableStateFlow(searchPreferences.globalHasResultsOnly)
	val hasResultsOnly: StateFlow<Boolean> = hasResultsOnlyState

	private val flatViewState = MutableStateFlow(searchPreferences.globalFlatView)
	val flatView: StateFlow<Boolean> = flatViewState

	private val hideLibraryState = MutableStateFlow(searchPreferences.globalHideLibrary)
	val hideLibrary: StateFlow<Boolean> = hideLibraryState

	private val localOnly = MutableStateFlow(settings.isSearchLocalOnly)
	private val results = MutableStateFlow<List<SearchResultsListModel>>(emptyList())
	private val availableLanguagesState = MutableStateFlow<List<String>>(emptyList())
	val availableLanguages: StateFlow<List<String>> = availableLanguagesState

	private val progressState = MutableStateFlow(GlobalSearchProgress())
	val searchProgress: StateFlow<GlobalSearchProgress> = progressState

	private var searchJob: Job? = null

	val hasActiveFilters: StateFlow<Boolean> = combine(
		sourceModeState,
		localOnly,
		hasResultsOnlyState,
		flatViewState,
		hideLibraryState,
	) { mode, local, hasResults, flat, hideLibrary ->
		mode != SearchSourceMode.ALL_SOURCES || local || !hasResults || flat || hideLibrary
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, false)

	val list: StateFlow<List<ListModel>> = combine(
		results,
		isLoading.dropWhile { !it },
		hasResultsOnlyState,
		flatViewState,
	) { groups, loading, hasResultsOnly, flat ->
		val visibleGroups = groups
			.filter { group -> !hasResultsOnly || group.list.isNotEmpty() || group.isLoading || group.error != null }
			.sortedBy { it.rank }

		val content: List<ListModel> = if (flat) {
			val manga = visibleGroups
				.flatMap { it.list }
				.distinctBy { item -> item.id to item.manga.title.trim().lowercase() }
			val feedback = visibleGroups.filter { it.error != null || it.isLoading }
			manga + feedback
		} else {
			visibleGroups
		}

		when {
			content.isEmpty() -> listOf(
				when {
					loading -> LoadingState
					else -> EmptyState(
						icon = R.drawable.ic_empty_common,
						textPrimary = R.string.nothing_found,
						textSecondary = R.string.text_search_holder_secondary,
						actionStringRes = 0,
					)
				},
			)
			loading -> content + LoadingFooter()
			else -> content
		}
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, listOf(LoadingState))

	init {
		doSearch()
	}

	fun getItems(ids: LongSet): Set<Manga> {
		val snapshot = results.value
		val result = ArraySet<Manga>(ids.size)
		snapshot.forEach { group ->
			for (item in group.list) {
				if (item.id in ids) result.add(item.manga)
			}
		}
		return result
	}

	fun retry() {
		searchJob?.cancel()
		results.value = emptyList()
		doSearch()
	}

	val isPinnedOnly: Boolean
		get() = sourceModeState.value == SearchSourceMode.PINNED_ONLY

	val isLocalOnly: Boolean
		get() = localOnly.value

	fun setPinnedOnly(value: Boolean) {
		setSourceMode(if (value) SearchSourceMode.PINNED_ONLY else SearchSourceMode.ALL_SOURCES)
	}

	fun setSourceMode(mode: SearchSourceMode) {
		if (sourceModeState.value == mode) return
		searchPreferences.globalMode = mode
		settings.isSearchPinnedOnly = mode == SearchSourceMode.PINNED_ONLY
		sourceModeState.value = mode
		retry()
	}

	fun setPreferredLanguages(languages: Set<String>) {
		val normalized = languages.ifEmpty { searchPreferences.defaultPreferredLanguages }
		if (preferredLanguagesState.value == normalized) return
		searchPreferences.preferredLanguages = normalized
		preferredLanguagesState.value = normalized
		retry()
	}

	fun setHasResultsOnly(value: Boolean) {
		if (hasResultsOnlyState.value == value) return
		searchPreferences.globalHasResultsOnly = value
		hasResultsOnlyState.value = value
	}

	fun setFlatView(value: Boolean) {
		if (flatViewState.value == value) return
		searchPreferences.globalFlatView = value
		flatViewState.value = value
	}

	fun setHideLibrary(value: Boolean) {
		if (hideLibraryState.value == value) return
		searchPreferences.globalHideLibrary = value
		hideLibraryState.value = value
		retry()
	}

	fun resetFilters() {
		searchPreferences.resetGlobal()
		settings.isSearchPinnedOnly = false
		settings.isSearchLocalOnly = false
		sourceModeState.value = SearchSourceMode.ALL_SOURCES
		preferredLanguagesState.value = searchPreferences.preferredLanguages
		hasResultsOnlyState.value = true
		flatViewState.value = false
		hideLibraryState.value = false
		localOnly.value = false
		retry()
	}

	fun setLocalOnly(value: Boolean) {
		if (localOnly.value != value) {
			settings.isSearchLocalOnly = value
			localOnly.value = value
			retry()
		}
	}

	private fun doSearch() {
		val prevJob = searchJob
		searchJob = launchLoadingJob(Dispatchers.Default) {
			prevJob?.cancelAndJoin()
			progressState.value = GlobalSearchProgress()

			upsertResult(searchFavorites())
			upsertResult(searchHistory())
			upsertResult(searchLocal())
			if (localOnly.value) return@launchLoadingJob

			sourcesRepository.ensureExternalSourcesReady()
			val allSources = sourcesRepository.getEnabledSources()
				.filter { it.isNovelSource == isNovelScope }
			refreshAvailableLanguages(allSources)
			val pinned = sourcesRepository.getPinnedSources().toSet()
			val preferred = preferredLanguagesState.value
			val popularOrder = historyRepository.getPopularSources(POPULAR_SOURCE_LIMIT)
				.withIndex().associate { (index, source) -> source to index }
			val libraryIds = if (hideLibraryState.value) {
				favouritesRepository.getAllManga().mapTo(HashSet()) { it.id }
			} else {
				emptySet()
			}

			val scopedSources = when (sourceModeState.value) {
				SearchSourceMode.PINNED_ONLY -> allSources.filter { it in pinned }
				SearchSourceMode.PREFERRED_LANGUAGES -> allSources.filter {
					it in pinned || it.matchesPreferredLanguage(preferred)
				}
				SearchSourceMode.ALL_SOURCES -> allSources
			}.sortedWith(
				compareBy<MangaSource>(
					{ if (it in pinned) 0 else 1 },
					{ if (it.matchesPreferredLanguage(preferred)) 0 else 1 },
					{ popularOrder[it] ?: Int.MAX_VALUE },
				),
			)

			progressState.value = GlobalSearchProgress(0, scopedSources.size)
			for ((index, source) in scopedSources.withIndex()) {
				upsertResult(
					SearchResultsListModel(
						titleResId = 0,
						source = source,
						listFilter = null,
						sortOrder = null,
						list = emptyList(),
						error = null,
						isLoading = true,
						rank = sourceRank(source, index, pinned, preferred, popularOrder, null),
					),
				)
			}

			val semaphore = Semaphore(MAX_PARALLELISM)
			scopedSources.mapIndexed { index, source ->
				launch {
					try {
						semaphore.withPermit {
							upsertResult(
								searchSource(source, index, pinned, preferred, popularOrder, libraryIds),
							)
						}
					} finally {
						progressState.update { state ->
							state.copy(completed = (state.completed + 1).coerceAtMost(state.total))
						}
					}
				}
			}.joinAll()
		}
	}

	private suspend fun searchSource(
		source: MangaSource,
		index: Int,
		pinned: Set<MangaSource>,
		preferred: Set<String>,
		popularOrder: Map<MangaSource, Int>,
		libraryIds: Set<Long>,
	): SearchResultsListModel = runCatchingCancellable {
		searchHelperFactory.create(source)(query, kind)
	}.fold(
		onSuccess = { result ->
			val uniqueManga = result?.manga
				?.asSequence()
				?.distinctBy { it.dedupeKey() }
				?.filterNot { it.id in libraryIds }
				?.toList()
				.orEmpty()
			val list = mangaListMapper.toListModelList(manga = uniqueManga, mode = ListMode.GRID)
			SearchResultsListModel(
				titleResId = 0,
				source = source,
				list = list,
				error = null,
				listFilter = result?.listFilter,
				sortOrder = result?.sortOrder,
				isLoading = false,
				rank = sourceRank(source, index, pinned, preferred, popularOrder, uniqueManga.firstOrNull()),
			)
		},
		onFailure = { error ->
			error.printStackTraceDebug()
			SearchResultsListModel(
				titleResId = 0,
				source = source,
				listFilter = null,
				sortOrder = null,
				list = emptyList(),
				error = error,
				isLoading = false,
				rank = sourceRank(source, index, pinned, preferred, popularOrder, null),
			)
		},
	)

	private suspend fun searchHistory(): SearchResultsListModel? = runCatchingCancellable {
		historyRepository.search(query, kind, Int.MAX_VALUE)
			.filter { it.source.isNovelSource == isNovelScope }
			.distinctBy { it.dedupeKey() }
	}.fold(
		onSuccess = { result ->
			if (result.isEmpty()) null else SearchResultsListModel(
				titleResId = R.string.history,
				source = UnknownMangaSource,
				list = mangaListMapper.toListModelList(manga = result, mode = ListMode.GRID),
				error = null,
				listFilter = null,
				sortOrder = null,
				rank = -200,
			)
		},
		onFailure = { null },
	)

	private suspend fun searchFavorites(): SearchResultsListModel? = runCatchingCancellable {
		favouritesRepository.search(query, kind, Int.MAX_VALUE)
			.filter { it.source.isNovelSource == isNovelScope }
			.distinctBy { it.dedupeKey() }
	}.fold(
		onSuccess = { result ->
			if (result.isEmpty()) null else SearchResultsListModel(
				titleResId = R.string.favourites,
				source = UnknownMangaSource,
				list = mangaListMapper.toListModelList(
					manga = result,
					mode = ListMode.GRID,
					flags = MangaListMapper.NO_FAVORITE,
				),
				error = null,
				listFilter = null,
				sortOrder = null,
				rank = -300,
			)
		},
		onFailure = { null },
	)

	private suspend fun searchLocal(): SearchResultsListModel? = if (isNovelScope) {
		null
	} else runCatchingCancellable {
		searchHelperFactory.create(LocalMangaSource).invoke(query, kind)
	}.fold(
		onSuccess = { result ->
			if (result?.manga.isNullOrEmpty()) null else SearchResultsListModel(
				titleResId = 0,
				source = LocalMangaSource,
				list = mangaListMapper.toListModelList(
					manga = result!!.manga.distinctBy { it.dedupeKey() },
					mode = ListMode.GRID,
					flags = MangaListMapper.NO_SAVED,
				),
				error = null,
				listFilter = result.listFilter,
				sortOrder = result.sortOrder,
				rank = -100,
			)
		},
		onFailure = { null },
	)

	private fun sourceRank(
		source: MangaSource,
		fallbackIndex: Int,
		pinned: Set<MangaSource>,
		preferred: Set<String>,
		popularOrder: Map<MangaSource, Int>,
		bestMatch: Manga?,
	): Int {
		val pinnedRank = if (source in pinned) 0 else 1
		val languageRank = if (source.matchesPreferredLanguage(preferred)) 0 else 1
		val relevance = bestMatch?.let {
			val parsedQuery = parseSearchQuery(query).text
			sequenceOf(it.title).plus(it.altTitles.asSequence())
				.minOfOrNull { title -> title.levenshteinDistance(parsedQuery) }
		} ?: 999
		val usageRank = popularOrder[source] ?: POPULAR_SOURCE_LIMIT + fallbackIndex
		return pinnedRank * 10_000_000 + languageRank * 1_000_000 + relevance.coerceAtMost(999) * 1_000 + usageRank.coerceAtMost(999)
	}

	private fun refreshAvailableLanguages(sources: List<MangaSource>) {
		availableLanguagesState.value = sources
			.map { it.searchLanguageCode() }
			.filter { it != LANGUAGE_LOCAL }
			.distinct()
			.sorted()
	}

	private fun upsertResult(item: SearchResultsListModel?) {
		if (item == null) return
		results.update { current ->
			val index = current.indexOfFirst { it.source == item.source && it.titleResId == item.titleResId }
			if (index == -1) current + item else current.toMutableList().also { it[index] = item }
		}
	}

	private fun Manga.dedupeKey(): Pair<Long, String> = id to title.trim().lowercase()
}
