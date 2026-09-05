package org.koitharu.kotatsu.explore.ui

import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import androidx.core.view.MenuProvider
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.AppRouter

class ExploreMenuProvider(
	private val router: AppRouter,
	private val viewModel: ExploreViewModel,
	private val onSourceFilterClick: () -> Unit,
) : MenuProvider {

	override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
		menuInflater.inflate(R.menu.opt_explore, menu)
		menu.findItem(R.id.action_show_nsfw_sources)?.isChecked = viewModel.isNsfwVisible.value
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
}
