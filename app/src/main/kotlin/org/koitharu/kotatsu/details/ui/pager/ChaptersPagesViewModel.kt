package org.koitharu.kotatsu.details.ui.pager

import android.app.Activity
import androidx.core.net.toFile
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.plus
import okio.FileNotFoundException
import org.koitharu.kotatsu.bookmarks.domain.BookmarksRepository
import org.koitharu.kotatsu.core.model.isNovelContent
import org.koitharu.kotatsu.core.model.toChipModel
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.TriStateOption
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.ui.util.ReversibleAction
import org.koitharu.kotatsu.core.util.LocaleStringComparator
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.core.util.ext.combine
import org.koitharu.kotatsu.core.util.ext.requireValue
import org.koitharu.kotatsu.core.util.ext.sortedWithSafe
import org.koitharu.kotatsu.details.data.MangaDetails
import org.koitharu.kotatsu.details.domain.DetailsInteractor
import org.koitharu.kotatsu.details.ui.DetailsExpressiveActivity
import org.koitharu.kotatsu.details.ui.DetailsViewModel
import org.koitharu.kotatsu.details.ui.mapChapters
import org.koitharu.kotatsu.details.ui.model.ChapterListItem
import org.koitharu.kotatsu.download.domain.DownloadDestinationStore
import org.koitharu.kotatsu.download.ui.worker.DownloadTask
import org.koitharu.kotatsu.download.ui.worker.DownloadWorker
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.history.data.HistoryRepository
import org.koitharu.kotatsu.list.domain.ListFilterOption
import org.koitharu.kotatsu.local.data.index.LocalMangaIndex
import org.koitharu.kotatsu.local.domain.DeleteLocalMangaUseCase
import org.koitharu.kotatsu.local.domain.model.LocalManga
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.reader.ui.ReaderActivity
import org.koitharu.kotatsu.reader.ui.ReaderState
import org.koitharu.kotatsu.reader.ui.ReaderViewModel
import java.io.File
import tachiyomi.core.common.util.lang.compareToWithCollator

abstract class ChaptersPagesViewModel(
	@JvmField protected val settings: AppSettings,
	@JvmField protected val interactor: DetailsInteractor,
	private val bookmarksRepository: BookmarksRepository,
	private val historyRepository: HistoryRepository,
	private val downloadScheduler: DownloadWorker.Scheduler,
	private val downloadDestinationStore: DownloadDestinationStore,
	val favouriteSpace: FavouriteSpace,
	private val deleteLocalMangaUseCase: DeleteLocalMangaUseCase,
	private val localStorageChanges: SharedFlow<LocalManga?>,
	private val mangaDataRepository: MangaDataRepository,
	private val mangaRepositoryFactory: MangaRepository.Factory,
	private val chapterListOptionsStore: ChapterListOptionsStore? = null,
) : BaseViewModel() {

	val mangaDetails = MutableStateFlow<MangaDetails?>(null)
	val readingState = MutableStateFlow<ReaderState?>(null)

	val onActionDone = MutableEventFlow<ReversibleAction>()
	val onDownloadStarted = MutableEventFlow<Unit>()
	val onMangaRemoved = MutableEventFlow<Manga>()
	val onOpenChapterInBrowser = MutableEventFlow<String>()

	val chaptersQuery = MutableStateFlow("")
	val selectedBranch = MutableStateFlow<String?>(null)
	val selectedScanlator = MutableStateFlow<String?>(null)

	val manga = mangaDetails.map { x -> x?.toManga() }
		.withErrorHandling()
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, null)

	val coverUrl = mangaDetails.map { x -> x?.coverUrl(preferLarge = !settings.isBackdropEnabled) }
		.withErrorHandling()
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, null)

	val backdropUrl = mangaDetails.map { x -> x?.backdropUrl }
		.withErrorHandling()
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, null)

	val chapterListOptions = MutableStateFlow(
		ChapterListOptions(
			descending = settings.isChaptersReverse,
			grid = settings.isChaptersGridView,
		),
	)
	private val chapterReadOverrides = MutableStateFlow<Map<Long, Boolean>>(emptyMap())

	// Rich descriptions, cover enrichment and other presentation-only MangaDetails emissions should
	// not remap a list with hundreds/thousands of chapters. Referential chapter-list identity is enough
	// here: any real source/local chapter replacement produces a new list and therefore a new snapshot.
	private val chapterMappingDetails = mangaDetails
		.map { it }
		.distinctUntilChanged { old, new ->
			old?.id == new?.id &&
				old?.isLocal == new?.isLocal &&
				old?.sourceManga?.chapters === new?.sourceManga?.chapters &&
				old?.local?.manga?.chapters === new?.local?.manga?.chapters
		}

	val chapterBranchOptions = chapterMappingDetails
		.map { details ->
			details?.chapters?.keys
				?.filterNotNull()
				?.sortedWith(LocaleStringComparator())
				.orEmpty()
				.takeIf { (details?.chapters?.size ?: 0) > 1 }
				.orEmpty()
		}
		.distinctUntilChanged()
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, emptyList())

	val hasAllChapterBranch = chapterMappingDetails
		.map { details -> (details?.chapters?.size ?: 0) > 1 && details?.chapters?.containsKey(null) == true }
		.distinctUntilChanged()
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, false)

	val chapterScanlatorOptions = combine(chapterMappingDetails, selectedBranch) { details, branch ->
		details?.chapters?.get(branch)
			?.mapNotNull { it.scanlator?.trim()?.takeIf(String::isNotEmpty) }
			?.distinct()
			?.sortedWith(LocaleStringComparator())
			.orEmpty()
			.takeIf { it.size > 1 }
			.orEmpty()
	}
		.distinctUntilChanged()
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, emptyList())

	private val defaultChapterBranch = chapterMappingDetails
		.map { details ->
			val keys = details?.chapters?.keys.orEmpty()
			if (null in keys) null else keys.firstOrNull()
		}
		.distinctUntilChanged()
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, null)

	val isDownloadedFilterAvailable = chapterMappingDetails
		.map { details -> details != null && !details.isLocal }
		.distinctUntilChanged()
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, false)

	val isChapterFilterActive = combine(
		chapterListOptions,
		selectedBranch.combine(selectedScanlator) { branch, scanlator -> branch to scanlator },
		defaultChapterBranch,
	) { options, branchAndScanlator, defaultBranch ->
		val (branch, scanlator) = branchAndScanlator
		options.hasStatusFilter || branch != defaultBranch || scanlator != null
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, false)

	val newChaptersCount = mangaDetails
		.map { details -> details?.let { it.id to it.isLocal } }
		.distinctUntilChanged()
		.flatMapLatest { key ->
			if (key != null && !key.second) {
				interactor.observeNewChapters(key.first)
			} else {
				flowOf(0)
			}
		}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, 0)

	val emptyReason: StateFlow<EmptyMangaReason?> = combine(
		mangaDetails,
		isLoading,
		onError.onStart { emit(null) },
	) { details, loading, error ->
		when {
			details == null || loading -> null
			details.chapters.isNotEmpty() -> null
			details.toManga().state == MangaState.RESTRICTED -> EmptyMangaReason.RESTRICTED
			error != null -> EmptyMangaReason.LOADING_ERROR
			else -> EmptyMangaReason.NO_CHAPTERS
		}
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.WhileSubscribed(), null)

	// Bookmark rows are keyed by manga id. Keep the observer alive across presentation-only Details
	// updates; a genuine source-manga metadata/chapter replacement still restarts it naturally.
	val bookmarks = mangaDetails
		.map { it?.sourceManga }
		.distinctUntilChanged()
		.flatMapLatest { sourceManga ->
			if (sourceManga != null) {
				bookmarksRepository.observeBookmarks(sourceManga).withErrorHandling()
			} else {
				flowOf(emptyList())
			}
		}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Lazily, emptyList())

	private val activeChapterDownloads = combine(
		mangaDetails.map { it?.id }.distinctUntilChanged(),
		downloadScheduler.observeWorks(),
	) { mangaId, works -> mangaId to works }
		.mapLatest { (mangaId, works) ->
			if (mangaId == null) {
				return@mapLatest ActiveChapterDownloads()
			}
			var isAll = false
			val ids = mutableSetOf<Long>()
			for (work in works) {
				if (work.state.isFinished) continue
				val task = downloadScheduler.getTask(work.id) ?: continue
				if (task.mangaId != mangaId || task.favouriteSpace != favouriteSpace) continue
				val chapterIds = task.chaptersIds
				if (chapterIds == null) {
					isAll = true
				} else {
					chapterIds.forEach(ids::add)
				}
			}
			ActiveChapterDownloads(isAll = isAll, chapterIds = ids)
		}
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, ActiveChapterDownloads())

	val chapters = combine(
		combine(
			chapterMappingDetails.combine(chapterReadOverrides) { manga, overrides -> manga to overrides },
			readingState.map { it?.chapterId ?: 0L }.distinctUntilChanged(),
			selectedBranch.combine(selectedScanlator) { branch, scanlator -> branch to scanlator },
			newChaptersCount,
			bookmarks,
			chapterListOptions,
		) { mangaWithOverrides, currentChapterId, branchAndScanlator, news, bookmarks, options ->
			val (manga, overrides) = mangaWithOverrides
			val (branch, scanlator) = branchAndScanlator
			manga?.mapChapters(
				currentChapterId = currentChapterId,
				newCount = news,
				branch = branch,
				bookmarks = bookmarks,
				isGrid = options.grid,
				// Always map the complete Room/local snapshot. Status filters below are in-memory only.
				isDownloadedOnly = false,
				readOverrides = overrides,
			).orEmpty()
				.filter { item -> scanlator == null || item.chapter.scanlator?.trim() == scanlator }
				.map { item -> item.withTitleMode(options.titleMode) }
		},
		chapterListOptions,
		chaptersQuery,
		activeChapterDownloads,
	) { list, options, query, activeDownloads ->
		val filtered = list
			.applyChapterOptions(options)
			.filterSearch(query)
		if (activeDownloads.isEmpty) {
			filtered
		} else {
			filtered.map { item ->
				item.withDownloading(!item.isDownloaded && activeDownloads.contains(item.chapter.id))
			}
		}
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, emptyList())

	val quickFilter = combine(
		chapterMappingDetails,
		selectedBranch,
	) { details, branch ->
		val branches = details?.chapters?.toList()?.sortedWithSafe(
			compareBy(LocaleStringComparator()) { x -> x.first },
		).orEmpty()
		if (branches.size > 1) {
			branches.map {
				val option = ListFilterOption.Branch(titleText = it.first, chaptersCount = it.second.size)
				option.toChipModel(isChecked = it.first == branch)
			}
		} else {
			emptyList()
		}
	}

	val isScanlatorsMerged = MutableStateFlow(false)

	init {
		chapterListOptionsStore?.let { store ->
			launchJob(Dispatchers.Default) {
				mangaDetails
					.map { details -> details?.toManga()?.let { manga -> manga.id to manga.isNovelContent } }
					.distinctUntilChanged()
					.filterNotNull()
					.collect { (_, isNovel) ->
						chapterListOptions.value = store.getDefault(favouriteSpace, isNovel)
					}
			}
		}
		launchJob(Dispatchers.Default) {
			mangaDetails.map { it?.id }.distinctUntilChanged().filterNotNull().collect { mangaId ->
				chapterReadOverrides.value = settings.getChapterReadOverrides(mangaId)
			}
		}
		launchJob(Dispatchers.Default) {
			localStorageChanges
				.collect { onDownloadComplete(it) }
		}
		launchJob(Dispatchers.Default) {
			LocalMangaIndex.rebuildEvents.collect { onLocalIndexRebuilt() }
		}
		launchJob(Dispatchers.Default) {
			val id = mangaDetails.filterNotNull().first().id
			isScanlatorsMerged.value = mangaDataRepository.isScanlatorsMerged(id)
		}
	}

	abstract fun reload()

	fun setScanlatorsMerged(isMerged: Boolean) {
		launchJob(Dispatchers.Default) {
			val manga = mangaDetails.requireValue().sourceManga
			mangaDataRepository.setScanlatorsMerged(manga, isMerged)
			isScanlatorsMerged.value = isMerged
			selectedBranch.value = null
			selectedScanlator.value = null
			reload()
		}
	}

	fun setSelectedBranch(branch: String?) {
		selectedBranch.value = branch
		selectedScanlator.value = null
	}

	fun setSelectedScanlator(scanlator: String?) {
		selectedScanlator.value = scanlator
	}

	fun setDownloadedOnly(value: Boolean) = updateChapterOptions { copy(downloadedOnly = value) }

	fun setUnreadOnly(value: Boolean) = updateChapterOptions { copy(unreadOnly = value) }

	fun setBookmarkedOnly(value: Boolean) = updateChapterOptions { copy(bookmarkedOnly = value) }

	fun setNewOnly(value: Boolean) = updateChapterOptions { copy(newOnly = value) }

	fun setChapterSortMode(mode: ChapterSortMode) {
		updateChapterOptions {
			if (sortMode == mode) copy(descending = !descending) else copy(sortMode = mode)
		}
	}

	fun toggleChapterSortDirection() = updateChapterOptions { copy(descending = !descending) }

	fun setChapterTitleMode(mode: ChapterTitleMode) = updateChapterOptions { copy(titleMode = mode) }

	fun setChaptersGridView(value: Boolean) = updateChapterOptions { copy(grid = value) }

	fun saveChapterOptionsAsDefault() {
		val store = chapterListOptionsStore ?: return
		val manga = getMangaOrNull() ?: return
		store.setDefault(favouriteSpace, manga.isNovelContent, chapterListOptions.value)
	}

	fun resetChapterOptions() {
		val manga = getMangaOrNull() ?: return
		chapterListOptions.value = chapterListOptionsStore?.getDefault(favouriteSpace, manga.isNovelContent)
			?: ChapterListOptions(
				descending = settings.isChaptersReverse,
				grid = settings.isChaptersGridView,
			)
		selectedBranch.value = defaultChapterBranch.value
		selectedScanlator.value = null
	}

	private inline fun updateChapterOptions(transform: ChapterListOptions.() -> ChapterListOptions) {
		chapterListOptions.update { current -> current.transform() }
	}

	fun performChapterSearch(query: String?) {
		chaptersQuery.value = query?.trim().orEmpty()
	}

	fun getMangaOrNull(): Manga? = mangaDetails.value?.toManga()

	fun getSourceMangaOrNull(): Manga? = mangaDetails.value?.sourceManga

	fun requireManga() = mangaDetails.requireValue().toManga()

	open suspend fun getChapterOpenMode(chapterId: Long): ChapterOpenMode {
		if (!settings.isChapterJumpDialogEnabled) {
			return ChapterOpenMode.NORMAL
		}
		val details = mangaDetails.value ?: return ChapterOpenMode.NORMAL
		val manga = details.toManga()
		val history = runCatchingCancellable {
			historyRepository.getOne(manga)
		}.getOrNull() ?: return ChapterOpenMode.NORMAL
		if (chapterId == history.chapterId) {
			return ChapterOpenMode.NORMAL
		}
		if (interactor.observeIncognitoMode(flowOf(manga)).first() == TriStateOption.ENABLED) {
			return ChapterOpenMode.NORMAL
		}
		val chapters = details.chapters.values.firstOrNull { list -> list.any { it.id == chapterId } }
			?: return ChapterOpenMode.NORMAL
		val currentIndex = chapters.indexOfFirst { it.id == history.chapterId }
		val targetIndex = chapters.indexOfFirst { it.id == chapterId }
		return if (currentIndex >= 0 && targetIndex == currentIndex + 1) {
			ChapterOpenMode.NORMAL
		} else {
			ChapterOpenMode.ASK
		}
	}

	fun disableChapterJumpDialog() {
		settings.isChapterJumpDialogEnabled = false
	}

	fun toggleChapterReadState(chapterId: Long) {
		val item = chapters.value.firstOrNull { it.chapter.id == chapterId } ?: return
		val mangaId = mangaDetails.value?.id ?: return
		val markRead = item.isUnread
		settings.setChapterReadOverride(mangaId, chapterId, markRead)
		chapterReadOverrides.update { current -> current + (chapterId to markRead) }
	}

	fun markChapterAsCurrent(chapterId: Long) {
		launchJob(Dispatchers.Default) {
			val manga = mangaDetails.requireValue()
			val chapters = checkNotNull(manga.chapters[selectedBranch.value])
			val chapterIndex = chapters.indexOfFirst { it.id == chapterId }
			check(chapterIndex in chapters.indices) { "Chapter not found" }
			val percent = (chapterIndex + 1) / chapters.size.toFloat()
			historyRepository.addOrUpdate(
				manga = manga.toManga(),
				chapterId = chapterId,
				page = 0,
				scroll = 0,
				percent = percent,
				force = true,
			)
		}
	}

	fun openChapterInBrowser(chapterId: Long) {
		val chapter = chapters.value.firstOrNull { it.chapter.id == chapterId }?.chapter ?: return
		launchJob(Dispatchers.Default) {
			val url = mangaRepositoryFactory.create(requireManga().source).getChapterUrl(chapter)
			if (!url.isNullOrEmpty()) {
				onOpenChapterInBrowser.call(url)
			}
		}
	}

	fun download(chaptersIds: Set<Long>?, allowMeteredNetwork: Boolean) {
		launchJob(Dispatchers.Default) {
			val manga = requireManga()
			val active = activeChapterDownloads.value
			val effectiveChapterIds = when {
				chaptersIds == null && active.isAll -> return@launchJob
				chaptersIds == null -> null
				else -> chaptersIds.filterNot(active::contains).toSet().takeIf { it.isNotEmpty() }
					?: return@launchJob
			}
			val task = DownloadTask(
				mangaId = manga.id,
				isPaused = false,
				isSilent = false,
				chaptersIds = effectiveChapterIds?.toLongArray(),
				destination = downloadDestinationStore.effectiveRoot(favouriteSpace),
				format = null,
				allowMeteredNetwork = allowMeteredNetwork,
				favouriteSpace = favouriteSpace,
			)
			downloadScheduler.schedule(setOf(manga to task))
			onDownloadStarted.call(Unit)
		}
	}

	fun deleteLocal() {
		val m = mangaDetails.value?.local?.manga
		if (m == null) {
			errorEvent.call(FileNotFoundException())
			return
		}
		launchLoadingJob(Dispatchers.Default) {
			deleteLocalMangaUseCase(m)
			onMangaRemoved.call(m)
		}
	}

	private fun List<ChapterListItem>.applyChapterOptions(options: ChapterListOptions): List<ChapterListItem> {
		if (isEmpty()) return this
		val filtered = if (options.hasStatusFilter) {
			filter { item ->
				(!options.downloadedOnly || item.isDownloaded) &&
					(!options.unreadOnly || item.isUnread) &&
					(!options.bookmarkedOnly || item.isBookmarked) &&
					(!options.newOnly || item.isNew)
			}
		} else {
			this
		}
		if (filtered.size < 2) return filtered
		if (options.sortMode == ChapterSortMode.SOURCE) {
			return if (options.descending) filtered.asReversed() else filtered
		}

		val indexed = filtered.withIndex()
		val sorted = indexed.sortedWith { left, right ->
			val chapterCompare = when (options.sortMode) {
				ChapterSortMode.SOURCE -> 0
				ChapterSortMode.NUMBER -> compareKnown(
					left.value.chapter.number.takeIf { it > 0f },
					right.value.chapter.number.takeIf { it > 0f },
					options.descending,
				)
				ChapterSortMode.UPLOAD_DATE -> compareKnown(
					left.value.chapter.uploadDate.takeIf { it > 0L },
					right.value.chapter.uploadDate.takeIf { it > 0L },
					options.descending,
				)
				ChapterSortMode.ALPHABETICAL -> compareKnownText(
					left.value.chapter.title?.trim()?.takeIf { it.isNotEmpty() },
					right.value.chapter.title?.trim()?.takeIf { it.isNotEmpty() },
					options.descending,
				)
			}
			if (chapterCompare != 0) chapterCompare else left.index.compareTo(right.index)
		}
		return sorted.map { it.value }
	}

	private fun <T : Comparable<T>> compareKnown(left: T?, right: T?, descending: Boolean): Int = when {
		left == null && right == null -> 0
		left == null -> 1
		right == null -> -1
		else -> if (descending) right.compareTo(left) else left.compareTo(right)
	}

	private fun compareKnownText(
		left: String?,
		right: String?,
		descending: Boolean,
	): Int = when {
		left == null && right == null -> 0
		left == null -> 1
		right == null -> -1
		else -> {
			val result = left.compareToWithCollator(right)
			if (descending) -result else result
		}
	}

	private fun List<ChapterListItem>.filterSearch(query: String): List<ChapterListItem> {
		if (query.isEmpty() || this.isEmpty()) {
			return this
		}
		return filter { it.contains(query) }
	}

	private suspend fun onLocalIndexRebuilt() {
		val current = mangaDetails.value ?: return
		if (!current.isLocal) {
			return
		}
		if (this is ReaderViewModel) {
			getCurrentState()?.let(::saveCurrentState)
			readingState.value = null
		}
		reload()
	}

	private suspend fun onDownloadComplete(downloadedManga: LocalManga?) {
		val current = mangaDetails.value ?: return
		val expectedRoots = downloadDestinationStore.readableRoots(favouriteSpace)
		if (downloadedManga != null && expectedRoots.isNotEmpty() && expectedRoots.none { downloadedManga.file.isInside(it) }) {
			return
		}
		if (downloadedManga == null) {
			val local = current.local ?: return
			// Null storage changes are emitted after a whole local container is removed. Partial chapter
			// removals publish the updated LocalManga instead, so scanning every chapter file here adds
			// O(N) filesystem stats without improving correctness. Root existence covers manga folders,
			// single EPUB novels, and multi-EPUB novel directories uniformly.
			if (local.file.exists()) {
				return
			}
			// Removing a downloaded copy must not tear down an online Reader session. The active pages
			// remain valid; only the local/download markers need to disappear. Truly local content still
			// follows its index-rebuild reload path so replaced/deleted files are re-resolved safely.
			if (this is ReaderViewModel && !current.isLocal) {
				mangaDetails.value = current.copy(localManga = null)
				return
			}
			if (this is ReaderViewModel) {
				getCurrentState()?.let(::saveCurrentState)
				readingState.value = null
			}
			reload()
			return
		}

		val local = current.local
		val isCurrentManga = current.id == downloadedManga.manga.id ||
			local?.manga?.id == downloadedManga.manga.id ||
			local?.file == downloadedManga.file
		if (!isCurrentManga) {
			return
		}
		mangaDetails.value = interactor.updateLocal(current, downloadedManga) ?: current
		// A normal download only enriches a remote title with an on-device copy. Updating Details is
		// enough; reloading Reader would blank the current content and refetch the chapter. Local-source
		// file changes still reload because their chapter/page URLs can genuinely have changed.
		if (this is ReaderViewModel && current.isLocal) {
			getCurrentState()?.let(::saveCurrentState)
			readingState.value = null
			reload()
		}
	}

	private fun File.isInside(root: File): Boolean {
		val normalizedRoot = runCatching { root.canonicalFile }.getOrDefault(root.absoluteFile)
		val normalizedFile = runCatching { canonicalFile }.getOrDefault(absoluteFile)
		return normalizedFile == normalizedRoot ||
			normalizedFile.path.startsWith(normalizedRoot.path + File.separator)
	}

	class ActivityVMLazy(
		private val fragment: Fragment,
	) : Lazy<ChaptersPagesViewModel> {
		private var cached: ChaptersPagesViewModel? = null

		override val value: ChaptersPagesViewModel
		get() {
				val viewModel = cached
				return if (viewModel == null) {
					val activity = fragment.requireActivity()
					val vmClass = getViewModelClass(activity)
					ViewModelProvider.create(
						store = activity.viewModelStore,
						factory = activity.defaultViewModelProviderFactory,
						extras = activity.defaultViewModelCreationExtras,
					)[vmClass].also { cached = it }
				} else {
					viewModel
				}
			}

		override fun isInitialized(): Boolean = cached != null

		private fun getViewModelClass(activity: Activity) = when (activity) {
			is ReaderActivity -> ReaderViewModel::class.java
			is DetailsExpressiveActivity -> DetailsViewModel::class.java
			else -> error("Wrong activity ${activity.javaClass.simpleName} for ${ChaptersPagesViewModel::class.java.simpleName}")
		}
	}

	enum class ChapterOpenMode {
		NORMAL, ASK
	}
}

private data class ActiveChapterDownloads(
	val isAll: Boolean = false,
	val chapterIds: Set<Long> = emptySet(),
) {
	val isEmpty: Boolean
		get() = !isAll && chapterIds.isEmpty()

	fun contains(chapterId: Long): Boolean = isAll || chapterId in chapterIds
}
