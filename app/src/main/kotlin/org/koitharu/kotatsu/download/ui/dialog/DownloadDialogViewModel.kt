package org.koitharu.kotatsu.download.ui.dialog

import androidx.collection.ArrayMap
import androidx.collection.ArraySet
import androidx.collection.MutableLongLongMap
import androidx.lifecycle.SavedStateHandle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.getPreferredBranch
import org.koitharu.kotatsu.core.model.isNovelContent
import org.koitharu.kotatsu.core.model.parcelable.ParcelableManga
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.DownloadFormat
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.core.util.ext.isWriteable
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.core.util.ext.require
import org.koitharu.kotatsu.download.domain.DownloadDestinationStore
import org.koitharu.kotatsu.download.domain.NormalDownloadAdmissionPlan
import org.koitharu.kotatsu.download.ui.worker.DownloadTask
import org.koitharu.kotatsu.download.ui.worker.DownloadWorker
import org.koitharu.kotatsu.favourites.data.EXTRA_FAVOURITE_SPACE
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.favourites.domain.FavouritesRepository
import org.koitharu.kotatsu.history.data.HistoryRepository
import org.koitharu.kotatsu.local.data.LocalMangaRepository
import org.koitharu.kotatsu.local.data.LocalStorageManager
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.util.mapToSet
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.parsers.util.sizeOrZero
import org.koitharu.kotatsu.parsers.util.suspendlazy.suspendLazy
import org.koitharu.kotatsu.settings.storage.DirectoryModel
import org.koitharu.kotatsu.sources.compat.EhentaiSourceFamily
import java.io.File
import javax.inject.Inject

@HiltViewModel
class DownloadDialogViewModel @Inject constructor(
	private val savedStateHandle: SavedStateHandle,
	private val scheduler: DownloadWorker.Scheduler,
	private val localStorageManager: LocalStorageManager,
	private val localMangaRepository: LocalMangaRepository,
	private val mangaRepositoryFactory: MangaRepository.Factory,
	private val historyRepository: HistoryRepository,
	private val settings: AppSettings,
	private val destinationStore: DownloadDestinationStore,
	private val favouritesRepository: FavouritesRepository,
) : BaseViewModel() {

	val manga = savedStateHandle.require<Array<ParcelableManga>>(AppRouter.KEY_MANGA).map {
		it.manga
	}
	private val officialGallery = manga.singleOrNull()?.takeIf {
		EhentaiSourceFamily.isOfficialSource(it.source.name)
	}
	val supportsSeparateExhentaiCopy = officialGallery != null
	val supportsExhentaiFilenameId = officialGallery?.let {
		(EhentaiSourceFamily.galleryId(it.publicUrl, it.url) ?: 0L) > 0L
	} == true

	private val explicitSpace = savedStateHandle.get<Int>(EXTRA_FAVOURITE_SPACE)?.let {
		FavouriteSpace.fromArgument(it)
	}
	private val mangaDetails = suspendLazy {
		coroutineScope {
			manga.map { m ->
				async { m.getDetails() }
			}.awaitAll()
		}
	}
	val onScheduled = MutableEventFlow<Boolean>()
	val defaultFormat = MutableStateFlow<DownloadFormat?>(null)
	val availableDestinations = MutableStateFlow(listOf(defaultDestination()))
	internal val normalDestinationPlan = MutableStateFlow<NormalDownloadAdmissionPlan?>(null)
	val chaptersSelectOptions = MutableStateFlow(
		ChapterSelectOptions(
			wholeManga = ChaptersSelectMacro.WholeManga(0),
			wholeBranch = null,
			firstChapters = null,
			unreadChapters = null,
		),
	)
	val isOptionsLoading = MutableStateFlow(true)

	init {
		launchJob(Dispatchers.Default) {
			defaultFormat.value = settings.preferredDownloadFormat
		}
		launchJob(Dispatchers.Default) {
			try {
				loadAvailableOptions()
			} finally {
				isOptionsLoading.value = false
			}
		}
		loadAvailableDestinations()
	}

	fun confirm(
		startNow: Boolean,
		chaptersMacro: ChaptersSelectMacro,
		format: DownloadFormat?,
		destination: DirectoryModel?,
		allowMetered: Boolean,
		appendGalleryId: Boolean = false,
		separateCopy: Boolean = false,
	) {
		val allowDiagnosticExport = !settings.isIncognitoModeEnabled
		launchLoadingJob(Dispatchers.Default) {
			val space = resolveFavouriteSpace()
			val plan = checkNotNull(normalDestinationPlan.value)
			val configuredRoot = destinationStore.effectiveRoot(space)
			val selectedRoot = if (plan.eligibleIds.size == manga.size && manga.isNotEmpty()) plan.root
				else destination?.file ?: configuredRoot
			if (selectedRoot != null && !selectedRoot.isWriteable()) {
				throw kotlin.io.AccessDeniedException(selectedRoot)
			}
			val tasks = mangaDetails.get().map { m ->
				val isOfficialExhentai = officialGallery != null && EhentaiSourceFamily.isOfficialSource(m.source.name)
				val selectedFormat = if (isOfficialExhentai) format ?: settings.preferredDownloadFormat else format
				val separate = isOfficialExhentai && separateCopy
				val chapters = checkNotNull(m.chapters) { "Manga \"${m.title}\" cannot be loaded" }
				m to DownloadTask(
					mangaId = m.id,
					isPaused = !startNow,
					isSilent = false,
					chaptersIds = if (separate) null else chaptersMacro.getChaptersIds(m.id, chapters)?.toLongArray(),
					destination = selectedRoot,
					format = if (m.isNovelContent) null else selectedFormat,
					allowMeteredNetwork = allowMetered,
					favouriteSpace = space,
					appendGalleryIdToChapterFile = isOfficialExhentai && appendGalleryId &&
						supportsExhentaiFilenameId && selectedFormat != DownloadFormat.SINGLE_CBZ,
					downloadSeparateCopy = separate,
					allowDiagnosticExport = allowDiagnosticExport,
				)
			}
			scheduler.schedule(tasks, explicitSpace = explicitSpace ?: space.takeIf { it == FavouriteSpace.PRIVATE },
				normalSelection = plan)
			onScheduled.call(startNow)
		}
	}

	fun setSelectedBranch(branch: String?) {
		val snapshot = chaptersSelectOptions.value
		chaptersSelectOptions.value = snapshot.copy(
			wholeBranch = snapshot.wholeBranch?.copy(branch),
		)
	}

	fun setFirstChaptersCount(count: Int) {
		val snapshot = chaptersSelectOptions.value
		chaptersSelectOptions.value = snapshot.copy(
			firstChapters = snapshot.firstChapters?.copy(count),
		)
	}

	fun setUnreadChaptersCount(count: Int) {
		val snapshot = chaptersSelectOptions.value
		chaptersSelectOptions.value = snapshot.copy(
			unreadChapters = snapshot.unreadChapters?.copy(count),
		)
	}

	private fun defaultDestination() = DirectoryModel(
		title = null,
		titleRes = R.string.system_default,
		file = null,
		isRemovable = false,
		isChecked = true,
		isAvailable = true,
	)

	private suspend fun loadAvailableOptions() {
		val details = mangaDetails.get()
		var totalChapters = 0
		val branches = ArrayMap<String?, Int>()
		var maxChapters = 0
		var maxUnreadChapters = 0
		val preferredBranches = ArraySet<String?>(details.size)
		val currentChaptersIds = MutableLongLongMap(details.size)

		details.forEach { m ->
			val history = historyRepository.getOne(m)
			if (history != null) {
				currentChaptersIds[m.id] = history.chapterId
				val unreadChaptersCount = m.chapters
					?.dropWhile { it.id != history.chapterId }
					?.drop(1)
					.sizeOrZero()
				maxUnreadChapters = maxOf(maxUnreadChapters, unreadChaptersCount)
			} else {
				maxUnreadChapters = maxOf(maxUnreadChapters, m.chapters.sizeOrZero())
			}
			maxChapters = maxOf(maxChapters, m.chapters.sizeOrZero())
			preferredBranches.add(m.getPreferredBranch(history))
			m.chapters?.forEach { c ->
				totalChapters++
				branches.increment(c.branch)
			}
		}
		val defaultBranch = preferredBranches.firstOrNull()
		chaptersSelectOptions.value = ChapterSelectOptions(
			wholeManga = ChaptersSelectMacro.WholeManga(totalChapters),
			wholeBranch = if (branches.size > 1) {
				ChaptersSelectMacro.WholeBranch(
					branches = branches,
					selectedBranch = defaultBranch,
				)
			} else {
				null
			},
			firstChapters = if (maxChapters > 0) {
				ChaptersSelectMacro.FirstChapters(
					chaptersCount = minOf(5, maxChapters),
					maxAvailableCount = maxChapters,
					branch = defaultBranch,
				)
			} else {
				null
			},
			unreadChapters = if (currentChaptersIds.isNotEmpty()) {
				ChaptersSelectMacro.UnreadChapters(
					chaptersCount = minOf(5, maxUnreadChapters),
					maxAvailableCount = maxUnreadChapters,
					currentChaptersIds = currentChaptersIds,
				)
			} else {
				null
			},
		)
	}

	private fun loadAvailableDestinations() = launchJob(Dispatchers.Default) {
		val space = resolveFavouriteSpace()
		val provenance = explicitSpace ?: space.takeIf { it == FavouriteSpace.PRIVATE }
		val plan = scheduler.previewNormalDestination(manga, provenance)
		if (manga.isNotEmpty() && plan.eligibleIds.size == manga.size) {
			// For wholly eligible selections the user sees the only permitted destination.
			val root = plan.root
			availableDestinations.value = listOf(if (root == null) defaultDestination().copy(isAvailable = false)
				else DirectoryModel(
					title = localStorageManager.getDirectoryDisplayName(root, isFullPath = true),
					titleRes = 0, file = root, isChecked = true, isRemovable = false,
					isAvailable = root.isDirectory && root.isWriteable(),
				))
			normalDestinationPlan.value = plan
			return@launchJob
		}
		// In a mixed selection keep the existing picker for noneligible items; eligible
		// Normal items have a separate, explicitly displayed fixed destination.
		val configuredRoot = destinationStore.effectiveRoot(space)
		val defaultDir = if (space == FavouriteSpace.PRIVATE && destinationStore.privateUsesOwnRoot()) {
			configuredRoot
		} else {
			manga.mapToSet {
				localMangaRepository.getOutputDir(it, configuredRoot)
			}.singleOrNull() ?: configuredRoot
		}
		val dirs = localStorageManager.getWriteableDirs()
		availableDestinations.value = buildList(dirs.size + 1) {
			if (defaultDir == null) {
				add(defaultDestination())
			} else if (defaultDir !in dirs) {
				add(
					DirectoryModel(
						title = localStorageManager.getDirectoryDisplayName(defaultDir, isFullPath = false),
						titleRes = 0,
						file = defaultDir,
						isChecked = true,
						isAvailable = runCatching { defaultDir.isWriteable() }.getOrDefault(false),
						isRemovable = false,
					),
				)
			}
			dirs.mapTo(this) { dir ->
				DirectoryModel(
					title = localStorageManager.getDirectoryDisplayName(dir, isFullPath = false),
					titleRes = 0,
					file = dir,
					isChecked = dir == defaultDir,
					isAvailable = true,
					isRemovable = false,
				)
			}
		}
		normalDestinationPlan.value = plan
	}

	private suspend fun resolveFavouriteSpace(): FavouriteSpace {
		explicitSpace?.let { return it }
		if (manga.isEmpty()) return FavouriteSpace.NORMAL
		val allPrivateOnly = manga.all { item ->
			val privateMember = favouritesRepository.isFavorite(item.id, FavouriteSpace.PRIVATE)
			val normalMember = favouritesRepository.isFavorite(item.id, FavouriteSpace.NORMAL)
			privateMember && !normalMember
		}
		return if (allPrivateOnly) FavouriteSpace.PRIVATE else FavouriteSpace.NORMAL
	}

	private suspend fun Manga.getDetails(): Manga = runCatchingCancellable {
		mangaRepositoryFactory.create(source).getDetails(this)
	}.onFailure { e ->
		e.printStackTraceDebug()
	}.getOrDefault(this)

	private fun <T> MutableMap<T, Int>.increment(key: T) {
		put(key, getOrDefault(key, 0) + 1)
	}
}
