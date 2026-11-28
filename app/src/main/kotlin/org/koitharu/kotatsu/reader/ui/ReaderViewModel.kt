package org.koitharu.kotatsu.reader.ui

import android.net.Uri
import android.os.FileObserver
import androidx.annotation.AnyThread
import androidx.annotation.MainThread
import androidx.annotation.WorkerThread
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
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
import org.koitharu.kotatsu.core.util.ext.processLifecycleScope
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.details.data.MangaDetails
import org.koitharu.kotatsu.details.data.ChapterPersonalRepository
import org.koitharu.kotatsu.details.data.ChapterProgressRepository
import org.koitharu.kotatsu.details.data.ChapterPersonalKey
import org.koitharu.kotatsu.details.ui.resolveChapterPersonalKey
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
import org.koitharu.kotatsu.local.library.LocalContentReader
import org.koitharu.kotatsu.local.domain.DeleteLocalMangaUseCase
import org.koitharu.kotatsu.local.domain.DownloadedMangaResolver
import org.koitharu.kotatsu.local.domain.model.LocalManga
import org.koitharu.kotatsu.local.domain.model.ExhentaiContentVersion
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
import java.io.File
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.transformLatest
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.logs.AppLogger
import org.koitharu.kotatsu.reader.domain.ReaderDiagnostics

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
    chapterPersonalRepository: ChapterPersonalRepository,
    chapterProgressRepository: ChapterProgressRepository,
    localContentReader: LocalContentReader,
    appLogger: AppLogger,
    private val database: MangaDatabase,
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
    downloadSpaceProvenance = savedStateHandle.get<Int>(EXTRA_FAVOURITE_SPACE)?.let { FavouriteSpace.fromArgument(it) },
    deleteLocalMangaUseCase = deleteLocalMangaUseCase,
    localStorageChanges = localStorageChanges,
    mangaDataRepository = dataRepository,
    mangaRepositoryFactory = mangaRepositoryFactory,
    chapterPersonalRepository = chapterPersonalRepository,
    chapterProgressRepository = chapterProgressRepository,
    localContentReader = localContentReader,
) {
    override fun onCleared() {
        // Let the process-scoped consumer drain the last position during reader teardown.
        chapterProgressWrites.close()
        pageLoader.diagnostics = null
        chaptersLoader.diagnostics = null
        // The ViewModel scope is cancelled during teardown; use an independent IO job to release
        // Reader-owned backing without blocking the main thread.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { chaptersLoader.close() }
        super.onCleared()
    }

    private val titleSettings = settings
    private val intent = MangaIntent(savedStateHandle)
    internal class ExhentaiChoiceRequest(
        val choices: List<DownloadedMangaResolver.ExhentaiReadChoice>,
        val privateOnly: Boolean,
        val answer: CompletableDeferred<DownloadedMangaResolver.ExhentaiReadChoice?> = CompletableDeferred(),
    )

    private val mutableExhentaiChoiceRequest = MutableStateFlow<ExhentaiChoiceRequest?>(null)
    internal val exhentaiChoiceRequest = mutableExhentaiChoiceRequest
    // Owned by this Reader ViewModel only; a new Reader never inherits it.
    @Volatile private var exhentaiSessionChoice: DownloadedMangaResolver.ExhentaiSessionChoice? = null
    internal val hasExhentaiSessionChoice: Boolean get() = exhentaiSessionChoice != null

    private suspend fun chooseExhentaiFile(
        manga: Manga,
        space: FavouriteSpace,
        choices: List<DownloadedMangaResolver.ExhentaiReadChoice>,
    ): DownloadedMangaResolver.ExhentaiSessionChoice? {
        exhentaiSessionChoice?.let { return it }
        if (choices.isEmpty()) return null
        // Determine Private-only content from Room before exposing any filesystem path.
        val privateOnly = space == FavouriteSpace.PRIVATE ||
            database.getPrivateFavouritesDao().observePrivateOnly(manga.id).first()
        val request = ExhentaiChoiceRequest(choices, privateOnly)
        mutableExhentaiChoiceRequest.value = request
        try {
            val selected = request.answer.await() ?: throw CancellationException("CBZ choice cancelled")
            val approved = detailsLoadUseCase.approveExhentaiReadChoice(manga, space, selected)
                ?: throw java.io.IOException("Selected CBZ changed or is unreadable")
            exhentaiSessionChoice = approved
            return approved
        } finally {
            if (mutableExhentaiChoiceRequest.value === request) mutableExhentaiChoiceRequest.value = null
        }
    }
    internal enum class ExhentaiFileChange { CHANGED, RELOADING, UNAVAILABLE }

    private val mutableExhentaiFileChange = MutableStateFlow<ExhentaiFileChange?>(null)
    internal val exhentaiFileChange = mutableExhentaiFileChange
    internal val hasPinnedExhentaiVersion: Boolean
        get() = mangaDetails.value?.local?.exhentaiContentVersion != null
    private var exhentaiReloadJob: Job? = null
    private val exhentaiHistoryLock = Any()
    private val exhentaiHistoryWrites = HashSet<Job>()

    private fun markExhentaiFileChanged() = synchronized(exhentaiHistoryLock) {
        if (mutableExhentaiFileChange.value == null) {
            mutableExhentaiFileChange.value = ExhentaiFileChange.CHANGED
        }
    }

    /** Only the foreground Reader's selected archive is watched. Resume also verifies full bytes. */
    internal suspend fun monitorExhentaiFileChanges() {
        combine(mangaDetails, readingState, content) { details, state, displayed ->
            details?.local?.exhentaiContentVersion?.takeIf { version ->
                state?.chapterId == version.chapterId &&
                    displayed.pages.any { it.chapterId == version.chapterId }
            }
        }.distinctUntilChanged().collectLatest { version ->
            if (version == null) return@collectLatest
            val file = File(version.path)
            callbackFlow<Unit> {
                val mask = FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO or FileObserver.MOVED_FROM or
                    FileObserver.DELETE or FileObserver.CREATE or FileObserver.ATTRIB or
                    FileObserver.DELETE_SELF or FileObserver.MOVE_SELF
                @Suppress("DEPRECATION")
                val watcher = object : FileObserver(file.parentFile!!.path, mask) {
                    override fun onEvent(event: Int, path: String?) {
                        if (path == null || path == file.name) trySend(Unit)
                    }
                }
                watcher.startWatching()
                // Watch first, then hash, so a write during verification also queues a check.
                trySend(Unit)
                awaitClose { watcher.stopWatching() }
            }.buffer(Channel.CONFLATED).collect {
                if (mutableExhentaiFileChange.value == ExhentaiFileChange.RELOADING) return@collect
                val current = detailsLoadUseCase.isExhentaiReaderVersionCurrent(favouriteSpace, version)
                if (!current && mangaDetails.value?.local?.exhentaiContentVersion == version &&
                    readingState.value?.chapterId == version.chapterId &&
                    mutableExhentaiFileChange.value == null
                ) markExhentaiFileChanged()
            }
        }
    }

    /** Explicit user action. Keep the old cursor/snapshot on any unavailable or corrupt replacement. */
    @MainThread
    internal fun reloadChangedExhentaiFile() {
        if (mutableExhentaiFileChange.value == null || exhentaiReloadJob?.isActive == true) return
        val oldDetails = mangaDetails.value ?: return
        val previous = oldDetails.local?.exhentaiContentVersion ?: return
        val oldState = readingState.value?.takeIf { it.chapterId == previous.chapterId } ?: return
        mutableExhentaiFileChange.value = ExhentaiFileChange.RELOADING
        exhentaiReloadJob = launchJob(Dispatchers.Default) {
            var succeeded = false
            try {
                loadingJob?.cancelAndJoin()
                stateChangeJob?.cancelAndJoin()
                bookmarkJob?.cancelAndJoin()
                deferredHistorySaveJob?.cancelAndJoin()
                // No old asynchronous History write may overtake the new cursor.
                val writes = synchronized(exhentaiHistoryLock) { exhentaiHistoryWrites.toList() }
                // Tracker/scrobbler work can outlive the local transaction. Fail safely instead
                // of indefinitely blocking this explicit reload on an unfinished old write.
                if (withTimeoutOrNull(15000) { writes.joinAll(); true } != true) return@launchJob
                val (local, selection) = detailsLoadUseCase.reloadExhentaiReaderVersion(
                    oldDetails.sourceManga, favouriteSpace, previous,
                ) ?: return@launchJob
                val version = local.exhentaiContentVersion ?: return@launchJob
                val next = oldDetails.copy(localManga = local)
                pageLoader.invalidate(clearCache = false)
                if (!chaptersLoader.reloadExhentaiVersion(next) {
                    detailsLoadUseCase.isExhentaiReaderVersionCurrent(favouriteSpace, version)
                }) return@launchJob
                val state = if (version.chapterId == previous.chapterId) clampChapterPosition(oldState)
                    else ReaderState(version.chapterId, 0, 0)
                exhentaiSessionChoice = selection
                mangaDetails.value = next
                readingState.value = state
                savedStateHandle[ReaderIntent.EXTRA_STATE] = state
                visitAcknowledgement.reset()
                notifyStateChanged()
                content.value = ReaderContent(chaptersLoader.snapshot(), state)
                succeeded = true
                mutableExhentaiFileChange.value = null
                saveLoadedStateAsync(next.toManga())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Do not expose file paths or fall back to a different archive or online chapter.
            } finally {
                if (!succeeded) mutableExhentaiFileChange.value = ExhentaiFileChange.UNAVAILABLE
            }
        }
    }

    private fun saveReaderHistory(manga: Manga, state: ReaderState, percent: Float) {
        if (!hasPinnedExhentaiVersion) {
            historyUpdateUseCase.invokeAsync(manga, state, percent, favouriteSpace)
            return
        }
        synchronized(exhentaiHistoryLock) {
            if (mutableExhentaiFileChange.value != null ||
                content.value.pages.none { it.chapterId == state.chapterId }
            ) return
            val job = historyUpdateUseCase.invokeAsync(manga, state, percent, favouriteSpace)
            exhentaiHistoryWrites.add(job)
            job.invokeOnCompletion { synchronized(exhentaiHistoryLock) { exhentaiHistoryWrites.remove(job) } }
        }
    }

    private val pageMetadataRecoverySession = PageMetadataRecoverySession()

    private var loadingJob: Job? = null
    private var pageSaveJob: Job? = null
    private var bookmarkJob: Job? = null
    private var stateChangeJob: Job? = null
    private var deferredHistorySaveJob: Job? = null

    private data class ChapterProgressWrite(
        val manga: Manga,
        val key: ChapterPersonalKey,
        val state: ReaderState,
        val totalPages: Int,
        val resetStamp: Long,
        val visitToken: Long,
        val newVisit: Boolean,
    )

    // One consumer preserves the order of active-page updates, including backwards navigation.
    private val chapterProgressWrites = Channel<ChapterProgressWrite>(Channel.UNLIMITED)
    private val visitAcknowledgement = ChapterVisitAcknowledgement()

    init {
        processLifecycleScope.launch(Dispatchers.IO, CoroutineStart.ATOMIC) {
            for (write in chapterProgressWrites) {
                try {
                    chapterProgressRepository.record(
                        manga = write.manga,
                        space = favouriteSpace,
                        key = write.key,
                        state = write.state,
                        totalPages = write.totalPages,
                        eventStamp = write.resetStamp,
                        visitToken = write.visitToken,
                        isNewVisit = write.newVisit,
                    )
                } catch (error: Exception) {
                    error.printStackTraceDebug()
                }
            }
        }
    }

    private fun enqueueChapterProgress(state: ReaderState) {
        if (mutableExhentaiFileChange.value != null) return
        if (isPeekMode.value || isIncognitoMode.value != false) return
        val manga = getMangaOrNull()?.takeUnless { it.isNovelContent } ?: return
        val chapter = chaptersLoader.peekChapter(state.chapterId) ?: return
        // Only the displayed chapter may create a visited record: a preloaded chapter alone
        // never invokes this function and pages must actually be loaded.
        val pages = chaptersLoader.getPagesCount(state.chapterId)
        if (pages <= 0) return
        val sourceChapters = mangaDetails.value?.sourceManga?.chapters.orEmpty()
        val key = resolveChapterPersonalKey(chapter, sourceChapters, exactNormal = mangaDetails.value?.exactNormalDownloadIdentity == true)
        // Respect user ordering: an unread swipe made after this visit must not be undone
        // when an older queued database write eventually completes.
        val (isNewVisit, token) = visitAcknowledgement.capture(state.chapterId)
        val stamp = settings.getChapterPageResetStamp(manga.id, state.chapterId, favouriteSpace.dbValue)
        if (isNewVisit) acknowledgeChapterVisit(manga.id, state.chapterId)
        chapterProgressWrites.trySend(ChapterProgressWrite(
            manga = manga, key = key, state = state, totalPages = pages,
            resetStamp = stamp, visitToken = token, newVisit = isNewVisit,
        ))
    }

    private fun clampChapterPosition(requested: ReaderState): ReaderState {
        if (getMangaOrNull()?.isNovelContent == true) return requested
        val count = chaptersLoader.getPagesCount(requested.chapterId)
        return if (count > 0 && requested.page >= count) requested.copy(page = count - 1, scroll = 0)
        else requested
    }

    private suspend fun chapterResumeState(chapterId: Long): ReaderState {
        val manga = getMangaOrNull() ?: return ReaderState(chapterId, 0, 0)
        if (!manga.isNovelContent &&
            (settings.getChapterReadOverrides(manga.id)[chapterId] == false ||
                settings.getChapterPageResetStamp(manga.id, chapterId, favouriteSpace.dbValue) < 0)
        ) {
            return ReaderState(chapterId, 0, 0)
        }
        if (manga.isNovelContent) return ReaderState(chapterId, 0, 0)
        val chapter = chaptersLoader.peekChapter(chapterId) ?: return ReaderState(chapterId, 0, 0)
        val sourceChapters = mangaDetails.value?.sourceManga?.chapters.orEmpty()
        val key = resolveChapterPersonalKey(chapter, sourceChapters, exactNormal = mangaDetails.value?.exactNormalDownloadIdentity == true)
        val saved = chapterProgressRepository.get(manga.id, favouriteSpace, key, chapterId)
        if (saved != null) return ReaderState(chapterId, saved.page, saved.scroll)
        // Only the current legacy History cursor is known; never infer visits to older chapters.
        val history = if (mangaDetails.value?.exactNormalDownloadIdentity == true) historyRepository.getOneExact(manga.id) else historyRepository.getOne(manga)
        return if (history?.chapterId == chapterId) ReaderState(history) else ReaderState(chapterId, 0, 0)
    }

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
        if (it) {
            observeWebtoonZoomOut()
        } else {
            flowOf(0f)
        }
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
        if (currentManga == null) {
            flowOf(emptyList<Bookmark>())
        } else {
            bookmarksRepository.observeBookmarks(currentManga)
        }
    }.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, emptyList())

    val isBookmarkAdded = combine(readingState, manga, readerBookmarks) { state, currentManga, bookmarks ->
        if (state == null || currentManga == null) {
            false
        } else {
            currentBookmark(currentManga, state, bookmarks) != null
        }
    }.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, false)

    init {
        initIncognitoMode()
        if (favouriteSpace == FavouriteSpace.NORMAL) {
            val publicManga = MutableStateFlow<Long?>(null)
            val diagnostics = ReaderDiagnostics {
                val approved = publicManga.value
                approved != null && getMangaOrNull()?.id == approved &&
                    ReaderDiagnostics.isPublicMode(isIncognitoMode.value, isPeekMode.value)
            }
            pageLoader.diagnostics = diagnostics
            chaptersLoader.diagnostics = diagnostics
            launchJob(Dispatchers.Default) {
                combine(
                    appLogger.state.map { it.phase == AppLogger.Phase.RECORDING }.distinctUntilChanged(),
                    manga,
                ) { recording, title -> title?.id?.takeIf { recording } }
                    .distinctUntilChanged()
                    .transformLatest { id ->
                        emit(null as Long?)
                        if (id != null) {
                            database.getPrivateFavouritesDao().observePrivateOnly(id)
                                .catch { emit(true) }
                                .collect { privateOnly -> emit(id.takeUnless { privateOnly }) }
                        }
                    }.collect { publicManga.value = it }
            }
        }
        observePageMetadataFailures()
        loadImpl()
        launchJob(Dispatchers.Default) {
            val mangaId = manga.filterNotNull().first().id
            if (!isIncognitoMode.firstNotNull()) {
                appShortcutManager.notifyMangaOpened(mangaId)
            }
        }
    }

    override fun reload() {
        if (mutableExhentaiFileChange.value != null) return
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

    fun onStop() {
        discordRpc.clearRpc()
    }

    fun onIdle() {
        discordRpc.setIdle()
    }

    fun switchMode(newMode: ReaderMode) {
        readerMode.value = newMode
        launchJob {
            val manga = checkNotNull(getMangaOrNull())
            dataRepository.saveReaderMode(
                manga = manga,
                mode = newMode,
            )
            content.update {
                it.copy(state = getCurrentState())
            }
        }
    }

    fun saveCurrentState(state: ReaderState? = null) {
        if (mutableExhentaiFileChange.value != null) return
        // Ignore a late Fragment callback from the version just replaced.
        if (state != null && hasPinnedExhentaiVersion &&
            content.value.pages.none { it.chapterId == state.chapterId }
        ) return
        if (state != null) {
            readingState.value = state
            savedStateHandle[ReaderIntent.EXTRA_STATE] = state
        }
        if (isIncognitoMode.value != false || isPeekMode.value) {
            return
        }
        val readerState = state ?: readingState.value ?: return
        saveReaderHistory(getMangaOrNull() ?: return, readerState, computePercent(readerState))
        enqueueChapterProgress(readerState)
    }

    fun getCurrentState() = readingState.value

    fun getCurrentChapterPages(): List<MangaPage>? {
        val chapterId = readingState.value?.chapterId ?: return null
        return chaptersLoader.getPages(chapterId)
    }

    fun saveCurrentPage(
        pageSaveHelper: PageSaveHelper
    ) {
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
            val dest = pageSaveHelper.save(setOf(task))
            onPageSaved.call(dest)
        }
    }

    fun getCurrentPage(): MangaPage? {
        val state = readingState.value ?: return null
        return content.value.pages.find {
            it.chapterId == state.chapterId && it.index == state.page
        }?.toMangaPage()
    }

    fun switchChapter(id: Long, page: Int, scroll: Int = 0, resume: Boolean = false) {
        if (mutableExhentaiFileChange.value != null) return
        val prevJob = loadingJob
        loadingJob = launchLoadingJob(Dispatchers.Default) {
            prevJob?.cancelAndJoin()
            if (!chaptersLoader.loadSingleChapter(id)) {
                return@launchLoadingJob
            }
            val requested = if (resume) chapterResumeState(id) else ReaderState(id, page, scroll)
            if (mutableExhentaiFileChange.value != null) return@launchLoadingJob
            val newState = clampChapterPosition(requested)
            // Opening a chapter explicitly starts a new visit, including reopening this chapter.
            visitAcknowledgement.reset()
            content.value = ReaderContent(chaptersLoader.snapshot(), newState)
            saveCurrentState(newState)
        }
    }

    fun switchChapterBy(delta: Int) {
        if (mutableExhentaiFileChange.value != null) return
        val prevJob = loadingJob
        loadingJob = launchLoadingJob(Dispatchers.Default) {
            prevJob?.cancelAndJoin()
            val prevState = readingState.requireValue()
            val newChapterId = if (delta != 0) {
                val allChapters = mangaDetails.requireValue().allChapters
                var index = allChapters.indexOfFirst { x -> x.id == prevState.chapterId }
                if (index < 0) {
                    return@launchLoadingJob
                }
                index += delta
                (allChapters.getOrNull(index) ?: return@launchLoadingJob).id
            } else {
                prevState.chapterId
            }
            if (!chaptersLoader.loadSingleChapter(newChapterId)) {
                return@launchLoadingJob
            }
            val newState = clampChapterPosition(if (delta == 0) prevState else chapterResumeState(newChapterId))
            if (mutableExhentaiFileChange.value != null) return@launchLoadingJob
            if (delta != 0) visitAcknowledgement.reset()
            content.value = ReaderContent(chaptersLoader.snapshot(), newState)
            saveCurrentState(newState)
        }
    }

    @MainThread
    fun onCurrentPageChanged(lowerPos: Int, upperPos: Int) {
        if (mutableExhentaiFileChange.value != null) return
        val prevJob = stateChangeJob
        val pages = content.value.pages // capture immediately
        stateChangeJob = launchJob(Dispatchers.Default) {
            prevJob?.cancelAndJoin()
            loadingJob?.join()
            if (mutableExhentaiFileChange.value != null || pages !== content.value.pages) {
                return@launchJob // TODO
            }
            val centerPos = (lowerPos + upperPos) / 2
            pages.getOrNull(centerPos)?.let { page ->
                readingState.update { cs ->
                    cs?.copy(chapterId = page.chapterId, page = page.index)
                }
            }
            notifyStateChanged()
            readingState.value?.let(::enqueueChapterProgress)
            if (pages.isEmpty() || loadingJob?.isActive == true) {
                return@launchJob
            }
            ensureActive()
            val autoLoadAllowed = readerMode.value != ReaderMode.WEBTOON || !isWebtoonPullGestureEnabled.value
            if (autoLoadAllowed) {
                if (upperPos >= pages.lastIndex - BOUNDS_PAGE_OFFSET) {
                    loadPrevNextChapter(pages.last().chapterId, isNext = true)
                }
                if (lowerPos <= BOUNDS_PAGE_OFFSET) {
                    loadPrevNextChapter(pages.first().chapterId, isNext = false)
                }
            }
            if (pageLoader.isPrefetchApplicable()) {
                pageLoader.prefetch(pages.trySublist(upperPos + 1, upperPos + PREFETCH_LIMIT))
            }
        }
    }

    fun toggleBookmark() {
        if (mutableExhentaiFileChange.value != null) return
        if (bookmarkJob?.isActive == true) {
            return
        }
        bookmarkJob = launchJob(Dispatchers.Default) {
            loadingJob?.join()
            if (mutableExhentaiFileChange.value != null) return@launchJob
            val state = checkNotNull(getCurrentState())
            val manga = requireManga()
            val existingBookmark = currentBookmark(manga, state, readerBookmarks.value)
            if (existingBookmark != null) {
                if (mangaDetails.value?.local?.exhentaiContentVersion != null) {
                    // A legacy page ID may also be present in the other ExHentai source.
                    bookmarksRepository.removeBookmark(existingBookmark)
                } else {
                    bookmarksRepository.removeBookmarks(setOf(existingBookmark.pageId))
                }
                onShowToast.call(R.string.bookmark_removed)
            } else {
                val isEpub = manga.isEpub
                val page = getCurrentPage()
                val bookmark = Bookmark(
                    manga = manga,
                    pageId = if (isEpub) {
                        UUID.randomUUID().leastSignificantBits and Long.MAX_VALUE
                    } else {
                        checkNotNull(page) { "Page not found" }.id
                    },
                    chapterId = state.chapterId,
                    page = state.page,
                    scroll = state.scroll,
                    imageUrl = if (isEpub) {
                        manga.coverUrl.orEmpty()
                    } else {
                        checkNotNull(page) { "Page not found" }.let { currentPage ->
                            currentPage.preview.ifNullOrEmpty { currentPage.url }
                        }
                    },
                    createdAt = Instant.now(),
                    percent = computePercent(state),
                )
                bookmarksRepository.addBookmark(bookmark)
                onShowToast.call(R.string.bookmark_added)
            }
        }
    }

    override suspend fun getChapterOpenMode(chapterId: Long): ChapterOpenMode {
        return if (isIncognitoMode.value == true) {
            ChapterOpenMode.NORMAL // nothing will be saved anyway
        } else {
            super.getChapterOpenMode(chapterId)
        }
    }

    fun setPeekMode(value: Boolean) {
        if (isPeekMode.value == value) {
            return
        }
        isPeekMode.value = value
        if (!value) visitAcknowledgement.reset()
        savedStateHandle[ReaderIntent.EXTRA_PEEK] = value
        if (value) {
            discardCurrentSessionTracking()
            onShowToast.call(R.string.peek_mode_hint)
        }
    }

    override fun allowsDownloadDiagnosticExport(): Boolean =
        super.allowsDownloadDiagnosticExport() &&
            ReaderDiagnostics.isPublicMode(isIncognitoMode.value, isPeekMode.value)

    fun setIncognitoMode(value: Boolean, dontAskAgain: Boolean) {
        isIncognitoMode.value = value
        if (!value) visitAcknowledgement.reset()
        if (value) {
            discardCurrentSessionTracking()
        }
        if (dontAskAgain) {
            settings.incognitoModeForNsfw = if (value) TriStateOption.ENABLED else TriStateOption.DISABLED
        }
    }

    private fun observePageMetadataFailures() {
        launchJob(Dispatchers.Default) {
            PageLoadFailureEvents.events.collect { failure ->
                val failedPage = pageMetadataRecoverySession.record(content.value.pages, failure)
                    ?: return@collect
                recoverPageMetadata(failedPage)
            }
        }
    }

    private suspend fun recoverPageMetadata(failedPage: ReaderPage) {
        if (mutableExhentaiFileChange.value != null) return
        val stateBeforeRefresh = readingState.value
        val oldPageId = stateBeforeRefresh
            ?.takeIf { it.chapterId == failedPage.chapterId }
            ?.let { state ->
                content.value.pages.firstOrNull {
                    it.chapterId == state.chapterId && it.index == state.page
                }?.id
            }
        if (!chaptersLoader.refreshChapterPages(failedPage.chapterId)) {
            return
        }
        if (mutableExhentaiFileChange.value != null) return
        val freshPages = chaptersLoader.snapshot()
        val currentState = readingState.value
        val preservedState = if (
            stateBeforeRefresh != null &&
            currentState == stateBeforeRefresh &&
            stateBeforeRefresh.chapterId == failedPage.chapterId
        ) {
            PageMetadataRecovery.preserveState(stateBeforeRefresh, oldPageId, freshPages)
        } else {
            currentState
        }
        if (currentState == stateBeforeRefresh && preservedState != null) {
            // Metadata recovery is not a reading action: update in-memory position only. In
            // particular, do not call saveCurrentState() or create a history/progress mutation.
            readingState.value = preservedState
        }
        content.value = ReaderContent(freshPages, preservedState)
        if (preservedState != null) {
            notifyStateChanged()
        }
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
                    chooseExhentaiFile = ::chooseExhentaiFile,
                ).collect { details ->
                        loadedDetails = details
                        if (mutableExhentaiFileChange.value != null) return@collect
                        val displayedVersion = mangaDetails.value?.local?.exhentaiContentVersion
                        if (displayedVersion != null &&
                            content.value.pages.any { it.chapterId == displayedVersion.chapterId } &&
                            details.local?.exhentaiContentVersion != displayedVersion
                        ) {
                            if (readingState.value?.chapterId == displayedVersion.chapterId) {
                                markExhentaiFileChanged()
                            }
                            return@collect
                        }
                        if (mangaDetails.value == null) {
                            mangaDetails.value = details
                        }
                        chaptersLoader.init(
                            details,
                            normalProvenance = intent.favouriteSpace?.let { FavouriteSpace.fromArgument(it) },
                            useScopedNormal = true,
                        )
                        val manga = details.toManga()
                        // obtain state
                        if (readingState.value == null) {
                            val newState = getStateFromIntent(details)
                            if (newState == null) {
                                return@collect // manga not loaded yet if cannot get state
                            }
                            readingState.value = newState
                            val mode = runCatchingCancellable {
                                detectReaderModeUseCase(manga, newState)
                            }.getOrDefault(settings.defaultReaderMode)
                            val branch = chaptersLoader.peekChapter(newState.chapterId)?.branch
                            selectedBranch.value = branch
                            readerMode.value = mode
                            try {
                                if (!chaptersLoader.loadSingleChapter(newState.chapterId)) {
                                    readingState.value = null
                                    return@collect
                                }
                                readingState.value = clampChapterPosition(newState)
                            } catch (e: Exception) {
                                readingState.value = null // try next time
                                exception = e.mergeWith(exception)
                                return@collect
                            }
                        }
                        mangaDetails.value = details.filterChapters(selectedBranch.value)

                        // Reader content is the critical path. Publish it before persistence or an
                        // incognito decision can wait on history/scrobbler work.
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
                val loadedManga = loadedDetails // for smart cast
                if (loadedManga != null) {
                    mangaDetails.value = loadedManga.filterChapters(selectedBranch.value)
                }
                val loadingError = when {
                    exception != null -> exception
                    loadedManga == null || !loadedManga.isLoaded -> null
                    loadedManga.isRestricted -> EmptyMangaException(
                        EmptyMangaReason.RESTRICTED,
                        loadedManga.toManga(),
                        null,
                    )

                    loadedManga.allChapters.isEmpty() -> EmptyMangaException(
                        EmptyMangaReason.NO_CHAPTERS,
                        loadedManga.toManga(),
                        null,
                    )

                    else -> null
                } ?: IllegalStateException("Unable to load manga. This should never happen. Please report")
                onLoadingError.call(loadingError)
            } else exception?.let { e ->
                // manga has been loaded but error occurred
                errorEvent.call(e)
            }
        }
    }

    /**
     * Persist the state without holding up the reader's first render. When the NSFW incognito choice
     * is still pending, keep exactly one waiter and save the latest state only after the choice is
     * resolved; Peek and Incognito continue to suppress history exactly as before.
     */
    private fun saveLoadedStateAsync(manga: Manga) {
        if (mutableExhentaiFileChange.value != null || isPeekMode.value) return
        val state = readingState.value ?: return
        when (isIncognitoMode.value) {
            false -> {
                saveReaderHistory(manga, state, computePercent(state))
                enqueueChapterProgress(state)
            }
            true -> Unit
            null -> {
                deferredHistorySaveJob?.cancel()
                deferredHistorySaveJob = launchJob(Dispatchers.Default) {
                    if (!isIncognitoMode.firstNotNull() && !isPeekMode.value) {
                        val latestState = readingState.value ?: return@launchJob
                        saveReaderHistory(getMangaOrNull() ?: manga, latestState, computePercent(latestState))
                        enqueueChapterProgress(latestState)
                    }
                }
            }
        }
    }

    @AnyThread
    private fun loadPrevNextChapter(currentId: Long, isNext: Boolean) {
        if (mutableExhentaiFileChange.value != null) return
        val prevJob = loadingJob
        loadingJob = launchLoadingJob(Dispatchers.Default) {
            prevJob?.join()
            chaptersLoader.loadPrevNextChapter(mangaDetails.requireValue(), currentId, isNext)
            if (mutableExhentaiFileChange.value != null) return@launchLoadingJob
            content.value = ReaderContent(chaptersLoader.snapshot(), null)
        }
    }

    private fun <T> List<T>.trySublist(fromIndex: Int, toIndex: Int): List<T> {
        val fromIndexBounded = fromIndex.coerceIn(0, size)
        val toIndexBounded = toIndex.coerceIn(fromIndexBounded, size)
        return if (fromIndexBounded == toIndexBounded) {
            emptyList()
        } else {
            subList(fromIndexBounded, toIndexBounded)
        }
    }

    fun onEpubProgressChanged(
        chapterId: Long,
        charOffset: Int,
        chapterPm: Int,
        readingUnits: Int,
        page: Int = 0,
        pageCount: Int = 0,
    ) {
        // Chapter buttons update readingState before the EPUB surface reports its position. The
        // toolbar is UI state, so compare against that instead of the already-updated reader state.
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
        if (
            currentManga?.isNovelContent == true &&
            isIncognitoMode.value == false &&
            !isPeekMode.value
        ) {
            statsCollector.onNovelProgress(
                mangaId = currentManga.id,
                chapterId = chapterId,
                progressPermille = chapterPm,
            )
            readerJourneyCollector.onNovelProgress(
                mangaId = currentManga.id,
                chapterId = chapterId,
                progressPermille = chapterPm,
                readingUnits = readingUnits,
            )
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
                // The EPUB slider always represents the active chapter.
                currentPage = if (pageCount > 0) page.coerceIn(0, pageCount - 1) else chapterPm.coerceIn(0, EPUB_SLIDER_MAX),
                totalPages = if (pageCount > 0) pageCount else EPUB_SLIDER_MAX + 1,
                isEpubPaged = pageCount > 0,
            )
        }
    }

    @WorkerThread
    private fun notifyStateChanged() {
        val state = getCurrentState() ?: return
        val chapter = chaptersLoader.peekChapter(state.chapterId) ?: return
        val m = mangaDetails.value ?: return
        val chapterIndex = m.chapters[chapter.branch]?.indexOfFirst { it.id == chapter.id } ?: -1
        val isEpub = m.toManga().isEpub
        // in paged epub mode the slider is a page index driven by onEpubProgressChanged; a chapter
        // change must not reset it to the smooth 0..1000 scrollbar (the imminent progress report
        // refreshes the real page/count) - so carry the paged fields over instead of clobbering them
        val prevUi = uiState.value
        val isEpubPaged = isEpub && prevUi?.isEpubPaged == true
        val totalPages = when {
            !isEpub -> chaptersLoader.getPagesCount(chapter.id)
            isEpubPaged -> prevUi.totalPages
            else -> EPUB_SLIDER_MAX + 1
        }
        val newState = ReaderUiState(
            mangaName = m.displayTitle(titleSettings.exhentaiFullTitle),
            chapter = chapter,
            chapterIndex = chapterIndex,
            chaptersTotal = m.chapters[chapter.branch].sizeOrZero(),
            totalPages = totalPages,
            currentPage = when {
                !isEpub -> state.page
                isEpubPaged -> prevUi.currentPage.coerceIn(0, (totalPages - 1).coerceAtLeast(0))
                // exact-offset states (negative) carry no permille; keep the last slider value
                // until the imminent progress report supplies the real one
                ReaderState.decodeEpubOffset(state.scroll) != null ->
                    (prevUi?.currentPage ?: 0).coerceIn(0, EPUB_SLIDER_MAX)
                else -> state.scroll.coerceIn(0, EPUB_SLIDER_MAX)
            },
            percent = computePercent(state),
            incognito = isIncognitoMode.value == true,
            isPeek = isPeekMode.value,
            isEpub = isEpub,
            isEpubPaged = isEpubPaged,
        )
        uiState.value = newState
        if (isIncognitoMode.value == false) {
            val currentManga = m.toManga()
            if (!isPeekMode.value) {
                statsCollector.onStateChanged(m.id, state, totalPages)
                if (!currentManga.isNovelContent) {
                    readerJourneyCollector.onMangaProgress(
                        mangaId = currentManga.id,
                        chapterId = state.chapterId,
                        page = state.page,
                        totalPages = totalPages,
                    )
                }
            }
            // Only http(s) covers work on Discord (URL override or source default); a local custom
            // image can't be reached by Discord's servers, so fall back to the source cover.
            val discordCover = m.toManga().coverUrl?.takeIf { it.isHttpUrl() } ?: m.sourceManga.coverUrl
            discordRpc.updateRpc(m.toManga(), newState, discordCover)
        }
    }

    private fun computePercent(state: ReaderState): Float {
        val branch = chaptersLoader.peekChapter(state.chapterId)?.branch
        val chapters = mangaDetails.value?.chapters?.get(branch) ?: return PROGRESS_NONE
        val chaptersCount = chapters.size
        val chapterIndex = chapters.indexOfFirst { x -> x.id == state.chapterId }
        val (pageIndex, pagesCount) = if (chaptersCount == 1) {
            getPageProgress(state)
        } else {
            0 to 0
        }
        return ReadingProgress.calculatePercent(
            chapterIndex = chapterIndex,
            chaptersCount = chaptersCount,
            pageIndex = pageIndex,
            pagesCount = pagesCount,
        )
    }

    private fun currentBookmark(manga: Manga, state: ReaderState, bookmarks: List<Bookmark>): Bookmark? {
        return bookmarks.firstOrNull { bookmark ->
            bookmark.chapterId == state.chapterId && if (manga.isEpub) {
                bookmark.scroll == state.scroll
            } else {
                bookmark.page == state.page
            }
        }
    }

    private fun getPageProgress(state: ReaderState): Pair<Int, Int> {
        if (mangaDetails.value?.toManga()?.isEpub != true) {
            val loadedCount = chaptersLoader.getPagesCount(state.chapterId)
            // Fragment teardown may save the last displayed state after the Reader backing
            // lease has been released. For the selected ExHentai CBZ version, the published
            // page snapshot still describes that exact chapter. Do not turn a completed
            // cursor into 0% merely because the loader has already cleared its pages.
            val pageCount = if (loadedCount == 0 &&
                mangaDetails.value?.local?.exhentaiContentVersion?.chapterId == state.chapterId
            ) {
                content.value.pages.count { it.chapterId == state.chapterId }
            } else loadedCount
            return state.page to pageCount
        }
        val progress = uiState.value?.takeIf { it.chapter.id == state.chapterId }
        if (progress != null) {
            return progress.currentPage to progress.totalPages
        }
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
        if (isIncognitoMode.value != null) {
            return
        }
        launchJob(Dispatchers.Default) {
            interactor.observeIncognitoMode(manga)
                .collect {
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

    private suspend fun getStateFromIntent(details: MangaDetails): ReaderState? {
        val manga = details.toManga()
        val isLoaded = details.isLoaded
        // check if we have at least some chapters loaded
        if (manga.chapters.isNullOrEmpty()) {
            return null
        }
        // A referenced chapter can be missing from the pre-refresh database snapshot (isLoaded ==
        // false) — return null and wait for the refreshed emission. Once the source list is loaded,
        // a still-missing chapter means the source really dropped it (scanlator removed, url
        // changed): fall through to the next strategy instead of failing the whole reader with
        // "Unable to load manga. This should never happen".

        // specific state is requested
        val requestedState: ReaderState? = savedStateHandle[ReaderIntent.EXTRA_STATE]
        val selectedVersion = details.local?.exhentaiContentVersion
        val selectedChapter = selectedVersion?.let { version -> manga.findChapterById(version.chapterId) }
        if (selectedChapter != null && (
            requestedState == null ||
                savedStateHandle.get<Boolean>(ReaderIntent.EXTRA_HISTORY_RESUME) == true ||
                (isLoaded && manga.findChapterById(requestedState.chapterId) == null)
            )) {
            // Resume the selected physical version, never a cursor from another version or source.
            // An explicit valid chapter/page tap remains authoritative below.
            val chapterId = selectedChapter.id
            if (settings.getChapterReadOverrides(manga.id)[chapterId] == false ||
                settings.getChapterPageResetStamp(manga.id, chapterId, favouriteSpace.dbValue) < 0
            ) return ReaderState(chapterId, 0, 0)
            val key = resolveChapterPersonalKey(selectedChapter, details.sourceManga.chapters.orEmpty(), exactNormal = details.exactNormalDownloadIdentity)
            val saved = chapterProgressRepository.get(manga.id, favouriteSpace, key, chapterId)
            if (saved != null) return ReaderState(chapterId, saved.page, saved.scroll)
            val history = database.getHistoryDao().find(manga.id)
            return if (history?.chapterId == chapterId && history.space == favouriteSpace.dbValue) {
                ReaderState(chapterId, history.page, history.scroll.toInt())
            } else ReaderState(chapterId, 0, 0)
        }
        if (requestedState != null) {
            when {
                manga.findChapterById(requestedState.chapterId) != null -> {
                    // Widget-provided History is not a deliberate bookmark/page jump.
                    // Do not bypass manual unread by treating it as an explicit page.
                    val isHistoryResume = savedStateHandle.get<Boolean>(ReaderIntent.EXTRA_HISTORY_RESUME) == true
                    return if (isHistoryResume && !manga.isNovelContent &&
                        (settings.getChapterReadOverrides(manga.id)[requestedState.chapterId] == false ||
                            settings.getChapterPageResetStamp(manga.id, requestedState.chapterId, favouriteSpace.dbValue) < 0)
                    ) ReaderState(requestedState.chapterId, 0, 0) else requestedState
                }
                !isLoaded -> return null
            }
        }

        val requestedBranch: String? = savedStateHandle[ReaderIntent.EXTRA_BRANCH]
        // continue reading
        val history = if (details.exactNormalDownloadIdentity) historyRepository.getOneExact(manga.id) else historyRepository.getOne(manga)
        if (history != null) {
            val chapter = manga.findChapterById(history.chapterId)
            val resume = if (!manga.isNovelContent &&
                (settings.getChapterReadOverrides(manga.id)[history.chapterId] == false ||
                    settings.getChapterPageResetStamp(manga.id, history.chapterId, favouriteSpace.dbValue) < 0)
            ) ReaderState(history.chapterId, 0, 0) else ReaderState(history)
            when {
                chapter == null -> if (!isLoaded) return null
                // A manual unread resets the chapter even when continuing with an explicit branch.
                ReaderIntent.EXTRA_BRANCH in savedStateHandle -> return if (chapter.branch == requestedBranch) {
                    resume
                } else {
                    ReaderState(manga, requestedBranch)
                }

                else -> return resume
            }
        }

        // start from beginning
        val preferredBranch = requestedBranch ?: manga.getPreferredBranch(null)
        return ReaderState(manga, preferredBranch)
    }

    private fun Exception.mergeWith(other: Exception?): Exception = if (other == null) {
        this
    } else {
        other.addSuppressed(this)
        other
    }
}
