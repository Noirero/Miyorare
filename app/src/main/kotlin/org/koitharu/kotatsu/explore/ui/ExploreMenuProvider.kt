package org.koitharu.kotatsu.explore.ui

import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.widget.SearchView
import androidx.core.view.MenuProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.explore.ui.model.MangaSourceItem

class ExploreMenuProvider(
	private val router: AppRouter,
	private val viewModel: ExploreViewModel,
	private val onSourceFilterClick: () -> Unit,
) : MenuProvider {

	override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
		menuInflater.inflate(R.menu.opt_explore, menu)
		menu.findItem(R.id.action_show_nsfw_sources)?.isChecked = viewModel.isNsfwVisible.value
		(menu.findItem(R.id.action_source_search)?.actionView as? SearchView)?.let { searchView ->
			searchView.queryHint = searchView.context.getString(R.string.search_extensions)
			searchView.maxWidth = Int.MAX_VALUE
			searchView.setOnQueryTextListener(
				object : SearchView.OnQueryTextListener {
					override fun onQueryTextSubmit(query: String?): Boolean {
						return submitSourceSearch(searchView, query.orEmpty())
					}

					override fun onQueryTextChange(newText: String?): Boolean = false
				},
			)
		}
	}

	override fun onPrepareMenu(menu: Menu) {
		menu.findItem(R.id.action_show_nsfw_sources)?.isChecked = viewModel.isNsfwVisible.value
	}

	override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
		return when (menuItem.itemId) {
			R.id.action_show_nsfw_sources -> {
				val isVisible = !menuItem.isChecked
				menuItem.isChecked = isVisible
				viewModel.setNsfwVisible(isVisible)
				true
			}

			R.id.action_content_classification_reset_all -> {
				viewModel.resetContentClassifications()
				true
			}

			R.id.action_manage -> {
				router.openSourcesCatalog(isExternalOnly = true)
				true
			}

			R.id.action_source_filter -> {
				onSourceFilterClick()
				true
			}

			else -> false
		}
	}

	private fun submitSourceSearch(searchView: SearchView, rawQuery: String): Boolean {
		val query = rawQuery.trim()
		if (query.isEmpty()) return false

		val context = searchView.context
		val content = viewModel.sources.value
		val matches = (content.manga + content.novel)
			.filterIsInstance<MangaSourceItem>()
			.distinctBy { it.id }
			.filter { item -> item.source.getTitle(context).contains(query, ignoreCase = true) }
			.sortedBy { item -> item.source.getTitle(context).lowercase() }

		when (matches.size) {
			0 -> Toast.makeText(context, R.string.nothing_found, Toast.LENGTH_SHORT).show()
			1 -> {
				searchView.clearFocus()
				router.openList(matches[0].source, null, null)
			}

			else -> MaterialAlertDialogBuilder(context)
				.setTitle(R.string.search_results)
				.setItems(matches.map { it.source.getTitle(context) }.toTypedArray()) { _, which ->
					searchView.clearFocus()
					router.openList(matches[which].source, null, null)
				}
				.setNegativeButton(android.R.string.cancel, null)
				.show()
		}
		return true
	}
}
