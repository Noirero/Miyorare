package org.koitharu.kotatsu.details.ui.pager

import android.content.Context
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.widget.SearchView
import androidx.core.view.MenuProvider
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.LabelFormatter
import com.google.android.material.slider.Slider
import com.google.android.material.slider.TickVisibilityMode
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.sheet.BaseAdaptiveSheet
import org.koitharu.kotatsu.core.util.ext.setOptionalIconsVisibleCompat
import org.koitharu.kotatsu.core.util.ext.setValueRounded
import org.koitharu.kotatsu.core.util.progress.IntPercentLabelFormatter
import org.koitharu.kotatsu.details.ui.DetailsViewModel
import org.koitharu.kotatsu.details.ui.pager.ChaptersPagesSheet.Companion.TAB_BOOKMARKS
import org.koitharu.kotatsu.details.ui.pager.ChaptersPagesSheet.Companion.TAB_CHAPTERS
import org.koitharu.kotatsu.details.ui.pager.ChaptersPagesSheet.Companion.TAB_PAGES
import org.koitharu.kotatsu.local.data.isEpub
import org.koitharu.kotatsu.reader.ui.ReaderViewModel
import java.lang.ref.WeakReference

class ChapterPagesMenuProvider(
	private val sheet: BaseAdaptiveSheet<*>,
	private val pager: ViewPager2,
	private val settings: AppSettings,
	private val viewModel: ChaptersPagesViewModel,
	private val toolbarContent: View,
) : OnBackPressedCallback(false), MenuProvider, MenuItem.OnActionExpandListener,
	SearchView.OnQueryTextListener, Slider.OnChangeListener {

	private enum class SearchMode { CHAPTERS, NOTES }

	private var expandedItemRef: WeakReference<MenuItem>? = null
	private var menuRef: WeakReference<Menu>? = null
	private var searchMode: SearchMode? = null

	override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
		menuRef = WeakReference(menu)
		when (getCurrentTab()) {
			TAB_CHAPTERS -> {
				menuInflater.inflate(R.menu.opt_chapters, menu)
				menu.setOptionalIconsVisibleCompat(true)
				// Manage and Reader Toolbox share one deterministic search entry point.
				// The user chooses chapter-title or personal-note search before entering it.
				menu.findItem(R.id.action_search_notes)?.isVisible = false
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
		menu.findItem(R.id.action_search_notes)?.isVisible = false
		menu.findItem(R.id.action_reversed)?.isChecked = chapterOptions.descending
		menu.findItem(R.id.action_grid_view)?.let {
			it.isChecked = chapterOptions.grid
			it.isVisible = viewModel.getMangaOrNull()?.isEpub != true
		}
		menu.findItem(R.id.action_downloaded)?.let { item ->
			item.isVisible = viewModel.mangaDetails.value?.local != null
			item.isChecked = chapterOptions.downloadedOnly
		}
		menu.findItem(R.id.action_merge_scanlators)?.let { item ->
			val isMerged = viewModel.isScanlatorsMerged.value
			item.isVisible = isMerged || (viewModel.mangaDetails.value?.chapters?.size ?: 0) > 1
			item.isChecked = isMerged
		}
		expandedItemRef?.get()?.let { hideOtherMenuItems(menu, it) }
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
			true
		}
		R.id.action_grid_view -> if (viewModel.getMangaOrNull()?.isEpub == true) true else {
			val grid = !menuItem.isChecked
			viewModel.setChaptersGridView(grid)
			if (viewModel is ReaderViewModel) settings.isChaptersGridView = grid
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
		if (item.itemId == R.id.action_search && searchMode == null) return false
		expandedItemRef = WeakReference(item)
		menuRef?.get()?.let { hideOtherMenuItems(it, item) }
		sheet.expandAndLock()
		isEnabled = true
		if (item.itemId == R.id.action_search) {
			toolbarContent.isGone = true
			activateSearchMode(searchMode ?: SearchMode.CHAPTERS, item.actionView as? SearchView)
		}
		return true
	}

	override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
		expandedItemRef = null
		isEnabled = false
		sheet.unlock()
		if (item.itemId == R.id.action_search) {
			toolbarContent.isVisible = true
			(item.actionView as? SearchView)?.apply {
				hideSearchKeyboard()
				setOnQueryTextListener(null)
				setQuery("", false)
				clearFocus()
				setOnQueryTextListener(this@ChapterPagesMenuProvider)
			}
			viewModel.performChapterSearch(null)
			viewModel.exitChapterNoteSearch()
			searchMode = null
		}
		// Recompute normal menu visibility from source-of-truth state. Do not restore a
		// stale visibility snapshot captured before search; that caused the search icon
		// to disappear nondeterministically after Back in Manage and Reader Toolbox.
		menuRef?.get()?.let(::onPrepareMenu)
		return true
	}

	override fun onQueryTextSubmit(query: String?): Boolean = false

	override fun onQueryTextChange(newText: String?): Boolean {
		when (searchMode) {
			SearchMode.NOTES -> viewModel.performChapterNoteSearch(newText.orEmpty())
			SearchMode.CHAPTERS -> viewModel.performChapterSearch(newText)
			null -> Unit
		}
		return true
	}

	override fun onValueChange(slider: Slider, value: Float, fromUser: Boolean) {
		if (fromUser) settings.gridSizePages = value.toInt()
	}

	private fun setupSearchItem(item: MenuItem) {
		item.setOnActionExpandListener(this)
		item.setOnMenuItemClickListener {
			showSearchModeChooser(item)
			true
		}
		(item.actionView as? SearchView)?.apply {
			setIconifiedByDefault(false)
			setOnQueryTextListener(this@ChapterPagesMenuProvider)
			findViewById<View?>(androidx.appcompat.R.id.search_close_btn)?.setOnClickListener {
				// X always clears the active query and keeps the selected search mode active.
				setQuery("", false)
				focusSearchInput()
			}
			findViewById<View?>(androidx.appcompat.R.id.search_mag_icon)?.setOnClickListener {
				showSearchModeChooser(item)
			}
		}
	}

	private fun showSearchModeChooser(item: MenuItem) {
		val context = toolbarContent.context
		val modes = arrayOf(SearchMode.CHAPTERS, SearchMode.NOTES)
		val labels = arrayOf(
			context.getString(R.string.search_chapters),
			context.getString(R.string.chapter_search_notes),
		)
		MaterialAlertDialogBuilder(context)
			.setTitle(R.string.search)
			.setItems(labels) { dialog, which ->
				val selectedMode = modes[which]
				if (item.isActionViewExpanded) {
					activateSearchMode(selectedMode, item.actionView as? SearchView)
				} else {
					searchMode = selectedMode
					item.expandActionView()
				}
				dialog.dismiss()
			}
			.setNegativeButton(android.R.string.cancel, null)
			.show()
	}

	private fun activateSearchMode(mode: SearchMode, searchView: SearchView?) {
		searchMode = mode
		searchView?.apply {
			setOnQueryTextListener(null)
			setQuery("", false)
			queryHint = when (mode) {
				SearchMode.CHAPTERS -> context.getString(R.string.search_chapters)
				SearchMode.NOTES -> context.getString(R.string.chapter_search_notes)
			}
			setOnQueryTextListener(this@ChapterPagesMenuProvider)
		}
		when (mode) {
			SearchMode.CHAPTERS -> {
				viewModel.exitChapterNoteSearch()
				viewModel.performChapterSearch("")
			}
			SearchMode.NOTES -> {
				viewModel.performChapterSearch(null)
				viewModel.performChapterNoteSearch("")
			}
		}
		searchView?.focusSearchInput()
	}

	private fun SearchView.focusSearchInput() {
		post {
			isIconified = false
			val input = findViewById<View>(androidx.appcompat.R.id.search_src_text)
			input.requestFocus()
			(context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
				?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
		}
	}

	private fun SearchView.hideSearchKeyboard() {
		(context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
			?.hideSoftInputFromWindow(windowToken, 0)
	}

	private fun hideOtherMenuItems(menu: Menu, expandedItem: MenuItem) {
		for (index in 0 until menu.size()) {
			val item = menu.getItem(index)
			if (item != expandedItem) item.isVisible = false
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
