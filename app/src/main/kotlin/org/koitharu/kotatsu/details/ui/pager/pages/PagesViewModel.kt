package org.koitharu.kotatsu.details.ui.pager.pages

import android.net.Uri
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.combine
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.list.ui.model.ErrorFooter
import org.koitharu.kotatsu.list.ui.model.LoadingFooter
import org.koitharu.kotatsu.parsers.model.MangaChapter
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.plus
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.observeAsStateFlow
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.core.util.ext.firstNotNull
import org.koitharu.kotatsu.core.util.ext.requireValue
import org.koitharu.kotatsu.details.data.MangaDetails
import org.koitharu.kotatsu.local.data.isEpub
import org.koitharu.kotatsu.list.ui.model.ListHeader
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.reader.domain.ChaptersLoader
import org.koitharu.kotatsu.reader.ui.PageSaveHelper
import org.koitharu.kotatsu.reader.ui.ReaderState
import org.koitharu.kotatsu.reader.ui.pager.ReaderPage
import javax.inject.Inject

@HiltViewModel
class PagesViewModel @Inject constructor(
	private val chaptersLoader: ChaptersLoader,
	settings: AppSettings,
	private val savedState: SavedStateHandle,
	@ApplicationContext private val context: Context,
) : BaseViewModel() {

	private var loadingJob: Job? = null
	private var loadingPrevJob: Job? = null
	private var loadingNextJob: Job? = null

	private val state = MutableStateFlow<State?>(null)
	private val visible = MutableStateFlow(false)
	private val expanded = MutableStateFlow(savedState.get<LongArray>("expanded_chapters")?.toSet().orEmpty())
	private var previousActive: Long? = null
	private var previousRetry = 0
	private var previewChapters: List<MangaChapter> = emptyList()
	private val previews = mutableMapOf<Long, List<ReaderPage>>()
	private val failures = mutableMapOf<Long, Throwable>()

	private val State.isExpandable: Boolean
		get() = org.koitharu.kotatsu.local.library.isSmartLocalUri(details.toManga().url) && !details.toManga().isEpub

	fun setVisible(value: Boolean) { visible.value = value }

	fun toggleExpanded(chapterId: Long) {
		val current = state.value ?: return
		if (!current.isExpandable || current.details.allChapters.none { it.id == chapterId }) return
		expanded.value = if (chapterId in expanded.value) expanded.value - chapterId else expanded.value + chapterId
		savedState["expanded_chapters"] = expanded.value.toLongArray()
	}

	fun retryPreviews() {
		state.value?.let { current -> updateState(current.copy(revision = current.revision + 1)) }
	}
	val thumbnails = MutableStateFlow<List<ListModel>>(emptyList())
	val isLoadingUp = MutableStateFlow(false)
	val isLoadingDown = MutableStateFlow(false)
	val onPageSaved = MutableEventFlow<Collection<Uri>>()

	val gridScale = settings.observeAsStateFlow(
		scope = viewModelScope + Dispatchers.Default,
		key = AppSettings.KEY_GRID_SIZE_PAGES,
		valueProducer = { gridSizePages / 100f },
	)

	init {
		launchJob(Dispatchers.Default) {
			combine(state, visible, expanded) { current, showing, opened -> Triple(current, showing, opened) }
				.collect { (current, showing, opened) ->
					loadingJob?.cancelAndJoin()
					if (current == null) return@collect
					loadingJob = launchLoadingJob(Dispatchers.Default) {
						if (current.isExpandable) {
							if (showing) initExpandable(current, opened) else {
								previews.clear(); failures.clear(); previousActive = null; thumbnails.value = emptyList()
							}
						} else doInit(current)
					}
				}
		}
	}

	private suspend fun initExpandable(current: State, opened: Set<Long>) {
		loadingPrevJob?.cancelAndJoin()
		loadingNextJob?.cancelAndJoin()
		val chapters = current.details.allChapters
		val active = current.readerState?.chapterId?.takeIf { id -> chapters.any { it.id == id } } ?: chapters.firstOrNull()?.id
		val valid = chapters.mapTo(HashSet()) { it.id }
		val reconciled = reconcileExpandedChapters(opened, valid, active, previousActive)
		previousActive = active
		if (reconciled != opened) {
			kotlinx.coroutines.withContext(Dispatchers.Main.immediate) {
				expanded.value = reconciled
				savedState["expanded_chapters"] = reconciled.toLongArray()
			}
			return
		}
		if (previousRetry != current.revision) { failures.clear(); previousRetry = current.revision }
		if (previewChapters != chapters) { previews.clear(); failures.clear(); previewChapters = chapters }
		previews.keys.retainAll(opened)
		failures.keys.retainAll(opened)
		chaptersLoader.init(current.details)
		updateExpandableList(current, opened)
		for (chapter in chapters) {
			if (chapter.id !in opened || chapter.id in previews || chapter.id in failures) continue
			try {
				val pages = chaptersLoader.loadPreviewPages(chapter.id)
				if (pages.isEmpty()) throw org.koitharu.kotatsu.core.exceptions.NoDataReceivedException(chapter.url)
				previews[chapter.id] = pages
			} catch (error: CancellationException) { throw error }
			catch (error: Exception) { failures[chapter.id] = error }
			updateExpandableList(current, opened)
		}
	}

	private fun updateExpandableList(current: State, opened: Set<Long>) {
		thumbnails.value = buildList {
			for (chapter in current.details.allChapters) {
				val isOpen = chapter.id in opened
				add(ListHeader(chapter,
					buttonTextRes = if (isOpen) R.string.smart_local_collapse_chapter else R.string.smart_local_expand_chapter,
					payload = chapter.id,
					badge = if (chapter.id == current.readerState?.chapterId) context.getString(R.string.smart_local_current_chapter) else null,
				))
				if (!isOpen) continue
				val pages = previews[chapter.id]
				when {
					failures[chapter.id] != null -> add(ErrorFooter(checkNotNull(failures[chapter.id])))
					pages == null -> add(LoadingFooter(chapter.id.hashCode()))
					else -> pages.forEach { page -> add(PageThumbnail(
						isCurrent = page.chapterId == current.readerState?.chapterId && page.index == current.readerState?.page,
						page = page,
					)) }
				}
			}
		}
	}

	fun updateState(newState: State?) {
		if (newState != null) {
			state.value = newState
		}
	}

	fun loadPrevChapter() {
		if (state.value?.isExpandable == true || loadingJob?.isActive == true || loadingPrevJob?.isActive == true) {
			return
		}
		loadingPrevJob = loadPrevNextChapter(isNext = false)
	}

	fun loadNextChapter() {
		if (state.value?.isExpandable == true || loadingJob?.isActive == true || loadingNextJob?.isActive == true) {
			return
		}
		loadingNextJob = loadPrevNextChapter(isNext = true)
	}

	fun savePages(
		pageSaveHelper: PageSaveHelper,
		pages: Set<ReaderPage>,
	) {
		launchLoadingJob(Dispatchers.Default) {
			val manga = state.requireValue().details.toManga()
			val tasks = pages.map {
				PageSaveHelper.Task(
					manga = manga,
					chapterId = it.chapterId,
					pageNumber = it.index + 1,
					page = it.toMangaPage(),
				)
			}
			val dest = pageSaveHelper.save(tasks)
			onPageSaved.call(dest)
		}
	}

	private suspend fun doInit(state: State) {
		chaptersLoader.init(state.details)
		val initialChapterId = state.readerState?.chapterId?.takeIf {
			chaptersLoader.peekChapter(it) != null
		} ?: state.details.allChapters.firstOrNull()?.id ?: return
		if (!chaptersLoader.hasPages(initialChapterId)) {
			var hasPages = chaptersLoader.loadSingleChapter(initialChapterId)
			while (!hasPages) {
				if (chaptersLoader.loadPrevNextChapter(state.details, initialChapterId, isNext = true)) {
					hasPages = chaptersLoader.snapshot().isNotEmpty()
				} else {
					break
				}
			}
		}
		updateList(state.readerState)
	}

	private fun loadPrevNextChapter(isNext: Boolean): Job = launchJob(Dispatchers.Default) {
		val indicator = if (isNext) isLoadingDown else isLoadingUp
		indicator.value = true
		try {
			val currentState = state.firstNotNull()
			val currentId = (if (isNext) chaptersLoader.last() else chaptersLoader.first()).chapterId
			chaptersLoader.loadPrevNextChapter(currentState.details, currentId, isNext)
			updateList(currentState.readerState)
		} finally {
			indicator.value = false
		}
	}

	private fun updateList(readerState: ReaderState?) {
		val snapshot = chaptersLoader.snapshot()
		val pages = buildList(snapshot.size + chaptersLoader.size + 2) {
			var previousChapterId = 0L
			for (page in snapshot) {
				if (page.chapterId != previousChapterId) {
					chaptersLoader.peekChapter(page.chapterId)?.let {
						add(ListHeader(it))
					}
					previousChapterId = page.chapterId
				}
				this += PageThumbnail(
					isCurrent = readerState?.let {
						page.chapterId == it.chapterId && page.index == it.page
					} == true,
					page = page,
				)
			}
		}
		thumbnails.value = pages
	}

	data class State(
		val details: MangaDetails,
		val readerState: ReaderState?,
		val branch: String?,
		val revision: Int = 0,
	)
}
