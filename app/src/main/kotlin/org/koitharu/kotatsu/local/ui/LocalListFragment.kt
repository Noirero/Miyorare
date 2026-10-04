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
import org.koitharu.kotatsu.remotelist.ui.MangaSearchMenuProvider
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
        addMenuProvider(LocalListMenuProvider(this, { router.showImportDialog() }, viewModel::onRefresh,
            ::addFolder, ::showFilters, viewModel::requestExclusions))
        addMenuProvider(MangaSearchMenuProvider(filterCoordinator, viewModel, activity))
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
    override fun onScrolledToEnd() = Unit

    override fun onListHeaderClick(item: ListHeader, view: View) {
        when (val action = item.payload) {
            LocalLibraryAction.ToggleFolders -> viewModel.toggleFolders()
            LocalLibraryAction.AddFolder -> addFolder()
            LocalLibraryAction.Filters -> showFilters()
            LocalLibraryAction.Restore -> viewModel.requestExclusions()
            LocalLibraryAction.Acknowledge -> viewModel.acknowledgeDiscoveries()
            is LocalLibraryAction.Folder -> showFolderActions(action.root)
            is LocalLibraryAction.Diagnosis -> showDiagnosis(action.issue)
            is LocalLibraryAction.Open -> router.openMangaDetails(action.manga, view)
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
        menu.findItem(R.id.action_share)?.isVisible = false // SAF documents are not java.io.File links.
        return super.onCreateActionMode(controller, menuInflater, menu)
    }
    override fun onActionItemClicked(controller: ListSelectionController, mode: ActionMode?, item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_remove -> { showDeletionChoices(selectedItemsIds, mode); true }
        R.id.action_smart_local_info -> {
            val book = viewModel.library.state.value.books.firstOrNull { it.id in selectedItemsIds }
            if (book != null) showInformation(book)
            else selectedItems.singleOrNull()?.let { router.showLocalInfoDialog(it) }
            true
        }
        else -> super.onActionItemClicked(controller, mode, item)
    }

    private fun addFolder() {
        if (!folderPicker.tryLaunch(null)) Snackbar.make(requireView(), R.string.operation_not_supported, Snackbar.LENGTH_LONG).show()
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
        MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.delete_manga)
            .setItems(arrayOf(getString(R.string.smart_local_hide), getString(R.string.smart_local_delete_device))) { _, index ->
                if (index == 0) { viewModel.delete(ids, false); mode?.finish() }
                else MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.smart_local_delete_device)
                    .setMessage(R.string.smart_local_delete_device_message)
                    .setPositiveButton(R.string.delete) { _, _ -> viewModel.delete(ids, true); mode?.finish() }
                    .setNegativeButton(android.R.string.cancel, null).show()
            }.setNegativeButton(android.R.string.cancel, null).show()
    }
    private fun showFilters() {
        val options = arrayOf(getString(R.string.smart_local_reading_filter), getString(R.string.sort_order),
            getString(R.string.smart_local_extensions), getString(R.string.smart_local_restore))
        MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.filter).setItems(options) { _, index ->
            when (index) {
                0 -> MaterialAlertDialogBuilder(requireContext()).setTitle(options[0])
                    .setSingleChoiceItems(resources.getStringArray(R.array.smart_local_reading_filters), viewModel.library.readingFilter.ordinal) { dialog, choice ->
                        viewModel.updateOptions(viewModel.library.showExtensions, LocalReadingFilter.entries[choice], viewModel.library.sort); dialog.dismiss()
                    }.setNegativeButton(android.R.string.cancel, null).show()
                1 -> MaterialAlertDialogBuilder(requireContext()).setTitle(options[1])
                    .setSingleChoiceItems(resources.getStringArray(R.array.smart_local_sorts), viewModel.library.sort.ordinal) { dialog, choice ->
                        viewModel.updateOptions(viewModel.library.showExtensions, viewModel.library.readingFilter, LocalLibrarySort.entries[choice]); dialog.dismiss()
                    }.setNegativeButton(android.R.string.cancel, null).show()
                2 -> MaterialAlertDialogBuilder(requireContext()).setTitle(options[2])
                    .setSingleChoiceItems(arrayOf(getString(R.string.smart_local_extension_off), getString(R.string.smart_local_extension_on)),
                        if (viewModel.library.showExtensions) 1 else 0) { dialog, choice ->
                        viewModel.updateOptions(choice == 1, viewModel.library.readingFilter, viewModel.library.sort); dialog.dismiss()
                    }.setNegativeButton(android.R.string.cancel, null).show()
                3 -> viewModel.requestExclusions()
            }
        }.setNegativeButton(android.R.string.cancel, null).show()
    }
    private fun diagnosisLabel(issue: LocalDiagnosis): String {
        val status = when (issue.reason) {
            "review" -> R.string.smart_local_needs_review
            "unavailable" -> R.string.smart_local_unavailable
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
    private fun showInformation(book: LocalBook) {
        MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.smart_local_information)
            .setMessage(getString(R.string.smart_local_information_message, book.node.uri, book.chapters.size,
                android.text.format.Formatter.formatFileSize(requireContext(), book.size),
                java.text.DateFormat.getDateTimeInstance().format(java.util.Date(book.scannedAt)), book.ignored))
            .setPositiveButton(android.R.string.ok, null).show()
    }
}
