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
import org.koitharu.kotatsu.core.util.ext.HapticEffect
import org.koitharu.kotatsu.core.util.ext.hapticFeedback
import org.koitharu.kotatsu.details.domain.DetailsLoadUseCase
import org.koitharu.kotatsu.favourites.groups.domain.LibraryGroupsRepository
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.reader.ui.pager.ReaderUiState

/**
 * Adds cross-member chapter navigation only to readers launched with an Advanced Library Group id.
 * Every member remains an ordinary manga with its original source, chapter ids, history and bookmarks.
 */
internal class LibraryGroupReaderNavigationController(
	private val activity: ReaderActivity,
) {

	private val groupId = activity.intent
		.getLongExtra(ReaderIntent.EXTRA_LIBRARY_GROUP_ID, 0L)
		.takeIf { it != 0L }

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

	init {
		activity.window.decorView.post {
			if (groupId != null && !activity.isFinishing && !activity.isDestroyed) {
				bindChapterButtons()
				observeNavigation()
			}
		}
	}

	fun switchChapterBy(delta: Int): Boolean {
		if (groupId == null || delta == 0) return false
		val current = snapshot
		val uiState = current.uiState ?: return false
		val hasChapterInsideMember = if (delta > 0) {
			uiState.hasNextChapter()
		} else {
			uiState.hasPreviousChapter()
		}
		if (hasChapterInsideMember) return false

		val targetMangaId = (
			if (delta > 0) current.nextMangaId else current.previousMangaId
		) ?: return false
		if (isSwitchingMember) return true

		isSwitchingMember = true
		updateChapterButtons()
		activity.lifecycleScope.launch {
			try {
				val target = withContext(Dispatchers.Default) {
					resolveTarget(targetMangaId, delta)
				}
				openTarget(target)
			} catch (cancelled: CancellationException) {
				throw cancelled
			} catch (_: Throwable) {
				isSwitchingMember = false
				updateChapterButtons()
				Toast.makeText(
					activity,
					R.string.library_group_navigation_error,
					Toast.LENGTH_SHORT,
				).show()
			}
		}
		return true
	}

	private fun bindChapterButtons() {
		bindChapterButton(R.id.button_prev, -1)
		bindChapterButton(R.id.button_next, 1)
	}

	private fun bindChapterButton(id: Int, delta: Int) {
		activity.findViewById<View>(id)?.setOnClickListener { view ->
			view.hapticFeedback(HapticEffect.LIGHT_CLICK)
			if (!switchChapterBy(delta)) {
				activity.switchChapterBy(delta)
			}
		}
	}

	private fun observeNavigation() {
		val targetGroupId = groupId ?: return
		activity.lifecycleScope.launch {
			activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
				combine(
					entryPoint.libraryGroupsRepository.observeGroups(),
					viewModel.uiState,
				) { groups, uiState ->
					val currentMangaId = viewModel.getMangaOrNull()?.id
					val group = groups.firstOrNull { it.id == targetGroupId }
					val index = if (group != null && currentMangaId != null) {
						group.members.indexOfFirst { it.mangaId == currentMangaId }
					} else {
						-1
					}
					NavigationSnapshot(
						uiState = uiState,
						previousMangaId = if (index > 0) group?.members?.getOrNull(index - 1)?.mangaId else null,
						nextMangaId = if (index >= 0) group?.members?.getOrNull(index + 1)?.mangaId else null,
					)
				}.collect { state ->
					snapshot = state
					updateChapterButtons()
				}
			}
		}
	}

	private fun updateChapterButtons() {
		val current = snapshot
		val uiState = current.uiState
		val nextEnabled = !isSwitchingMember && uiState != null &&
			(uiState.hasNextChapter() || current.nextMangaId != null)
		val previousEnabled = !isSwitchingMember && uiState != null &&
			(uiState.hasPreviousChapter() || current.previousMangaId != null)

		activity.findViewById<View>(R.id.button_next)?.let { button ->
			button.post { button.isEnabled = nextEnabled }
		}
		activity.findViewById<View>(R.id.button_prev)?.let { button ->
			button.post { button.isEnabled = previousEnabled }
		}
	}

	private suspend fun resolveTarget(mangaId: Long, delta: Int): ResolvedTarget {
		val mangaIntent = MangaIntent(
			SavedStateHandle(mapOf(AppRouter.KEY_ID to mangaId)),
		)
		val details = entryPoint.detailsLoadUseCase(mangaIntent, force = false)
			.first { it.isLoaded }
		val chapter = if (delta > 0) {
			details.allChapters.firstOrNull()
		} else {
			details.allChapters.lastOrNull()
		} ?: error("Adjacent group manga has no chapters")
		return ResolvedTarget(details.toManga(), chapter)
	}

	private fun openTarget(target: ResolvedTarget) {
		val targetGroupId = groupId ?: return
		viewModel.saveCurrentState()
		val builder = ReaderIntent.Builder(activity)
			.manga(target.manga)
			.state(ReaderState(target.chapter.id, page = 0, scroll = 0))
			.libraryGroup(targetGroupId)
		viewModel.isIncognitoMode.value?.let { builder.incognito(it) }
		if (viewModel.isPeekMode.value) builder.peek()

		activity.startActivity(builder.build().intent)
		activity.finish()
	}

	private data class NavigationSnapshot(
		val uiState: ReaderUiState? = null,
		val previousMangaId: Long? = null,
		val nextMangaId: Long? = null,
	)

	private data class ResolvedTarget(
		val manga: Manga,
		val chapter: MangaChapter,
	)
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface LibraryGroupReaderNavigationEntryPoint {
	val libraryGroupsRepository: LibraryGroupsRepository
	val detailsLoadUseCase: DetailsLoadUseCase
}
