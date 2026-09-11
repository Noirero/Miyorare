package org.koitharu.kotatsu.remotelist.ui

import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.appcompat.view.ActionMode
import androidx.core.view.MenuProvider
import androidx.fragment.app.viewModels
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.drop
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.NovelSourceCapability
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.model.isNovelSource
import org.koitharu.kotatsu.core.model.supportsNovelCapability
import org.koitharu.kotatsu.core.model.unwrap
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.ui.list.ListSelectionController
import org.koitharu.kotatsu.core.ui.util.MenuInvalidator
import org.koitharu.kotatsu.core.util.ext.addMenuProvider
import org.koitharu.kotatsu.core.util.ext.getCauseUrl
import org.koitharu.kotatsu.core.util.ext.isHttpUrl
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.core.util.ext.withArgs
import org.koitharu.kotatsu.databinding.FragmentListBinding
import org.koitharu.kotatsu.filter.ui.FilterCoordinator
import org.koitharu.kotatsu.list.ui.MangaListFragment
import org.koitharu.kotatsu.lnreader.model.LnMangaSource
import org.koitharu.kotatsu.mihon.model.MihonMangaSource
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.search.domain.SearchKind

@AndroidEntryPoint
class RemoteListFragment : MangaListFragment(), FilterCoordinator.Owner {

    override val viewModel by viewModels<RemoteListViewModel>()

    override val filterCoordinator: FilterCoordinator
        get() = viewModel.filterCoordinator

    private val canUseSourceFilters: Boolean
        get() = !viewModel.source.isNovelSource ||
            viewModel.source.supportsNovelCapability(NovelSourceCapability.FILTERS)

    /**
     * The source home page is independent from a successful catalogue request, so keep it available
     * when the list itself cannot connect. Mihon extensions expose it through HttpSource.baseUrl;
     * LNReader plugins expose the equivalent site field.
     */
    private val sourceWebViewUrl: String?
        get() = when (val source = viewModel.source.unwrap()) {
            is MihonMangaSource -> (source.catalogueSource as? HttpSource)?.baseUrl
            is LnMangaSource -> source.plugin.site
            else -> null
        }?.trim()?.takeIf { it.isHttpUrl() }

    override fun onViewBindingCreated(binding: FragmentListBinding, savedInstanceState: Bundle?) {
        super.onViewBindingCreated(binding, savedInstanceState)
        addMenuProvider(RemoteListMenuProvider())
        addMenuProvider(MangaSearchMenuProvider(filterCoordinator, viewModel, activity))
        viewModel.isRandomLoading.observe(viewLifecycleOwner, MenuInvalidator(requireActivity()))
        viewModel.onOpenManga.observeEvent(viewLifecycleOwner) { router.openDetails(it) }
        viewModel.onBrokenSortFallback.observeEvent(viewLifecycleOwner) { showBrokenSortWarning() }
        filterCoordinator.observe().distinctUntilChangedBy { it.listFilter.isEmpty() }
            .drop(1)
            .observe(viewLifecycleOwner) {
                activity?.invalidateMenu()
            }
    }

    override fun onScrolledToEnd() {
        viewModel.loadNextPage()
    }

    override fun onCreateActionMode(
        controller: ListSelectionController,
        menuInflater: MenuInflater,
        menu: Menu
    ): Boolean {
        menuInflater.inflate(R.menu.mode_remote, menu)
        return super.onCreateActionMode(controller, menuInflater, menu)
    }

    override fun onActionItemClicked(
        controller: ListSelectionController,
        mode: ActionMode?,
        item: MenuItem,
    ): Boolean {
        if (item.itemId == R.id.action_favourite) {
            val itemsSnapshot = selectedItems
            if (itemsSnapshot.isEmpty()) return false
            // Keep the selection active while duplicate/category checks are opening. The generic
            // list handler finishes ActionMode immediately, which makes a large batch look as if it
            // was deselected while the next dialog is still doing its database work.
            router.showFavoriteDialog(itemsSnapshot)
            return true
        }
        return super.onActionItemClicked(controller, mode, item)
    }

    override fun onFilterClick(view: View?) {
        if (canUseSourceFilters) router.showFilterSheet()
    }

    override fun onEmptyActionClick() {
        if (filterCoordinator.isFilterApplied) {
            filterCoordinator.reset()
        } else {
            openInBrowser(sourceWebViewUrl)
        }
    }

    override fun onFooterButtonClick() {
        val filter = filterCoordinator.snapshot().listFilter
        when {
            !filter.query.isNullOrEmpty() -> router.openSearch(filter.query.orEmpty(), SearchKind.SIMPLE)
            !filter.author.isNullOrEmpty() -> router.openSearch(filter.author.orEmpty(), SearchKind.AUTHOR)
            filter.tags.size == 1 -> router.openSearch(filter.tags.singleOrNull()?.title.orEmpty(), SearchKind.TAG)
        }
    }

    override fun onSecondaryErrorActionClick(error: Throwable) {
        openInBrowser(error.getCauseUrl() ?: sourceWebViewUrl)
    }

    private fun openInBrowser(url: String?) {
        if (url?.isHttpUrl() == true) {
            router.openBrowser(
                url = url,
                source = viewModel.source,
                title = viewModel.source.getTitle(requireContext()),
            )
        } else {
            Snackbar.make(requireViewBinding().recyclerView, R.string.operation_not_supported, Snackbar.LENGTH_SHORT)
                .show()
        }
    }

    private fun showBrokenSortWarning() {
        Snackbar.make(
            viewBinding?.recyclerView ?: return,
            R.string.source_sort_broken_warning,
            Snackbar.LENGTH_LONG,
        ).show()
    }

    private inner class RemoteListMenuProvider : MenuProvider {

        override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
            menuInflater.inflate(R.menu.opt_list_remote, menu)
            if (sourceWebViewUrl != null && menu.findItem(R.id.action_browser) == null) {
                menu.add(Menu.NONE, R.id.action_browser, 40, R.string.open_in_webview)
                    .setIcon(R.drawable.ic_open_external)
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            }
        }

        override fun onMenuItemSelected(menuItem: MenuItem): Boolean = when (menuItem.itemId) {
            R.id.action_browser -> {
                openInBrowser(sourceWebViewUrl)
                true
            }

            R.id.action_source_settings -> {
                router.openSourceSettings(viewModel.source)
                true
            }

            R.id.action_random -> {
                viewModel.openRandom()
                true
            }

            R.id.action_filter -> {
                onFilterClick(null)
                true
            }

            R.id.action_filter_reset -> {
                filterCoordinator.reset()
                true
            }

            else -> false
        }

        override fun onPrepareMenu(menu: Menu) {
            super.onPrepareMenu(menu)
            menu.findItem(R.id.action_random)?.isEnabled = !viewModel.isRandomLoading.value
            menu.findItem(R.id.action_filter)?.isVisible = canUseSourceFilters
            menu.findItem(R.id.action_browser)?.isVisible = sourceWebViewUrl != null
            // Keep Reset available for a legacy/restored filter even when the current Novel source
            // does not advertise dynamic filters. This lets the user always recover to a valid state.
            menu.findItem(R.id.action_filter_reset)?.isVisible = filterCoordinator.isFilterApplied
        }
    }

    companion object {

        const val ARG_SOURCE = "provider"

        fun newInstance(source: MangaSource) = RemoteListFragment().withArgs(1) {
            putString(ARG_SOURCE, source.name)
        }
    }
}
