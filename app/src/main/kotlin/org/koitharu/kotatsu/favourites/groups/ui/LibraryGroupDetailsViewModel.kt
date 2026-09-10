package org.koitharu.kotatsu.favourites.groups.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.backup.local.domain.CustomCoverCodec
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.model.FavouriteCategory
import org.koitharu.kotatsu.core.model.withOverride
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.nav.MangaIntent
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.details.domain.DetailsLoadUseCase
import org.koitharu.kotatsu.favourites.data.EXTRA_FAVOURITE_SPACE
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.favourites.domain.FavouritesRepository
import org.koitharu.kotatsu.favourites.groups.domain.LibraryGroup
import org.koitharu.kotatsu.favourites.groups.domain.LibraryGroupMember
import org.koitharu.kotatsu.favourites.groups.domain.LibraryGroupTimelineItem
import org.koitharu.kotatsu.favourites.groups.domain.LibraryGroupsRepository
import org.koitharu.kotatsu.favourites.groups.tracking.LibraryGroupTracking
import org.koitharu.kotatsu.favourites.groups.tracking.LibraryGroupTrackingRepository
import org.koitharu.kotatsu.favourites.ui.FavouritesActivity
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerManga
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerMangaInfo
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import javax.inject.Inject

data class LibraryGroupDetailsMemberUi(
	val member: LibraryGroupMember,
	val manga: Manga,
	val chapters: List<MangaChapter>,
	val isExpanded: Boolean = false,
	val isLoading: Boolean = false,
	val error: String? = null,
)

data class LibraryGroupDetailsState(
	val group: LibraryGroup? = null,
	val members: List<LibraryGroupDetailsMemberUi> = emptyList(),
	val timeline: List<LibraryGroupTimelineItem> = emptyList(),
	val tracking: List<LibraryGroupTracking> = emptyList(),
	val trackingProgress: Int = 0,
	val isLoading: Boolean = true,
	val error: String? = null,
)

private data class LibraryGroupReloadData(
	val group: LibraryGroup,
	val members: List<LibraryGroupDetailsMemberUi>,
	val timeline: List<LibraryGroupTimelineItem>,
	val tracking: List<LibraryGroupTracking>,
	val progress: Int,
)

@HiltViewModel
class LibraryGroupDetailsViewModel @Inject constructor(
	savedStateHandle: SavedStateHandle,
	private val groupsRepository: LibraryGroupsRepository,
	private val favouritesRepository: FavouritesRepository,
	private val mangaDataRepository: MangaDataRepository,
	private val detailsLoadUseCase: DetailsLoadUseCase,
	private val customCoverCodec: CustomCoverCodec,
	private val database: MangaDatabase,
	private val trackingRepository: LibraryGroupTrackingRepository,
) : ViewModel() {

	val groupId: Long = savedStateHandle[FavouritesActivity.EXTRA_LIBRARY_GROUP_ID] ?: 0L
	val favouriteSpace: FavouriteSpace = FavouriteSpace.fromArgument(
		savedStateHandle.get<Int>(EXTRA_FAVOURITE_SPACE) ?: FavouriteSpace.NORMAL.dbValue,
	)
	private val memberJobs = HashMap<Long, Job>()
	private val _state = MutableStateFlow(LibraryGroupDetailsState())
	val state: StateFlow<LibraryGroupDetailsState> = _state.asStateFlow()

	init {
		reload()
		mangaDataRepository.observeOverridesTrigger(emitInitialState = false)
			.onEach { refreshOverrides() }
			.launchIn(viewModelScope)
	}

	fun reload() {
		viewModelScope.launch(Dispatchers.Default) {
			_state.update { it.copy(isLoading = true, error = null) }
			runCatching {
				require(groupId != 0L) { "Missing library group id" }
				groupsRepository.repairInvalidGroups(favouriteSpace)
				val group = requireNotNull(groupsRepository.getGroup(groupId, favouriteSpace)) {
					"Library group is no longer available"
				}
				val members = group.members.mapNotNull { member ->
					val sourceManga = mangaDataRepository.findMangaById(member.mangaId, withChapters = true)
						?: return@mapNotNull null
					val manga = sourceManga.withOverride(mangaDataRepository.getOverride(member.mangaId))
					LibraryGroupDetailsMemberUi(
						member = member,
						manga = manga,
						chapters = manga.chapters.orEmpty(),
					)
				}
				require(members.size >= 2) { "Library group no longer has enough members" }
				val timeline = groupsRepository.getTimeline(groupId, favouriteSpace)
				LibraryGroupReloadData(
					group = group,
					members = members,
					timeline = timeline,
					tracking = trackingRepository.get(groupId),
					progress = calculateTrackingProgress(timeline),
				)
			}.onSuccess { data ->
				val firstId = data.members.first().member.mangaId
				_state.value = LibraryGroupDetailsState(
					group = data.group,
					members = data.members.map { it.copy(isExpanded = it.member.mangaId == firstId) },
					timeline = data.timeline,
					tracking = data.tracking,
					trackingProgress = data.progress,
					isLoading = false,
				)
				if (data.members.first().chapters.isEmpty()) loadMember(firstId, force = false)
			}.onFailure { error ->
				_state.value = LibraryGroupDetailsState(
					isLoading = false,
					error = error.message,
				)
			}
		}
	}

	fun toggleMember(mangaId: Long) {
		val target = _state.value.members.firstOrNull { it.member.mangaId == mangaId } ?: return
		val expand = !target.isExpanded
		_state.update { current ->
			current.copy(
				members = current.members.map { member ->
					if (member.member.mangaId == mangaId) member.copy(isExpanded = expand) else member
				},
			)
		}
		if (expand && target.chapters.isEmpty() && !target.isLoading) {
			loadMember(mangaId, force = false)
		}
	}

	fun refreshMember(mangaId: Long) {
		loadMember(mangaId, force = true)
	}

	suspend fun getPlacementCategories(): List<FavouriteCategory> = withContext(Dispatchers.Default) {
		favouritesRepository.observeCategoriesForLibrary(favouriteSpace).first()
	}

	suspend fun setCategoryPlacement(categoryIds: Collection<Long>) = withContext(Dispatchers.Default) {
		groupsRepository.replaceCategories(groupId, categoryIds, favouriteSpace)
		val normalized = LinkedHashSet(categoryIds.filter { it > 0L })
		_state.update { current ->
			current.copy(group = current.group?.copy(categoryIds = normalized))
		}
	}

	suspend fun updateMetadata(
		title: String,
		alternativeTitle: String?,
		author: String?,
		artist: String?,
		description: String?,
		coverUrl: String?,
		metadataSource: Int? = _state.value.group?.metadataSource,
		metadataTargetId: Long? = _state.value.group?.metadataTargetId,
	) = withContext(Dispatchers.Default) {
		groupsRepository.updateMetadata(
			groupId = groupId,
			title = title,
			coverUrl = coverUrl,
			alternativeTitle = alternativeTitle,
			author = author,
			artist = artist,
			description = description,
			metadataSource = metadataSource,
			metadataTargetId = metadataTargetId,
			space = favouriteSpace,
		)
		val refreshed = requireNotNull(groupsRepository.getGroup(groupId, favouriteSpace))
		_state.update { it.copy(group = refreshed) }
	}

	suspend fun setLocalCover(uri: String) = withContext(Dispatchers.Default) {
		val group = requireNotNull(_state.value.group) { "Library group is no longer available" }
		val encoded = requireNotNull(customCoverCodec.read(uri)) { "Unable to read the selected image" }
		val storedUrl = requireNotNull(
			customCoverCodec.materialize(
				mangaId = groupCoverStorageId(group.id),
				coverData = encoded.data,
				coverFileExtension = encoded.extension,
				previousUrl = group.coverUrl,
			),
		) { "Unable to store the selected image" }
		groupsRepository.updateGroup(group.id, group.title, storedUrl, favouriteSpace)
		_state.update { current ->
			current.copy(group = current.group?.copy(coverUrl = storedUrl))
		}
	}

	fun availableTrackingServices(): List<ScrobblerService> = trackingRepository.availableServices()

	suspend fun searchTracking(service: ScrobblerService, query: String): List<ScrobblerManga> =
		withContext(Dispatchers.Default) { trackingRepository.search(service, query) }

	suspend fun getTrackingMetadata(service: ScrobblerService, targetId: Long): ScrobblerMangaInfo =
		withContext(Dispatchers.Default) { trackingRepository.getMetadata(service, targetId) }

	suspend fun linkTracking(service: ScrobblerService, target: ScrobblerManga) = withContext(Dispatchers.Default) {
		val linked = trackingRepository.link(
			groupId = groupId,
			space = favouriteSpace,
			service = service,
			target = target,
			groupProgress = _state.value.trackingProgress,
		)
		_state.update { current ->
			current.copy(tracking = (current.tracking.filterNot { it.service == service } + linked).sortedBy { it.service.id })
		}
	}

	suspend fun refreshTracking(service: ScrobblerService) = withContext(Dispatchers.Default) {
		val refreshed = trackingRepository.refresh(groupId, favouriteSpace, service)
		_state.update { current ->
			current.copy(tracking = current.tracking.map { if (it.service == service) refreshed else it })
		}
	}

	suspend fun unlinkTracking(service: ScrobblerService) = withContext(Dispatchers.Default) {
		trackingRepository.unlink(groupId, favouriteSpace, service)
		_state.update { current -> current.copy(tracking = current.tracking.filterNot { it.service == service }) }
	}

	suspend fun syncTrackingProgress() = withContext(Dispatchers.Default) {
		val progress = calculateTrackingProgress(_state.value.timeline)
		val synced = trackingRepository.syncProgress(groupId, favouriteSpace, progress)
		_state.update { it.copy(tracking = synced, trackingProgress = progress) }
	}

	suspend fun deleteGroup() = withContext(Dispatchers.Default) {
		groupsRepository.deleteGroup(groupId, favouriteSpace)
	}

	suspend fun prepareTimelineEditor(): List<LibraryGroupTimelineEditorItem> = withContext(Dispatchers.Default) {
		val group = requireNotNull(_state.value.group) { "Library group is no longer available" }
		val memberSnapshot = _state.value.members.associateBy { it.member.mangaId }
		val available = ArrayList<LibraryGroupTimelineEditorItem>()
		group.members.forEachIndexed { memberPosition, groupMember ->
			val original = requireNotNull(memberSnapshot[groupMember.mangaId]) {
				"A group member is no longer available"
			}
			val loaded = if (original.chapters.isNotEmpty()) original else loadMemberForTimeline(original)
			loaded.chapters.distinctBy { it.id }.forEachIndexed { chapterIndex, chapter ->
				available += LibraryGroupTimelineEditorItem(
					mangaId = groupMember.mangaId,
					mangaTitle = loaded.manga.title,
					memberPosition = memberPosition,
					chapter = chapter,
					chapterIndex = chapterIndex,
				)
			}
		}

		val saved = groupsRepository.getTimeline(groupId, favouriteSpace)
		if (saved.isEmpty()) return@withContext available
		val availableByKey = available.associateBy { it.key }
		val scheduled = saved.mapNotNull { item ->
			availableByKey[LibraryGroupTimelineKey(item.mangaId, item.chapterId)]
		}
		val scheduledKeys = scheduled.mapTo(HashSet()) { it.key }
		scheduled + available.filterNot { it.key in scheduledKeys }
	}

	suspend fun saveTimeline(items: List<LibraryGroupTimelineEditorItem>) = withContext(Dispatchers.Default) {
		val orderedItems = items.mapIndexed { index, item ->
			LibraryGroupTimelineItem(
				mangaId = item.mangaId,
				chapterId = item.chapter.id,
				position = index,
			)
		}
		groupsRepository.replaceTimeline(
			groupId = groupId,
			orderedItems = orderedItems,
			space = favouriteSpace,
		)
		val progress = calculateTrackingProgress(orderedItems)
		_state.update { it.copy(timeline = orderedItems, trackingProgress = progress) }
	}

	private suspend fun calculateTrackingProgress(timeline: List<LibraryGroupTimelineItem>): Int {
		if (timeline.isEmpty()) return 0
		var best = 0
		for (mangaId in timeline.mapTo(LinkedHashSet()) { it.mangaId }) {
			val history = database.getHistoryDao().find(mangaId) ?: continue
			val index = timeline.indexOfLast { item ->
				item.mangaId == mangaId && item.chapterId == history.chapterId
			}
			if (index >= 0) best = maxOf(best, index + 1)
		}
		return best
	}

	private suspend fun loadMemberForTimeline(member: LibraryGroupDetailsMemberUi): LibraryGroupDetailsMemberUi {
		val intent = MangaIntent(
			SavedStateHandle(mapOf(AppRouter.KEY_ID to member.member.mangaId)),
		)
		val details = detailsLoadUseCase(intent, force = false).first { it.isLoaded }
		val updated = member.copy(
			manga = details.toManga(),
			chapters = details.allChapters,
			isLoading = false,
			error = null,
		)
		updateMember(member.member.mangaId) { current ->
			updated.copy(isExpanded = current.isExpanded)
		}
		return updated
	}

	private fun loadMember(mangaId: Long, force: Boolean) {
		memberJobs[mangaId]?.cancel()
		memberJobs[mangaId] = viewModelScope.launch(Dispatchers.Default) {
			updateMember(mangaId) { it.copy(isLoading = true, error = null) }
			try {
				val intent = MangaIntent(
					SavedStateHandle(mapOf(AppRouter.KEY_ID to mangaId)),
				)
				detailsLoadUseCase(intent, force).collect { details ->
					updateMember(mangaId) { member ->
						member.copy(
							manga = details.toManga(),
							chapters = details.allChapters,
							error = null,
						)
					}
				}
				updateMember(mangaId) { it.copy(isLoading = false) }
			} catch (cancelled: CancellationException) {
				throw cancelled
			} catch (error: Throwable) {
				updateMember(mangaId) {
					it.copy(isLoading = false, error = error.message)
				}
			}
		}
	}

	private suspend fun refreshOverrides() {
		val snapshot = _state.value.members
		if (snapshot.isEmpty()) return
		val overrides = snapshot.associate { member ->
			member.member.mangaId to mangaDataRepository.getOverride(member.member.mangaId)
		}
		_state.update { current ->
			current.copy(
				members = current.members.map { member ->
					member.copy(manga = member.manga.withOverride(overrides[member.member.mangaId]))
				},
			)
		}
	}

	private fun updateMember(
		mangaId: Long,
		transform: (LibraryGroupDetailsMemberUi) -> LibraryGroupDetailsMemberUi,
	) {
		_state.update { current ->
			current.copy(
				members = current.members.map { member ->
					if (member.member.mangaId == mangaId) transform(member) else member
				},
			)
		}
	}

	private fun groupCoverStorageId(id: Long): Long = Long.MIN_VALUE + id

	override fun onCleared() {
		memberJobs.values.forEach { it.cancel() }
		memberJobs.clear()
		super.onCleared()
	}
}
