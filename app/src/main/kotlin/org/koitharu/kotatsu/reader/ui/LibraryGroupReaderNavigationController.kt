package org.koitharu.kotatsu.reader.ui

import android.view.View
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.nav.MangaIntent
import org.koitharu.kotatsu.core.nav.ReaderIntent
import org.koitharu.kotatsu.details.domain.DetailsLoadUseCase
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.favourites.groups.domain.LibraryGroupTimelineItem
import org.koitharu.kotatsu.favourites.groups.domain.LibraryGroupsRepository
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.reader.ui.pager.ReaderUiState

/** Group-aware chapter/member navigation for readers launched from Advanced Library Groups. */
internal class LibraryGroupReaderNavigationController private constructor(
	private val activity: ReaderActivity,
) {

	private val groupId = activity.intent
		.getLongExtra(ReaderIntent.EXTRA_LIBRARY_GROUP_ID, 0L)
		.takeIf { it != 0L }

	private val favouriteSpace = FavouriteSpace.fromArgument(
		activity.intent.getIntExtra(ReaderIntent.EXTRA_LIBRARY_GROUP_SPACE, FavouriteSpace.NORMAL.dbValue),
	)

	private val entryPoint by lazy {
		EntryPointAccessors.fromApplication<LibraryGroupReaderNavigationEntryPoint>(
			activity.applicationContext,
		)
	}

	private val viewModel by lazy {
		ViewModelProvider(activity)[ReaderViewModel::class.java]
	}

	private var snapshot = NavigationSnapshot()
	private var isSwitchingMember = false

	val isGroupReader: Boolean
		get() = groupId != null

	init {
		if (groupId != null) observeNavigation()
	}

	fun switchChapterBy(delta: Int): Boolean {
		if (groupId == null || delta == 0) return false
		val current = snapshot
		val uiState = current.uiState ?: return true
		val step = if (delta > 0) 1 else -1

		val timelineIndex = current.timeline.indexOfFirst { item ->
			item.mangaId == current.currentMangaId && item.chapterId == uiState.chapter.id
		}
		if (timelineIndex >= 0) {
			val targetItem = current.timeline.getOrNull(timelineIndex + step) ?: return true
			if (targetItem.mangaId == current.currentMangaId) {
				val targetChapter = viewModel.getMangaOrNull()?.chapters
					?.firstOrNull { it.id == targetItem.chapterId }
				if (targetChapter != null && targetChapter.branch == uiState.chapter.branch) {
					viewModel.switchChapter(targetChapter.id, page = 0, scroll = 0)
					return true
				}
			}
			beginTargetSwitch { resolveTimelineTarget(targetItem) }
			return true
		}

		val hasChapterInsideMember = if (delta > 0) uiState.hasNextChapter() else uiState.hasPreviousChapter()
		if (hasChapterInsideMember) return false
		val targetMangaId = (if (delta > 0) current.nextMangaId else current.previousMangaId) ?: return false
		beginTargetSwitch { resolveBoundaryTarget(targetMangaId, delta) }
		return true
	}

	fun canSwitchChapterBy(delta: Int): Boolean {
		if (groupId == null || delta == 0) return false
		val current = snapshot
		val uiState = current.uiState ?: return false
		val timelineIndex = current.timeline.indexOfFirst { item ->
			item.mangaId == current.currentMangaId && item.chapterId == uiState.chapter.id
		}
		if (timelineIndex >= 0) {
			return current.timeline.getOrNull(timelineIndex + if (delta > 0) 1 else -1) != null
		}
		return if (delta > 0) {
			uiState.hasNextChapter() || current.nextMangaId != null
		} else {
			uiState.hasPreviousChapter() || current.previousMangaId != null
		}
	}

	fun switchChapterAtPageBoundary(delta: Int): Boolean {
		if (groupId == null || delta == 0) return false
		val uiState = snapshot.uiState ?: return false
		val atBoundary = if (delta > 0) {
			uiState.currentPage >= (uiState.totalPages - 1).coerceAtLeast(0)
		} else {
			uiState.currentPage <= 0
		}
		if (!atBoundary || !canSwitchChapterBy(delta)) return false
		if (!switchChapterBy(delta)) viewModel.switchChapterBy(if (delta > 0) 1 else -1)
		return true
	}

	private fun beginTargetSwitch(resolver: suspend () -> ResolvedTarget) {
		if (isSwitchingMember) return
		isSwitchingMember = true
		updateChapterButtons()
		activity.lifecycleScope.launch {
			try {
				val target = withContext(Dispatchers.Default) { resolver() }
				openTarget(target)
			} catch (cancelled: CancellationException) {
				throw cancelled
			} catch (_: Throwable) {
				isSwitchingMember = false
				updateChapterButtons()
				Toast.makeText(activity, R.string.library_group_navigation_error, Toast.LENGTH_SHORT).show()
			}
		}
	}

	private fun observeNavigation() {
		val targetGroupId = groupId ?: return
		activity.lifecycleScope.launch {
			activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
				combine(
					entryPoint.libraryGroupsRepository.observeGroups(favouriteSpace),
					entryPoint.libraryGroupsRepository.observeTimeline(targetGroupId, favouriteSpace),
					viewModel.uiState,
				) { groups, timeline, uiState ->
					val currentMangaId = viewModel.getMangaOrNull()?.id
					val group = groups.firstOrNull { it.id == targetGroupId }
					val index = if (group != null && currentMangaId != null) {
						group.members.indexOfFirst { it.mangaId == currentMangaId }
					} else -1
					NavigationSnapshot(
						uiState = uiState,
						currentMangaId = currentMangaId,
						previousMangaId = if (index > 0) group?.members?.getOrNull(index - 1)?.mangaId else null,
						nextMangaId = if (index >= 0) group?.members?.getOrNull(index + 1)?.mangaId else null,
						timeline = timeline,
					)
				}.collect { state ->
					snapshot = state
					updateChapterButtons()
				}
			}
		}
	}

	private fun updateChapterButtons() {
		val nextAvailable = !isSwitchingMember && canSwitchChapterBy(1)
		val previousAvailable = !isSwitchingMember && canSwitchChapterBy(-1)
		activity.findViewById<View>(R.id.button_next)?.let { button ->
			button.post { button.isEnabled = nextAvailable }
		}
		activity.findViewById<View>(R.id.button_prev)?.let { button ->
			button.post { button.isEnabled = previousAvailable }
		}
	}

	private suspend fun resolveTimelineTarget(item: LibraryGroupTimelineItem): ResolvedTarget {
		val details = loadDetails(item.mangaId)
		val chapter = details.allChapters.firstOrNull { it.id == item.chapterId }
			?: error("Timeline chapter is no longer available")
		return ResolvedTarget(details.toManga(), chapter)
	}

	private suspend fun resolveBoundaryTarget(mangaId: Long, delta: Int): ResolvedTarget {
		val details = loadDetails(mangaId)
		val chapter = if (delta > 0) details.allChapters.firstOrNull() else details.allChapters.lastOrNull()
			?: error("Adjacent group manga has no chapters")
		return ResolvedTarget(details.toManga(), chapter)
	}

	private suspend fun loadDetails(mangaId: Long) = entryPoint.detailsLoadUseCase(
		MangaIntent(SavedStateHandle(mapOf(AppRouter.KEY_ID to mangaId))),
		force = false,
	).first { it.isLoaded }

	private fun openTarget(target: ResolvedTarget) {
		val targetGroupId = groupId ?: return
		viewModel.saveCurrentState()
		val builder = ReaderIntent.Builder(activity)
			.manga(target.manga)
			.branch(target.chapter.branch)
			.state(ReaderState(target.chapter.id, page = 0, scroll = 0))
			.libraryGroup(targetGroupId, favouriteSpace.dbValue)
		viewModel.isIncognitoMode.value?.let { builder.incognito(it) }
		if (viewModel.isPeekMode.value) builder.peek()
		activity.startActivity(builder.build().intent)
		activity.finish()
	}

	private data class NavigationSnapshot(
		val uiState: ReaderUiState? = null,
		val currentMangaId: Long? = null,
		val previousMangaId: Long? = null,
		val nextMangaId: Long? = null,
		val timeline: List<LibraryGroupTimelineItem> = emptyList(),
	)

	private data class ResolvedTarget(
		val manga: Manga,
		val chapter: MangaChapter,
	)

	companion object {
		fun from(activity: ReaderActivity): LibraryGroupReaderNavigationController {
			val decor = activity.window.decorView
			val existing = decor.getTag(R.id.tag_library_group_navigation_controller)
			if (existing is LibraryGroupReaderNavigationController) return existing
			return LibraryGroupReaderNavigationController(activity).also { controller ->
				decor.setTag(R.id.tag_library_group_navigation_controller, controller)
			}
		}
	}
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface LibraryGroupReaderNavigationEntryPoint {
	val libraryGroupsRepository: LibraryGroupsRepository
	val detailsLoadUseCase: DetailsLoadUseCase
}
