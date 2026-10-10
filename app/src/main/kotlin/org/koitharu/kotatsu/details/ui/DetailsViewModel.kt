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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.catch
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
import org.koitharu.kotatsu.core.model.isBroken
import org.koitharu.kotatsu.core.model.isLocal
import org.koitharu.kotatsu.core.model.isNsfw
import org.koitharu.kotatsu.core.model.getPreferredBranch
import org.koitharu.kotatsu.local.data.isEpubFile
import org.koitharu.kotatsu.local.library.LOCAL_LIBRARY_SCHEME
import java.io.File
import org.koitharu.kotatsu.core.nav.MangaIntent
import org.koitharu.kotatsu.core.db.MangaDatabase
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
import org.koitharu.kotatsu.details.domain.resolveTrackerRecommendation
import org.koitharu.kotatsu.details.ui.model.HistoryInfo
import org.koitharu.kotatsu.details.ui.model.MangaBranch
import org.koitharu.kotatsu.details.ui.pager.ChapterListOptionsStore
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
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.util.findById
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.reader.ui.ReaderState
import org.koitharu.kotatsu.scrobbling.common.domain.Scrobbler
import org.koitharu.kotatsu.scrobbling.common.domain.ReadTrackerDetailsUseCase
import org.koitharu.kotatsu.scrobbling.common.domain.ScrobblerRepositoryMap
import org.koitharu.kotatsu.scrobbling.common.domain.TrackerDetailsProvider
import org.koitharu.kotatsu.scrobbling.common.data.ScrobblingEntity
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerContent
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerPage
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerDetailsReadPolicy
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerRecommendation
import org.koitharu.kotatsu.scrobbling.mangaupdates.data.MangaUpdatesRepository
import org.koitharu.kotatsu.scrobbling.mangaupdates.domain.MangaUpdatesScrobbler
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
	private val readTrackerDetailsUseCase: ReadTrackerDetailsUseCase,
	private val scrobblerRepositories: ScrobblerRepositoryMap,
	private val readingTimeUseCase: ReadingTimeUseCase,
	statsRepository: StatsRepository,
	private val database: MangaDatabase,
	private val mangaDataRepository: MangaDataRepository,
	private val detailsNavigationCache: DetailsNavigationCache,
	mangaRepositoryFactory: MangaRepository.Factory,
	chapterListOptionsStore: ChapterListOptionsStore,
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
	chapterListOptionsStore = chapterListOptionsStore,
) {

	private val intent = MangaIntent(savedStateHandle)
	private val navigationManga = detailsNavigationCache.getLocalManga(intent.mangaId)
	private val navigationHistory = detailsNavigationCache.getHistory(intent.mangaId)
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
		val initialDetails = (navigationManga ?: intent.manga)?.let(::MangaDetails)
		mangaDetails.value = initialDetails
		readingState.value = navigationHistory?.let(::ReaderState)
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
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, navigationHistory)

	val favouriteCategories = interactor.observeFavourite(mangaId, favouriteSpace)
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

	private val peopleProviders = ScrobblerService.entries
		.mapNotNull { scrobblerRepositories[it] as? TrackerDetailsProvider }.associateBy { it.detailsService }
	private val peopleContent = setOf(TrackerContent.CHARACTERS, TrackerContent.STAFF)
	private val recommendationContent = setOf(TrackerContent.RECOMMENDATIONS)
	private fun trackerController(content: Set<TrackerContent>) = DetailsPeopleController(
		scope = viewModelScope,
		read = { key, service ->
			readTrackerDetailsUseCase(
				key.mangaId, setOf(service),
				content.associateWith { TrackerPage() },
				key.policy,
			)
		},
		isCurrent = { key ->
			// Recheck authorities before/after each provider, even if their observer is queued.
			val rows = database.getScrobblingDao().findAll(mangaId)
			val privateOnly = database.getPrivateFavouritesDao().isPrivateOnly(mangaId)
			key == peopleContext(mangaDetails.value, rows, privateOnly, content)
		},
	)
	private val peopleController = trackerController(peopleContent)
	private val recommendationController = trackerController(recommendationContent)
	val trackerPeople = peopleController.state
	val trackerRecommendations = recommendationController.state
	internal val onTrackerRecommendationNavigation = MutableEventFlow<TrackerRecommendationNavigation>()
	private var recommendationContext: DetailsPeopleContext? = null
	private var recommendationNavigationJob: Job? = null
	private var recommendationsActive = false
	private val mangaUpdatesRepository get() = scrobblerRepositories[ScrobblerService.MANGAUPDATES] as MangaUpdatesRepository
	val mangaUpdatesProgressFailed = combine(mangaUpdatesRepository.failedProgress, mangaUpdatesRepository.detailsSessionGeneration) { failed, _ ->
		mangaUpdatesRepository.isAuthorized && mangaId in failed
	}.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
	private val _trackerVolume = MutableStateFlow(TrackerVolumeUiState())
	val trackerVolume = _trackerVolume.asStateFlow()
	private var volumeJob: Job? = null
	private var volumeContext: DetailsPeopleContext? = null
	private var volumeDemanded = false

	init {
		val sessions = if (peopleProviders.isEmpty()) flowOf(emptyList()) else combine(
			peopleProviders.values.map { it.detailsSessionGeneration },
		) { it.toList() }
		val privacy = combine(
			database.getPrivateFavouritesDao().observePrivateOnly(mangaId),
			settings.observeAsFlow(AppSettings.KEY_INCOGNITO_MODE) { isIncognitoModeEnabled },
			settings.observeAsFlow(AppSettings.KEY_INCOGNITO_NSFW) { incognitoModeForNsfw },
		) { privateOnly, _, _ -> privateOnly }
		combine(mangaDetails, database.getScrobblingDao().observeAll(mangaId), privacy, sessions) {
			details, rows, privateOnly, _ ->
				peopleContext(details, rows, privateOnly, peopleContent) to
					peopleContext(details, rows, privateOnly, recommendationContent)
		}.distinctUntilChanged()
			.catch { e ->
				if (e is CancellationException) throw e
				// Unknown privacy/mapping must fail closed, without a core Details error.
				emit(null to null)
			}
			.onEach { (people, recommendations) ->
				peopleController.updateContext(people)
				if (recommendationContext != recommendations) {
					recommendationNavigationJob?.cancel()
					volumeJob?.cancel()
					volumeContext = null
					_trackerVolume.value = TrackerVolumeUiState()
				}
				recommendationContext = recommendations
				recommendationController.updateContext(recommendations)
				if (volumeDemanded) requestTrackerVolume()
			}
			.launchIn(viewModelScope)
	}

	private fun peopleContext(
		details: MangaDetails?, rows: List<ScrobblingEntity>, privateOnly: Boolean, content: Set<TrackerContent>,
	): DetailsPeopleContext? {
		val seed = details?.toManga() ?: return null
		if (seed.id != mangaId) return null // A changed local identity cannot reuse this screen's associations.
		val associations = rows.map { PeopleAssociation(it.scrobbler, it.id, it.targetId) }.toSet()
		val associated = peopleProviders.filterKeys { service -> associations.any { it.serviceId == service.id } }
		return DetailsPeopleContext(
			mangaId = seed.id, source = seed.source.name, url = seed.url,
			associations = associations,
			sessions = associated.mapValues { it.value.detailsSessionGeneration.value },
			services = associated.filterValues { provider ->
				provider.isAuthorized && provider.detailsCapabilities.any { it in content }
			}.keys,
			policy = TrackerDetailsReadPolicy(
				enabled = true,
				incognito = settings.isIncognitoModeEnabled ||
					(seed.isNsfw() && settings.incognitoModeForNsfw != TriStateOption.DISABLED),
				privateOnly = privateOnly || favouriteSpace == FavouriteSpace.PRIVATE,
				onDevice = details.isLocal || seed.source == LocalMangaSource ||
					org.koitharu.kotatsu.local.library.isSmartLocalUri(seed.url),
			),
		)
	}

	fun requestTrackerPeople() = peopleController.request()
	fun resumeTrackerPeople() = peopleController.setActive(true)
	fun pauseTrackerPeople() = peopleController.setActive(false)
	fun retryTrackerPeople(service: ScrobblerService) = peopleController.retry(service)
	fun refreshTrackerPeople() = peopleController.refresh()
	fun requestTrackerRecommendations() = recommendationController.request()
	fun retryTrackerRecommendations(service: ScrobblerService) = recommendationController.retry(service)
	fun refreshTrackerRecommendations() = recommendationController.refresh()
	fun resumeTrackerRecommendations() {
		recommendationsActive = true
		recommendationController.setActive(true)
		if (volumeDemanded) requestTrackerVolume()
	}
	fun pauseTrackerRecommendations() {
		recommendationsActive = false
		recommendationNavigationJob?.cancel()
		volumeJob?.cancel()
		volumeContext = null
		_trackerVolume.value = TrackerVolumeUiState()
		recommendationController.setActive(false)
	}

	fun requestTrackerVolume(force: Boolean = false) {
		volumeDemanded = true
		if (!recommendationsActive || volumeJob?.isActive == true) return
		val key = recommendationContext ?: return
		// Volume editing is an explicit tracking action, including for a linked on-device book.
		if (key.policy.incognito || key.policy.privateOnly || ScrobblerService.MANGAUPDATES !in key.services) return
		if (!force && volumeContext == key && _trackerVolume.value.isRequested) return
		volumeContext = key
		_trackerVolume.value = TrackerVolumeUiState(isRequested = true, isLoading = true)
		volumeJob = viewModelScope.launch {
			try {
				val volume = mangaUpdatesRepository.getVolume(mangaId)
				if (isVolumeContextCurrent(key)) _trackerVolume.value = TrackerVolumeUiState(isRequested = true, volume = volume)
			} catch (e: CancellationException) { throw e } catch (_: Exception) {
				if (isVolumeContextCurrent(key)) _trackerVolume.value = TrackerVolumeUiState(isRequested = true, isError = true)
			} finally {
				if (volumeContext == key && _trackerVolume.value.isLoading) _trackerVolume.value = TrackerVolumeUiState(isRequested = true, isError = true)
			}
		}
	}

	fun updateTrackerVolume(volume: Int) {
		if (volume < 0 || volumeJob?.isActive == true) return
		val key = volumeContext ?: return
		val target = key.associations.singleOrNull { it.serviceId == ScrobblerService.MANGAUPDATES.id }?.targetId ?: return
		val generation = key.sessions[ScrobblerService.MANGAUPDATES] ?: return
		val edit = mangaUpdatesRepository.captureEdit(mangaId, target, generation) ?: return
		_trackerVolume.value = _trackerVolume.value.copy(isLoading = true, isError = false)
		volumeJob = viewModelScope.launch {
			try {
				if (!isVolumeContextCurrent(key)) return@launch
				mangaUpdatesRepository.updateVolume(edit, volume)
				val confirmed = mangaUpdatesRepository.getVolume(mangaId)
				if (isVolumeContextCurrent(key)) _trackerVolume.value = TrackerVolumeUiState(isRequested = true, volume = confirmed)
			} catch (e: CancellationException) { throw e } catch (_: Exception) {
				if (isVolumeContextCurrent(key)) _trackerVolume.value = _trackerVolume.value.copy(isLoading = false, isError = true)
			} finally {
				if (volumeContext == key && _trackerVolume.value.isLoading) _trackerVolume.value = TrackerVolumeUiState(isRequested = true, isError = true)
			}
		}
	}

	fun clearTrackerVolume() {
		volumeDemanded = false
		volumeJob?.cancel()
		volumeContext = null
		_trackerVolume.value = TrackerVolumeUiState()
	}
	fun retryMangaUpdatesProgress() {
		val key = recommendationContext ?: return
		if (recommendationsActive && !key.policy.incognito && !key.policy.privateOnly && ScrobblerService.MANGAUPDATES in key.services) mangaUpdatesRepository.retryProgress(mangaId)
	}
	private suspend fun isVolumeContextCurrent(key: DetailsPeopleContext): Boolean = try {
		recommendationsActive && key == peopleContext(mangaDetails.value, database.getScrobblingDao().findAll(mangaId),
			database.getPrivateFavouritesDao().isPrivateOnly(mangaId), recommendationContent)
	} catch (e: CancellationException) { throw e } catch (_: Exception) { false }

	internal fun isRecommendationNavigationCurrent(event: TrackerRecommendationNavigation): Boolean =
		recommendationsActive && event.context == recommendationContext && event.context.policy.allowsNetwork &&
			!settings.isIncognitoModeEnabled && event.context.sessions.all { (service, generation) ->
				peopleProviders[service]?.let { it.isAuthorized && it.detailsSessionGeneration.value == generation } == true
			}

	internal fun selectTrackerRecommendation(event: TrackerRecommendationNavigation, id: Long) {
		if (isRecommendationNavigationCurrent(event)) navigateTrackerRecommendation(event.recommendation, selectedId = id)
	}

	fun openTrackerRecommendation(item: TrackerRecommendation) = navigateTrackerRecommendation(item)
	fun openTrackerRecommendationProvider(item: TrackerRecommendation) = navigateTrackerRecommendation(item, providerPage = true)

	private fun navigateTrackerRecommendation(item: TrackerRecommendation, selectedId: Long? = null, providerPage: Boolean = false) {
		if (recommendationNavigationJob?.isActive == true || !recommendationsActive) return
		val key = recommendationContext?.takeIf { it.policy.allowsNetwork } ?: return
		if (recommendationController.state.value.providers.none { item in it.recommendations.recommendationItems() }) return
		recommendationNavigationJob = viewModelScope.launch {
			try {
				val current: suspend () -> Boolean = {
					key == peopleContext(mangaDetails.value, database.getScrobblingDao().findAll(mangaId),
						database.getPrivateFavouritesDao().isPrivateOnly(mangaId), recommendationContent) && recommendationsActive
				}
				val result = resolveTrackerRecommendation(item, { target ->
					val candidates = mutableListOf<Manga>()
					val rows = database.getScrobblingDao().findByTarget(target.service.id, target.id.toLong())
					// A large mapping set requires title search rather than an incomplete automatic choice.
					if (rows.size < 65) for (row in rows) {
						if (database.getPrivateFavouritesDao().isPrivateOnly(row.mangaId)) continue
						val manga = mangaDataRepository.findMangaById(row.mangaId, withChapters = false) ?: continue
						if (!manga.source.isBroken && manga.url.isNotBlank()) candidates += manga
					}
					candidates
				}, Manga::id, current) ?: return@launch
				val selected = selectedId?.let { id -> result.candidates.singleOrNull { it.id == id } ?: return@launch }
				val url = if (providerPage) trackerPortraitUrl(item.target.url) ?: return@launch else null
				onTrackerRecommendationNavigation.call(TrackerRecommendationNavigation(key, item, result.candidates, selected, url))
			} catch (e: CancellationException) {
				throw e
			} catch (_: Exception) {
				// Fail closed when association/privacy lookup fails; only this optional section retries.
				recommendationController.refresh()
			}
		}
	}

	// Contextual/genre recommendations are independent from Related Titles. They begin with visibility
	// disabled in the ViewModel, so a persisted closed eye never triggers source/network work before
	// Compose has restored the user's preference. mapLatest cancels the in-flight load when hidden.
	val genreRecommendations: StateFlow<List<MangaListModel>> = combine(
		mangaDetails,
		genreRecommendationsVisible,
		genreRecommendationsActive,
	) { details, visible, active -> Triple(details, visible, active) }
		.mapLatest { (details, visible, active) ->
			if (details != null && details.isLoaded && visible && active && !isSmartLocal) {
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
			if (manga.toManga().url.toUri().scheme != LOCAL_LIBRARY_SCHEME) {
				remoteManga.value = interactor.findRemote(manga.toManga())
			}
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

		// Observe every committed Room snapshot. The reconciliation below already no-ops when the
		// chapter list is identical, while consuming the first emission avoids an ordering assumption
		// that could discard the first meaningful DB update during cold-start/process recreation.
		// The query is manga-scoped and distinctUntilChanged() suppresses unrelated table writes.
		mangaDataRepository.observeChapters(mangaId)
			.mapLatest { chapters -> syncCachedChaptersWhenLoadIdle(chapters) }
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
		if (!relatedDiscoveryEnabled.value || isSmartLocal) return
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
		peopleController.refresh()
		recommendationController.refresh()
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
		if (manga.source != LocalMangaSource || manga.url.toUri().scheme == LOCAL_LIBRARY_SCHEME) return null
		return runCatching { File(manga.url.toUri().schemeSpecificPart) }
			.getOrNull()
			?.takeIf { it.isEpubFile }
	}

	fun updateScrobbling(index: Int, rating: Float, status: ScrobblingStatus?) {
		val scrobbler = getScrobbler(index) ?: return
		if (scrobbler is MangaUpdatesScrobbler) {
			val target = scrobblingInfo.value.getOrNull(index)?.targetId ?: return
			val edit = scrobbler.captureEdit(mangaId, target) ?: return
			launchJob(Dispatchers.Default) { scrobbler.updateScrobblingInfo(edit, rating, status) }
			return
		}
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

	private suspend fun syncCachedChaptersWhenLoadIdle(chapters: List<MangaChapter>) {
		while (true) {
			val observedLoad = loadingJob
			if (observedLoad.isActive) {
				observedLoad.join()
				continue
			}

			val current = mangaDetails.value ?: return
			if (current.isLocal) return

			// Any concurrent Details load, override edit or download/local event gets priority. Retry from
			// the newest state instead of replacing it with the Room snapshot emitted above.
			if (loadingJob !== observedLoad || mangaDetails.value !== current) continue

			val currentSourceChapters = current.sourceManga.chapters.orEmpty()
			// A cache cleanup or other empty DB snapshot must not blank an already renderable Details list.
			if (chapters.isEmpty() && currentSourceChapters.isNotEmpty()) return

			var updated = current.copy(manga = current.sourceManga.copy(chapters = chapters))
			if (mangaDataRepository.isScanlatorsMerged(mangaId)) {
				updated = updated.withMergedBranches()
			}
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

