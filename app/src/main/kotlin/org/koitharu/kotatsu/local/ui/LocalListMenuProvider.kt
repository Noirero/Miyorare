package org.koitharu.kotatsu.local.ui

import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import org.koitharu.kotatsu.R

class LocalListMenuProvider(
	private val fragment: Fragment,
	private val onImportClick: Function0<Unit>,
	private val onRefreshClick: Function0<Unit>,
	private val onFoldersClick: () -> Unit,
	private val onFiltersClick: () -> Unit,
	private val onRestoreClick: () -> Unit,
) : MenuProvider {

	override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
		menuInflater.inflate(R.menu.opt_local, menu)
	}

	override fun onPrepareMenu(menu: Menu) {
		super.onPrepareMenu(menu)
		menu.findItem(R.id.action_filter)?.isVisible = true
	}

	override fun onMenuItemSelected(menuItem: MenuItem): Boolean = when (menuItem.itemId) {
		R.id.action_import -> { onImportClick(); true }
		R.id.action_refresh -> { onRefreshClick(); true }
		R.id.action_directories -> { onFoldersClick(); true }
		R.id.action_smart_local_restore -> { onRestoreClick(); true }
		R.id.action_filter -> { onFiltersClick(); true }
		else -> false
	}
}
