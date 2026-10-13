package org.koitharu.kotatsu.details.ui.pager.chapters

import android.os.Bundle
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.GestureDetectorCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.LocalMangaSource
import org.koitharu.kotatsu.core.nav.ReaderIntent
import org.koitharu.kotatsu.core.nav.dismissParentDialog
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.ui.BaseFragment
import org.koitharu.kotatsu.core.ui.list.ListSelectionController
import org.koitharu.kotatsu.core.ui.list.OnListItemClickListener
import org.koitharu.kotatsu.core.ui.util.PagerNestedScrollHelper
import org.koitharu.kotatsu.core.ui.util.RecyclerViewOwner
import org.koitharu.kotatsu.core.ui.widgets.ChipsView
import org.koitharu.kotatsu.core.util.RecyclerViewScrollCallback
import org.koitharu.kotatsu.core.util.ext.findAppCompatDelegate
import org.koitharu.kotatsu.core.util.ext.findParentCallback
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.core.util.ext.setTextAndVisible
import org.koitharu.kotatsu.databinding.FragmentChaptersBinding
import org.koitharu.kotatsu.details.ui.DetailsViewModel
import org.koitharu.kotatsu.details.ui.adapter.ChaptersAdapter
import org.koitharu.kotatsu.details.ui.adapter.ChaptersSelectionDecoration
import org.koitharu.kotatsu.details.ui.model.ChapterListItem
import org.koitharu.kotatsu.details.ui.showChapterPersonalNote
import org.koitharu.kotatsu.details.ui.showSearchNoteResultChooser
import org.koitharu.kotatsu.details.ui.pager.ChapterSortMode
import org.koitharu.kotatsu.details.ui.pager.ChaptersPagesViewModel
import org.koitharu.kotatsu.details.ui.withVolumeHeaders
import org.koitharu.kotatsu.details.ui.withRangeHeaders
import org.koitharu.kotatsu.list.domain.ListFilterOption
import org.koitharu.kotatsu.list.ui.adapter.TypedListSpacingDecoration
import org.koitharu.kotatsu.list.ui.model.ListHeader
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.local.ui.LocalChaptersRemoveService
import org.koitharu.kotatsu.reader.ui.ReaderNavigationCallback
import org.koitharu.kotatsu.reader.ui.ReaderState
import org.koitharu.kotatsu.reader.ui.showChapterJumpDialog
import kotlin.math.roundToInt

@AndroidEntryPoint
class ChaptersFragment :
	BaseFragment<FragmentChaptersBinding>(),
	OnListItemClickListener<ChapterListItem>,
	RecyclerViewOwner,
	ChipsView.OnChipClickListener {

	private val viewModel by ChaptersPagesViewModel.ActivityVMLazy(this)
	private var chaptersAdapter: ChaptersAdapter? = null
	private var selectionController: ListSelectionController? = null
	private var isInitialReverseValue = true
	private var pendingReverseScroll: Pair<Int, Int>? = null
	private var isRestoringList = false

	override val recyclerView: RecyclerView?
		get() = viewBinding?.recyclerViewChapters

	override fun onCreateViewBinding(inflater: LayoutInflater, container: ViewGroup?) =
		FragmentChaptersBinding.inflate(inflater, container, false)

	override fun onViewBindingCreated(binding: FragmentChaptersBinding, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)
		isRestoringList = viewModel is DetailsViewModel && savedInstanceState != null
		applyDetailsSheetBackground(binding)
		viewModel.chapterPersonalEditor.observe(viewLifecycleOwner) { item -> if (item != null) dismissParentDialog() }
		chaptersAdapter = ChaptersAdapter(
			onItemClickListener = this,
			onPersonalClick = viewModel::editChapterPersonal,
			onNoteClick = { item -> showChapterPersonalNote(requireContext(), item) },
			onRangeToggle = { range -> (viewModel as? DetailsViewModel)?.toggleChapterRange(range) },
			onDownloadClick = { item ->
				if (!item.canDownload) return@ChaptersAdapter
				router.askForDownloadOverMeteredNetwork { allow -> viewModel.download(setOf(item.chapter.id), allow) }
			},
			onDeleteClick = { item ->
				val manga = viewModel.getMangaOrNull()
				if (viewModel.isSmartLocal) {
					org.koitharu.kotatsu.local.ui.confirmLocalChapterDeletion(requireContext()) {
						viewModel.deleteSmartLocalChapters(setOf(item.chapter.id))
					}
				} else if (manga != null) {
					LocalChaptersRemoveService.start(requireContext(), manga, setOf(item.chapter.id), viewModel.favouriteSpace)
				}
			},
		)
		selectionController = ListSelectionController(
			appCompatDelegate = checkNotNull(findAppCompatDelegate()),
			decoration = ChaptersSelectionDecoration(binding.root.context),
			registryOwner = this,
			callback = ChaptersSelectionCallback(viewModel, router, binding.recyclerViewChapters),
		)
		viewModel.chapterListOptions.map { it.grid }.distinctUntilChanged().observe(viewLifecycleOwner) { grid ->
			binding.recyclerViewChapters.layoutManager = if (grid) {
				GridLayoutManager(context, ChapterGridSpanHelper.getSpanCount(binding.recyclerViewChapters)).apply {
					spanSizeLookup = ChapterGridSpanHelper.SpanSizeLookup(binding.recyclerViewChapters)
				}
			} else LinearLayoutManager(context)
		}
		with(binding.recyclerViewChapters) {
			addItemDecoration(TypedListSpacingDecoration(context, true))
			checkNotNull(selectionController).attachToRecyclerView(this)
			setHasFixedSize(true)
			PagerNestedScrollHelper(this).bind(viewLifecycleOwner)
			adapter = chaptersAdapter
			ChapterGridSpanHelper.attach(this)
			ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.RIGHT) {
				override fun getSwipeDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
					val item = chaptersAdapter?.items?.getOrNull(viewHolder.bindingAdapterPosition) as? ChapterListItem
					return if (item != null && !item.isGrid) ItemTouchHelper.RIGHT else 0
				}
				override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) = false
				override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
					val position = viewHolder.bindingAdapterPosition
					val adapter = chaptersAdapter ?: return
					val item = adapter.items.getOrNull(position) as? ChapterListItem
					if (item != null) viewModel.toggleChapterReadState(item.chapter.id)
					if (position != RecyclerView.NO_POSITION) adapter.notifyItemChanged(position)
				}
			}).attachToRecyclerView(this)
			attachVolumeHeaderGestures(this)
		}
		binding.chipsFilter.onChipClickListener = this
		viewModel.isLoading.observe(viewLifecycleOwner, this::onLoadingStateChanged)
		viewModel.chapterListOptions.map { it.descending }.distinctUntilChanged().observe(viewLifecycleOwner) {
			if (isInitialReverseValue) isInitialReverseValue = false else captureScrollForReverse()
		}
		((viewModel as? DetailsViewModel)?.groupedChapterPresentation ?: viewModel.chapterPresentation)
			.map { presentation ->
				val list = presentation.chapters
				val options = presentation.options
				val subset = presentation.searchActive || options.hasStatusFilter || options.sortMode != ChapterSortMode.SOURCE
				(if (subset) list else list.withVolumeHeaders(requireContext(), showMissingChapters = true)) to presentation.ranges
			}.flowOn(Dispatchers.Default).observe(viewLifecycleOwner) { (list, ranges) ->
				onChaptersChanged(ranges.withRangeHeaders(decorateVolumeHeaders(list)))
			}
		viewModel.quickFilter.observe(viewLifecycleOwner, this::onFilterChanged)
		combine(viewModel.emptyReason, viewModel.chapterNotesQuery, viewModel.chapters) { reason, notesQuery, chapters ->
			if (!notesQuery.isNullOrBlank() && chapters.isEmpty()) R.string.chapter_search_notes_empty else reason?.msgResId ?: 0
		}.observe(viewLifecycleOwner) { binding.textViewHolder.setTextAndVisible(it) }
		viewModel.onOpenChapterInBrowser.observeEvent(viewLifecycleOwner) { url ->
			val manga = viewModel.getMangaOrNull()
			router.openBrowser(url = url, source = manga?.source, title = manga?.title)
		}
	}

	private fun applyDetailsSheetBackground(binding: FragmentChaptersBinding) {
		if (viewModel !is DetailsViewModel) return
		val color = requireActivity().getThemeColor(android.R.attr.colorBackground)
		binding.root.setBackgroundColor(color)
		binding.scrollViewFilter.setBackgroundColor(color)
		binding.recyclerViewChapters.setBackgroundColor(color)
	}

	private fun decorateVolumeHeaders(list: List<ListModel>): List<ListModel> {
		val options = viewModel.chapterListOptions.value
		if ((viewModel.chapterNotesQuery.value?.isNotBlank() ?: viewModel.chaptersQuery.value.isNotEmpty()) || options.hasStatusFilter || options.sortMode != ChapterSortMode.SOURCE || viewModel.getMangaOrNull()?.source == LocalMangaSource) return list
		val chapterItems = viewModel.chapters.value
		if (chapterItems.none { it.chapter.volume > 0 }) return list
		return list.mapIndexed { index, item ->
			if (item !is ListHeader) return@mapIndexed item
			val next = list.getOrNull(index + 1) as? ChapterListItem ?: return@mapIndexed item
			val volume = next.chapter.volume
			if (volume <= 0) return@mapIndexed item
			val ready = chapterItems.count { it.chapter.volume == volume && !it.isDownloaded && !it.isDownloading && it.chapter.source != LocalMangaSource }
			ListHeader(item.getText(requireContext()) ?: "", R.string.download, volume, ready.takeIf { it > 0 }?.toString())
		}
	}

	private fun attachVolumeHeaderGestures(recyclerView: RecyclerView) {
		val detector = GestureDetectorCompat(recyclerView.context, object : GestureDetector.SimpleOnGestureListener() {
			override fun onDown(e: MotionEvent) = true
			override fun onSingleTapUp(e: MotionEvent): Boolean {
				val target = findVolumeHeaderAt(recyclerView, e) ?: return false
				confirmVolumeDownload(setOf(target.second)); return true
			}
			override fun onLongPress(e: MotionEvent) {
				val target = findVolumeHeaderAt(recyclerView, e) ?: return
				showVolumeActions(target.second, target.first)
			}
		})
		recyclerView.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
			override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean { detector.onTouchEvent(e); return false }
		})
	}

	private fun findVolumeHeaderAt(recyclerView: RecyclerView, event: MotionEvent): Pair<View, Int>? {
		val child = recyclerView.findChildViewUnder(event.x, event.y) ?: return null
		val position = recyclerView.getChildAdapterPosition(child)
		if (position == RecyclerView.NO_POSITION) return null
		val item = chaptersAdapter?.items?.getOrNull(position) as? ListHeader ?: return null
		return child to (item.payload as? Int ?: return null)
	}

	private fun showVolumeActions(volume: Int, anchor: View) {
		val items = volumeItems(setOf(volume)); if (items.isEmpty()) return
		PopupMenu(anchor.context, anchor).apply {
			menu.add(0, ACTION_DOWNLOAD_VOLUME, 0, R.string.volume_download_action)
			menu.add(0, ACTION_SELECT_VOLUME, 1, R.string.volume_select_action)
			if (items.any { it.isDownloaded }) menu.add(0, ACTION_DELETE_VOLUME, 2, R.string.volume_delete_action)
			menu.add(0, ACTION_DOWNLOAD_BY_VOLUME, 3, R.string.volume_multi_action)
			setOnMenuItemClickListener { m ->
				when (m.itemId) {
					ACTION_DOWNLOAD_VOLUME -> confirmVolumeDownload(setOf(volume))
					ACTION_SELECT_VOLUME -> selectVolume(volume)
					ACTION_DELETE_VOLUME -> confirmDeleteDownloadedVolume(volume)
					ACTION_DOWNLOAD_BY_VOLUME -> showVolumeDownloadPicker(setOf(volume))
					else -> return@setOnMenuItemClickListener false
				}; true
			}; show()
		}
	}

	private fun showVolumeDownloadPicker(preselectedVolumes: Set<Int> = emptySet()) {
		val groups = viewModel.chapters.value.filter { it.chapter.volume > 0 && it.chapter.source != LocalMangaSource }.groupBy { it.chapter.volume }.entries.map { e ->
			val ready = e.value.count { !it.isDownloaded && !it.isDownloading }; VolumeDownloadGroup(e.key, e.value.size, ready)
		}.filter { it.readyCount > 0 }
		if (groups.isEmpty()) { Toast.makeText(requireContext(), R.string.volume_download_nothing, Toast.LENGTH_SHORT).show(); return }
		val checked = BooleanArray(groups.size) { groups[it].volume in preselectedVolumes }
		val labels = groups.map { getString(R.string.volume_multi_item, getString(R.string.volume_, it.volume), it.totalCount, it.readyCount) }.toTypedArray()
		MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.volume_multi_title).setMultiChoiceItems(labels, checked) { _, which, value -> checked[which] = value }
			.setPositiveButton(R.string.download) { _, _ ->
				val selected = groups.indices.filter { checked[it] }.mapTo(LinkedHashSet()) { groups[it].volume }
				if (selected.isNotEmpty()) confirmVolumeDownload(selected)
			}.setNegativeButton(android.R.string.cancel, null).show()
	}

	private fun confirmVolumeDownload(volumes: Set<Int>) {
		val items = volumeItems(volumes)
		val downloaded = items.count { it.isDownloaded }; val queued = items.count { !it.isDownloaded && it.isDownloading }
		val ready = items.filter { !it.isDownloaded && !it.isDownloading }
		if (ready.isEmpty()) { Toast.makeText(requireContext(), R.string.volume_download_nothing, Toast.LENGTH_SHORT).show(); return }
		val title = if (volumes.size == 1) getString(R.string.volume_download_title, getString(R.string.volume_, volumes.first())) else getString(R.string.volume_multi_title)
		MaterialAlertDialogBuilder(requireContext()).setTitle(title).setMessage(getString(R.string.volume_download_summary, items.size, ready.size, downloaded, queued))
			.setPositiveButton(R.string.download) { _, _ ->
				val ids = ready.mapTo(LinkedHashSet()) { it.chapter.id }; router.askForDownloadOverMeteredNetwork { viewModel.download(ids, it) }
			}.setNegativeButton(android.R.string.cancel, null).show()
	}

	private fun selectVolume(volume: Int) { selectionController?.addAll(volumeItems(setOf(volume)).map { it.chapter.id }) }

	private fun confirmDeleteDownloadedVolume(volume: Int) {
		val downloaded = volumeItems(setOf(volume)).filter { it.isDownloaded }
		if (downloaded.isEmpty()) { Toast.makeText(requireContext(), R.string.volume_no_downloaded, Toast.LENGTH_SHORT).show(); return }
		val manga = viewModel.getMangaOrNull() ?: return
		MaterialAlertDialogBuilder(requireContext()).setTitle(getString(R.string.volume_delete_title, getString(R.string.volume_, volume)))
			.setMessage(resources.getQuantityString(R.plurals.chapters, downloaded.size, downloaded.size)).setPositiveButton(R.string.delete) { _, _ ->
				LocalChaptersRemoveService.start(requireContext(), manga, downloaded.mapTo(LinkedHashSet()) { it.chapter.id }, viewModel.favouriteSpace)
			}.setNegativeButton(android.R.string.cancel, null).show()
	}

	private fun volumeItems(volumes: Set<Int>) = viewModel.chapters.value.filter { it.chapter.volume in volumes && it.chapter.source != LocalMangaSource }

	override fun onDestroyView() { chaptersAdapter = null; selectionController = null; super.onDestroyView() }

	override fun onItemClick(item: ChapterListItem, view: View) {
		if (selectionController?.onItemClick(item.chapter.id) == true) {
			view.postDelayed({ view.isPressed = false; view.jumpDrawablesToCurrentState() }, 250); view.clearFocus(); return
		}
		if (!viewModel.chapterNotesQuery.value.isNullOrBlank()) {
			showSearchNoteResultChooser(view.context, item, viewModel.chapters.value)
			return
		}
		val listener = findParentCallback(ReaderNavigationCallback::class.java)
		if (listener != null && listener.onChapterSelected(item.chapter)) dismissParentDialog() else {
			val state = if (item.isCurrent && viewModel.readingState.value?.chapterId == item.chapter.id) viewModel.readingState.value!! else ReaderState(item.chapter.id, 0, 0)
			val manga = viewModel.getMangaOrNull() ?: return; val context = view.context
			viewLifecycleOwner.lifecycleScope.launch {
				val openReader = { peek: Boolean -> router.openReader(ReaderIntent.Builder(context).manga(manga).favouriteSpace(viewModel.favouriteSpace.dbValue).state(state).apply { if (peek) peek() }.build()) }
				when (viewModel.getChapterOpenMode(item.chapter.id)) {
					ChaptersPagesViewModel.ChapterOpenMode.NORMAL -> openReader(false)
					ChaptersPagesViewModel.ChapterOpenMode.ASK -> showChapterJumpDialog(requireActivity(), { openReader(true) }, { openReader(false) }, { viewModel.disableChapterJumpDialog() })
				}
			}
		}
	}

	override fun onItemLongClick(item: ChapterListItem, view: View) = selectionController?.onItemLongClick(view, item.chapter.id) == true
	override fun onItemContextClick(item: ChapterListItem, view: View) = selectionController?.onItemContextClick(view, item.chapter.id) == true
	override fun onChipClick(chip: Chip, data: Any?) { if (data is ListFilterOption.Branch) viewModel.setSelectedBranch(data.titleText) }

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		viewBinding?.run {
			val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()); val clearance = resources.getDimensionPixelSize(R.dimen.margin_normal)
			recyclerViewChapters.updatePadding(left = bars.left, right = bars.right, bottom = bars.bottom + clearance)
			chipsFilter.updatePadding(left = bars.left, right = bars.right)
		}; return WindowInsetsCompat.CONSUMED
	}

	private fun onChaptersChanged(list: List<ListModel>) {
		val adapter = chaptersAdapter ?: return; val reverse = pendingReverseScroll
		when {
			adapter.itemCount == 0 && !isRestoringList -> {
				val position = list.indexOfFirst { it is ChapterListItem && it.isCurrent } - 1
				if (position > 0) adapter.setItems(list, RecyclerViewScrollCallback(requireViewBinding().recyclerViewChapters, position, (resources.getDimensionPixelSize(R.dimen.chapter_list_item_height) * 0.6).roundToInt())) else adapter.items = list
			}
			reverse != null -> { pendingReverseScroll = null; adapter.setItems(list, RecyclerViewScrollCallback(requireViewBinding().recyclerViewChapters, reverse.first, reverse.second)) }
			else -> adapter.items = list
		}
		if (list.isNotEmpty()) isRestoringList = false
	}

	private fun captureScrollForReverse() {
		val rv = viewBinding?.recyclerViewChapters ?: return; val lm = rv.layoutManager as? LinearLayoutManager ?: return; val position = lm.findFirstVisibleItemPosition()
		if (position == RecyclerView.NO_POSITION) return
		pendingReverseScroll = position to ((lm.findViewByPosition(position)?.top ?: 0) - rv.paddingTop)
	}
	private fun onFilterChanged(list: List<ChipsView.ChipModel>) { viewBinding?.chipsFilter?.run { setChips(list); isGone = list.isEmpty() } }
	private fun onLoadingStateChanged(isLoading: Boolean) { requireViewBinding().progressBar.isVisible = isLoading }
	private data class VolumeDownloadGroup(val volume: Int, val totalCount: Int, val readyCount: Int)
	private companion object { const val ACTION_DOWNLOAD_VOLUME = 1; const val ACTION_SELECT_VOLUME = 2; const val ACTION_DELETE_VOLUME = 3; const val ACTION_DOWNLOAD_BY_VOLUME = 4 }
}
