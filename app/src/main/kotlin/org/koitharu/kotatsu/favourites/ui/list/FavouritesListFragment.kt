package org.koitharu.kotatsu.favourites.ui.list

import android.content.DialogInterface
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.view.ActionMode
import androidx.core.graphics.ColorUtils
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil3.request.ImageRequest
import coil3.size.Size
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.isNovelSource
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.prefs.MiyorareDesignStyle
import org.koitharu.kotatsu.core.prefs.VisualEffectLevel
import org.koitharu.kotatsu.core.prefs.VisualEffectPreferences
import org.koitharu.kotatsu.core.ui.MiyorareVisualTokens
import org.koitharu.kotatsu.core.ui.list.ListSelectionController
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import org.koitharu.kotatsu.core.util.ext.mangaExtra
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.stableMangaCoverKey
import org.koitharu.kotatsu.core.util.ext.viewLifecycleScope
import org.koitharu.kotatsu.core.util.ext.withArgs
import org.koitharu.kotatsu.databinding.FragmentListBinding
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.favourites.domain.DOWNLOADED_FAVOURITES_CATEGORY_ID
import org.koitharu.kotatsu.favourites.domain.PrivateTransferDestination
import org.koitharu.kotatsu.favourites.domain.PrivateTransferResult
import org.koitharu.kotatsu.favourites.domain.TransferFavouritesToPrivateUseCase
import org.koitharu.kotatsu.favourites.groups.domain.LibraryGroup
import org.koitharu.kotatsu.favourites.groups.ui.LibraryGroupListModel
import org.koitharu.kotatsu.favourites.groups.ui.LibraryGroupManageAdapter
import org.koitharu.kotatsu.favourites.groups.ui.LibraryGroupManageItem
import org.koitharu.kotatsu.favourites.groups.ui.libraryGroupAD
import org.koitharu.kotatsu.list.ui.MangaListFragment
import org.koitharu.kotatsu.list.ui.adapter.ListItemType
import org.koitharu.kotatsu.list.ui.adapter.MangaListAdapter
import org.koitharu.kotatsu.list.ui.config.ListConfigSection
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.MangaListModel
import org.koitharu.kotatsu.list.ui.size.DynamicItemSizeResolver
import org.koitharu.kotatsu.local.domain.DeleteLocalMangaUseCase
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import javax.inject.Inject
import androidx.appcompat.R as appcompatR
import com.google.android.material.R as materialR

@AndroidEntryPoint
class FavouritesListFragment : MangaListFragment() {

	@Inject lateinit var visualEffectPreferences: VisualEffectPreferences
	@Inject lateinit var transferFavouritesToPrivateUseCase: TransferFavouritesToPrivateUseCase
	@Inject lateinit var deleteLocalMangaUseCase: DeleteLocalMangaUseCase

	override val viewModel by viewModels<FavouritesListViewModel>()

	override val isSwipeRefreshEnabled = false
	override val paginationOffset = 12

	private val coverPrefetchSemaphore = Semaphore(3)
	private val prefetchedCovers = LinkedHashSet<String>()
	private var coverPrefetchJob: Job? = null
	private var pendingScrollPosition: PendingScroll? = null
	private var modernSurfaceDecoration: ModernLibrarySurfaceDecoration? = null
	private var modernChildAttachListener: RecyclerView.OnChildAttachStateChangeListener? = null

	val categoryId
		get() = viewModel.categoryId

	override fun onViewBindingCreated(binding: FragmentListBinding, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)
		binding.recyclerView.isVP2BugWorkaroundEnabled = true
		if (settings.miyorareDesignStyle == MiyorareDesignStyle.MODERN) {
			modernSurfaceDecoration = ModernLibrarySurfaceDecoration().also { decoration ->
				binding.recyclerView.addItemDecoration(decoration, 0)
			}
			modernChildAttachListener = object : RecyclerView.OnChildAttachStateChangeListener {
				override fun onChildViewAttachedToWindow(view: View) = compactModernEmptyState(view)

				override fun onChildViewDetachedFromWindow(view: View) = Unit
			}.also(binding.recyclerView::addOnChildAttachStateChangeListener)
			visualEffectPreferences.level.observe(viewLifecycleOwner) { level ->
				applyModernLibraryVisuals(binding, level)
			}
		}
		viewModel.gridScale.observe(viewLifecycleOwner) {
			val adapter = binding.recyclerView.adapter ?: return@observe
			val layoutManager = binding.recyclerView.layoutManager as? GridLayoutManager ?: return@observe
			val first = layoutManager.findFirstVisibleItemPosition()
			val last = layoutManager.findLastVisibleItemPosition()
			if (first >= 0 && last >= first && first < adapter.itemCount) {
				adapter.notifyItemRangeChanged(first, (last - first + 1).coerceAtMost(adapter.itemCount - first))
			}
		}
		viewModel.content.observe(viewLifecycleOwner) { items ->
			prefetchCovers(items)
			pendingScrollPosition?.let { target ->
				pendingScrollPosition = null
				binding.recyclerView.post {
					val position = if (target == PendingScroll.BOTTOM) {
						(binding.recyclerView.adapter?.itemCount ?: 0) - 1
					} else {
						0
					}
					if (position >= 0) binding.recyclerView.scrollToPosition(position)
				}
			}
		}
	}

	override fun onDestroyView() {
		viewBinding?.recyclerView?.let { recyclerView ->
			modernChildAttachListener?.let(recyclerView::removeOnChildAttachStateChangeListener)
			modernSurfaceDecoration?.let(recyclerView::removeItemDecoration)
		}
		modernChildAttachListener = null
		modernSurfaceDecoration = null
		super.onDestroyView()
	}

	override fun onResume() {
		super.onResume()
		prefetchCovers(viewModel.content.value)
	}

	private fun compactModernEmptyState(view: View) {
		if (view.id != R.id.empty_view) return
		val icon = view.findViewById<View>(R.id.icon) ?: return
		val size = (MODERN_EMPTY_STATE_ICON_DP * view.resources.displayMetrics.density).toInt()
		val params = icon.layoutParams ?: return
		if (params.width == size && params.height == size) return
		params.width = size
		params.height = size
		icon.layoutParams = params
	}

	private fun applyModernLibraryVisuals(binding: FragmentListBinding, level: VisualEffectLevel) {
		val context = binding.root.context
		val surface = context.getThemeColor(materialR.attr.colorSurface, Color.TRANSPARENT)
		val primary = context.getThemeColor(appcompatR.attr.colorPrimary, surface)
		val tertiary = context.getThemeColor(materialR.attr.colorTertiary, primary)
		val (topFraction, bottomFraction) = when (level) {
			VisualEffectLevel.LIGHT -> 0.015f to 0f
			VisualEffectLevel.BALANCED -> 0.055f to 0.035f
			VisualEffectLevel.FULL -> 0.095f to 0.065f
		}
		binding.root.background = GradientDrawable(
			GradientDrawable.Orientation.TOP_BOTTOM,
			intArrayOf(
				ColorUtils.blendARGB(surface, primary, topFraction),
				ColorUtils.blendARGB(surface, tertiary, bottomFraction),
				surface,
			),
		)
		modernSurfaceDecoration?.update(level, surface, primary, tertiary)
		binding.recyclerView.invalidateItemDecorations()
	}

	private fun prefetchCovers(items: List<ListModel>) {
		if (!isResumed) return
		val columns = viewModel.gridColumns.value ?: 2
		val width = (resources.displayMetrics.widthPixels / columns.coerceAtLeast(1)).coerceAtLeast(120)
		val size = Size(width, width * 18 / 13)
		val candidates = items.filterIsInstance<MangaListModel>()
			.takeLast(COVER_PREFETCH_BATCH)
			.mapNotNull { item ->
				val coverUrl = item.coverUrl ?: return@mapNotNull null
				CoverPrefetchCandidate(item, coverUrl, "${item.id}:$coverUrl")
			}

		// Only the newest page needs to stay queued. A semaphore alone limits active requests but leaves
		// every older pagination batch suspended behind it, which can accumulate hundreds of stale jobs
		// during a fast scroll through a large library.
		coverPrefetchJob?.cancel()
		coverPrefetchJob = viewLifecycleScope.launch {
			coroutineScope {
				for (candidate in candidates) {
					launch {
						coverPrefetchSemaphore.withPermit {
							if (!prefetchedCovers.add(candidate.key)) return@withPermit
							var completed = false
							try {
								val request = ImageRequest.Builder(requireContext())
									.data(candidate.coverUrl)
									.size(size)
									.mangaExtra(candidate.item.manga)
									.stableMangaCoverKey(candidate.item.manga, candidate.coverUrl)
									.build()
								runCatchingCancellable { coil.execute(request) }
								completed = true
							} finally {
								// A cancelled active request should be eligible again in the newest batch.
								if (!completed) prefetchedCovers.remove(candidate.key)
							}
							while (prefetchedCovers.size > MAX_REMEMBERED_COVERS) {
								prefetchedCovers.remove(prefetchedCovers.first())
							}
						}
					}
				}
			}
		}
	}

	override fun onCreateAdapter() = MangaListAdapter(
		listener = this,
		sizeResolver = DynamicItemSizeResolver(resources, viewLifecycleOwner, settings, adjustWidth = false),
		titleTapToRead = settings.isTitleTapToReadEnabled,
		onTipClose = { viewModel.dismissScalingTip() },
		gridVisualScaleProvider = { viewModel.gridScale.value },
	).apply {
		addDelegate(ListItemType.LIBRARY_GROUP, libraryGroupAD(::onLibraryGroupClick))
	}

	private fun onLibraryGroupClick(item: LibraryGroupListModel, view: View) {
		showLibraryGroupOverview(item.group.id)
	}

	override fun onScrolledToEnd() = viewModel.requestMoreItems()

	override fun onEmptyActionClick() = viewModel.clearFilter()

	override fun onFilterClick(view: View?) {
		router.showListSortSheet(ListConfigSection.Favorites(categoryId))
	}

	fun scrollToTop() {
		if (viewModel.requestTopPage()) {
			pendingScrollPosition = PendingScroll.TOP
		} else {
			(viewBinding?.recyclerView?.layoutManager as? LinearLayoutManager)
				?.scrollToPositionWithOffset(0, 0)
		}
	}

	fun scrollToBottom() {
		if (viewModel.requestBottomPage()) {
			pendingScrollPosition = PendingScroll.BOTTOM
		} else {
			val recyclerView = viewBinding?.recyclerView ?: return
			val last = (recyclerView.adapter?.itemCount ?: 0) - 1
			if (last >= 0) recyclerView.scrollToPosition(last)
		}
	}

	override fun onCreateActionMode(
		controller: ListSelectionController,
		menuInflater: MenuInflater,
		menu: Menu
	): Boolean {
		menuInflater.inflate(R.menu.mode_favourites, menu)
		return super.onCreateActionMode(controller, menuInflater, menu)
	}

	override fun onPrepareActionMode(controller: ListSelectionController, mode: ActionMode?, menu: Menu): Boolean {
		val pinned = viewModel.pinnedIds.value
		val ids = selectedItemsIds
		menu.findItem(R.id.action_pin)?.isVisible = ids.isNotEmpty() && ids.none { it in pinned }
		menu.findItem(R.id.action_unpin)?.isVisible = ids.isNotEmpty() && ids.all { it in pinned }
		val groupItems = selectedItems
		menu.findItem(R.id.action_group)?.isVisible =
			viewModel.isLibraryGroupingAvailable &&
				groupItems.size >= 2 &&
				groupItems.none { it.source.isNovelSource }
		menu.findItem(R.id.action_move_private)?.isVisible =
			viewModel.favouriteSpace == FavouriteSpace.NORMAL &&
				categoryId != DOWNLOADED_FAVOURITES_CATEGORY_ID &&
				ids.isNotEmpty()
		// Downloaded is a virtual file-backed shelf and may contain titles that were never favourited.
		// Category membership is managed through action_favourite; a generic remove action would be a
		// misleading no-op for those downloaded-only items.
		menu.findItem(R.id.action_remove)?.isVisible = categoryId != DOWNLOADED_FAVOURITES_CATEGORY_ID
		return super.onPrepareActionMode(controller, mode, menu)
	}

	override fun onActionItemClicked(controller: ListSelectionController, mode: ActionMode?, item: MenuItem): Boolean {
		return when (item.itemId) {
			R.id.action_select_all -> {
				viewLifecycleScope.launch {
					controller.addAll(viewModel.getAllSelectableIds())
				}
				true
			}

			R.id.action_pin -> {
				viewModel.setPinned(selectedItemsIds, true)
				mode?.finish()
				true
			}

			R.id.action_unpin -> {
				viewModel.setPinned(selectedItemsIds, false)
				mode?.finish()
				true
			}

			R.id.action_group -> {
				showCreateLibraryGroupDialog(selectedItemsIds.toList(), mode)
				true
			}

			R.id.action_move_private -> {
				showMoveToPrivateDialog(selectedItemsIds.toSet(), mode)
				true
			}

			R.id.action_remove -> {
				showRemoveMangaDialog(selectedItemsIds.toSet(), mode)
				true
			}

			R.id.action_mark_current -> {
				val itemsSnapshot = selectedItems
				MaterialAlertDialogBuilder(context ?: return false)
					.setTitle(item.title)
					.setMessage(R.string.mark_as_completed_prompt)
					.setNegativeButton(android.R.string.cancel, null)
					.setPositiveButton(android.R.string.ok) { _, _ ->
						viewModel.markAsRead(itemsSnapshot)
						mode?.finish()
					}.show()
				true
			}

			else -> super.onActionItemClicked(controller, mode, item)
		}
	}

	private fun showMoveToPrivateDialog(ids: Set<Long>, mode: ActionMode?) {
		if (ids.isEmpty()) return
		MaterialAlertDialogBuilder(requireContext())
			.setTitle(R.string.private_transfer_title)
			.setMessage(R.string.private_transfer_storage_note)
			.setItems(
				arrayOf(
					getString(R.string.private_transfer_preserve_categories),
					getString(R.string.private_transfer_choose_categories),
				),
			) { _, which ->
				when (which) {
					0 -> {
						mode?.finish()
						startPrivateTransfer(ids, PrivateTransferDestination.PreserveCategories)
					}
					1 -> showPrivateCategoryChooser(ids, mode)
				}
			}
			.setNegativeButton(android.R.string.cancel, null)
			.show()
	}

	private fun showPrivateCategoryChooser(ids: Set<Long>, mode: ActionMode?) {
		viewLifecycleScope.launch {
			val categoriesResult = runCatchingCancellable { transferFavouritesToPrivateUseCase.getPrivateCategories() }
			val categories = categoriesResult.getOrElse {
				showPrivateOperationError(it, R.string.private_transfer_error)
				return@launch
			}
			if (categories.isEmpty()) {
				Toast.makeText(requireContext(), R.string.private_transfer_no_private_categories, Toast.LENGTH_LONG).show()
				return@launch
			}
			val selected = BooleanArray(categories.size)
			MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.private_transfer_choose_category_title)
				.setMultiChoiceItems(categories.map { it.title }.toTypedArray(), selected) { _, which, checked ->
					selected[which] = checked
				}
				.setNegativeButton(android.R.string.cancel, null)
				.setPositiveButton(android.R.string.ok) { _, _ ->
					val targetIds = categories.mapIndexedNotNullTo(LinkedHashSet()) { index, category ->
						category.id.takeIf { selected[index] }
					}
					if (targetIds.isEmpty()) {
						Toast.makeText(requireContext(), R.string.private_transfer_select_category, Toast.LENGTH_SHORT).show()
					} else {
						mode?.finish()
						startPrivateTransfer(ids, PrivateTransferDestination.PrivateCategories(targetIds))
					}
				}
				.show()
		}
	}

	private fun startPrivateTransfer(ids: Set<Long>, destination: PrivateTransferDestination) {
		val progressDialog = MaterialAlertDialogBuilder(requireContext())
			.setTitle(R.string.private_transfer_title)
			.setMessage(getString(R.string.private_transfer_preparing, ids.size))
			.setCancelable(false)
			.create()
		progressDialog.show()
		viewLifecycleScope.launch {
			val result = runCatchingCancellable {
				transferFavouritesToPrivateUseCase.transfer(ids, destination) { progress ->
					view?.post {
						if (progressDialog.isShowing) {
							progressDialog.setMessage(
								getString(R.string.private_transfer_progress, progress.processed, progress.total),
							)
						}
					}
				}
			}
			if (progressDialog.isShowing) progressDialog.dismiss()
			result.onSuccess { showPrivateTransferResult(ids, it) }
				.onFailure { showPrivateOperationError(it, R.string.private_transfer_error) }
		}
	}

	private fun showPrivateTransferResult(ids: Set<Long>, result: PrivateTransferResult) {
		val builder = MaterialAlertDialogBuilder(requireContext())
			.setTitle(R.string.private_transfer_title)
			.setNegativeButton(R.string.close, null)
		if (result.isComplete) {
			builder
				.setMessage(getString(R.string.private_transfer_success, result.verifiedCount, result.sourceCount))
				.setPositiveButton(R.string.private_transfer_remove_normal) { _, _ ->
					showRemoveMangaDialog(ids, mode = null, removeWholeNormal = true)
				}
		} else {
			builder.setMessage(getString(R.string.private_transfer_partial, result.verifiedCount, result.sourceCount))
		}
		builder.show()
	}

	private fun showRemoveMangaDialog(
		ids: Set<Long>,
		mode: ActionMode?,
		removeWholeNormal: Boolean = false,
	) {
		if (ids.isEmpty()) return
		MaterialAlertDialogBuilder(requireContext())
			.setTitle(R.string.private_remove_title)
			.setMessage(R.string.private_remove_shared_download_warning)
			.setItems(
				arrayOf(
					getString(R.string.private_remove_only),
					getString(R.string.private_remove_with_downloads),
				),
			) { _, which ->
				mode?.finish()
				when (which) {
					0 -> removeFavouritesOnly(ids, removeWholeNormal)
					1 -> removeFavouritesWithDownloads(ids, removeWholeNormal)
				}
			}
			.setNegativeButton(android.R.string.cancel, null)
			.show()
	}

	private fun removeFavouritesOnly(ids: Set<Long>, removeWholeNormal: Boolean) {
		if (!removeWholeNormal) {
			viewModel.removeFromFavourites(ids)
			return
		}
		viewLifecycleScope.launch {
			runCatchingCancellable { transferFavouritesToPrivateUseCase.removeFromNormal(ids) }
				.onSuccess {
					Toast.makeText(requireContext(), R.string.removed_from_favourites, Toast.LENGTH_SHORT).show()
				}
				.onFailure { showPrivateOperationError(it, R.string.private_remove_error) }
		}
	}

	private fun removeFavouritesWithDownloads(ids: Set<Long>, removeWholeNormal: Boolean) {
		val progressDialog = MaterialAlertDialogBuilder(requireContext())
			.setTitle(R.string.private_remove_title)
			.setMessage(R.string.private_remove_downloads_progress)
			.setCancelable(false)
			.create()
		progressDialog.show()
		viewLifecycleScope.launch {
			val result = runCatchingCancellable {
				val removedDownloads = deleteLocalMangaUseCase(ids)
				if (removeWholeNormal) {
					transferFavouritesToPrivateUseCase.removeFromNormal(ids)
				} else {
					viewModel.removeFromFavourites(ids)
				}
				removedDownloads
			}
			if (progressDialog.isShowing) progressDialog.dismiss()
			result.onSuccess { removedDownloads ->
				Toast.makeText(
					requireContext(),
					getString(R.string.private_remove_downloads_done, removedDownloads),
					Toast.LENGTH_LONG,
				).show()
			}.onFailure { showPrivateOperationError(it, R.string.private_remove_error) }
		}
	}

	private fun showPrivateOperationError(error: Throwable, fallback: Int) {
		if (!isAdded) return
		Toast.makeText(
			requireContext(),
			error.message?.takeIf { it.isNotBlank() } ?: getString(fallback),
			Toast.LENGTH_LONG,
		).show()
	}

	private fun showCreateLibraryGroupDialog(mangaIds: List<Long>, mode: ActionMode?) {
		if (mangaIds.size < 2) return
		val input = EditText(requireContext()).apply {
			hint = getString(R.string.library_group_title_hint)
			setText(R.string.library_group_default_title)
			selectAll()
			inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
		}
		MaterialAlertDialogBuilder(requireContext())
			.setTitle(R.string.library_group_create)
			.setView(input)
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(R.string.library_group_create) { _, _ ->
				val title = input.text?.toString().orEmpty()
				viewLifecycleScope.launch {
					runCatching { viewModel.createLibraryGroup(title, mangaIds) }
						.onSuccess {
							Toast.makeText(requireContext(), R.string.library_group_created, Toast.LENGTH_SHORT).show()
							mode?.finish()
						}
						.onFailure(::showLibraryGroupError)
				}
			}.show()
	}

	private fun showLibraryGroupOverview(groupId: Long) {
		viewLifecycleScope.launch {
			val loaded = runCatching { viewModel.getLibraryGroupManageItems(groupId) }
				.onFailure(::showLibraryGroupError)
				.getOrNull() ?: return@launch
			val (group, members) = loaded
			if (members.isEmpty()) return@launch
			MaterialAlertDialogBuilder(requireContext())
				.setTitle(group.title)
				.setItems(members.map { it.member.displayTitle }.toTypedArray()) { _, which ->
					members.getOrNull(which)?.let { showLibraryGroupMemberActions(group.id, it) }
				}
				.setNegativeButton(R.string.close, null)
				.setNeutralButton(R.string.library_group_edit) { _, _ -> showEditLibraryGroupDialog(group) }
				.setPositiveButton(R.string.library_group_manage_order) { _, _ -> showLibraryGroupOrderDialog(group.id) }
				.show()
		}
	}

	private fun showLibraryGroupMemberActions(groupId: Long, item: LibraryGroupManageItem) {
		val actions = arrayOf(
			getString(R.string.library_group_open_member),
			getString(R.string.library_group_edit_member),
			getString(R.string.library_group_remove_member),
		)
		MaterialAlertDialogBuilder(requireContext())
			.setTitle(item.member.displayTitle)
			.setItems(actions) { _, which ->
				when (which) {
					0 -> router.openDetails(item.manga)
					1 -> router.openMangaOverrideConfig(item.manga)
					2 -> confirmRemoveLibraryGroupMember(groupId, item)
				}
			}.show()
	}

	private fun confirmRemoveLibraryGroupMember(groupId: Long, item: LibraryGroupManageItem) {
		MaterialAlertDialogBuilder(requireContext())
			.setTitle(R.string.library_group_remove_member)
			.setMessage(R.string.library_group_remove_member_confirm)
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(R.string.remove) { _, _ ->
				viewLifecycleScope.launch {
					runCatching { viewModel.removeLibraryGroupMember(groupId, item.member.mangaId) }
						.onSuccess {
							Toast.makeText(requireContext(), R.string.library_group_removed, Toast.LENGTH_SHORT).show()
						}
						.onFailure(::showLibraryGroupError)
				}
			}.show()
	}

	private fun showEditLibraryGroupDialog(group: LibraryGroup) {
		val density = resources.displayMetrics.density
		val padding = (20f * density).toInt()
		val container = LinearLayout(requireContext()).apply {
			orientation = LinearLayout.VERTICAL
			setPadding(padding, padding / 2, padding, 0)
		}
		val titleInput = EditText(requireContext()).apply {
			hint = getString(R.string.library_group_title_hint)
			setText(group.title)
			inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
		}
		val coverInput = EditText(requireContext()).apply {
			hint = getString(R.string.library_group_cover_hint)
			setText(group.coverUrl.orEmpty())
			inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
		}
		container.addView(titleInput)
		container.addView(coverInput)
		MaterialAlertDialogBuilder(requireContext())
			.setTitle(R.string.library_group_edit)
			.setView(container)
			.setNegativeButton(R.string.close, null)
			.setNeutralButton(R.string.library_group_delete) { _, _ -> confirmDeleteLibraryGroup(group.id, group.title) }
			.setPositiveButton(android.R.string.ok) { _, _ ->
				viewLifecycleScope.launch {
					runCatching {
						viewModel.updateLibraryGroup(
							group.id,
							titleInput.text?.toString().orEmpty(),
							coverInput.text?.toString(),
						)
					}.onSuccess {
						Toast.makeText(requireContext(), R.string.library_group_updated, Toast.LENGTH_SHORT).show()
					}.onFailure(::showLibraryGroupError)
				}
			}.show()
	}

	private fun confirmDeleteLibraryGroup(groupId: Long, title: String) {
		MaterialAlertDialogBuilder(requireContext())
			.setTitle(title)
			.setMessage(R.string.library_group_delete_confirm)
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(R.string.library_group_delete) { _, _ ->
				viewLifecycleScope.launch {
					runCatching { viewModel.deleteLibraryGroup(groupId) }
						.onFailure(::showLibraryGroupError)
				}
			}.show()
	}

	private fun showLibraryGroupOrderDialog(groupId: Long) {
		viewLifecycleScope.launch {
			val loaded = runCatching { viewModel.getLibraryGroupManageItems(groupId) }
				.onFailure(::showLibraryGroupError)
				.getOrNull() ?: return@launch
			val (group, members) = loaded
			if (members.size < 2) return@launch
			val adapter = LibraryGroupManageAdapter(members) { member ->
				showLibraryGroupMemberActions(group.id, member)
			}
			val recyclerView = RecyclerView(requireContext()).apply {
				layoutManager = LinearLayoutManager(requireContext())
				this.adapter = adapter
				setPadding(0, resources.getDimensionPixelOffset(R.dimen.margin_small), 0, 0)
			}
			ItemTouchHelper(
				object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
					override fun onMove(
						recyclerView: RecyclerView,
						viewHolder: RecyclerView.ViewHolder,
						target: RecyclerView.ViewHolder,
					): Boolean = adapter.move(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)

					override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit
				},
			).attachToRecyclerView(recyclerView)

			val dialog = MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.library_group_manage_order)
				.setMessage(R.string.library_group_drag_hint)
				.setView(recyclerView)
				.setNegativeButton(R.string.close, null)
				.setNeutralButton(R.string.library_group_natural_sort, null)
				.setPositiveButton(R.string.library_group_save_order, null)
				.show()
			dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
				adapter.naturalSort()
			}
			dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
				viewLifecycleScope.launch {
					runCatching { viewModel.reorderLibraryGroup(group.id, adapter.snapshotIds()) }
						.onSuccess { dialog.dismiss() }
						.onFailure(::showLibraryGroupError)
				}
			}
		}
	}

	private fun showLibraryGroupError(error: Throwable) {
		if (!isAdded) return
		Toast.makeText(
			requireContext(),
			error.message?.takeIf { it.isNotBlank() } ?: getString(R.string.library_group_error),
			Toast.LENGTH_LONG,
		).show()
	}

	private inner class ModernLibrarySurfaceDecoration : RecyclerView.ItemDecoration() {
		private val density = resources.displayMetrics.density
		private val fillInset = density
		private val strokeInset = density * 1.5f
		private val minCardHeight = MIN_CARD_HEIGHT_DP * density
		private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
		private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
		private val bounds = RectF()
		private var radius = MiyorareVisualTokens.RADIUS_CARD_DP * density
		private var shouldDrawStroke = true

		fun update(level: VisualEffectLevel, surface: Int, primary: Int, tertiary: Int) {
			val fillFraction = when (level) {
				VisualEffectLevel.LIGHT -> 0.035f
				VisualEffectLevel.BALANCED -> 0.10f
				VisualEffectLevel.FULL -> 0.16f
			}
			val accent = ColorUtils.blendARGB(primary, tertiary, 0.30f)
			fillPaint.color = ColorUtils.blendARGB(surface, accent, fillFraction)
			strokePaint.color = ColorUtils.setAlphaComponent(
				accent,
				when (level) {
					VisualEffectLevel.LIGHT -> 24
					VisualEffectLevel.BALANCED -> 62
					VisualEffectLevel.FULL -> 96
				},
			)
			strokePaint.strokeWidth = density
			shouldDrawStroke = level != VisualEffectLevel.LIGHT
			radius = MiyorareVisualTokens.RADIUS_CARD_DP * density
		}

		override fun onDraw(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
			for (index in 0 until parent.childCount) {
				val child = parent.getChildAt(index)
				if (child.id == R.id.empty_view || child.height < minCardHeight) continue
				bounds.set(
					child.left + fillInset + child.translationX,
					child.top + fillInset + child.translationY,
					child.right - fillInset + child.translationX,
					child.bottom - fillInset + child.translationY,
				)
				canvas.drawRoundRect(bounds, radius, radius, fillPaint)
			}
		}

		override fun onDrawOver(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
			if (!shouldDrawStroke) return
			for (index in 0 until parent.childCount) {
				val child = parent.getChildAt(index)
				if (child.id == R.id.empty_view || child.height < minCardHeight) continue
				bounds.set(
					child.left + strokeInset + child.translationX,
					child.top + strokeInset + child.translationY,
					child.right - strokeInset + child.translationX,
					child.bottom - strokeInset + child.translationY,
				)
				canvas.drawRoundRect(bounds, radius, radius, strokePaint)
			}
		}
	}

	private data class CoverPrefetchCandidate(
		val item: MangaListModel,
		val coverUrl: String,
		val key: String,
	)

	private enum class PendingScroll { TOP, BOTTOM }

	companion object {

		const val NO_ID = 0L
		private const val COVER_PREFETCH_BATCH = 24
		private const val MAX_REMEMBERED_COVERS = 256
		private const val MIN_CARD_HEIGHT_DP = 56f
		private const val MODERN_EMPTY_STATE_ICON_DP = 220f

		fun newInstance(categoryId: Long) = FavouritesListFragment().withArgs(1) {
			putLong(AppRouter.KEY_ID, categoryId)
		}
	}
}
