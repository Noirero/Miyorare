package org.koitharu.kotatsu.history.ui

import android.os.Bundle
import android.widget.FrameLayout
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

	// Diagnostic state is screen-local and never persisted to user preferences.
	private var debugHistoryOverlayBypassed = false

	override val viewModel by viewModels<HistoryListViewModel>()
	override val isSwipeRefreshEnabled = false

	override fun onViewBindingCreated(binding: FragmentListBinding, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)
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
			))
			viewModel.isStatsEnabled.observe(viewLifecycleOwner, MenuInvalidator(requireActivity()))
		}
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

	override fun onCreateAdapter() = HistoryListAdapter(
		this,
		DynamicItemSizeResolver(resources, viewLifecycleOwner, settings, adjustWidth = false),
		titleTapToRead = settings.isTitleTapToReadEnabled,
		onTipClose = { viewModel.dismissScalingTip() },
		debugHistoryOverlayBypass = if (BuildConfig.DEBUG) ({ debugHistoryOverlayBypassed }) else null,
	)
}
