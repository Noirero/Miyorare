package org.koitharu.kotatsu.details.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.bookmarks.domain.BookmarksRepository
import androidx.core.net.toUri
import org.koitharu.kotatsu.core.model.LocalMangaSource
import org.koitharu.kotatsu.core.model.getPreferredBranch
import org.koitharu.kotatsu.local.data.isEpubFile
import java.io.File
import org.koitharu.kotatsu.core.nav.MangaIntent
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.TABLE_CHAPTERS
import org.koitharu.kotatsu.core.db.entity.toMangaChapters
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.ListMode
import org.koitharu.kotatsu.core.prefs.TriStateOption
import org.koitharu.kotatsu.core.prefs.observeAsFlow
import org.koitharu.kotatsu.core.ui.util.ReversibleAction
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.core.util.ext.computeSize
import org.koitharu.kotatsu.details.data.DetailsNavigationCache
import org.koitharu.kotatsu.details.data.MangaDetails
import org.koitharu.kotatsu.details.domain.BranchComparator
import org.koitharu.kotatsu.details.domain.ContextualRecommendationUseCase
import org.koitharu.kotatsu.details.domain.DetailsInteractor
import org.koitharu.kotatsu.details.domain.DetailsLoadUseCase
import org.koitharu.kotatsu.details.domain.ProgressUpdateUseCase
import org.koitharu.kotatsu.details.domain.ReadingTimeUseCase
import org.koitharu.kotatsu.details.domain.RelatedMangaGroup
import org.koitharu.kotatsu.details.domain.RelatedMangaUseCase
import org.koitharu.kotatsu.details.ui.model.HistoryInfo
import org.koitharu.kotatsu.details.ui.model.MangaBranch
import org.koitharu.kotatsu.details.ui.pager.ChaptersPagesViewModel
import org.koitharu.kotatsu.download.domain.DownloadDestinationStore
import org.koitharu.kotatsu.download.ui.worker.DownloadWorker
import org.koitharu.kotatsu.favourites.data.EXTRA_FAVOURITE_SPACE
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.history.data.HistoryRepository
import org.koitharu.kotatsu.list.domain.MangaListMapper
import org.koitharu.kotatsu.list.ui.model.MangaListModel
import org.koitharu.kotatsu.local.data.LocalStorageChanges
import org.koitharu.kotatsu.local.domain.DeleteLocalMangaUseCase
import org.koitharu.kotatsu.local.domain.model.LocalManga
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.util.findById
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.reader.ui.ReaderState
import org.koitharu.kotatsu.scrobbling.common.domain.Scrobbler
import org.koitharu.kotatsu.scrobbling.common.domain.SyncProgressFromScrobblersUseCase
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblingInfo
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblingStatus
import org.koitharu.kotatsu.stats.data.StatsRepository
import javax.inject.Inject

data class DetailsRelatedUiState(
	val groups: List<RelatedMangaGroup> = emptyList(),
	val isLoading: Boolean = false,
	val isRequested: Boolean = false,
	val isComplete: Boolean = false,
	val error: Throwable? = null,
)

@HiltViewModel
class DetailsViewModel @Inject constructor(
	private val historyRepository: HistoryRepository,
	bookmarksRepository: BookmarksRepository,
	settings: AppSettings,
	private val scrobblers: Set<@JvmSuppressWildcards Scrobbler>,
	@LocalStorageChanges localStorageChanges: SharedFlow<LocalManga?>,
	downloadScheduler: DownloadWorker.Scheduler,
	downloadDestinationStore: DownloadDestinationStore,
	interactor: DetailsInteractor,
	savedStateHandle: SavedStateHandle,
	deleteLocalMangaUseCase: DeleteLocalMangaUseCase,
	private val relatedMangaUseCase: RelatedMangaUseCase,
	private val contextualRecommendationUseCase: ContextualRecommendationUseCase,
	private val mangaListMapper: MangaListMapper,
	private val detailsLoadUseCase: DetailsLoadUseCase,
	private val progressUpdateUseCase: ProgressUpdateUseCase,
	private val syncProgressFromScrobblersUseCase: SyncProgressFromScrobblersUseCase,
	private val readingTimeUseCase: ReadingTimeUseCase,
	statsRepository: StatsRepository,
	private val database: MangaDatabase,
	private val mangaDataRepository: MangaDataRepository,
	private val detailsNavigationCache: DetailsNavigationCache,
	mangaRepositoryFactory: MangaRepository.Factory,
) : ChaptersPagesViewModel(
	settings = settings,
	interactor = interactor,
	bookmarksRepository = bookmarksRepository,
	historyRepository = historyRepository,
	downloadScheduler = downloadScheduler,
	downloadDestinationStore = downloadDestinationStore,
	favouriteSpace = FavouriteSpace.fromArgument(
		savedStateHandle.get<Int>(EXTRA_FAVOURITE_SPACE) ?: FavouriteSpace.NORMAL.dbValue,
	),
	deleteLocalMangaUseCase = deleteLocalMangaUseCase,
	localStorageChanges = localStorageChanges,
	mangaDataRepository = mangaDataRepository,
	mangaRepositoryFactory = mangaRepositoryFactory,
) {

	private val intent = MangaIntent(savedStateHandle)
	private val navigationSnapshot = detailsNavigationCache.get(intent.mangaId)
	private var loadingJob: Job
	private var expandedRelatedJob: Job? = null
	private var expandedRelatedGeneration = 0L
	val mangaId = intent.mangaId
	val onTrackingProgressSynced = MutableEventFlow<Int>()

	private val _isRefreshing = MutableStateFlow(false)
	val isRefreshing = _isRefreshing.asStateFlow()
	private val _expandedRelated = MutableStateFlow(DetailsRelatedUiState())
	val expandedRelated = _expandedRelated.asStateFlow()
	private val genreRecommendationsVisible = MutableStateFlow(false)
	private val genreRecommendationsActive = MutableStateFlow(true)
	val relatedDiscoveryEnabled = settings.observeAsFlow(
		key = AppSettings.KEY_RELATED_MANGA,
		valueProducer = { isRelatedMangaEnabled },
	).stateIn(
		viewModelScope + Dispatchers.Default,
		SharingStarted.Eagerly,
		settings.isRelatedMangaEnabled,
	)
	val isRelatedDiscoveryEnabled: Boolean
		get() = relatedDiscoveryEnabled.value

	init {
		val initialDetails = (navigationSnapshot?.manga ?: intent.manga)?.let(::MangaDetails)
		mangaDetails.value = initialDetails
		readingState.value = navigationSnapshot?.history?.let(::ReaderState)
		// Named scanlator/language branches have no null-key entry. Select a usable branch alongside
		// the cached snapshot so the chapter count/list do not wait for the source refresh collector.
		if (initialDetails != null && initialDetails.allChapters.isNotEmpty()) {
			val branches = initialDetails.chapters.keys
			selectedBranch.value = if (null in branches) null else branches.first()
		}
		relatedDiscoveryEnabled
			.onEach { enabled ->
				if (!enabled) clearExpandedRelated()
			}
			.launchIn(viewModelScope + Dispatchers.Default)
	}

	val history = historyRepository.observeOne(mangaId)
		.onEach { h ->
			readingState.value = h?.let(::ReaderState)
		}.withErrorHandling()
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, navigationSnapshot?.history)

	val favouriteCategories = interactor.observeFavourite(mangaId)
		.withErrorHandling()
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, emptySet())

	val isStatsAvailable = statsRepository.observeHasStats(mangaId)
		.withErrorHandling()
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, false)

	val remoteManga = MutableStateFlow<Manga?>(null)

	val historyInfo: StateFlow<HistoryInfo> = combine(
		mangaDetails,
		selectedBranch,
		history,
		interactor.observeIncognitoMode(manga),
	) { m, b, h, im ->
		val estimatedTime = readingTimeUseCase.invoke(m, b, h)
		HistoryInfo(m, b, h, im == TriStateOption.ENABLED, estimatedTime)
	}.withErrorHandling()
		.stateIn(
			scope = viewModelScope + Dispatchers.Default,
			started = SharingStarted.Eagerly,
			initialValue = HistoryInfo(null, null, null, false, null),
		)

	val localSize = mangaDetails
		.map { it?.local }
		.distinctUntilChanged()
		.combine(
			localStorageChanges
				.filter { changed ->
					val local = mangaDetails.value?.local ?: return@filter false
					if (changed == null) {
						// Null means a whole local container was removed. Ignore unrelated removals while
						// this title's root still exists; one stat is enough to avoid a recursive size walk.
						!local.file.exists()
					} else {
						changed.manga.id == local.manga.id || changed.file == local.file
					}
				}
				.onStart { emit(null) },
		) { local, _ -> local }
		.mapLatest { local ->
			if (local != null) {
				runCatchingCancellable {
					withContext(Dispatchers.IO) { local.file.computeSize() }
				}.getOrDefault(0L)
			} else {
				0L
			}
		}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.WhileSubscribed(5000), 0L)

	val cachedSourceTitle = mangaDetails
		.mapLatest { details ->
			if (details == null) null else database.getMangaDao().findSourceTitle(mangaId)
		}
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, null)

	val isScrobblingAvailable: Boolean
		get() = scrobblers.any { it.isEnabled }

	val scrobblingInfo: StateFlow<List<ScrobblingInfo>> = interactor.observeScrobblingInfo(mangaId)
		.withErrorHandling()
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, emptyList())

	// Contextual/genre recommendations are independent from Related Titles. They begin with visibility
	// disabled in the ViewModel, so a persisted closed eye never triggers source/network work before
	// Compose has restored the user's preference. mapLatest cancels the in-flight load when hidden.
	val genreRecommendations: StateFlow<List<MangaListModel>> = combine(
		mangaDetails,
		genreRecommendationsVisible,
		genreRecommendationsActive,
	) { details, visible, active -> Triple(details, visible, active) }
		.mapLatest { (details, visible, active) ->
			if (details != null && details.isLoaded && visible && active) {
				mangaListMapper.toListModelList(
					manga = contextualRecommendationUseCase(details.toManga()),
					mode = ListMode.GRID,
				)
			} else {
				emptyList()
			}
		}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Lazily, emptyList())

	val tags = manga.mapLatest {
		mangaListMapper.mapTags(it?.tags.orEmpty())
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, emptyList())

	val branches: StateFlow<List<MangaBranch>> = combine(
		mangaDetails,
		selectedBranch,
		history,
	) { m, b, h ->
		val c = m?.chapters
		if (c.isNullOrEmpty()) {
			return@combine emptyList()
		}
		val currentBranch = h?.let { m.allChapters.findById(it.chapterId) }?.branch
		c.map { x ->
			MangaBranch(
				name = x.key,
				count = x.value.size,
				isSelected = x.key == b,
				isCurrent = h != null && x.key == currentBranch,
			)
		}.sortedWith(BranchComparator())
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, emptyList())

	val selectedBranchValue: String?
		get() = selectedBranch.value

	init {
		loadingJob = doLoad(force = false)
		launchJob(Dispatchers.Default + SkipErrors) {
			val manga = mangaDetails.firstOrNull { !it?.chapters.isNullOrEmpty() } ?: return@launchJob
			val h = history.firstOrNull()
			if (h != null) {
				progressUpdateUseCase(manga.toManga())
			}
		}
		launchJob(Dispatchers.Default) {
			val manga = mangaDetails.firstOrNull { it != null && it.isLocal } ?: return@launchJob
			remoteManga.value = interactor.findRemote(manga.toManga())
		}
		// Re-apply the override as soon as it changes in the DB so edits from the override editor
		// are reflected instantly, without waiting for a manual reload or re-entering the screen.
		mangaDataRepository.observeOverridesTrigger(emitInitialState = false)
			.onEach {
				val current = mangaDetails.value ?: return@onEach
				mangaDetails.value = current.withOverride(mangaDataRepository.getOverride(mangaId))
			}
			.withErrorHandling()
			.launchIn(viewModelScope + Dispatchers.Default)

		// DetailsLoadUseCase owns the initial Room read. Observe only later table invalidations so a
		// cached open does not materialize the same 1k-3k chapter list twice. The observer never calls
		// reload/source code: it waits out an active Details load, then applies the latest committed DB
		// snapshot while preserving metadata, local/download overlay and the current loaded state.
		database.invalidationTracker.createFlow(
			tables = arrayOf(TABLE_CHAPTERS),
			emitInitialState = false,
		)
			.mapLatest { syncCachedChaptersWhenLoadIdle() }
			.withErrorHandling()
			.launchIn(viewModelScope + Dispatchers.Default)
	}

	fun setGenreRecommendationsVisible(visible: Boolean) {
		genreRecommendationsVisible.value = visible
	}

	fun pauseGenreRecommendations() {
		genreRecommendationsActive.value = false
	}

	fun resumeGenreRecommendations() {
		genreRecommendationsActive.value = true
	}

	fun requestExpandedRelated() {
		if (!relatedDiscoveryEnabled.value) return
		val state = _expandedRelated.value
		if (state.isLoading || state.isComplete || expandedRelatedJob?.isActive == true) return
		val details = mangaDetails.value?.takeIf { it.isLoaded } ?: return
		val seed = details.toManga()
		val generation = ++expandedRelatedGeneration

		_expandedRelated.value = state.copy(
			isLoading = true,
			isRequested = true,
			error = null,
		)
		expandedRelatedJob = viewModelScope.launch(Dispatchers.Default) {
			try {
				relatedMangaUseCase.collectGroups(
					seed = seed,
					includePrimary = false,
					excludedIds = emptySet(),
				) { group ->
					if (generation != expandedRelatedGeneration || group.keyword == null) return@collectGroups
					val current = _expandedRelated.value
					if (current.groups.none { it.keyword.equals(group.keyword, ignoreCase = true) }) {
						_expandedRelated.value = current.copy(
							groups = current.groups + group,
							isLoading = true,
							error = null,
						)
					}
				}
				if (generation == expandedRelatedGeneration) {
					_expandedRelated.value = _expandedRelated.value.copy(
						isLoading = false,
						isComplete = true,
					)
				}
			} catch (e: CancellationException) {
				throw e
			} catch (e: Throwable) {
				if (generation == expandedRelatedGeneration) {
					_expandedRelated.value = _expandedRelated.value.copy(
						isLoading = false,
						isComplete = false,
						error = e,
					)
				}
			} finally {
				if (generation == expandedRelatedGeneration) {
					expandedRelatedJob = null
				}
			}
		}
	}

	/** Turning the global Related Titles control off must stop network/source enrichment immediately. */
	private fun clearExpandedRelated() {
		expandedRelatedGeneration++
		expandedRelatedJob?.cancel()
		expandedRelatedJob = null
		_expandedRelated.value = DetailsRelatedUiState()
	}

	/** Stop enrichment when Details leaves the foreground. Partial groups stay available. */
	fun pauseExpandedRelated() {
		val job = expandedRelatedJob ?: return
		if (!job.isActive) return
		expandedRelatedGeneration++
		job.cancel()
		expandedRelatedJob = null
		_expandedRelated.value = _expandedRelated.value.copy(isLoading = false)
	}

	/** Resume only a discovery that the user had already reached before leaving Details. */
	fun resumeExpandedRelatedIfNeeded() {
		val state = _expandedRelated.value
		if (state.isRequested && !state.isComplete && !state.isLoading) {
			requestExpandedRelated()
		}
	}

	override fun reload() {
		loadingJob.cancel()
		loadingJob = doLoad(force = true)
	}

	/**
	 * The EPUB backing this entry, or null when there is nothing to export. A downloaded novel already
	 * *is* an epub, so exporting is a copy rather than a rebuild — and like LNReader, only downloaded
	 * chapters can be exported.
	 */
	fun getLocalEpubFile(): File? {
		mangaDetails.value?.local?.file?.takeIf { it.isEpubFile }?.let { return it }
		// A book opened straight from local storage has no separate "local" copy to look up.
		val manga = getMangaOrNull() ?: return null
		if (manga.source != LocalMangaSource) return null
		return runCatching { File(manga.url.toUri().schemeSpecificPart) }
			.getOrNull()
			?.takeIf { it.isEpubFile }
	}

	fun updateScrobbling(index: Int, rating: Float, status: ScrobblingStatus?) {
		val scrobbler = getScrobbler(index) ?: return
		launchJob(Dispatchers.Default) {
			scrobbler.updateScrobblingInfo(
				mangaId = mangaId,
				rating = rating,
				status = status,
				comment = null,
			)
		}
	}

	fun unregisterScrobbling(index: Int) {
		val scrobbler = getScrobbler(index) ?: return
		launchJob(Dispatchers.Default) {
			scrobbler.unregisterScrobbling(
				mangaId = mangaId,
			)
		}
	}

	fun removeFromHistory() {
		launchJob(Dispatchers.Default) {
			val handle = historyRepository.delete(setOf(mangaId))
			onActionDone.call(ReversibleAction(R.string.removed_from_history, handle))
		}
	}

	private suspend fun syncCachedChaptersWhenLoadIdle() {
		while (true) {
			val observedLoad = loadingJob
			if (observedLoad.isActive) {
				observedLoad.join()
				continue
			}

			val chapters = database.getChaptersDao().findAll(mangaId).toMangaChapters()
			val current = mangaDetails.value ?: return
			if (current.isLocal) return
			val currentSourceChapters = current.sourceManga.chapters.orEmpty()
			// A cache cleanup or other empty DB snapshot must not blank an already renderable Details list.
			if (chapters.isEmpty() && currentSourceChapters.isNotEmpty()) return

			var updated = current.copy(manga = current.sourceManga.copy(chapters = chapters))
			if (mangaDataRepository.isScanlatorsMerged(mangaId)) {
				updated = updated.withMergedBranches()
			}
			// Any concurrent Details load, override edit or download/local event gets priority. Retry from
			// the newest state instead of replacing it with the snapshot captured above.
			if (loadingJob !== observedLoad || mangaDetails.value !== current) continue
			if (updated.sourceManga.chapters == current.sourceManga.chapters) return

			mangaDetails.value = updated
			val availableBranches = updated.chapters.keys
			if (availableBranches.isNotEmpty() && selectedBranch.value !in availableBranches) {
				selectedBranch.value = if (null in availableBranches) null else availableBranches.first()
			}
			return
		}
	}

	private fun doLoad(force: Boolean) = launchJob(Dispatchers.Default) {
		var initialLoading = mangaDetails.value?.allChapters.isNullOrEmpty()
		if (initialLoading) {
			loadingCounter.increment()
		}
		var firstEmission = true
		var scrobblingSynced = false
		try {
			detailsLoadUseCase.invoke(intent, force)
				.withErrorHandling()
				.collect {
					val current = mangaDetails.value
					// Keep presentation-only progressive snapshots from replacing an already renderable state.
					// The first chapter-bearing emission is the local resolveIntent()/Room snapshot and may replace
					// an older navigation snapshot. Later incomplete emissions are source-progress snapshots and
					// must not shrink a usable cached list while the final refresh is still running.
					val addsLocalCopy = it.local != null && current?.local == null
					val addsChapters = it.allChapters.isNotEmpty() && current?.allChapters.isNullOrEmpty()
					val isFirstResolvedChapterSnapshot = firstEmission && it.allChapters.isNotEmpty()
					if (
						!it.isLoaded && current.hasRenderableSnapshot() && !addsLocalCopy && !addsChapters &&
						!isFirstResolvedChapterSnapshot
					) {
						firstEmission = false
						if (!initialLoading && current?.allChapters?.isNotEmpty() == true) {
							_isRefreshing.value = true
						}
						return@collect
					}
					firstEmission = false
					if (it.allChapters.isNotEmpty()) {
						val manga = it.toManga()
						val hist = historyRepository.getOne(manga)
						val preferredBranch = manga.getPreferredBranch(hist)
						val resolvedBranch = resolveSelectedBranch(
							selectedBranch = selectedBranch.value,
							availableBranches = it.chapters.keys,
							preferredBranch = preferredBranch,
						)
						if (resolvedBranch != selectedBranch.value) {
							selectedBranch.value = resolvedBranch
						}
					}
					mangaDetails.value = it
					if (initialLoading && it.allChapters.isNotEmpty()) {
						loadingCounter.decrement()
						initialLoading = false
					}
					_isRefreshing.value = !initialLoading && !it.isLoaded
					if (force && it.isLoaded && !scrobblingSynced) {
						scrobblingSynced = true
						launchJob(Dispatchers.Default + SkipErrors) {
							syncProgressFromScrobblersUseCase(it.toManga(), selectedBranch.value)?.let { chapter ->
								onTrackingProgressSynced.call(chapter)
							}
						}
					}
				}
		} finally {
			if (initialLoading) {
				loadingCounter.decrement()
			}
			_isRefreshing.value = false
		}
	}

	suspend fun isSourceRecommended(sourceName: String): Boolean {
		if (!sourceName.startsWith("MIHON_")) return false
		val recommended = runCatching {
			database.getMangaDao().findExternalSourcesInLibrary()
		}.getOrDefault(emptyList())
		return sourceName in recommended
	}

	private fun getScrobbler(index: Int): Scrobbler? {
		val info = scrobblingInfo.value.getOrNull(index)
		val scrobbler = if (info != null) {
			scrobblers.find { it.scrobblerService == info.scrobbler && it.isEnabled }
		} else {
			null
		}
		if (scrobbler == null) {
			errorEvent.call(IllegalStateException("Scrobbler [$index] is not available"))
		}
		return scrobbler
	}
}

private fun MangaDetails?.hasRenderableSnapshot(): Boolean {
	return this != null && (isLoaded || description != null || allChapters.isNotEmpty())
}

internal fun resolveSelectedBranch(
	selectedBranch: String?,
	availableBranches: Set<String?>,
	preferredBranch: String?,
): String? {
	if (availableBranches.isEmpty()) {
		return null
	}
	return when {
		selectedBranch in availableBranches -> selectedBranch
		preferredBranch in availableBranches -> preferredBranch
		else -> availableBranches.first()
	}
}
