package org.koitharu.kotatsu.favourites.ui.container

import android.content.Context
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import androidx.core.view.MenuProvider
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.ui.dialog.buildAlertDialog
import org.koitharu.kotatsu.favourites.domain.DOWNLOADED_FAVOURITES_CATEGORY_ID
import org.koitharu.kotatsu.favourites.domain.LOCAL_FAVOURITES_CATEGORY_ID
import org.koitharu.kotatsu.favourites.domain.PRIVATE_COMPLETED_CATEGORY_ID
import org.koitharu.kotatsu.favourites.domain.PRIVATE_IN_PROGRESS_CATEGORY_ID
import org.koitharu.kotatsu.favourites.ui.list.FavouritesListFragment.Companion.NO_ID

class FavouriteTabPopupMenuProvider(
	private val context: Context,
	private val router: AppRouter,
	private val viewModel: FavouritesContainerViewModel,
	private val categoryId: Long
) : MenuProvider {

	override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
		val menuResId = if (categoryId == NO_ID) {
			R.menu.popup_fav_tab_all
		} else {
			R.menu.popup_fav_tab
		}
		menuInflater.inflate(menuResId, menu)
		if (categoryId.isVirtualCategory()) {
			menu.findItem(R.id.action_edit)?.isVisible = false
			menu.findItem(R.id.action_delete)?.isVisible = false
		}
	}

	override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
		when (menuItem.itemId) {
			R.id.action_hide -> viewModel.hide(categoryId)
			R.id.action_edit -> if (!categoryId.isVirtualCategory()) {
				router.openFavoriteCategoryEdit(categoryId, viewModel.favouriteSpace)
			}
			R.id.action_delete -> if (!categoryId.isVirtualCategory()) confirmDelete()
			R.id.action_manage -> router.openFavoriteCategories(viewModel.favouriteSpace)
			else -> return false
		}
		return true
	}

	private fun confirmDelete() {
		buildAlertDialog(context, isCentered = true) {
			setMessage(R.string.categories_delete_confirm)
			setTitle(R.string.remove_category)
			setIcon(R.drawable.ic_delete)
			setNegativeButton(android.R.string.cancel, null)
			setPositiveButton(R.string.remove) { _, _ -> viewModel.deleteCategory(categoryId) }
		}.show()
	}

	private fun Long.isVirtualCategory(): Boolean =
		this == DOWNLOADED_FAVOURITES_CATEGORY_ID ||
			this == LOCAL_FAVOURITES_CATEGORY_ID ||
			this == PRIVATE_IN_PROGRESS_CATEGORY_ID ||
			this == PRIVATE_COMPLETED_CATEGORY_ID
}
