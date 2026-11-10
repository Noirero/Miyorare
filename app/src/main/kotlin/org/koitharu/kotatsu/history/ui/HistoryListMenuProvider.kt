package org.koitharu.kotatsu.history.ui

import android.content.Context
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import androidx.core.view.MenuProvider
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.ui.dialog.showSingleChoiceDialog
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

private const val HISTORY_AB_MENU_ID = 0x6A420001 // Debug-only, not a resource ID.

class HistoryListMenuProvider(
	private val context: Context,
	private val router: AppRouter,
	private val viewModel: HistoryListViewModel,
	private val onToggleHistoryOverlayProbe: (() -> Boolean)? = null,
	private val isHistoryOverlayBypassed: (() -> Boolean)? = null,
) : MenuProvider {

	override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
		menuInflater.inflate(R.menu.opt_history, menu)
		if (BuildConfig.DEBUG && onToggleHistoryOverlayProbe != null) {
			menu.add(Menu.NONE, HISTORY_AB_MENU_ID, Menu.NONE, probeLabel())
				.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
		}
	}

	override fun onPrepareMenu(menu: Menu) {
		super.onPrepareMenu(menu)
		menu.findItem(R.id.action_stats)?.isVisible = viewModel.isStatsEnabled.value
		menu.findItem(HISTORY_AB_MENU_ID)?.title = probeLabel()
	}

	override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
		return when (menuItem.itemId) {
			HISTORY_AB_MENU_ID -> {
				if (!BuildConfig.DEBUG) return false
				onToggleHistoryOverlayProbe?.invoke() ?: return false
				menuItem.title = probeLabel()
				true
			}
			R.id.action_clear_history -> {
				showClearHistoryDialog()
				true
			}

			R.id.action_stats -> {
				router.openStatistic()
				true
			}

			else -> false
		}
	}

	private fun probeLabel() = if (isHistoryOverlayBypassed?.invoke() == true) {
		"Cover A/B: B (overlay off)"
	} else {
		"Cover A/B: A (normal)"
	}

	private fun showClearHistoryDialog() {
		showSingleChoiceDialog(
			context = context,
			icon = R.drawable.ic_delete_all,
			title = context.getString(R.string.clear_history),
			options = listOf(
				context.getString(R.string.last_2_hours),
				context.getString(R.string.today),
				context.getString(R.string.not_in_favorites),
				context.getString(R.string.clear_all_history),
			),
			selectedIndex = 1,
			confirmLabel = context.getString(R.string.clear),
			destructive = true,
		) { selection ->
			when (selection) {
				0 -> viewModel.clearHistory(Instant.now().minus(2, ChronoUnit.HOURS))
				1 -> viewModel.clearHistory(LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant())
				2 -> viewModel.removeNotFavorite()
				3 -> viewModel.clearHistory(null)
			}
		}
	}
}
