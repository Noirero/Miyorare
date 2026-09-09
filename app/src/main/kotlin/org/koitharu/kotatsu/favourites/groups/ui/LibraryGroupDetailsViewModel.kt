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
import org.koitharu.kotatsu.core.model.FavouriteCategory
import org.koitharu.kotatsu.core.model.withOverride
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.nav.MangaIntent
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.details.domain.DetailsLoadUseCase
import org.koitharu.kotatsu.favourites.domain.FavouritesRepository
import org.koitharu.kotatsu.favourites.groups.domain.LibraryGroup
import org.koitharu.kotatsu.favourites.groups.domain.LibraryGroupMember
import org.koitharu.kotatsu.favourites.groups.domain.LibraryGroupTimelineItem
import org.koitharu.kotatsu.favourites.groups.domain.LibraryGroupsRepository
import org.koitharu.kotatsu.favourites.ui.FavouritesActivity
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
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
	val isLoading: Boolean = true,
	val error: String? = null,
)

@HiltViewModel
class LibraryGroupDetailsViewModel @Inject constructor(
	savedStateHandle: SavedStateHandle,
	private val groupsRepository: LibraryGroupsRepository,
	private val favouritesRepository: FavouritesRepository,
	private val mangaDataRepository: MangaDataRepository,
	private val detailsLoadUseCase: DetailsLoadUseCase,
	private val customCoverCodec: CustomCoverCodec,
) : ViewModel() {

	val groupId: Long = savedStateHandle[FavouritesActivity.EXTRA_LIBRARY_GROUP_ID] ?: 0L
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
				groupsRepository.repairInvalidGroups()
				val group = requireNotNull(groupsRepository.getGroup(groupId)) {
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
				Triple(group, members, groupsRepository.getTimeline(groupId))
			}.onSuccess { (group, members, timeline) ->
				val firstId = members.first().member.mangaId
				_state.value = LibraryGroupDetailsState(
					group = group,
					members = members.map { it.copy(isExpanded = it.member.mangaId == firstId) },
					timeline = timeline,
					isLoading = false,
				)
				if (members.first().chapters.isEmpty()) loadMember(firstId, force = false)
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
		favouritesRepository.observeCategories().first()
	}

	suspend fun setCategoryPlacement(categoryIds: Collection<Long>) = withContext(Dispatchers.Default) {
		groupsRepository.replaceCategories(groupId, categoryIds)
		val normalized = LinkedHashSet(categoryIds.filter { it > 0L })
		_state.update { current ->
			current.copy(group = current.group?.copy(categoryIds = normalized))
		}
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
		groupsRepository.updateGroup(group.id, group.title, storedUrl)
		_state.update { current ->
			current.copy(group = current.group?.copy(coverUrl = storedUrl))
		}
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

		val saved = groupsRepository.getTimeline(groupId)
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
		)
		_state.update { it.copy(timeline = orderedItems) }
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
