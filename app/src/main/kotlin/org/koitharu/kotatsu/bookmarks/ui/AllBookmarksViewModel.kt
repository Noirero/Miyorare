package org.koitharu.kotatsu.bookmarks.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.bookmarks.domain.Bookmark
import org.koitharu.kotatsu.bookmarks.domain.BookmarksRepository
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.ui.util.ReversibleAction
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.favourites.data.EXTRA_FAVOURITE_SPACE
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.favourites.domain.FavouritesRepository
import org.koitharu.kotatsu.list.ui.model.EmptyState
import org.koitharu.kotatsu.list.ui.model.ListHeader
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.LoadingState
import org.koitharu.kotatsu.list.ui.model.toErrorState
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.reader.ui.PageSaveHelper
import javax.inject.Inject

@HiltViewModel
class AllBookmarksViewModel @Inject constructor(
	private val repository: BookmarksRepository,
	private val favouritesRepository: FavouritesRepository,
	savedStateHandle: SavedStateHandle,
) : BaseViewModel() {

	private val favouriteSpace = FavouriteSpace.fromArgument(
		savedStateHandle[EXTRA_FAVOURITE_SPACE] ?: FavouriteSpace.NORMAL.dbValue,
	)
	val onActionDone = MutableEventFlow<ReversibleAction>()
	private val limit = MutableStateFlow(BOOKMARK_PAGE_SIZE)
	private val paginationReady = AtomicBoolean(false)
	private var loadedSourceBookmarkCount = 0

	/**
	 * Normal keeps the original global bookmark surface. When launched from the authenticated Private
	 * workspace, the same UI is scoped to actual Private membership. Membership is observed so moving
	 * a title out of Private removes it from this screen without reopening the activity.
	 */
	private val visibleMangaIds: Flow<Set<Long>?> = if (favouriteSpace == FavouriteSpace.PRIVATE) {
		favouritesRepository.observeFavouritesChanges(FavouriteSpace.PRIVATE).mapLatest {
			favouritesRepository.getMemberships(FavouriteSpace.PRIVATE)
				.mapTo(LinkedHashSet()) { membership -> membership.mangaId }
		}
	} else {
		flowOf(null)
	}

	val content: StateFlow<List<ListModel>> = combine(
		limit.flatMapLatest(repository::observeBookmarks),
		visibleMangaIds,
	) { list, visibleIds ->
		// Pagination is driven by the unfiltered source window. A 60-row global window may contain only
		// a few Private rows; using the filtered count here would incorrectly stop before later Private
		// bookmarks had a chance to enter the window.
		loadedSourceBookmarkCount = list.values.sumOf { it.size }
		if (visibleIds == null) list else list.filterKeys { manga -> manga.id in visibleIds }
	}.map { list ->
		if (list.isEmpty()) {
			listOf(
				EmptyState(
					icon = R.drawable.ic_empty_favourites,
					textPrimary = R.string.no_bookmarks_yet,
					textSecondary = R.string.no_bookmarks_summary,
					actionStringRes = 0,
				),
			)
		} else {
			mapList(list)
		}
	}
		.onEach { paginationReady.set(true) }
		.catch { e -> emit(listOf(e.toErrorState(canRetry = false))) }
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, listOf(LoadingState))

	fun removeBookmarks(ids: Set<Long>) {
		launchJob(Dispatchers.Default) {
			val handle = repository.removeBookmarks(ids)
			onActionDone.call(ReversibleAction(R.string.bookmarks_removed, handle))
		}
	}

	fun requestMoreItems() {
		if (loadedSourceBookmarkCount < limit.value || !paginationReady.compareAndSet(true, false)) return
		limit.value += BOOKMARK_PAGE_SIZE
	}

	fun savePages(pageSaveHelper: PageSaveHelper, ids: Set<Long>) {
		launchLoadingJob(Dispatchers.Default) {
			val tasks = content.value.mapNotNull {
				if (it !is Bookmark || it.pageId !in ids) return@mapNotNull null
				PageSaveHelper.Task(
					manga = it.manga,
					chapterId = it.chapterId,
					pageNumber = it.page + 1,
					page = it.toMangaPage(),
				)
			}
			val dest = pageSaveHelper.save(tasks)
			val msg = if (dest.size == 1) R.string.page_saved else R.string.pages_saved
			onActionDone.call(ReversibleAction(msg, null))
		}
	}

	private fun mapList(data: Map<Manga, List<Bookmark>>): List<ListModel> {
		val result = ArrayList<ListModel>(data.values.sumOf { it.size + 1 })
		for ((manga, bookmarks) in data) {
			result.add(ListHeader(manga.title, R.string.more, manga))
			result.addAll(bookmarks)
		}
		return result
	}

	private companion object {
		const val BOOKMARK_PAGE_SIZE = 60
	}
}
