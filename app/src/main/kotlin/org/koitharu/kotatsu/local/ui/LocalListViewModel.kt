package org.koitharu.kotatsu.local.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.combine
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.ListMode
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.filter.ui.FilterCoordinator
import org.koitharu.kotatsu.list.domain.MangaListMapper
import org.koitharu.kotatsu.list.ui.MangaListViewModel
import org.koitharu.kotatsu.list.ui.model.EmptyState
import org.koitharu.kotatsu.list.ui.model.ListHeader
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.MangaCompactListModel
import org.koitharu.kotatsu.list.ui.model.MangaDetailedListModel
import org.koitharu.kotatsu.list.ui.model.MangaGridModel
import org.koitharu.kotatsu.list.ui.model.LoadingState
import org.koitharu.kotatsu.list.ui.model.SmartLocalPanelModel
import org.koitharu.kotatsu.list.ui.model.SmartLocalResumeModel
import org.koitharu.kotatsu.list.ui.model.SmartLocalCollectionHeaderModel
import org.koitharu.kotatsu.core.nav.ReaderIntent
import org.koitharu.kotatsu.reader.ui.ReaderState
import org.koitharu.kotatsu.local.data.LocalStorageChanges
import org.koitharu.kotatsu.local.domain.model.LocalManga
import org.koitharu.kotatsu.local.library.*
import org.koitharu.kotatsu.parsers.model.Manga
import javax.inject.Inject

sealed interface LocalLibraryAction {
	data object ContinueAll : LocalLibraryAction
	data object Restore : LocalLibraryAction
	data class Acknowledge(val ids: Set<Long>) : LocalLibraryAction
	data class Diagnosis(val issue: LocalDiagnosis) : LocalLibraryAction
}

@HiltViewModel
class LocalListViewModel @Inject constructor(
	private val savedStateHandle: SavedStateHandle,
	override val filterCoordinator: FilterCoordinator,
	private val settings: AppSettings,
	mangaDataRepository: MangaDataRepository,
	@LocalStorageChanges localStorageChanges: SharedFlow<LocalManga?>,
	private val mangaListMapper: MangaListMapper,
	val library: SmartLocalLibrary,
	private val db: MangaDatabase,
	@ApplicationContext private val context: Context,
) : MangaListViewModel(settings, mangaDataRepository, localStorageChanges), FilterCoordinator.Owner {
	override val content = MutableStateFlow<List<ListModel>>(listOf(LoadingState))
	val onMangaRemoved = MutableEventFlow<Unit>()
	val exclusions = MutableEventFlow<Map<String, String>>()
	val scanCompleted = MutableEventFlow<Boolean>()
	private val showAllReading = MutableStateFlow(savedStateHandle["all_reading"] ?: false)
	private val revision = MutableStateFlow(0)
	private val localQuery = MutableStateFlow(savedStateHandle["local_query"] ?: "")
	private val contentType = MutableStateFlow(restoreLocalContentSelection(
		hasSavedValue = savedStateHandle.contains("content_type"),
		savedValue = savedStateHandle["content_type"], persisted = library.selectedContentType,
	))

	private var refreshJob: Job? = null
	private var folderJob: Job? = null

	init {
		launchJob(Dispatchers.IO) {
			contentType.collect { type -> library.setContentType(type) }
		}
		launchLoadingJob(Dispatchers.IO) {
			library.initialize()
			if (library.state.value.roots.isNotEmpty() && library.state.value.books.isEmpty()) library.scan()
		}
		launchJob(Dispatchers.Default) {
			combine(library.state, observeListModeWithTriggers(), localQuery, contentType, revision) { snapshot, mode, query, type, _ ->
				CollectionState(snapshot, mode, query, type)
			}.collect { state ->
				content.value = buildContent(state.snapshot, state.mode, state.query, state.type)
			}
		}
		launchJob(Dispatchers.Default) {
			db.getHistoryDao().observeAll().collect { revision.value++ }
		}
	}

	override fun onRefresh() {
		if (refreshJob?.isActive == true || folderJob?.isActive == true) return
		refreshJob = launchLoadingJob(Dispatchers.IO) {
			library.scan()
			revision.value++
			scanCompleted.call(library.state.value.diagnoses.isEmpty())
		}
	}

	override fun onRetry() = onRefresh()
	fun setLocalQuery(query: String) {
		localQuery.value = query
		savedStateHandle["local_query"] = query
	}
	fun setContentType(type: LocalContentType?) {
		contentType.value = type
		savedStateHandle["content_type"] = type?.name
	}
	fun showAllContinueReading() {
		showAllReading.value = !showAllReading.value
		savedStateHandle["all_reading"] = showAllReading.value
		revision.value++
	}
	fun setLocalListMode(mode: ListMode) { settings.listMode = mode }
	fun resume(manga: Manga) {
		launchJob(Dispatchers.IO) {
			val history = db.getHistoryDao().find(manga.id) ?: return@launchJob
			val book = library.book(manga.id) ?: return@launchJob
			// A removed chapter must go through Reader's existing recovery, never a guessed file route.
			val state = history.takeIf { h -> book.chapters.any { it.id == h.chapterId } }
				?.let { ReaderState(it.chapterId, it.page, it.scroll.toInt()) }
			resumeIntent.call(ReaderIntent.Builder(context).manga(manga).state(state).build())
		}
	}
	val resumeIntent = MutableEventFlow<ReaderIntent>()
	fun addFolder(uri: Uri) {
		if (folderJob?.isActive == true || refreshJob?.isActive == true) return
		folderJob = launchLoadingJob(Dispatchers.IO) { library.addRoot(uri); scanCompleted.call(library.state.value.diagnoses.isEmpty()) }
	}
	fun removeFolder(uri: String) {
		if (folderJob?.isActive == true || refreshJob?.isActive == true) return
		folderJob = launchLoadingJob(Dispatchers.IO) { library.removeRoot(uri) }
	}
	fun confirmFolder(issue: LocalDiagnosis, node: LocalTreeScanner.Node) {
		launchLoadingJob(Dispatchers.IO) { library.confirmManga(issue.rootUri, node) }
	}
	fun updateOptions(extensions: Boolean, filter: LocalReadingFilter, order: LocalLibrarySort) {
		launchLoadingJob(Dispatchers.IO) { library.setDisplayOptions(extensions, filter, order); revision.value++ }
	}
	fun requestExclusions() { launchJob(Dispatchers.IO) { exclusions.call(library.excluded()) } }
	fun restore(key: String) { launchLoadingJob(Dispatchers.IO) { library.restore(key) } }
	fun acknowledgeDiscoveries(ids: Set<Long>) { launchJob(Dispatchers.IO) { library.acknowledgeDiscoveries(ids) } }

	fun delete(ids: Set<Long>, fromDevice: Boolean) {
		launchLoadingJob(Dispatchers.IO) {
			val managed = library.state.value.books.filter { it.id in ids }.mapTo(HashSet()) { it.id }
			if (fromDevice) library.deleteFromDevice(managed) else library.hide(managed)
			revision.value++
			onMangaRemoved.call(Unit)
		}
	}

	private suspend fun buildContent(snapshot: LocalLibrarySnapshot, mode: ListMode, query: String?, type: LocalContentType?): List<ListModel> {
		val ids = snapshot.books.map { it.id }
		val histories = ids.chunked(500).flatMap { db.getHistoryDao().findByIds(it) }.associateBy { it.mangaId }
		val result = ArrayList<ListModel>()
		result += SmartLocalPanelModel(
			folderCount = snapshot.roots.size,
			titleCount = snapshot.books.size,
			chapterCount = snapshot.books.sumOf { it.chapters.size },
			query = query.orEmpty(),
			contentType = type,
			sort = library.sort,
			readingFilter = library.readingFilter,
		)
		if (snapshot.excludedCount > 0) result += ListHeader(context.resources.getQuantityString(R.plurals.smart_local_hidden_titles, snapshot.excludedCount, snapshot.excludedCount),
			R.string.smart_local_restore, LocalLibraryAction.Restore)
		if (snapshot.diagnoses.isNotEmpty()) result += ListHeader(context.resources.getQuantityString(R.plurals.smart_local_attention_items, snapshot.diagnoses.size, snapshot.diagnoses.size),
			R.string.smart_local_inspect, snapshot.diagnoses)
		if (query.isNullOrBlank()) {
			val discovered = snapshot.books.filter { (type == null || it.contentType == type) && it.newChapters > 0 }
			val count = discovered.sumOf { it.newChapters }
			if (count > 0) result += ListHeader(context.resources.getQuantityString(R.plurals.smart_local_discovered_chapters, count, count),
				R.string.smart_local_mark_seen, LocalLibraryAction.Acknowledge(discovered.mapTo(HashSet()) { it.id }), buttonStyle = ListHeader.ButtonStyle.NOTICE)
			val reading = snapshot.books.filter { book ->
				(type == null || book.contentType == type) && histories[book.id]?.let { !book.isCompleted(it) } == true
			}.sortedByDescending { histories[it.id]?.updatedAt }
			if (reading.isNotEmpty()) {
				result += ListHeader(R.string.smart_local_continue,
					if (reading.size > 3) (if (showAllReading.value) R.string.smart_local_show_less else R.string.smart_local_see_all) else 0,
					LocalLibraryAction.ContinueAll)
				for (book in if (showAllReading.value) reading else reading.take(3)) {
					val history = histories.getValue(book.id)
					val chapter = book.chapters.firstOrNull { it.id == history.chapterId }
					val label = chapter?.metadataTitle ?: chapter?.node?.name?.let {
						LocalTreeScanner.displayName(it, library.showExtensions, chapter.node.directory)
					}.orEmpty()
					val progress = (history.percent.coerceIn(0f, 1f) * 100).toInt()
					val subtitle = if (chapter == null) context.getString(R.string.chapter_is_missing)
						else if (book.isNovel) context.getString(R.string.smart_local_resume_novel, label, progress)
						else context.getString(R.string.smart_local_resume_manga, label, history.page + 1)
					result += SmartLocalResumeModel(book.toManga(library.showExtensions), subtitle, progress)
				}
			}
		}
		result += SmartLocalCollectionHeaderModel(mode)
		val manga = library.list(query, type).skipNsfwIfNeeded()
		val models = mangaListMapper.toListModelList(manga, mode, MangaListMapper.NO_SAVED)
		val booksById = snapshot.books.associateBy { it.id }
		for (model in models) {
			val book = booksById[model.id] ?: continue
			val history = histories[model.id]
			val unread = if (history == null) book.chapters.size else {
				val index = book.chapters.indexOfFirst { it.id == history.chapterId }
				if (index < 0) book.chapters.size else (book.chapters.size - index - 1).coerceAtLeast(0)
			}
			val status = context.getString(R.string.smart_local_book_counts_localized,
				context.resources.getQuantityString(R.plurals.smart_local_chapters, book.chapters.size, book.chapters.size),
				context.resources.getQuantityString(R.plurals.smart_local_unread_chapters, unread, unread),
			)
			result += when (model) {
				is MangaCompactListModel -> model.copy(subtitle = status, counter = book.chapters.size, isSaved = true)
				is MangaDetailedListModel -> model.copy(subtitle = status, counter = book.chapters.size, isSaved = true)
				is MangaGridModel -> model.copy(counter = book.chapters.size, isSaved = true)
				else -> model
			}
		}
		if (manga.isEmpty() && snapshot.initialized) {
			val reason = localCollectionEmptyReason(snapshot.roots.size,
				snapshot.books.mapTo(HashSet()) { it.contentType }, snapshot.diagnoses.isNotEmpty(), query, type)
			val message = when (reason) {
				LocalCollectionEmptyReason.NO_FOLDERS -> R.string.smart_local_empty
				LocalCollectionEmptyReason.ACCESS -> R.string.smart_local_access_empty
				LocalCollectionEmptyReason.SEARCH -> R.string.smart_local_search_empty
				LocalCollectionEmptyReason.MANGA -> R.string.smart_local_manga_empty
				LocalCollectionEmptyReason.NOVEL -> R.string.smart_local_novel_empty
				LocalCollectionEmptyReason.NO_CONTENT -> R.string.smart_local_content_empty
				LocalCollectionEmptyReason.FILTER -> R.string.smart_local_filter_empty
			}

			result += EmptyState(R.drawable.ic_empty_local, R.string.smart_local_collection, message,
				if (snapshot.roots.isEmpty()) R.string.smart_local_add_folder else 0)
		}
		return result
	}

	private data class CollectionState(
		val snapshot: LocalLibrarySnapshot,
		val mode: ListMode,
		val query: String,
		val type: LocalContentType?,
	)
}
