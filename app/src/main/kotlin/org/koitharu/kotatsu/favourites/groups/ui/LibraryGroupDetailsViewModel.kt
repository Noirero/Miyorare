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
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.core.model.withOverride
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.nav.MangaIntent
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.details.domain.DetailsLoadUseCase
import org.koitharu.kotatsu.favourites.groups.domain.LibraryGroup
import org.koitharu.kotatsu.favourites.groups.domain.LibraryGroupMember
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
	val isLoading: Boolean = true,
	val error: String? = null,
)

@HiltViewModel
class LibraryGroupDetailsViewModel @Inject constructor(
	savedStateHandle: SavedStateHandle,
	private val groupsRepository: LibraryGroupsRepository,
	private val mangaDataRepository: MangaDataRepository,
	private val detailsLoadUseCase: DetailsLoadUseCase,
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
				group to members
			}.onSuccess { (group, members) ->
				val firstId = members.first().member.mangaId
				_state.value = LibraryGroupDetailsState(
					group = group,
					members = members.map { it.copy(isExpanded = it.member.mangaId == firstId) },
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

	override fun onCleared() {
		memberJobs.values.forEach { it.cancel() }
		memberJobs.clear()
		super.onCleared()
	}
}
