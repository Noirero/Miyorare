package org.koitharu.kotatsu.download.ui.list

import androidx.collection.ArrayMap
import androidx.collection.LongSet
import androidx.collection.LongSparseArray
import androidx.collection.getOrElse
import androidx.collection.set
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.ui.model.DateTimeAgo
import org.koitharu.kotatsu.core.ui.util.ReversibleAction
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.calculateTimeAgo
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.core.util.ext.isEmpty
import org.koitharu.kotatsu.download.domain.DownloadState
import org.koitharu.kotatsu.download.ui.list.chapters.DownloadChapter
import org.koitharu.kotatsu.download.ui.worker.DownloadTask
import org.koitharu.kotatsu.download.ui.worker.DownloadWorker
import org.koitharu.kotatsu.favourites.data.EXTRA_FAVOURITE_SPACE
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.favourites.domain.FavouritesRepository
import org.koitharu.kotatsu.list.ui.model.EmptyState
import org.koitharu.kotatsu.list.ui.model.ListHeader
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.LoadingState
import org.koitharu.kotatsu.local.data.LocalMangaRepository
import org.koitharu.kotatsu.local.data.LocalStorageChanges
import org.koitharu.kotatsu.local.data.findSavedMangaInRoot
import org.koitharu.kotatsu.local.domain.model.LocalManga
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.util.mapToSet
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import java.util.LinkedList
import java.util.UUID
import javax.inject.Inject

private const val EMPTY_STATE_GRACE_MS = 300L
private const val UI_ACTION_TTL_MS = 5000L
private const val ACTIVE_WORK_HYDRATION_RETRIES = 3
private const val ACTIVE_WORK_HYDRATION_RETRY_DELAY_MS = 50L

@HiltViewModel
class DownloadsViewModel @Inject constructor(
	savedStateHandle: SavedStateHandle,
	private val workScheduler: DownloadWorker.Scheduler,
	private val mangaDataRepository: MangaDataRepository,
	private val mangaRepositoryFactory: MangaRepository.Factory,
	@LocalStorageChanges private val localStorageChanges: MutableSharedFlow<LocalManga?>,
	private val localMangaRepository: LocalMangaRepository,
	private val favouritesRepository: FavouritesRepository,
) : BaseViewModel() {

	private val favouriteSpace = FavouriteSpace.fromArgument(
		savedStateHandle[EXTRA_FAVOURITE_SPACE] ?: FavouriteSpace.NORMAL.dbValue,
	)
	private val mangaCache = LongSparseArray<Manga>()
	private val cacheMutex = Mutex()
	private val expanded = MutableStateFlow(emptySet<UUID>())
	private val chaptersCache = ArrayMap<UUID, StateFlow<List<DownloadChapter>?>>()
	private val pendingUiActions = MutableStateFlow<Map<UUID, DownloadUiAction>>(emptyMap())

	/**
	 * Downloads can be opened either as the public/Normal queue or as an authenticated Private queue.
	 * Build both membership sets once per invalidation and let the active FavouriteSpace decide which
	 * WorkManager rows may be rendered. Normal keeps non-favourite downloads plus Normal memberships,
	 * while hiding Private-only manga. Private shows only manga that actually belong to the vault.
	 *
	 * New work is additionally scoped by DownloadTask.favouriteSpace, so when the same manga belongs
	 * to both spaces its Normal and Private jobs remain distinct. Retained legacy jobs deserialize as
	 * Normal and therefore keep their historical public-queue behaviour.
	 *
	 * Keep this as a cold Flow instead of giving it an empty initial StateFlow value. `combine` below
	 * then waits for the first real membership snapshot before emitting any WorkManager rows, which
	 * prevents a Private download from flashing on the public queue during cold start.
	 */
	private val membershipVisibility = combine(
		favouritesRepository.observeFavouritesChanges(FavouriteSpace.PRIVATE),
		favouritesRepository.observeFavouritesChanges(FavouriteSpace.NORMAL),
	) { _, _ ->
		DownloadMembershipVisibility(
			privateIds = favouritesRepository.getMemberships(FavouriteSpace.PRIVATE)
				.mapTo(HashSet()) { it.mangaId },
			normalIds = favouritesRepository.getMemberships(FavouriteSpace.NORMAL)
				.mapTo(HashSet()) { it.mangaId },
		)
	}

	private val baseWorks = combine(
		workScheduler.observeWorks(),
		expanded,
		membershipVisibility,
	) { list, exp, visibility ->
		list.toDownloadsList(exp, visibility)
	}.withErrorHandling()
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, null)

	/**
	 * WorkManager remains the source of truth, but controls should feel immediate. A short-lived UI
	 * action masks the round-trip through BroadcastReceiver/Worker/WorkManager until the worker state
	 * catches up. As soon as the real state reflects the request, the optimistic layer disappears.
	 */
	private val works = combine(baseWorks, pendingUiActions) { list, actions ->
		list?.map { item -> item.applyUiAction(actions[item.id]) }
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, null)

	val onActionDone = MutableEventFlow<ReversibleAction>()

	/**
	 * Avoid flashing the real empty-state during the small window between enqueueing work and
	 * WorkManager publishing its first row. Keep this short enough that opening a genuinely empty
	 * queue still feels immediate.
	 */
	val items = works.transformLatest { current ->
		when {
			current == null -> emit(listOf(LoadingState))
			current.isEmpty() -> {
				emit(listOf(LoadingState))
				delay(EMPTY_STATE_GRACE_MS)
				emit(emptyStateList())
			}
			else -> emit(current.toUiList())
		}
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, listOf(LoadingState))

	val hasPausedWorks = works.map {
		it?.any { x -> x.canResume } == true
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.WhileSubscribed(5000), false)

	val hasActiveWorks = works.map {
		it?.any { x -> x.canPause } == true
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.WhileSubscribed(5000), false)

	val hasCancellableWorks = works.map {
		it?.any { x -> x.canCancel } == true
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.WhileSubscribed(5000), false)

	fun cancel(id: UUID) {
		markUiAction(listOf(id), DownloadUiAction.CANCELLING)
		workScheduler.pause(id)
		launchJob(Dispatchers.Default) {
			workScheduler.cancel(id)
		}
	}

	fun cancel(ids: Set<Long>) {
		val targets = works.value.orEmpty().filter {
			it.id.mostSignificantBits in ids && it.canCancel
		}.map { it.id }
		if (targets.isEmpty()) return
		markUiAction(targets, DownloadUiAction.CANCELLING)
		targets.forEach(workScheduler::pause)
		launchJob(Dispatchers.Default) {
			for (id in targets) {
				workScheduler.cancel(id)
			}
			onActionDone.call(ReversibleAction(R.string.downloads_cancelled, null))
		}
	}

	fun cancelAll() {
		val targets = works.value.orEmpty()
			.filter { it.canCancel }
			.map { it.id }
		if (targets.isEmpty()) return
		markUiAction(targets, DownloadUiAction.CANCELLING)
		targets.forEach(workScheduler::pause)
		launchJob(Dispatchers.Default) {
			for (id in targets) {
				workScheduler.cancel(id)
			}
			onActionDone.call(ReversibleAction(R.string.downloads_cancelled, null))
		}
	}

	fun pause(id: UUID) {
		val item = works.value.orEmpty().firstOrNull { it.id == id && it.canPause } ?: return
		markUiAction(listOf(item.id), DownloadUiAction.PAUSING)
		workScheduler.pause(item.id)
	}

	fun pause(ids: Set<Long>) {
		val targets = works.value.orEmpty().filter {
			it.id.mostSignificantBits in ids && it.canPause
		}.map { it.id }
		if (targets.isEmpty()) return
		markUiAction(targets, DownloadUiAction.PAUSING)
		targets.forEach(workScheduler::pause)
		onActionDone.call(ReversibleAction(R.string.downloads_paused, null))
	}

	fun pauseAll() {
		val targets = works.value.orEmpty().filter { it.canPause }.map { it.id }
		if (targets.isEmpty()) return
		markUiAction(targets, DownloadUiAction.PAUSING)
		targets.forEach(workScheduler::pause)
		onActionDone.call(ReversibleAction(R.string.downloads_paused, null))
	}

	fun resume(id: UUID) {
		val item = works.value.orEmpty().firstOrNull { it.id == id && it.canResume } ?: return
		markUiAction(listOf(item.id), DownloadUiAction.RESUMING)
		workScheduler.resume(item.id)
	}

	fun resumeAll() {
		val targets = works.value.orEmpty().filter { it.canResume }.map { it.id }
		if (targets.isEmpty()) return
		markUiAction(targets, DownloadUiAction.RESUMING)
		targets.forEach(workScheduler::resume)
		onActionDone.call(ReversibleAction(R.string.downloads_resumed, null))
	}

	fun resume(ids: Set<Long>) {
		val targets = works.value.orEmpty().filter {
			it.id.mostSignificantBits in ids && it.canResume
		}.map { it.id }
		if (targets.isEmpty()) return
		markUiAction(targets, DownloadUiAction.RESUMING)
		targets.forEach(workScheduler::resume)
		onActionDone.call(ReversibleAction(R.string.downloads_resumed, null))
	}

	fun remove(ids: Set<Long>) {
		launchJob(Dispatchers.Default) {
			val snapshot = works.value ?: return@launchJob
			val uuids = HashSet<UUID>(ids.size)
			for (work in snapshot) {
				if (work.id.mostSignificantBits in ids) {
					uuids.add(work.id)
				}
			}
			workScheduler.delete(uuids)
			onActionDone.call(ReversibleAction(R.string.downloads_removed, null))
		}
	}

	fun removeCompleted() {
		val targets = works.value.orEmpty()
			.filterTo(LinkedHashSet()) { it.workState.isFinished && it.uiAction == null }
			.mapTo(LinkedHashSet()) { it.id }
		if (targets.isEmpty()) return
		launchJob(Dispatchers.Default) {
			workScheduler.delete(targets)
			onActionDone.call(ReversibleAction(R.string.downloads_removed, null))
		}
	}

	fun snapshot(ids: LongSet): Collection<DownloadItemModel> {
		return works.value?.filterTo(ArrayList(ids.size)) { x -> x.id.mostSignificantBits in ids }.orEmpty()
	}

	fun allIds(): Set<Long> = works.value?.mapToSet {
		it.id.mostSignificantBits
	} ?: emptySet()

	fun expandCollapse(item: DownloadItemModel) {
		expanded.update {
			if (item.id in it) {
				it - item.id
			} else {
				it + item.id
			}
		}
	}

	private fun markUiAction(ids: Collection<UUID>, action: DownloadUiAction) {
		if (ids.isEmpty()) return
		val targets = ids.toSet()
		pendingUiActions.update { current -> current + targets.associateWith { action } }
		viewModelScope.launch(Dispatchers.Default) {
			delay(UI_ACTION_TTL_MS)
			pendingUiActions.update { current ->
				current.toMutableMap().apply {
					for (id in targets) {
						if (this[id] == action) remove(id)
					}
				}
			}
		}
	}

	private fun DownloadItemModel.applyUiAction(action: DownloadUiAction?): DownloadItemModel = when (action) {
		DownloadUiAction.PAUSING -> if (isPaused || workState.isFinished) {
			this
		} else {
			copy(isPaused = true, eta = -1L, isStuck = false, uiAction = action)
		}
		DownloadUiAction.RESUMING -> if (!isPaused || workState.isFinished) {
			this
		} else {
			copy(isPaused = false, uiAction = action)
		}
		DownloadUiAction.CANCELLING -> if (workState.isFinished) {
			this
		} else {
			copy(isPaused = true, eta = -1L, isStuck = false, uiAction = action)
		}
		null -> this
	}

	private suspend fun List<WorkInfo>.toDownloadsList(
		exp: Set<UUID>,
		visibility: DownloadMembershipVisibility,
	): List<DownloadItemModel> {
		if (isEmpty()) {
			return emptyList()
		}
		val list = mapNotNullTo(ArrayList(size)) { it.toUiModel(it.id in exp, visibility) }
		list.sortByDescending { it.timestamp }
		return list
	}

	private fun List<DownloadItemModel>.toUiList(): List<ListModel> {
		if (isEmpty()) {
			return emptyStateList()
		}
		val queued = LinkedList<ListModel>()
		val running = LinkedList<ListModel>()
		val destination = ArrayDeque<ListModel>((size * 1.4).toInt())
		var prevDate: DateTimeAgo? = null
		for (item in this) {
			when (item.workState) {
				WorkInfo.State.RUNNING -> running += item
				WorkInfo.State.BLOCKED,
				WorkInfo.State.ENQUEUED -> queued += item

				else -> {
					val date = calculateTimeAgo(item.timestamp)
					if (prevDate != date) {
						destination += if (date != null) {
							ListHeader(date)
						} else {
							ListHeader(R.string.unknown)
						}
					}
					prevDate = date
					destination += item
				}
		}
		}
		if (running.isNotEmpty()) {
			running.addFirst(ListHeader(R.string.in_progress))
		}
		destination.addAll(0, running)
		if (queued.isNotEmpty()) {
			queued.addFirst(ListHeader(R.string.queued))
		}
		destination.addAll(0, queued)
		return destination
	}

	private suspend fun WorkInfo.toUiModel(
		isExpanded: Boolean,
		visibility: DownloadMembershipVisibility,
	): DownloadItemModel? {
		val workData = outputData.takeUnless { it.isEmpty }
			?: progress.takeUnless { it.isEmpty }
			?: workScheduler.getInputData(id)
			?: return null
		val task = workScheduler.getTask(id)
		if (task != null && task.favouriteSpace != favouriteSpace) return null
		val mangaId = DownloadState.getMangaId(workData)
		if (mangaId == 0L || !visibility.isVisible(mangaId, favouriteSpace)) return null
		val manga = getManga(mangaId) ?: return null
		val chapters = synchronized(chaptersCache) {
			chaptersCache.getOrPut(id) {
				observeChapters(manga, id, task)
			}
		}
		return DownloadItemModel(
			id = id,
			workState = state,
			manga = manga,
			error = DownloadState.getError(workData),
			isIndeterminate = DownloadState.isIndeterminate(workData),
			isPaused = DownloadState.isPaused(workData),
			max = DownloadState.getMax(workData),
			progress = DownloadState.getProgress(workData),
			eta = DownloadState.getEta(workData),
			isStuck = DownloadState.isStuck(workData),
			timestamp = DownloadState.getTimestamp(workData),
			chaptersDownloaded = DownloadState.getDownloadedChapters(workData),
			isExpanded = isExpanded,
			chapters = chapters,
		)
	}

	private fun emptyStateList() = listOf(
		EmptyState(
			icon = R.drawable.ic_empty_common,
			textPrimary = R.string.text_downloads_list_holder,
			textSecondary = 0,
			actionStringRes = 0,
		),
	)

	private suspend fun getManga(mangaId: Long): Manga? {
		mangaCache[mangaId]?.let { return it }

		var resolved: Manga? = null
		for (attempt in 0 until ACTIVE_WORK_HYDRATION_RETRIES) {
			resolved = mangaDataRepository.findMangaById(mangaId, withChapters = true)
			if (resolved != null) break
			if (attempt + 1 < ACTIVE_WORK_HYDRATION_RETRIES) {
				delay(ACTIVE_WORK_HYDRATION_RETRY_DELAY_MS)
			}
		}
		val manga = resolved ?: return null
		return cacheMutex.withLock {
			mangaCache[mangaId] ?: manga.also { mangaCache[mangaId] = it }
		}
	}

	private fun observeChapters(
		manga: Manga,
		workId: UUID,
		taskSnapshot: DownloadTask?,
	): StateFlow<List<DownloadChapter>?> = flow {
		val task = taskSnapshot ?: workScheduler.getTask(workId)
		val chapterIds = task?.chaptersIds
		// The DB lookup above already asks for chapters. Reuse that snapshot first and only contact the
		// source when chapter metadata is genuinely absent; opening Downloads must not fan out network
		// requests merely to decide whether a collapsed row can expand.
		val chapters = manga.chapters ?: tryLoad(manga)?.chapters ?: return@flow

		suspend fun mapChapters(): List<DownloadChapter> {
			val size = chapterIds?.size ?: chapters.size
			val localManga = task?.destination?.let { root ->
				localMangaRepository.findSavedMangaInRoot(manga, root)
			} ?: localMangaRepository.findSavedManga(manga)
			val localChapters = localManga?.manga?.chapters?.mapToSet { it.id }.orEmpty()
			return chapters.mapNotNullTo(ArrayList(size)) {
				if (chapterIds == null || it.id in chapterIds) {
					DownloadChapter(
						number = it.numberString(),
						name = it.title.orEmpty(),
						isDownloaded = it.id in localChapters,
					)
				} else {
					null
				}
			}
		}
		emit(mapChapters())
		localStorageChanges.collect { changed ->
			if (changed?.manga?.id == manga.id) {
				if (task?.destination == null || changed.file.isInside(task.destination)) {
					emit(mapChapters())
				}
			}
		}
	}.stateIn(
		viewModelScope + Dispatchers.Default,
		SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000L),
		null,
	)

	private fun java.io.File.isInside(root: java.io.File): Boolean {
		val normalizedRoot = runCatching { root.canonicalFile }.getOrDefault(root.absoluteFile)
		val normalizedFile = runCatching { canonicalFile }.getOrDefault(absoluteFile)
		return normalizedFile == normalizedRoot ||
			normalizedFile.path.startsWith(normalizedRoot.path + java.io.File.separator)
	}

	private suspend fun tryLoad(manga: Manga) = runCatchingCancellable {
		mangaRepositoryFactory.create(manga.source).getDetails(manga)
	}.getOrNull()

	private data class DownloadMembershipVisibility(
		val privateIds: Set<Long>,
		val normalIds: Set<Long>,
	) {
		fun isVisible(mangaId: Long, space: FavouriteSpace): Boolean = when (space) {
			FavouriteSpace.PRIVATE -> mangaId in privateIds
			FavouriteSpace.NORMAL -> mangaId !in privateIds || mangaId in normalIds
		}
	}
}
