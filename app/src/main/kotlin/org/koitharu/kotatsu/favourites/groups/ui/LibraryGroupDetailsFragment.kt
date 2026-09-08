package org.koitharu.kotatsu.favourites.groups.ui

import android.content.DialogInterface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.ReaderIntent
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.ui.BaseFragment
import org.koitharu.kotatsu.core.util.ext.tryLaunch
import org.koitharu.kotatsu.databinding.FragmentLibraryGroupDetailsBinding
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.reader.ui.ReaderState
import org.koitharu.kotatsu.settings.compose.DropSauceTheme

@AndroidEntryPoint
class LibraryGroupDetailsFragment : BaseFragment<FragmentLibraryGroupDetailsBinding>() {

	private val viewModel by viewModels<LibraryGroupDetailsViewModel>()
	private val pickGroupCoverLauncher = registerForActivityResult(
		ActivityResultContracts.PickVisualMedia(),
	) { uri ->
		if (uri == null || !isAdded) return@registerForActivityResult
		lifecycleScope.launch {
			runCatching { viewModel.setLocalCover(uri.toString()) }
				.onSuccess { showTimelineMessage(R.string.library_group_cover_updated) }
				.onFailure { showTimelineMessage(R.string.library_group_cover_error) }
		}
	}

	override fun onCreateViewBinding(
		inflater: LayoutInflater,
		container: ViewGroup?,
	) = FragmentLibraryGroupDetailsBinding.inflate(inflater, container, false)

	override fun onViewBindingCreated(
		binding: FragmentLibraryGroupDetailsBinding,
		savedInstanceState: android.os.Bundle?,
	) {
		binding.composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		binding.composeView.setContent {
			DropSauceTheme {
				val state by viewModel.state.collectAsState()
				LaunchedEffect(state.group?.title) {
					state.group?.title?.let { title -> requireActivity().title = title }
				}
				LibraryGroupDetailsScreen(
					state = state,
					onRetry = viewModel::reload,
					onToggleMember = viewModel::toggleMember,
					onRefreshMember = viewModel::refreshMember,
					onOpenMember = { member -> router.openDetails(member.manga) },
					onChapterClick = ::openChapter,
					onManageTimeline = ::openTimelineEditor,
					onPickCover = ::openLocalCoverPicker,
					onManagePlacement = ::openCategoryPlacement,
				)
			}
		}
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
		requireViewBinding().composeView.updatePadding(
			left = bars.left,
			right = bars.right,
			bottom = bars.bottom,
		)
		return insets
	}

	private fun openChapter(member: LibraryGroupDetailsMemberUi, chapter: MangaChapter) {
		val manga = member.manga
		val intent = ReaderIntent.Builder(requireContext())
			.manga(manga)
			.branch(chapter.branch)
			.state(
				ReaderState(
					chapterId = chapter.id,
					page = 0,
					scroll = 0,
				),
			)
			.libraryGroup(viewModel.groupId)
			.build()
		router.openReader(intent)
	}

	private fun openLocalCoverPicker() {
		if (!pickGroupCoverLauncher.tryLaunch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))) {
			showTimelineMessage(R.string.operation_not_supported)
		}
	}

	private fun openCategoryPlacement() {
		lifecycleScope.launch {
			val categories = runCatching { viewModel.getPlacementCategories() }
				.getOrElse {
					showTimelineMessage(R.string.library_group_placement_error)
					return@launch
				}
			if (categories.isEmpty()) {
				showTimelineMessage(R.string.library_group_placement_empty)
				return@launch
			}
			val group = viewModel.state.value.group ?: return@launch
			val checked = BooleanArray(categories.size) { index -> categories[index].id in group.categoryIds }
			MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.library_group_placement)
				.setMessage(R.string.library_group_placement_summary)
				.setMultiChoiceItems(
					categories.map { it.title }.toTypedArray(),
					checked,
				) { _, which, isChecked ->
					if (which in checked.indices) checked[which] = isChecked
				}
				.setNegativeButton(android.R.string.cancel, null)
				.setPositiveButton(android.R.string.ok) { _, _ ->
					val selected = categories.indices
						.filter { checked[it] }
						.map { categories[it].id }
					lifecycleScope.launch {
						runCatching { viewModel.setCategoryPlacement(selected) }
							.onSuccess { showTimelineMessage(R.string.library_group_placement_saved) }
							.onFailure { showTimelineMessage(R.string.library_group_placement_error) }
					}
				}
				.show()
		}
	}

	private fun openTimelineEditor() {
		viewLifecycleOwner.lifecycleScope.launch {
			val items = runCatching { viewModel.prepareTimelineEditor() }
				.getOrElse {
					showTimelineMessage(R.string.library_group_timeline_error)
					return@launch
				}
			if (items.isEmpty()) {
				showTimelineMessage(R.string.library_group_timeline_empty)
				return@launch
			}
			showTimelineDialog(items)
		}
	}

	private fun showTimelineDialog(items: List<LibraryGroupTimelineEditorItem>) {
		val context = requireContext()
		val adapter = LibraryGroupTimelineAdapter(items)
		val padding = (16 * resources.displayMetrics.density).toInt()
		val maxListHeight = (resources.displayMetrics.heightPixels * 0.42f).toInt()
		val list = object : RecyclerView(context) {
			override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
				val cappedHeightSpec = View.MeasureSpec.makeMeasureSpec(maxListHeight, View.MeasureSpec.AT_MOST)
				super.onMeasure(widthMeasureSpec, cappedHeightSpec)
			}
		}.apply {
			layoutManager = LinearLayoutManager(context)
			this.adapter = adapter
			setPadding(padding, 0, padding, 0)
			clipToPadding = false
			isVerticalScrollBarEnabled = true
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
		).attachToRecyclerView(list)

		val dialog = MaterialAlertDialogBuilder(context)
			.setTitle(R.string.library_group_timeline)
			.setMessage(R.string.library_group_timeline_summary)
			.setView(list)
			.setNegativeButton(android.R.string.cancel, null)
			.setNeutralButton(R.string.library_group_natural_sort, null)
			.setPositiveButton(R.string.library_group_save_order, null)
			.create()
		dialog.setOnShowListener {
			dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
				adapter.naturalSort()
			}
			dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
				val saveButton = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
				saveButton.isEnabled = false
				viewLifecycleOwner.lifecycleScope.launch {
					runCatching { viewModel.saveTimeline(adapter.snapshot()) }
						.onSuccess {
							dialog.dismiss()
							showTimelineMessage(R.string.library_group_timeline_saved)
						}
						.onFailure {
							saveButton.isEnabled = true
							showTimelineMessage(R.string.library_group_timeline_error)
						}
				}
			}
		}
		dialog.show()
	}

	private fun showTimelineMessage(message: Int) {
		view?.let { Snackbar.make(it, message, Snackbar.LENGTH_SHORT).show() }
	}
}
