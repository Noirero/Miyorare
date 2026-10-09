package org.koitharu.kotatsu.local.ui

import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.appcompat.view.ActionMode
import androidx.fragment.app.viewModels
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.LocalMangaSource
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.os.OpenDocumentTreeHelper
import org.koitharu.kotatsu.core.ui.list.ListSelectionController
import org.koitharu.kotatsu.core.util.ext.addMenuProvider
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.core.util.ext.tryLaunch
import org.koitharu.kotatsu.databinding.FragmentListBinding
import org.koitharu.kotatsu.filter.ui.FilterCoordinator
import org.koitharu.kotatsu.list.ui.MangaListFragment
import org.koitharu.kotatsu.list.ui.model.ListHeader
import org.koitharu.kotatsu.local.library.*
import org.koitharu.kotatsu.remotelist.ui.RemoteListFragment

class LocalListFragment : MangaListFragment(), FilterCoordinator.Owner {
	override val viewModel by viewModels<LocalListViewModel>()
	override val filterCoordinator get() = viewModel.filterCoordinator
	private val folderPicker = OpenDocumentTreeHelper(this) { uri -> uri?.let(viewModel::addFolder) }

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		arguments = (arguments ?: Bundle()).apply { putString(RemoteListFragment.ARG_SOURCE, LocalMangaSource.name) }
	}

	override fun onViewBindingCreated(binding: FragmentListBinding, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)
		addMenuProvider(
			LocalListMenuProvider(
				onImportClick = ::addFolder,
				onRefreshClick = viewModel::onRefresh,
				onFoldersClick = ::showFolderManager,
				onFiltersClick = ::showFilters,
				onRestoreClick = viewModel::requestExclusions,
				onCacheClick = viewModel::requestCoverCacheReport,
			),
		)
		viewModel.coverCacheReport.observeEvent(viewLifecycleOwner) { showCoverCache(it) }
		viewModel.coverCacheCleared.observeEvent(viewLifecycleOwner) {
			Snackbar.make(binding.recyclerView, R.string.smart_local_cover_cache_cleared, Snackbar.LENGTH_SHORT).show()
		}
		viewModel.resumeIntent.observeEvent(viewLifecycleOwner) { router.openReader(it) }
		viewModel.scanCompleted.observeEvent(viewLifecycleOwner) { complete ->
			Snackbar.make(binding.recyclerView, if (complete) R.string.smart_local_scan_complete else R.string.smart_local_scan_attention, Snackbar.LENGTH_LONG).show()
		}
		viewModel.onMangaRemoved.observeEvent(viewLifecycleOwner) {
			Snackbar.make(binding.recyclerView, R.string.removal_completed, Snackbar.LENGTH_SHORT).show()
		}
		viewModel.exclusions.observeEvent(viewLifecycleOwner) { exclusions ->
			val entries = exclusions.entries.toList()
			MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.smart_local_restore)
				.setItems(entries.map { it.value }.toTypedArray()) { _, index -> viewModel.restore(entries[index].key) }
				.setNegativeButton(android.R.string.cancel, null).show()
		}
	}

	override fun onEmptyActionClick() = addFolder()
	override fun onFilterClick(view: View?) = showFilters()
	override fun onSmartLocalQueryChanged(query: String) = viewModel.setLocalQuery(query)
	override fun onSmartLocalFilterClick(view: View) = showFilters()
	override fun onSmartLocalTypeChanged(type: LocalContentType?) = viewModel.setContentType(type)
	override fun onSmartLocalResume(manga: org.koitharu.kotatsu.parsers.model.Manga) = viewModel.resume(manga)
	override fun onSmartLocalSortClick(view: View) = showSort()
	override fun onSmartLocalListModeChanged(mode: org.koitharu.kotatsu.core.prefs.ListMode) = viewModel.setLocalListMode(mode)
	override fun onSmartLocalManageFoldersClick(view: View) = showFolderManager()
	override fun onScrolledToEnd() = Unit

	override fun onListHeaderClick(item: ListHeader, view: View) {
		when (val action = item.payload) {
			LocalLibraryAction.ContinueAll -> viewModel.showAllContinueReading()
			LocalLibraryAction.Restore -> viewModel.requestExclusions()
			is LocalLibraryAction.Acknowledge -> viewModel.acknowledgeDiscoveries(action.ids)
			is LocalLibraryAction.Diagnosis -> showDiagnosis(action.issue)
			is List<*> -> {
				val diagnoses = action.filterIsInstance<LocalDiagnosis>()
				MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.smart_local_diagnosis)
					.setItems(diagnoses.map { diagnosisLabel(it) }.toTypedArray()) { _, i -> showDiagnosis(diagnoses[i]) }
					.setNegativeButton(android.R.string.cancel, null).show()
			}
		}
	}

	override fun onCreateActionMode(controller: ListSelectionController, menuInflater: MenuInflater, menu: Menu): Boolean {
		menuInflater.inflate(R.menu.mode_local, menu)
		menu.add(Menu.NONE, R.id.action_smart_local_info, 90, R.string.smart_local_information)
		return super.onCreateActionMode(controller, menuInflater, menu)
	}

	override fun onPrepareActionMode(controller: ListSelectionController, mode: ActionMode?, menu: Menu): Boolean {
		val result = super.onPrepareActionMode(controller, mode, menu)
		menu.findItem(R.id.action_share)?.isVisible = false
		menu.findItem(R.id.action_smart_local_info)?.isVisible = selectedItemsIds.size == 1
		return result
	}

	override fun onActionItemClicked(controller: ListSelectionController, mode: ActionMode?, item: MenuItem): Boolean = when (item.itemId) {
		R.id.action_remove -> { showDeletionChoices(selectedItemsIds, mode); true }
		R.id.action_smart_local_info -> {
			viewModel.library.state.value.books.firstOrNull { it.id in selectedItemsIds }?.let(::showInformation)
			true
		}
		else -> super.onActionItemClicked(controller, mode, item)
	}

	private fun addFolder() {
		if (!folderPicker.tryLaunch(null)) Snackbar.make(requireView(), R.string.operation_not_supported, Snackbar.LENGTH_LONG).show()
	}

	private fun showFolderManager() {
		val roots = viewModel.library.state.value.roots
		val labels = roots.map { it.name } + getString(R.string.smart_local_add_folder)
		MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.smart_local_manage_folders)
			.setItems(labels.toTypedArray()) { _, index ->
				if (index == roots.size) addFolder() else showFolderActions(roots[index])
			}.setNegativeButton(android.R.string.cancel, null).show()
	}

	private fun showFolderActions(root: LocalFolder) {
		MaterialAlertDialogBuilder(requireContext()).setTitle(root.name)
			.setItems(arrayOf(getString(R.string.rescan), getString(R.string.smart_local_detach))) { _, index ->
				if (index == 0) viewModel.onRefresh() else MaterialAlertDialogBuilder(requireContext())
					.setTitle(R.string.smart_local_detach).setMessage(R.string.smart_local_detach_message)
					.setPositiveButton(R.string.smart_local_detach) { _, _ -> viewModel.removeFolder(root.uri) }
					.setNegativeButton(android.R.string.cancel, null).show()
			}.setNegativeButton(android.R.string.cancel, null).show()
	}

	private fun showDeletionChoices(ids: Set<Long>, mode: ActionMode?) {
		showLocalLibraryDeletionDialog(requireContext(),
			{ viewModel.delete(ids, false); mode?.finish() },
			{ viewModel.delete(ids, true); mode?.finish() })
	}

	private fun showFilters() {
		val options = arrayOf(getString(R.string.smart_local_reading_filter),
			getString(R.string.smart_local_extensions), getString(R.string.smart_local_restore))
		MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.filter).setItems(options) { _, index ->
			when (index) {
				0 -> MaterialAlertDialogBuilder(requireContext()).setTitle(options[0])
					.setSingleChoiceItems(resources.getStringArray(R.array.smart_local_reading_filters), viewModel.library.readingFilter.ordinal) { dialog, choice ->
						viewModel.updateOptions(viewModel.library.showExtensions, LocalReadingFilter.entries[choice], viewModel.library.sort); dialog.dismiss()
					}.setNegativeButton(android.R.string.cancel, null).show()
				1 -> MaterialAlertDialogBuilder(requireContext()).setTitle(options[1])
					.setSingleChoiceItems(arrayOf(getString(R.string.smart_local_extension_off), getString(R.string.smart_local_extension_on)),
						if (viewModel.library.showExtensions) 1 else 0) { dialog, choice ->
						viewModel.updateOptions(choice == 1, viewModel.library.readingFilter, viewModel.library.sort); dialog.dismiss()
					}.setNegativeButton(android.R.string.cancel, null).show()
				2 -> viewModel.requestExclusions()
			}
		}.setNegativeButton(android.R.string.cancel, null).show()
	}

	private fun showSort() {
		MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.sort_order)
			.setSingleChoiceItems(resources.getStringArray(R.array.smart_local_sorts), viewModel.library.sort.ordinal) { dialog, choice ->
				viewModel.updateOptions(viewModel.library.showExtensions, viewModel.library.readingFilter, LocalLibrarySort.entries[choice])
				dialog.dismiss()
			}.setNegativeButton(android.R.string.cancel, null).show()
	}

	private fun diagnosisLabel(issue: LocalDiagnosis): String {
		val status = when (issue.reason) {
			"review" -> R.string.smart_local_needs_review
			"unavailable" -> R.string.smart_local_unavailable
			"unreadable" -> R.string.smart_local_unreadable
			else -> R.string.smart_local_unsupported
		}
		return "${issue.node?.name ?: viewModel.library.state.value.roots.firstOrNull { it.uri == issue.rootUri }?.name.orEmpty()} • ${getString(status)}"
	}

	private fun showDiagnosis(issue: LocalDiagnosis) {
		val builder = MaterialAlertDialogBuilder(requireContext()).setTitle(diagnosisLabel(issue))
			.setNegativeButton(android.R.string.cancel, null)
		if (issue.candidates.isNotEmpty()) builder.setItems(issue.candidates.map { it.name }.toTypedArray()) { _, i ->
			MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.smart_local_confirm_boundary)
				.setMessage(getString(R.string.smart_local_confirm_boundary_message, issue.candidates[i].name))
				.setPositiveButton(android.R.string.ok) { _, _ -> viewModel.confirmFolder(issue, issue.candidates[i]) }
				.setNegativeButton(android.R.string.cancel, null).show()
		} else builder.setMessage(getString(R.string.smart_local_diagnosis_message, issue.node?.uri ?: issue.rootUri))
		builder.show()
	}

	private fun showCoverCache(report: SmartLocalCoverCacheReport) {
		val storage = report.storage
		MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.smart_local_cover_cache)
			.setMessage(getString(R.string.smart_local_cover_cache_summary,
				android.text.format.Formatter.formatFileSize(requireContext(), storage.bytes), storage.entries))
			.setPositiveButton(R.string.smart_local_cover_cache_clear) { _, _ ->
				MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.smart_local_cover_cache_clear)
					.setMessage(R.string.smart_local_cover_cache_clear_message)
					.setPositiveButton(R.string.smart_local_cover_cache_clear) { _, _ -> viewModel.clearCoverCache() }
					.setNegativeButton(android.R.string.cancel, null).show()
			}.setNeutralButton(R.string.smart_local_cover_cache_diagnostics) { _, _ -> showCoverDiagnostics(report) }
			.setNegativeButton(android.R.string.cancel, null).show()
	}

	private fun showCoverDiagnostics(report: SmartLocalCoverCacheReport) {
		val text = buildString {
			appendLine("${report.storage.entries} thumbnails · ${report.storage.bytes} bytes")
			appendLine("Entry bytes median/p90/p95: ${report.storage.medianEntryBytes}/${report.storage.p90EntryBytes}/${report.storage.p95EntryBytes}")
			for ((reason, count) in report.diagnostics.counts) if (count > 0) appendLine("$reason: $count")
			for (event in report.diagnostics.recent.takeLast(12)) {
				appendLine("${event.reason} ${event.cacheKey?.take(12).orEmpty()} ${event.sourceKind.orEmpty()} candidate=${event.candidateIndex} bytes=${event.entryBytes} sourceBytes=${event.sourceBytes} ms=${event.elapsedNanos / 1_000_000}")
			}
		}
		MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.smart_local_cover_cache_diagnostics)
			.setMessage(text).setPositiveButton(android.R.string.ok, null)
			.setNeutralButton(R.string.smart_local_cover_cache_reset_measurement) { _, _ -> viewModel.resetCoverDiagnostics() }.show()
	}

	private fun showInformation(book: LocalBook) {
		MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.smart_local_information)
			.setMessage(getString(R.string.smart_local_information_message_localized, book.node.uri,
				resources.getQuantityString(R.plurals.smart_local_chapters, book.chapters.size, book.chapters.size),
				android.text.format.Formatter.formatFileSize(requireContext(), book.size),
				java.text.DateFormat.getDateTimeInstance().format(java.util.Date(book.scannedAt)),
				resources.getQuantityString(R.plurals.smart_local_ignored_files, book.ignored, book.ignored)))
			.setPositiveButton(android.R.string.ok, null).show()
	}
}

