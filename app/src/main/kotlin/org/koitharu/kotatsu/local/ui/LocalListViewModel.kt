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
import org.koitharu.kotatsu.local.data.LocalStorageChanges
import org.koitharu.kotatsu.local.domain.model.LocalManga
import org.koitharu.kotatsu.local.library.*
import org.koitharu.kotatsu.parsers.model.Manga
import javax.inject.Inject

sealed interface LocalLibraryAction {
	data object ToggleFolders : LocalLibraryAction
	data object AddFolder : LocalLibraryAction
	data object Filters : LocalLibraryAction
	data object Restore : LocalLibraryAction
	data object Acknowledge : LocalLibraryAction
	data class Folder(val root: LocalFolder) : LocalLibraryAction
	data class Diagnosis(val issue: LocalDiagnosis) : LocalLibraryAction
	data class Open(val manga: Manga) : LocalLibraryAction
}

@HiltViewModel
class LocalListViewModel @Inject constructor(
	private val savedStateHandle: SavedStateHandle,
	override val filterCoordinator: FilterCoordinator,
	settings: AppSettings,
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
	private val revision = MutableStateFlow(0)
	private val localQuery = MutableStateFlow("")
	private var refreshJob: Job? = null
	private var folderJob: Job? = null

	init {
		launchLoadingJob(Dispatchers.IO) {
			library.initialize()
			if (library.state.value.roots.isNotEmpty() && library.state.value.books.isEmpty()) library.scan()
		}
		launchJob(Dispatchers.Default) {
			combine(library.state, observeListModeWithTriggers(), localQuery, revision) { snapshot, mode, query, _ ->
				Triple(snapshot, mode, query)
			}.collect { (snapshot, mode, query) ->
				content.value = buildContent(snapshot, mode, query)
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
		}
	}

	override fun onRetry() = onRefresh()
	fun setLocalQuery(query: String) { localQuery.value = query }
	fun toggleFolders() {
		savedStateHandle["folders_expanded"] = !foldersExpanded(library.state.value)
		revision.value++
	}
	fun addFolder(uri: Uri) {
		if (folderJob?.isActive == true || refreshJob?.isActive == true) return
		// addRoot publishes the selected root before scanning. Do not replace the already useful
		// collection UI with a full-page loading state while SAF discovery/database reconciliation runs.
		folderJob = launchJob(Dispatchers.IO) { library.addRoot(uri) }
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
	fun acknowledgeDiscoveries() { launchJob(Dispatchers.IO) { library.acknowledgeDiscoveries() } }

	fun delete(ids: Set<Long>, fromDevice: Boolean) {
		launchLoadingJob(Dispatchers.IO) {
			val managed = library.state.value.books.filter { it.id in ids }.mapTo(HashSet()) { it.id }
			if (fromDevice) library.deleteFromDevice(managed) else library.hide(managed)
			revision.value++
			onMangaRemoved.call(Unit)
		}
	}

	private fun foldersExpanded(snapshot: LocalLibrarySnapshot): Boolean =
		savedStateHandle["folders_expanded"] ?: snapshot.roots.isEmpty()

	private suspend fun buildContent(snapshot: LocalLibrarySnapshot, mode: ListMode, query: String?): List<ListModel> {
		val ids = snapshot.books.map { it.id }
		val histories = ids.chunked(500).flatMap { db.getHistoryDao().findByIds(it) }.associateBy { it.mangaId }
		val expanded = foldersExpanded(snapshot)
		val result = ArrayList<ListModel>()
		result += ListHeader(context.getString(R.string.smart_local_roots_summary, snapshot.roots.size, snapshot.books.size),
			if (expanded) R.string.smart_local_collapse else R.string.smart_local_expand, LocalLibraryAction.ToggleFolders)
		if (expanded) {
			for (root in snapshot.roots) {
				val books = snapshot.books.filter { it.rootUri == root.uri }
				result += ListHeader(context.getString(R.string.smart_local_root_counts, root.name, books.size, books.sumOf { it.chapters.size }),
					R.string.smart_local_more, LocalLibraryAction.Folder(root))
			}
			result += ListHeader(R.string.smart_local_add_folder, R.string.add, LocalLibraryAction.AddFolder)
		}
		result += SmartLocalPanelModel(
			mangaCount = snapshot.books.size,
			chapterCount = snapshot.books.sumOf { it.chapters.size },
			readingCount = snapshot.books.count { histories[it.id] != null && !it.isCompleted(histories[it.id]) },
			newCount = snapshot.books.count { it.newChapters > 0 },
			query = query.orEmpty(),
		)
		if (snapshot.excludedCount > 0) result += ListHeader(context.getString(R.string.smart_local_hidden_count, snapshot.excludedCount),
			R.string.smart_local_restore, LocalLibraryAction.Restore)
		if (snapshot.diagnoses.isNotEmpty()) result += ListHeader(context.getString(R.string.smart_local_review_count, snapshot.diagnoses.size),
			R.string.smart_local_inspect, snapshot.diagnoses)
		if (query.isNullOrBlank()) {
			val reading = snapshot.books.filter { b -> histories[b.id]?.let { it.lastReaderActivityAt > 0 && !b.isCompleted(it) } == true }
				.sortedByDescending { histories[it.id]?.lastReaderActivityAt }.take(3)
			if (reading.isNotEmpty()) {
				result += ListHeader(R.string.smart_local_continue)
				for (book in reading) {
					val manga = book.toManga(library.showExtensions)
					val model = mangaListMapper.toListModel(manga, ListMode.LIST, MangaListMapper.NO_SAVED)
					val chapter = book.chapters.firstOrNull { it.id == histories[book.id]?.chapterId }
					result += if (model is MangaCompactListModel) model.copy(showContinueReading = true,
						subtitle = chapter?.metadataTitle ?: chapter?.node?.name?.let { LocalTreeScanner.displayName(it, library.showExtensions, chapter.node.directory) }.orEmpty()) else model
				}
			}
			val discovered = snapshot.books.filter { it.newChapters > 0 }
			if (discovered.isNotEmpty()) {
				result += ListHeader(R.string.smart_local_discovered, R.string.smart_local_mark_seen, LocalLibraryAction.Acknowledge)
				for (book in discovered) result += ListHeader(context.getString(R.string.smart_local_new_chapters,
					book.toManga(library.showExtensions).title, book.newChapters), R.string.smart_local_open, LocalLibraryAction.Open(book.toManga(library.showExtensions)))
			}
		}
		result += ListHeader(R.string.smart_local_collection)
		val manga = library.list(query).skipNsfwIfNeeded()
		val models = mangaListMapper.toListModelList(manga, mode, MangaListMapper.NO_SAVED)
		val booksById = snapshot.books.associateBy { it.id }
		for (model in models) {
			val book = booksById[model.id] ?: continue
			val history = histories[model.id]
			val unread = if (history == null) book.chapters.size else {
				val index = book.chapters.indexOfFirst { it.id == history.chapterId }
				if (index < 0) book.chapters.size else (book.chapters.size - index - 1).coerceAtLeast(0)
			}
			val status = context.getString(R.string.smart_local_book_counts, book.chapters.size, unread)
			result += when (model) {
				is MangaCompactListModel -> model.copy(subtitle = status, counter = book.chapters.size, isSaved = true)
				is MangaDetailedListModel -> model.copy(subtitle = status, counter = book.chapters.size, isSaved = true)
				is MangaGridModel -> model.copy(counter = book.chapters.size, isSaved = true)
				else -> model
			}
		}
		if (manga.isEmpty()) result += EmptyState(R.drawable.ic_empty_local,
			R.string.text_local_holder_primary, R.string.smart_local_empty, R.string.smart_local_add_folder)
		return result
	}
}
