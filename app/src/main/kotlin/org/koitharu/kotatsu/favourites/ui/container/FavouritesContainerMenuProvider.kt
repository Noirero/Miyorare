package org.koitharu.kotatsu.favourites.ui.container

import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import androidx.core.view.MenuProvider
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.AppRouter

class FavouritesContainerMenuProvider(
	private val router: AppRouter,
	private val isAllFavouritesSelected: () -> Boolean,
	private val totalTitle: () -> String,
	private val onGoToTop: () -> Unit,
	private val onGoToBottom: () -> Unit,
) : MenuProvider {

	override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
		menuInflater.inflate(R.menu.opt_favourites_container, menu)
	}

	override fun onPrepareMenu(menu: Menu) {
		val visible = isAllFavouritesSelected()
		menu.findItem(R.id.action_favourites_total)?.apply {
			isVisible = visible
			title = totalTitle()
		}
		menu.findItem(R.id.action_favourites_to_top)?.isVisible = visible
		menu.findItem(R.id.action_favourites_to_bottom)?.isVisible = visible
	}

	override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
		when (menuItem.itemId) {
			R.id.action_favourites_to_top -> onGoToTop()
			R.id.action_favourites_to_bottom -> onGoToBottom()
			R.id.action_manage -> {
				router.openFavoriteCategories()
			}

			else -> return false
		}
		return true
	}
}
