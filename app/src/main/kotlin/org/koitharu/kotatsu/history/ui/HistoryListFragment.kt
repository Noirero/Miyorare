package org.koitharu.kotatsu.history.ui

import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.button.MaterialButton
import com.google.android.material.search.SearchBar
import org.koitharu.kotatsu.main.ui.MainActivity
import org.koitharu.kotatsu.main.ui.installSharedSearchBarOwner
import org.koitharu.kotatsu.main.ui.releaseSharedSearchBarOwner
import org.koitharu.kotatsu.main.ui.ownsSharedSearchBar
import org.koitharu.kotatsu.main.ui.updateSharedSearchBarHint
import android.view.LayoutInflater
import androidx.core.view.updateLayoutParams
import com.google.android.material.button.MaterialButtonToggleGroup
import org.koitharu.kotatsu.core.model.MangaNovelSection
import org.koitharu.kotatsu.core.prefs.VisualEffectPreferences
import org.koitharu.kotatsu.core.ui.miyorareViewPalette
import org.koitharu.kotatsu.core.ui.miyorareViewPaletteFromPreferences
import org.koitharu.kotatsu.core.ui.applyMangaNovelTogglePalette
import javax.inject.Inject
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import androidx.appcompat.view.ActionMode
import androidx.fragment.app.viewModels
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.ui.dialog.buildAlertDialog
import org.koitharu.kotatsu.core.ui.list.ListSelectionController
import org.koitharu.kotatsu.core.ui.list.RecyclerScrollKeeper
import org.koitharu.kotatsu.core.ui.util.MenuInvalidator
import org.koitharu.kotatsu.core.util.ext.addMenuProvider
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.databinding.FragmentListBinding
import org.koitharu.kotatsu.favourites.data.EXTRA_FAVOURITE_SPACE
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.list.ui.MangaListFragment
import org.koitharu.kotatsu.list.ui.size.DynamicItemSizeResolver

@AndroidEntryPoint
class HistoryListFragment : MangaListFragment() {

	@Inject lateinit var visualEffectPreferences: VisualEffectPreferences

	// Diagnostic state is screen-local and does not alter persisted user settings.
	private var debugHistoryOverlayBypassed = false

	override val viewModel by viewModels<HistoryListViewModel>()
	override val isSwipeRefreshEnabled = false
	private var inlineSearchEdit: AppCompatEditText? = null
	private var inlineSearchActive = false
	private var restoreInlineSearch = false
	private var searchBackCallback: OnBackPressedCallback? = null

	override fun onViewBindingCreated(binding: FragmentListBinding, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)
		restoreInlineSearch = savedInstanceState?.getBoolean(STATE_INLINE_SEARCH)
			?: viewModel.searchQuery.value.isNotEmpty()
		searchBackCallback = object : OnBackPressedCallback(false) {
			override fun handleOnBackPressed() = exitInlineSearch()
		}.also { requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, it) }
		RecyclerScrollKeeper(binding.recyclerView).attach()
		val toggle = layoutInflater.inflate(R.layout.view_manga_novel_toggle, binding.root, false)
			as MaterialButtonToggleGroup
		binding.root.addView(toggle)
		binding.swipeRefreshLayout.updateLayoutParams<FrameLayout.LayoutParams> {
			topMargin = (76 * resources.displayMetrics.density).toInt()
		}
		toggle.check(if (viewModel.selectedSection.value == MangaNovelSection.NOVEL) {
			R.id.button_section_novel
		} else R.id.button_section_manga)
		toggle.addOnButtonCheckedListener { _, checkedId, checked ->
			if (!checked) return@addOnButtonCheckedListener
			val section = if (checkedId == R.id.button_section_novel) MangaNovelSection.NOVEL
				else MangaNovelSection.MANGA
			if (viewModel.selectedSection.value != section) {
				clearListSelection()
				viewModel.selectSection(section)
				updateInlineSearchHint()
				binding.recyclerView.scrollToPosition(0)
			}
		}
		val space = FavouriteSpace.fromArgument(arguments?.getInt(EXTRA_FAVOURITE_SPACE) ?: FavouriteSpace.NORMAL.dbValue)
		val initialPalette = requireContext().miyorareViewPaletteFromPreferences(
			privateFavourites = space == FavouriteSpace.PRIVATE,
		)
		if (initialPalette != null) {
			toggle.applyMangaNovelTogglePalette(initialPalette)
			visualEffectPreferences.level.observe(viewLifecycleOwner) { level ->
				toggle.applyMangaNovelTogglePalette(requireContext().miyorareViewPalette(settings, level))
			}
		}
		if (space == FavouriteSpace.NORMAL) {
			addMenuProvider(HistoryListMenuProvider(binding.root.context, router, viewModel,
				onToggleHistoryOverlayProbe = if (BuildConfig.DEBUG) {{
					debugHistoryOverlayBypassed = !debugHistoryOverlayBypassed
					android.util.Log.i("HistoryCoverAB", if (debugHistoryOverlayBypassed) "variant=B overlay_off" else "variant=A normal")
					binding.recyclerView.adapter?.notifyDataSetChanged()
					debugHistoryOverlayBypassed
				}} else null,
				isHistoryOverlayBypassed = if (BuildConfig.DEBUG) ({ debugHistoryOverlayBypassed }) else null,
			))
			viewModel.isStatsEnabled.observe(viewLifecycleOwner, MenuInvalidator(requireActivity()))
		}
	}

	override fun onSaveInstanceState(outState: Bundle) {
		outState.putBoolean(STATE_INLINE_SEARCH, restoreInlineSearch)
		super.onSaveInstanceState(outState)
	}

	override fun onResume() {
		super.onResume()
		if (!isHidden) installHistorySearchHandler()
	}

	override fun onHiddenChanged(hidden: Boolean) {
		super.onHiddenChanged(hidden)
		if (hidden) {
			exitInlineSearch(clearQuery = false, endSession = false)
			(activity as? MainActivity)?.releaseSharedSearchBarOwner(this)
		} else if (view != null) {
			installHistorySearchHandler()
		}
	}

	override fun onDestroyView() {
		exitInlineSearch(clearQuery = false, endSession = false)
		(activity as? MainActivity)?.releaseSharedSearchBarOwner(this)
		inlineSearchEdit?.let { (it.parent as? ViewGroup)?.removeView(it) }
		inlineSearchEdit = null
		searchBackCallback = null
		super.onDestroyView()
	}

	private fun searchHint() = getString(
		if (viewModel.selectedSection.value == MangaNovelSection.NOVEL) R.string.search_novel
		else R.string.search_manga,
	)

	private fun updateInlineSearchHint() {
		inlineSearchEdit?.hint = searchHint()
		(activity as? MainActivity)?.updateSharedSearchBarHint(this, searchHint())
	}

	private fun installHistorySearchHandler() {
		val host = activity as? MainActivity ?: return
		host.installSharedSearchBarOwner(this, searchHint(), ::openInlineSearch)
		if (restoreInlineSearch) openInlineSearch()
	}

	fun openInlineSearch() {
		if (inlineSearchActive || isHidden) return
		val host = activity as? MainActivity ?: return
		val bar = host.findViewById<SearchBar>(R.id.search_bar) ?: return
		val edit = inlineSearchEdit ?: createInlineSearchEdit(bar) ?: return
		restoreInlineSearch = true
		inlineSearchActive = true
		bar.isGone = true
		edit.isVisible = true
		searchBackCallback?.isEnabled = true
		host.findViewById<MaterialButton>(R.id.button_settings)?.apply {
			setIconResource(R.drawable.ic_arrow_back)
			contentDescription = getString(R.string.close)
			setOnClickListener { exitInlineSearch() }
		}
		edit.requestFocus()
		edit.setSelection(edit.text?.length ?: 0)
		edit.post {
			(context?.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
				?.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
		}
	}

	private fun exitInlineSearch(clearQuery: Boolean = true, endSession: Boolean = true) {
		val wasActive = inlineSearchActive
		inlineSearchActive = false
		if (endSession) restoreInlineSearch = false
		searchBackCallback?.isEnabled = false
		if (clearQuery) viewModel.setSearchQuery("")
		val host = activity as? MainActivity ?: return
		val bar = host.findViewById<SearchBar>(R.id.search_bar) ?: return
		inlineSearchEdit?.apply {
			if (clearQuery) setText("")
			clearFocus()
			isGone = true
		}
		val ownsSearchBar = host.ownsSharedSearchBar(this)
		if (ownsSearchBar) bar.isVisible = true
		if (wasActive && ownsSearchBar) host.restoreMainUtilityButton()
		inlineSearchEdit?.let {
			(host.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
				?.hideSoftInputFromWindow(it.windowToken, 0)
		}
	}

	private fun createInlineSearchEdit(bar: SearchBar): AppCompatEditText? {
		val parent = bar.parent as? LinearLayout ?: return null
		val density = resources.displayMetrics.density
		val edit = AppCompatEditText(requireContext()).apply {
			layoutParams = LinearLayout.LayoutParams(bar.layoutParams as LinearLayout.LayoutParams)
			background = bar.background?.constantState?.newDrawable(resources)?.mutate()
			hint = searchHint()
			setTextColor(bar.textView.currentTextColor)
			setHintTextColor(bar.textView.currentHintTextColor)
			textSize = bar.textView.textSize / resources.displayMetrics.scaledDensity
			gravity = Gravity.CENTER_VERTICAL
			isSingleLine = true
			maxLines = 1
			minimumHeight = (56f * density).toInt()
			setPadding((20f * density).toInt(), 0, (20f * density).toInt(), 0)
			setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0)
			compoundDrawablePadding = resources.getDimensionPixelOffset(R.dimen.margin_small)
			isGone = true
			setText(viewModel.searchQuery.value)
			doAfterTextChanged { viewModel.setSearchQuery(it?.toString().orEmpty()) }
		}
		parent.addView(edit, parent.indexOfChild(bar) + 1)
		inlineSearchEdit = edit
		return edit
	}

	override fun onScrolledToEnd() = viewModel.requestMoreItems()

	override fun onListHeaderClick(item: org.koitharu.kotatsu.list.ui.model.ListHeader, view: android.view.View) {
		val action = item.payload as? HistoryHeaderAction ?: return
		if (action.loadMore) viewModel.requestMoreHistoryGroup(action.key)
		else viewModel.toggleHistoryGroup(action.key)
	}

	override fun onEmptyActionClick() = viewModel.clearFilter()

	override fun onCreateActionMode(
		controller: ListSelectionController,
		menuInflater: MenuInflater,
		menu: Menu
	): Boolean {
		menuInflater.inflate(R.menu.mode_history, menu)
		return super.onCreateActionMode(controller, menuInflater, menu)
	}

	override fun onActionItemClicked(controller: ListSelectionController, mode: ActionMode?, item: MenuItem): Boolean {
		return when (item.itemId) {
			R.id.action_remove -> {
				viewModel.removeFromHistory(selectedItemsIds)
				mode?.finish()
				true
			}

			R.id.action_mark_current -> {
				val itemsSnapshot = selectedItems
				buildAlertDialog(context ?: return false, isCentered = true) {
					setTitle(item.title)
					setIcon(item.icon)
					setMessage(R.string.mark_as_completed_prompt)
					setNegativeButton(android.R.string.cancel, null)
					setPositiveButton(android.R.string.ok) { _, _ ->
						viewModel.markAsRead(itemsSnapshot)
						mode?.finish()
					}
				}.show()
				true
			}

			else -> super.onActionItemClicked(controller, mode, item)
		}
	}

	private companion object {
		const val STATE_INLINE_SEARCH = "history_inline_search_active"
	}

	override fun onCreateAdapter() = HistoryListAdapter(
		this,
		DynamicItemSizeResolver(resources, viewLifecycleOwner, settings, adjustWidth = false),
		titleTapToRead = settings.isTitleTapToReadEnabled,
		onTipClose = { viewModel.dismissScalingTip() },
		debugHistoryOverlayBypass = if (BuildConfig.DEBUG) ({ debugHistoryOverlayBypassed }) else null,
	)
}
