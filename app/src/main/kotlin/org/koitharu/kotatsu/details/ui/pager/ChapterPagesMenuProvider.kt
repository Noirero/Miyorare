package org.koitharu.kotatsu.details.ui.pager

import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.widget.SearchView
import androidx.core.view.MenuProvider
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.slider.LabelFormatter
import com.google.android.material.slider.Slider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.TickVisibilityMode
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.details.ui.DetailsViewModel
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.sheet.BaseAdaptiveSheet
import org.koitharu.kotatsu.core.util.ext.setValueRounded
import org.koitharu.kotatsu.core.util.ext.setOptionalIconsVisibleCompat
import org.koitharu.kotatsu.core.util.progress.IntPercentLabelFormatter
import org.koitharu.kotatsu.details.ui.pager.ChaptersPagesSheet.Companion.TAB_BOOKMARKS
import org.koitharu.kotatsu.details.ui.pager.ChaptersPagesSheet.Companion.TAB_CHAPTERS
import org.koitharu.kotatsu.details.ui.pager.ChaptersPagesSheet.Companion.TAB_PAGES
import org.koitharu.kotatsu.reader.ui.ReaderViewModel
import org.koitharu.kotatsu.local.data.isEpub
import java.lang.ref.WeakReference

class ChapterPagesMenuProvider(
	private val sheet: BaseAdaptiveSheet<*>,
	private val pager: ViewPager2,
	private val settings: AppSettings,
	private val viewModel: ChaptersPagesViewModel,
	private val toolbarContent: View,
) : OnBackPressedCallback(false), MenuProvider, MenuItem.OnActionExpandListener,
	SearchView.OnQueryTextListener,
	Slider.OnChangeListener {

	private var expandedItemRef: WeakReference<MenuItem>? = null
	private val menuVisibilityBeforeSearch = mutableMapOf<Int, Boolean>()

	override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
		val tab = getCurrentTab()
		when (tab) {
			TAB_CHAPTERS -> {
				menuInflater.inflate(R.menu.opt_chapters, menu)
				menu.setOptionalIconsVisibleCompat(true)
				// Details owns Notes search on its main surface. Reader keeps Notes search in
				// its chapter toolbox; the Details chapter manager owns chapter-title search only.
				menu.findItem(R.id.action_search_notes)?.let { item ->
					item.isVisible = viewModel is ReaderViewModel
					if (item.isVisible) setupSearchItem(item)
				}
				menu.findItem(R.id.action_search)?.let(::setupSearchItem)
			}
			TAB_PAGES, TAB_BOOKMARKS -> {
				menuInflater.inflate(R.menu.opt_pages, menu)
				menu.findItem(R.id.action_grid_size)?.run {
					setOnActionExpandListener(this@ChapterPagesMenuProvider)
					(actionView as? Slider)?.setupPagesSizeSlider()
				}
			}
		}
	}

	override fun onPrepareMenu(menu: Menu) {
		super.onPrepareMenu(menu)
		val chapterOptions = viewModel.chapterListOptions.value
		menu.findItem(R.id.action_chapter_grouping)?.isVisible = viewModel is DetailsViewModel
		menu.findItem(R.id.action_search_notes)?.isVisible = viewModel is ReaderViewModel
		menu.findItem(R.id.action_reversed)?.isChecked = chapterOptions.descending
		menu.findItem(R.id.action_grid_view)?.let { it.isChecked = chapterOptions.grid; it.isVisible = viewModel.getMangaOrNull()?.isEpub != true }
		menu.findItem(R.id.action_downloaded)?.let { item ->
			item.isVisible = viewModel.mangaDetails.value?.local != null
			item.isChecked = chapterOptions.downloadedOnly
		}
		menu.findItem(R.id.action_merge_scanlators)?.let { item ->
			val isMerged = viewModel.isScanlatorsMerged.value
			item.isVisible = isMerged || (viewModel.mangaDetails.value?.chapters?.size ?: 0) > 1
			item.isChecked = isMerged
		}
		if (expandedItemRef?.get() != null) hideOtherMenuItems(menu, expandedItemRef?.get())
	}

	override fun onMenuItemSelected(menuItem: MenuItem): Boolean = when (menuItem.itemId) {
		R.id.action_chapter_grouping -> {
			val context = toolbarContent.context
			val modes = ChapterGroupingMode.entries
			val labels = modes.map { mode ->
				when (mode) {
					ChapterGroupingMode.AUTOMATIC -> context.getString(R.string.chapter_grouping_automatic)
					ChapterGroupingMode.CHAPTERS_25 -> context.getString(R.string.chapter_grouping_size, 25)
					ChapterGroupingMode.CHAPTERS_50 -> context.getString(R.string.chapter_grouping_size, 50)
					ChapterGroupingMode.CHAPTERS_100 -> context.getString(R.string.chapter_grouping_size, 100)
					ChapterGroupingMode.OFF -> context.getString(R.string.chapter_grouping_off)
				}
			}.toTypedArray()
			MaterialAlertDialogBuilder(context)
				.setTitle(R.string.chapter_grouping)
				.setSingleChoiceItems(labels, modes.indexOf(viewModel.chapterListOptions.value.groupingMode)) { dialog, which ->
					viewModel.setChapterGroupingMode(modes[which])
					dialog.dismiss()
				}
				.setNegativeButton(android.R.string.cancel, null)
				.show()
			true
		}
		R.id.action_reversed -> {
			viewModel.toggleChapterSortDirection()
			val descending = viewModel.chapterListOptions.value.descending
			if (viewModel is ReaderViewModel) {
				settings.isChaptersReverse = descending
			} else if (viewModel is DetailsViewModel) {
				// Details now retains the user's explicit sort direction across reopen while
				// keeping the rest of its chapter-list defaults scoped to Details.
				settings.isChaptersReverse = descending
			}
			true
		}
		R.id.action_grid_view -> if (viewModel.getMangaOrNull()?.isEpub == true) true else {
			val grid = !menuItem.isChecked
			viewModel.setChaptersGridView(grid)
			if (viewModel is ReaderViewModel) {
				settings.isChaptersGridView = grid
			}
			true
		}
		R.id.action_downloaded -> {
			viewModel.setDownloadedOnly(!menuItem.isChecked)
			true
		}
		R.id.action_merge_scanlators -> {
			viewModel.setScanlatorsMerged(!menuItem.isChecked)
			true
		}
		else -> false
	}

	override fun handleOnBackPressed() {
		expandedItemRef?.get()?.collapseActionView()
	}

	override fun onMenuItemActionExpand(item: MenuItem): Boolean {
		if (expandedItemRef?.get() != null) return false
		expandedItemRef = WeakReference(item)
		item.menuInfo
		menuVisibilityBeforeSearch.clear()
		sheet.expandAndLock()
		isEnabled = true
		if (item.itemId == R.id.action_search || item.itemId == R.id.action_search_notes) {
			toolbarContent.isGone = true
			if (item.itemId == R.id.action_search_notes) {
				if (viewModel.chapterNotesQuery.value == null) viewModel.performChapterNoteSearch("")
			} else {
				viewModel.exitChapterNoteSearch()
			}
		}
		return true
	}

	override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
		expandedItemRef = null
		isEnabled = false
		sheet.unlock()
		if (item.itemId == R.id.action_search || item.itemId == R.id.action_search_notes) {
			toolbarContent.isVisible = true
			(item.actionView as? SearchView)?.apply {
				// Detach first so clearing one search mode cannot mutate the other query.
				setOnQueryTextListener(null)
				setQuery("", false)
				setOnQueryTextListener(this@ChapterPagesMenuProvider)
			}
			if (item.itemId == R.id.action_search_notes) viewModel.exitChapterNoteSearch()
			else viewModel.performChapterSearch(null)
		}
		menuVisibilityBeforeSearch.clear()
		return true
	}

	override fun onQueryTextSubmit(query: String?): Boolean = false

	override fun onQueryTextChange(newText: String?): Boolean {
		if (expandedItemRef?.get()?.itemId == R.id.action_search_notes) {
			viewModel.performChapterNoteSearch(newText.orEmpty())
		} else {
			viewModel.performChapterSearch(newText)
		}
		return true
	}

	override fun onValueChange(slider: Slider, value: Float, fromUser: Boolean) {
		if (fromUser) settings.gridSizePages = value.toInt()
	}

	private fun setupSearchItem(item: MenuItem) {
		item.setOnActionExpandListener(this)
		(item.actionView as? SearchView)?.apply {
			setIconifiedByDefault(false)
			queryHint = item.title
			setOnQueryTextListener(this@ChapterPagesMenuProvider)
		}
	}

	private fun hideOtherMenuItems(menu: Menu, expandedItem: MenuItem?) {
		for (index in 0 until menu.size()) {
			val item = menu.getItem(index)
			if (menuVisibilityBeforeSearch.putIfAbsent(item.itemId, item.isVisible) == null && item != expandedItem) {
				item.isVisible = false
			} else if (item != expandedItem) {
				item.isVisible = false
			}
		}
	}

	private fun Slider.setupPagesSizeSlider() {
		valueFrom = 50f
		valueTo = 150f
		stepSize = 5f
		tickVisibilityMode = TickVisibilityMode.TICK_VISIBILITY_HIDDEN
		labelBehavior = LabelFormatter.LABEL_FLOATING
		setLabelFormatter(IntPercentLabelFormatter(context))
		setValueRounded(settings.gridSizePages.toFloat())
		addOnChangeListener(this@ChapterPagesMenuProvider)
	}

	private fun getCurrentTab(): Int {
		var page = pager.currentItem
		if (page > 0 && pager.adapter?.itemCount == 2) page++
		return page
	}
}
