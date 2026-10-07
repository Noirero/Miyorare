package org.koitharu.kotatsu.reader.ui

import android.net.Uri
import androidx.annotation.AnyThread
import androidx.annotation.MainThread
import androidx.annotation.WorkerThread
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.plus
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.bookmarks.domain.Bookmark
import org.koitharu.kotatsu.bookmarks.domain.BookmarksRepository
import org.koitharu.kotatsu.core.exceptions.EmptyMangaException
import org.koitharu.kotatsu.core.model.getPreferredBranch
import org.koitharu.kotatsu.core.model.isNovelContent
import org.koitharu.kotatsu.core.nav.MangaIntent
import org.koitharu.kotatsu.core.nav.ReaderIntent
import org.koitharu.kotatsu.core.os.AppShortcutManager
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.ReaderMode
import org.koitharu.kotatsu.core.prefs.TriStateOption
import org.koitharu.kotatsu.core.prefs.observeAsFlow
import org.koitharu.kotatsu.core.prefs.observeAsStateFlow
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.core.util.ext.firstNotNull
import org.koitharu.kotatsu.core.util.ext.isHttpUrl
import org.koitharu.kotatsu.core.util.ext.requireValue
import org.koitharu.kotatsu.details.data.MangaDetails
import org.koitharu.kotatsu.details.domain.DetailsInteractor
import org.koitharu.kotatsu.details.domain.DetailsLoadUseCase
import org.koitharu.kotatsu.details.ui.pager.ChaptersPagesViewModel
import org.koitharu.kotatsu.details.ui.pager.EmptyMangaReason
import org.koitharu.kotatsu.download.domain.DownloadDestinationStore
import org.koitharu.kotatsu.download.ui.worker.DownloadWorker
import org.koitharu.kotatsu.favourites.data.EXTRA_FAVOURITE_SPACE
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.history.data.HistoryRepository
import org.koitharu.kotatsu.history.domain.HistoryUpdateUseCase
import org.koitharu.kotatsu.list.domain.ReadingProgress
import org.koitharu.kotatsu.list.domain.ReadingProgress.Companion.PROGRESS_NONE
import org.koitharu.kotatsu.local.data.LocalStorageChanges
import org.koitharu.kotatsu.local.data.isEpub
import org.koitharu.kotatsu.local.domain.DeleteLocalMangaUseCase
import org.koitharu.kotatsu.local.domain.model.LocalManga
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.util.ifNullOrEmpty
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.parsers.util.sizeOrZero
import org.koitharu.kotatsu.reader.domain.ChaptersLoader
import org.koitharu.kotatsu.reader.domain.DetectReaderModeUseCase
import org.koitharu.kotatsu.reader.domain.PageLoadFailureEvents
import org.koitharu.kotatsu.reader.domain.PageLoader
import org.koitharu.kotatsu.reader.domain.PageMetadataRecovery
import org.koitharu.kotatsu.reader.domain.PageMetadataRecoverySession
import org.koitharu.kotatsu.readerjourney.domain.ReaderJourneyCollector
import org.koitharu.kotatsu.reader.ui.config.ReaderSettings
import org.koitharu.kotatsu.reader.ui.pager.ReaderPage
import org.koitharu.kotatsu.reader.ui.pager.ReaderUiState
import org.koitharu.kotatsu.scrobbling.discord.ui.DiscordRpc
import org.koitharu.kotatsu.stats.domain.StatsCollector
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

private const val BOUNDS_PAGE_OFFSET = 2
private const val PREFETCH_LIMIT = 10
private const val EPUB_SLIDER_MAX = 1000

@HiltViewModel
class ReaderViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val dataRepository: MangaDataRepository,
    private val historyRepository: HistoryRepository,
    private val bookmarksRepository: BookmarksRepository,
    settings: AppSettings,
    private val pageLoader: PageLoader,
    private val chaptersLoader: ChaptersLoader,
    private val appShortcutManager: AppShortcutManager,
    private val detailsLoadUseCase: DetailsLoadUseCase,
    private val historyUpdateUseCase: HistoryUpdateUseCase,
    private val detectReaderModeUseCase: DetectReaderModeUseCase,
    private val statsCollector: StatsCollector,
    private val readerJourneyCollector: ReaderJourneyCollector,
    private val discordRpc: DiscordRpc,
    @LocalStorageChanges localStorageChanges: SharedFlow<LocalManga?>,
    interactor: DetailsInteractor,
    deleteLocalMangaUseCase: DeleteLocalMangaUseCase,
    downloadScheduler: DownloadWorker.Scheduler,
    downloadDestinationStore: DownloadDestinationStore,
    readerSettingsProducerFactory: ReaderSettings.Producer.Factory,
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
    mangaDataRepository = dataRepository,
    mangaRepositoryFactory = mangaRepositoryFactory,
) {
    private val intent = MangaIntent(savedStateHandle)
    private val pageMetadataRecoverySession = PageMetadataRecoverySession()

    private var loadingJob: Job? = null
    private var pageSaveJob: Job? = null
    private var bookmarkJob: Job? = null
    private var stateChangeJob: Job? = null
    private var deferredHistorySaveJob: Job? = null

    init {
        mangaDetails.value = intent.manga?.let { MangaDetails(it) }
    }

    val readerMode = MutableStateFlow<ReaderMode?>(null)
    val onPageSaved = MutableEventFlow<Collection<Uri>>()
    val onLoadingError = MutableEventFlow<Throwable>()
    val onShowToast = MutableEventFlow<Int>()
    val onReaderJourneyMilestone = readerJourneyCollector.onMilestoneUnlocked
    val onReaderJourneyProgressed = readerJourneyCollector.onJourneyProgressed
    val onAskNsfwIncognito = MutableEventFlow<Unit>()
    val uiState = MutableStateFlow<ReaderUiState?>(null)

    val isIncognitoMode = MutableStateFlow(savedStateHandle.get<Boolean>(ReaderIntent.EXTRA_INCOGNITO))

    // Peek mode: the reader works as usual but never writes reading progress (history) —
    // used when the user just looks into a chapter away from their current position.
    val isPeekMode = MutableStateFlow(savedStateHandle.get<Boolean>(ReaderIntent.EXTRA_PEEK) == true)

    val content = MutableStateFlow(ReaderContent(emptyList(), null))

    val pageAnimation = settings.observeAsStateFlow(
        scope = viewModelScope + Dispatchers.Default,
        key = AppSettings.KEY_READER_ANIMATION,
        valueProducer = { readerAnimation },
    )

    val isInfoBarEnabled = settings.observeAsStateFlow(
        scope = viewModelScope + Dispatchers.Default,
        key = AppSettings.KEY_READER_BAR,
        valueProducer = { isReaderBarEnabled },
    )

    val isKeepScreenOnEnabled = settings.observeAsStateFlow(
        scope = viewModelScope + Dispatchers.Default,
        key = AppSettings.KEY_READER_SCREEN_ON,
        valueProducer = { isReaderKeepScreenOn },
    )

    val isWebtoonGapsEnabled = settings.observeAsStateFlow(
        scope = viewModelScope + Dispatchers.Default,
        key = AppSettings.KEY_WEBTOON_GAPS,
        valueProducer = { isWebtoonGapsEnabled },
    )

    val isWebtoonPullGestureEnabled = settings.observeAsStateFlow(
        scope = viewModelScope + Dispatchers.Default,
        key = AppSettings.KEY_WEBTOON_PULL_GESTURE,
        valueProducer = { isWebtoonPullGestureEnabled },
    )

    val isWebtoonZoomEnabled = observeIsWebtoonZoomEnabled()
        .stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Lazily, false)

    val defaultWebtoonZoomOut = observeIsWebtoonZoomEnabled().flatMapLatest {
        if (it) observeWebtoonZoomOut() else flowOf(0f)
    }.flowOn(Dispatchers.Default)

    val isZoomControlsEnabled = getObserveIsZoomControlEnabled().flatMapLatest { zoom ->
        if (zoom) {
            combine(readerMode, isWebtoonZoomEnabled) { mode, ze -> ze || mode != ReaderMode.WEBTOON }
        } else {
            flowOf(false)
        }
    }.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Lazily, false)

    val readerSettingsProducer = readerSettingsProducerFactory.create(
        mangaId = manga.mapNotNull { it?.id },
        initialMangaId = intent.mangaId,
    )

    val isMangaNsfw = manga.map { it?.contentRating == ContentRating.ADULT }

    private val readerBookmarks = manga.flatMapLatest { currentManga ->
        if (currentManga == null) flowOf(emptyList()) else bookmarksRepository.observeBookmarks(currentManga)
    }.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, emptyList())

    val isBookmarkAdded = combine(readingState, manga, readerBookmarks) { state, currentManga, bookmarks ->
        state != null && currentManga != null && currentBookmark(currentManga, state, bookmarks) != null
    }.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, false)

    init {
        initIncognitoMode()
        observePageMetadataFailures()
        loadImpl()
        launchJob(Dispatchers.Default) {
            val mangaId = manga.filterNotNull().first().id
            if (!isIncognitoMode.firstNotNull()) appShortcutManager.notifyMangaOpened(mangaId)
        }
    }

    override fun reload() {
        loadingJob?.cancel()
        loadImpl()
    }

    fun onPause() {
        getMangaOrNull()?.let { manga ->
            if (isIncognitoMode.value == false && !isPeekMode.value) {
                statsCollector.onPause(manga.id)
                readerJourneyCollector.onPause(manga.id)
            } else {
                statsCollector.discard(manga.id)
                readerJourneyCollector.discard(manga.id)
            }
        }
    }

    fun onStop() = discordRpc.clearRpc()
    fun onIdle() = discordRpc.setIdle()

    fun switchMode(newMode: ReaderMode) {
        readerMode.value = newMode
        launchJob {
            val manga = checkNotNull(getMangaOrNull())
            dataRepository.saveReaderMode(manga = manga, mode = newMode)
            content.update { it.copy(state = getCurrentState()) }
        }
    }

    fun saveCurrentState(state: ReaderState? = null) {
        if (state != null) {
            readingState.value = state
            savedStateHandle[ReaderIntent.EXTRA_STATE] = state
        }
        if (isIncognitoMode.value != false || isPeekMode.value) return
        val readerState = state ?: readingState.value ?: return
        historyUpdateUseCase.invokeAsync(
            manga = getMangaOrNull() ?: return,
            readerState = readerState,
            percent = computePercent(readerState),
        )
    }

    fun getCurrentState() = readingState.value

    fun getCurrentChapterPages(): List<MangaPage>? {
        val chapterId = readingState.value?.chapterId ?: return null
        return chaptersLoader.getPages(chapterId)
    }

    fun saveCurrentPage(pageSaveHelper: PageSaveHelper) {
        val prevJob = pageSaveJob
        pageSaveJob = launchLoadingJob(Dispatchers.Default) {
            prevJob?.cancelAndJoin()
            val state = checkNotNull(getCurrentState())
            val currentManga = manga.requireValue()
            val task = PageSaveHelper.Task(
                manga = currentManga,
                chapterId = state.chapterId,
                pageNumber = state.page + 1,
                page = checkNotNull(getCurrentPage()) { "Cannot find current page" },
            )
            onPageSaved.call(pageSaveHelper.save(setOf(task)))
        }
    }

    fun getCurrentPage(): MangaPage? {
        val state = readingState.value ?: return null
        return content.value.pages.find { it.chapterId == state.chapterId && it.index == state.page }?.toMangaPage()
    }

    fun switchChapter(id: Long, page: Int, scroll: Int = 0) {
        val prevJob = loadingJob
        loadingJob = launchLoadingJob(Dispatchers.Default) {
            prevJob?.cancelAndJoin()
            if (!chaptersLoader.loadSingleChapter(id)) return@launchLoadingJob
            val newState = ReaderState(id, page, scroll)
            content.value = ReaderContent(chaptersLoader.snapshot(), newState)
            saveCurrentState(newState)
        }
    }

    fun switchChapterBy(delta: Int) {
        val prevJob = loadingJob
        loadingJob = launchLoadingJob(Dispatchers.Default) {
            prevJob?.cancelAndJoin()
            val prevState = readingState.requireValue()
            val newChapterId = if (delta != 0) {
                val allChapters = mangaDetails.requireValue().allChapters
                var index = allChapters.indexOfFirst { it.id == prevState.chapterId }
                if (index < 0) return@launchLoadingJob
                index += delta
                (allChapters.getOrNull(index) ?: return@launchLoadingJob).id
            } else prevState.chapterId
            if (!chaptersLoader.loadSingleChapter(newChapterId)) return@launchLoadingJob
            val newState = ReaderState(
                chapterId = newChapterId,
                page = if (delta == 0) prevState.page else 0,
                scroll = if (delta == 0) prevState.scroll else 0,
            )
            content.value = ReaderContent(chaptersLoader.snapshot(), newState)
            saveCurrentState(newState)
        }
    }

    @MainThread
    fun onCurrentPageChanged(lowerPos: Int, upperPos: Int) {
        val prevJob = stateChangeJob
        val pages = content.value.pages
        stateChangeJob = launchJob(Dispatchers.Default) {
            prevJob?.cancelAndJoin()
            loadingJob?.join()
            if (pages.size != content.value.pages.size) return@launchJob
            val centerPos = (lowerPos + upperPos) / 2
            pages.getOrNull(centerPos)?.let { page ->
                readingState.update { it?.copy(chapterId = page.chapterId, page = page.index) }
            }
            notifyStateChanged()
            if (pages.isEmpty() || loadingJob?.isActive == true) return@launchJob
            ensureActive()
            val autoLoadAllowed = readerMode.value != ReaderMode.WEBTOON || !isWebtoonPullGestureEnabled.value
            if (autoLoadAllowed) {
                if (upperPos >= pages.lastIndex - BOUNDS_PAGE_OFFSET) loadPrevNextChapter(pages.last().chapterId, true)
                if (lowerPos <= BOUNDS_PAGE_OFFSET) loadPrevNextChapter(pages.first().chapterId, false)
            }
            if (pageLoader.isPrefetchApplicable()) {
                pageLoader.prefetch(pages.trySublist(upperPos + 1, upperPos + PREFETCH_LIMIT))
            }
        }
    }

    fun toggleBookmark() {
        if (bookmarkJob?.isActive == true) return
        bookmarkJob = launchJob(Dispatchers.Default) {
            loadingJob?.join()
            val state = checkNotNull(getCurrentState())
            val manga = requireManga()
            val existingBookmark = currentBookmark(manga, state, readerBookmarks.value)
            if (existingBookmark != null) {
                bookmarksRepository.removeBookmarks(setOf(existingBookmark.pageId))
                onShowToast.call(R.string.bookmark_removed)
            } else {
                val isEpub = manga.isEpub
                val page = getCurrentPage()
                val bookmark = Bookmark(
                    manga = manga,
                    pageId = if (isEpub) UUID.randomUUID().leastSignificantBits and Long.MAX_VALUE else checkNotNull(page).id,
                    chapterId = state.chapterId,
                    page = state.page,
                    scroll = state.scroll,
                    imageUrl = if (isEpub) manga.coverUrl.orEmpty() else checkNotNull(page).let { it.preview.ifNullOrEmpty { it.url } },
                    createdAt = Instant.now(),
                    percent = computePercent(state),
                )
                bookmarksRepository.addBookmark(bookmark)
                onShowToast.call(R.string.bookmark_added)
            }
        }
    }

    override suspend fun getChapterOpenMode(chapterId: Long): ChapterOpenMode =
        if (isIncognitoMode.value == true) ChapterOpenMode.NORMAL else super.getChapterOpenMode(chapterId)

    fun setPeekMode(value: Boolean) {
        if (isPeekMode.value == value) return
        isPeekMode.value = value
        savedStateHandle[ReaderIntent.EXTRA_PEEK] = value
        if (value) {
            discardCurrentSessionTracking()
            onShowToast.call(R.string.peek_mode_hint)
        }
    }

    fun setIncognitoMode(value: Boolean, dontAskAgain: Boolean) {
        isIncognitoMode.value = value
        if (value) discardCurrentSessionTracking()
        if (dontAskAgain) settings.incognitoModeForNsfw = if (value) TriStateOption.ENABLED else TriStateOption.DISABLED
    }

    private fun observePageMetadataFailures() {
        launchJob(Dispatchers.Default) {
            PageLoadFailureEvents.events.collect { failure ->
                val failedPage = pageMetadataRecoverySession.record(content.value.pages, failure) ?: return@collect
                recoverPageMetadata(failedPage)
            }
        }
    }

    private suspend fun recoverPageMetadata(failedPage: ReaderPage) {
        val stateBeforeRefresh = readingState.value
        val oldPageId = stateBeforeRefresh
            ?.takeIf { it.chapterId == failedPage.chapterId }
            ?.let { state -> content.value.pages.firstOrNull { it.chapterId == state.chapterId && it.index == state.page }?.id }
        if (!chaptersLoader.refreshChapterPages(failedPage.chapterId)) return
        val freshPages = chaptersLoader.snapshot()
        val currentState = readingState.value
        val preservedState = if (
            stateBeforeRefresh != null && currentState == stateBeforeRefresh && stateBeforeRefresh.chapterId == failedPage.chapterId
        ) {
            PageMetadataRecovery.preserveState(stateBeforeRefresh, oldPageId, freshPages)
        } else currentState
        if (currentState == stateBeforeRefresh && preservedState != null) {
            readingState.value = preservedState
        }
        content.value = ReaderContent(freshPages, preservedState)
        if (preservedState != null) notifyStateChanged(trackProgress = false)
    }

    private fun loadImpl() {
        loadingJob = launchLoadingJob(Dispatchers.Default + EventExceptionHandler(onLoadingError)) {
            var exception: Exception? = null
            var loadedDetails: MangaDetails? = null
            try {
                detailsLoadUseCase(
                    intent = intent,
                    force = false,
                    favouriteSpace = favouriteSpace,
                    preferLocalBeforeInitialSnapshot = true,
                ).collect { details ->
                    loadedDetails = details
                    if (mangaDetails.value == null) mangaDetails.value = details
                    chaptersLoader.init(details)
                    val manga = details.toManga()
                    if (readingState.value == null) {
                        val newState = getStateFromIntent(manga, details.isLoaded) ?: return@collect
                        readingState.value = newState
                        val mode = runCatchingCancellable { detectReaderModeUseCase(manga, newState) }.getOrDefault(settings.defaultReaderMode)
                        selectedBranch.value = chaptersLoader.peekChapter(newState.chapterId)?.branch
                        readerMode.value = mode
                        try {
                            if (!chaptersLoader.loadSingleChapter(newState.chapterId)) {
                                readingState.value = null
                                return@collect
                            }
                        } catch (e: Exception) {
                            readingState.value = null
                            exception = e.mergeWith(exception)
                            return@collect
                        }
                    }
                    mangaDetails.value = details.filterChapters(selectedBranch.value)
                    notifyStateChanged()
                    content.value = ReaderContent(chaptersLoader.snapshot(), readingState.value)
                    saveLoadedStateAsync(manga)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                exception = e.mergeWith(exception)
            }
            if (readingState.value == null) {
                val loadedManga = loadedDetails
                if (loadedManga != null) mangaDetails.value = loadedManga.filterChapters(selectedBranch.value)
                val loadingError = when {
                    exception != null -> exception
                    loadedManga == null || !loadedManga.isLoaded -> null
                    loadedManga.isRestricted -> EmptyMangaException(EmptyMangaReason.RESTRICTED, loadedManga.toManga(), null)
                    loadedManga.allChapters.isEmpty() -> EmptyMangaException(EmptyMangaReason.NO_CHAPTERS, loadedManga.toManga(), null)
                    else -> null
                } ?: IllegalStateException("Unable to load manga. This should never happen. Please report")
                onLoadingError.call(loadingError)
            } else exception?.let { errorEvent.call(it) }
        }
    }

    private fun saveLoadedStateAsync(manga: Manga) {
        if (isPeekMode.value) return
        val state = readingState.value ?: return
        when (isIncognitoMode.value) {
            false -> historyUpdateUseCase.invokeAsync(manga, state, computePercent(state))
            true -> Unit
            null -> {
                deferredHistorySaveJob?.cancel()
                deferredHistorySaveJob = launchJob(Dispatchers.Default) {
                    if (!isIncognitoMode.firstNotNull() && !isPeekMode.value) {
                        val latestState = readingState.value ?: return@launchJob
                        historyUpdateUseCase.invokeAsync(
                            manga = getMangaOrNull() ?: manga,
                            readerState = latestState,
                            percent = computePercent(latestState),
                        )
                    }
                }
            }
        }
    }

    @AnyThread
    private fun loadPrevNextChapter(currentId: Long, isNext: Boolean) {
        val prevJob = loadingJob
        loadingJob = launchLoadingJob(Dispatchers.Default) {
            prevJob?.join()
            chaptersLoader.loadPrevNextChapter(mangaDetails.requireValue(), currentId, isNext)
            content.value = ReaderContent(chaptersLoader.snapshot(), null)
        }
    }

    private fun <T> List<T>.trySublist(fromIndex: Int, toIndex: Int): List<T> {
        val fromIndexBounded = fromIndex.coerceIn(0, size)
        val toIndexBounded = toIndex.coerceIn(fromIndexBounded, size)
        return if (fromIndexBounded == toIndexBounded) emptyList() else subList(fromIndexBounded, toIndexBounded)
    }

    fun onEpubProgressChanged(
        chapterId: Long,
        charOffset: Int,
        chapterPm: Int,
        readingUnits: Int,
        page: Int = 0,
        pageCount: Int = 0,
    ) {
        val chapterChanged = uiState.value?.chapter?.id != chapterId
        readingState.update {
            it?.copy(
                chapterId = chapterId,
                page = if (pageCount > 0) page.coerceIn(0, pageCount - 1) else 0,
                scroll = ReaderState.encodeEpubOffset(charOffset),
            )
        }
        updateEpubProgressUi(chapterPm, page, pageCount)
        val currentManga = getMangaOrNull()
        if (currentManga?.isNovelContent == true && isIncognitoMode.value == false && !isPeekMode.value) {
            statsCollector.onNovelProgress(currentManga.id, chapterId, chapterPm)
            readerJourneyCollector.onNovelProgress(currentManga.id, chapterId, chapterPm, readingUnits)
        }
        if (chapterChanged) {
            launchJob(Dispatchers.Default) {
                notifyStateChanged()
                updateEpubProgressUi(chapterPm, page, pageCount)
            }
        }
    }

    private fun updateEpubProgressUi(chapterPm: Int, page: Int, pageCount: Int) {
        uiState.update {
            it?.copy(
                currentPage = if (pageCount > 0) page.coerceIn(0, pageCount - 1) else chapterPm.coerceIn(0, EPUB_SLIDER_MAX),
                totalPages = if (pageCount > 0) pageCount else EPUB_SLIDER_MAX + 1,
                isEpubPaged = pageCount > 0,
            )
        }
    }

    @WorkerThread
    private fun notifyStateChanged(trackProgress: Boolean = true) {
        val state = getCurrentState() ?: return
        val chapter = chaptersLoader.peekChapter(state.chapterId) ?: return
        val m = mangaDetails.value ?: return
        val chapterIndex = m.chapters[chapter.branch]?.indexOfFirst { it.id == chapter.id } ?: -1
        val isEpub = m.toManga().isEpub
        val prevUi = uiState.value
        val isEpubPaged = isEpub && prevUi?.isEpubPaged == true
        val totalPages = when {
            !isEpub -> chaptersLoader.getPagesCount(chapter.id)
            isEpubPaged -> prevUi.totalPages
            else -> EPUB_SLIDER_MAX + 1
        }
        val newState = ReaderUiState(
            mangaName = m.toManga().title,
            chapter = chapter,
            chapterIndex = chapterIndex,
            chaptersTotal = m.chapters[chapter.branch].sizeOrZero(),
            totalPages = totalPages,
            currentPage = when {
                !isEpub -> state.page
                isEpubPaged -> prevUi.currentPage.coerceIn(0, (totalPages - 1).coerceAtLeast(0))
                ReaderState.decodeEpubOffset(state.scroll) != null -> (prevUi?.currentPage ?: 0).coerceIn(0, EPUB_SLIDER_MAX)
                else -> state.scroll.coerceIn(0, EPUB_SLIDER_MAX)
            },
            percent = computePercent(state),
            incognito = isIncognitoMode.value == true,
            isPeek = isPeekMode.value,
            isEpub = isEpub,
            isEpubPaged = isEpubPaged,
        )
        uiState.value = newState
        if (!trackProgress) return
        if (isIncognitoMode.value == false) {
            val currentManga = m.toManga()
            if (!isPeekMode.value) {
                statsCollector.onStateChanged(m.id, state, totalPages)
                if (!currentManga.isNovelContent) {
                    readerJourneyCollector.onMangaProgress(currentManga.id, state.chapterId, state.page, totalPages)
                }
            }
            val discordCover = m.toManga().coverUrl?.takeIf { it.isHttpUrl() } ?: m.sourceManga.coverUrl
            discordRpc.updateRpc(m.toManga(), newState, discordCover)
        }
    }

    private fun computePercent(state: ReaderState): Float {
        val branch = chaptersLoader.peekChapter(state.chapterId)?.branch
        val chapters = mangaDetails.value?.chapters?.get(branch) ?: return PROGRESS_NONE
        val chaptersCount = chapters.size
        val chapterIndex = chapters.indexOfFirst { it.id == state.chapterId }
        val (pageIndex, pagesCount) = if (chaptersCount == 1) getPageProgress(state) else 0 to 0
        return ReadingProgress.calculatePercent(chapterIndex, chaptersCount, pageIndex, pagesCount)
    }

    private fun currentBookmark(manga: Manga, state: ReaderState, bookmarks: List<Bookmark>): Bookmark? =
        bookmarks.firstOrNull { bookmark ->
            bookmark.chapterId == state.chapterId && if (manga.isEpub) bookmark.scroll == state.scroll else bookmark.page == state.page
        }

    private fun getPageProgress(state: ReaderState): Pair<Int, Int> {
        if (mangaDetails.value?.toManga()?.isEpub != true) return state.page to chaptersLoader.getPagesCount(state.chapterId)
        val progress = uiState.value?.takeIf { it.chapter.id == state.chapterId }
        if (progress != null) return progress.currentPage to progress.totalPages
        return state.scroll.coerceIn(0, EPUB_SLIDER_MAX) to (EPUB_SLIDER_MAX + 1)
    }

    private fun observeIsWebtoonZoomEnabled() = settings.observeAsFlow(
        key = AppSettings.KEY_WEBTOON_ZOOM,
        valueProducer = { isWebtoonZoomEnabled },
    )

    private fun observeWebtoonZoomOut() = settings.observeAsFlow(
        key = AppSettings.KEY_WEBTOON_ZOOM_OUT,
        valueProducer = { defaultWebtoonZoomOut },
    )

    private fun getObserveIsZoomControlEnabled() = settings.observeAsFlow(
        key = AppSettings.KEY_READER_ZOOM_BUTTONS,
        valueProducer = { isReaderZoomButtonsEnabled },
    )

    private fun initIncognitoMode() {
        if (isIncognitoMode.value != null) return
        launchJob(Dispatchers.Default) {
            interactor.observeIncognitoMode(manga).collect {
                when (it) {
                    TriStateOption.ENABLED -> {
                        isIncognitoMode.value = true
                        discardCurrentSessionTracking()
                    }
                    TriStateOption.ASK -> {
                        onAskNsfwIncognito.call(Unit)
                        return@collect
                    }
                    TriStateOption.DISABLED -> isIncognitoMode.value = false
                }
            }
        }
    }

    private fun discardCurrentSessionTracking() {
        getMangaOrNull()?.id?.let { mangaId ->
            statsCollector.discard(mangaId)
            readerJourneyCollector.discard(mangaId)
        }
    }

    private suspend fun getStateFromIntent(manga: Manga, isLoaded: Boolean): ReaderState? {
        if (manga.chapters.isNullOrEmpty()) return null
        val requestedState: ReaderState? = savedStateHandle[ReaderIntent.EXTRA_STATE]
        if (requestedState != null) {
            when {
                manga.findChapterById(requestedState.chapterId) != null -> return requestedState
                !isLoaded -> return null
            }
        }
        val requestedBranch: String? = savedStateHandle[ReaderIntent.EXTRA_BRANCH]
        val history = historyRepository.getOne(manga)
        if (history != null) {
            val chapter = manga.findChapterById(history.chapterId)
            when {
                chapter == null -> if (!isLoaded) return null
                ReaderIntent.EXTRA_BRANCH in savedStateHandle -> return if (chapter.branch == requestedBranch) ReaderState(history) else ReaderState(manga, requestedBranch)
                else -> return ReaderState(history)
            }
        }
        return ReaderState(manga, requestedBranch ?: manga.getPreferredBranch(null))
    }

    private fun Exception.mergeWith(other: Exception?): Exception = if (other == null) this else {
        other.addSuppressed(this)
        other
    }
}
