package org.koitharu.kotatsu.favourites.groups.ui

import android.content.DialogInterface
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.text.HtmlCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
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
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerManga
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerMangaInfo
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.settings.compose.DropSauceTheme

@AndroidEntryPoint
class LibraryGroupDetailsFragment : BaseFragment<FragmentLibraryGroupDetailsBinding>() {

	private val viewModel by viewModels<LibraryGroupDetailsViewModel>()
	private var activeEditDraft: EditGroupDraft? = null

	private val pickGroupCoverLauncher = registerForActivityResult(
		ActivityResultContracts.PickVisualMedia(),
	) { uri ->
		if (uri == null || !isAdded) return@registerForActivityResult
		lifecycleScope.launch {
			runCatching { viewModel.setLocalCover(uri.toString()) }
				.onSuccess {
					activeEditDraft?.cover?.setText(viewModel.state.value.group?.coverUrl.orEmpty())
					showMessage(R.string.library_group_cover_updated)
				}
				.onFailure { showMessage(R.string.library_group_cover_error) }
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
					onEditGroup = ::openEditGroup,
					onManageTimeline = ::openTimelineEditor,
					onManagePlacement = ::openCategoryPlacement,
					onManageTracking = ::openTrackingManager,
					onSyncTracking = ::syncTrackingProgress,
					onDeleteGroup = ::confirmDeleteGroup,
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
			.state(ReaderState(chapterId = chapter.id, page = 0, scroll = 0))
			.libraryGroup(viewModel.groupId, viewModel.favouriteSpace.dbValue)
			.build()
		router.openReader(intent)
	}

	private fun openEditGroup() {
		val group = viewModel.state.value.group ?: return
		val content = layoutInflater.inflate(R.layout.dialog_library_group_edit, null, false)
		val draft = EditGroupDraft(
			title = content.findViewById(R.id.edit_group_title),
			alternativeTitle = content.findViewById(R.id.edit_group_alternative_title),
			author = content.findViewById(R.id.edit_group_author),
			artist = content.findViewById(R.id.edit_group_artist),
			description = content.findViewById(R.id.edit_group_description),
			cover = content.findViewById(R.id.edit_group_cover_url),
			metadataSource = group.metadataSource,
			metadataTargetId = group.metadataTargetId,
		)
		draft.title.setText(group.title)
		draft.alternativeTitle.setText(group.alternativeTitle.orEmpty())
		draft.author.setText(group.author.orEmpty())
		draft.artist.setText(group.artist.orEmpty())
		draft.description.setText(group.description.orEmpty())
		draft.cover.setText(group.coverUrl.orEmpty())
		content.findViewById<MaterialButton>(R.id.button_choose_group_cover).setOnClickListener {
			activeEditDraft = draft
			openLocalCoverPicker()
		}
		content.findViewById<MaterialButton>(R.id.button_import_group_metadata).setOnClickListener {
			openMetadataImport(draft)
		}

		val dialog = MaterialAlertDialogBuilder(requireContext())
			.setTitle(R.string.library_group_edit)
			.setView(content)
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(android.R.string.ok, null)
			.create()
		activeEditDraft = draft
		dialog.setOnShowListener {
			dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
				val title = draft.title.text?.toString()?.trim().orEmpty()
				if (title.isEmpty()) {
					draft.title.error = getString(R.string.library_group_title_hint)
					return@setOnClickListener
				}
				dialog.getButton(DialogInterface.BUTTON_POSITIVE).isEnabled = false
				viewLifecycleOwner.lifecycleScope.launch {
					runCatching {
						viewModel.updateMetadata(
							title = title,
							alternativeTitle = draft.alternativeTitle.text?.toString(),
							author = draft.author.text?.toString(),
							artist = draft.artist.text?.toString(),
							description = draft.description.text?.toString(),
							coverUrl = draft.cover.text?.toString(),
							metadataSource = draft.metadataSource,
							metadataTargetId = draft.metadataTargetId,
						)
					}.onSuccess {
						dialog.dismiss()
						showMessage(R.string.library_group_updated)
					}.onFailure {
						dialog.getButton(DialogInterface.BUTTON_POSITIVE).isEnabled = true
						showMessage(R.string.library_group_error)
					}
				}
			}
		}
		dialog.setOnDismissListener { if (activeEditDraft === draft) activeEditDraft = null }
		dialog.show()
	}

	private fun openLocalCoverPicker() {
		if (!pickGroupCoverLauncher.tryLaunch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))) {
			showMessage(R.string.operation_not_supported)
		}
	}

	private fun openMetadataImport(draft: EditGroupDraft) {
		val services = viewModel.availableTrackingServices()
		if (services.isEmpty()) {
			showMessage(R.string.library_group_metadata_no_service)
			return
		}
		MaterialAlertDialogBuilder(requireContext())
			.setTitle(R.string.library_group_metadata_import_source)
			.setItems(services.map { getString(it.titleResId) }.toTypedArray()) { _, which ->
				promptTrackingSearch(services[which]) { target ->
					loadMetadataPreview(draft, services[which], target)
				}
			}
			.setNegativeButton(android.R.string.cancel, null)
			.show()
	}

	private fun loadMetadataPreview(draft: EditGroupDraft, service: ScrobblerService, target: ScrobblerManga) {
		viewLifecycleOwner.lifecycleScope.launch {
			val info = runCatching { viewModel.getTrackingMetadata(service, target.id) }
				.getOrElse {
					showMessage(R.string.library_group_metadata_error)
					return@launch
				}
			showMetadataPreview(draft, service, target, info)
		}
	}

	private fun showMetadataPreview(
		draft: EditGroupDraft,
		service: ScrobblerService,
		target: ScrobblerManga,
		info: ScrobblerMangaInfo,
	) {
		val description = HtmlCompat.fromHtml(info.descriptionHtml, HtmlCompat.FROM_HTML_MODE_LEGACY)
			.toString().trim().takeIf { it.isNotEmpty() }
		val candidates = buildList {
			add(MetadataCandidate(getString(R.string.library_group_title_hint), info.name, true) { draft.title.setText(it) })
			target.altName?.takeIf { it.isNotBlank() }?.let { value ->
				add(MetadataCandidate(getString(R.string.library_group_alternative_title), value, false) { draft.alternativeTitle.setText(it) })
			}
			info.author?.takeIf { it.isNotBlank() }?.let { value ->
				add(MetadataCandidate(getString(R.string.library_group_author), value, true) { draft.author.setText(it) })
			}
			info.artist?.takeIf { it.isNotBlank() }?.let { value ->
				add(MetadataCandidate(getString(R.string.library_group_artist), value, true) { draft.artist.setText(it) })
			}
			description?.let { value ->
				add(MetadataCandidate(getString(R.string.library_group_description), value, true) { draft.description.setText(it) })
			}
			info.cover.takeIf { it.isNotBlank() }?.let { value ->
				add(MetadataCandidate(getString(R.string.library_group_metadata_cover), value, false) { draft.cover.setText(it) })
			}
		}
		val checked = BooleanArray(candidates.size) { candidates[it].defaultChecked }
		MaterialAlertDialogBuilder(requireContext())
			.setTitle(R.string.library_group_metadata_preview)
			.setMultiChoiceItems(candidates.map { "${it.label}: ${it.value.take(90)}" }.toTypedArray(), checked) { _, which, isChecked ->
				checked[which] = isChecked
			}
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(R.string.library_group_metadata_apply) { _, _ ->
				candidates.indices.filter { checked[it] }.forEach { index -> candidates[index].applyValue(candidates[index].value) }
				draft.metadataSource = service.id
				draft.metadataTargetId = target.id
				showMessage(R.string.library_group_metadata_updated)
			}
			.show()
	}

	private fun promptTrackingSearch(service: ScrobblerService, onSelected: (ScrobblerManga) -> Unit) {
		val input = EditText(requireContext()).apply {
			inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
			hint = getString(R.string.library_group_metadata_search_hint)
			setText(viewModel.state.value.group?.title.orEmpty())
			setSelection(text.length)
		}
		MaterialAlertDialogBuilder(requireContext())
			.setTitle(R.string.library_group_tracking_search)
			.setView(input)
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(R.string.search) { _, _ ->
				val query = input.text?.toString()?.trim().orEmpty()
				if (query.isEmpty()) return@setPositiveButton
				viewLifecycleOwner.lifecycleScope.launch {
					val results = runCatching { viewModel.searchTracking(service, query) }
						.getOrElse {
							showMessage(R.string.library_group_tracking_error)
							return@launch
						}
					if (results.isEmpty()) {
						showMessage(R.string.library_group_metadata_no_results)
						return@launch
					}
					MaterialAlertDialogBuilder(requireContext())
						.setTitle(service.titleResId)
						.setItems(results.map { result ->
							buildString {
								append(result.name)
								result.altName?.takeIf { it.isNotBlank() }?.let { append("\n").append(it) }
							}
						}.toTypedArray()) { _, which -> onSelected(results[which]) }
						.setNegativeButton(android.R.string.cancel, null)
						.show()
				}
			}
			.show()
	}

	private fun openTrackingManager() {
		val current = viewModel.state.value.tracking
		if (current.isEmpty()) {
			openAddTracking()
			return
		}
		val labels = current.map { getString(it.service.titleResId) } + getString(R.string.library_group_tracking_add)
		MaterialAlertDialogBuilder(requireContext())
			.setTitle(R.string.library_group_tracking_manage)
			.setItems(labels.toTypedArray()) { _, which ->
				if (which == current.size) openAddTracking() else openTrackingActions(current[which].service)
			}
			.setNegativeButton(android.R.string.cancel, null)
			.show()
	}

	private fun openTrackingActions(service: ScrobblerService) {
		val actions = arrayOf(getString(R.string.library_group_tracking_refresh), getString(R.string.library_group_tracking_unlink))
		MaterialAlertDialogBuilder(requireContext())
			.setTitle(service.titleResId)
			.setItems(actions) { _, which ->
				when (which) {
					0 -> viewLifecycleOwner.lifecycleScope.launch {
						runCatching { viewModel.refreshTracking(service) }
							.onSuccess { showMessage(R.string.library_group_tracking_updated) }
							.onFailure { showMessage(R.string.library_group_tracking_error) }
					}
					1 -> viewLifecycleOwner.lifecycleScope.launch {
						runCatching { viewModel.unlinkTracking(service) }
							.onSuccess { showMessage(R.string.library_group_tracking_unlinked) }
							.onFailure { showMessage(R.string.library_group_tracking_error) }
					}
				}
			}
			.show()
	}

	private fun openAddTracking() {
		val linked = viewModel.state.value.tracking.mapTo(HashSet()) { it.service }
		val services = viewModel.availableTrackingServices().filterNot { it in linked }
		if (services.isEmpty()) {
			showMessage(R.string.library_group_tracking_no_service)
			return
		}
		MaterialAlertDialogBuilder(requireContext())
			.setTitle(R.string.library_group_tracking_add)
			.setItems(services.map { getString(it.titleResId) }.toTypedArray()) { _, which ->
				val service = services[which]
				promptTrackingSearch(service) { target ->
					viewLifecycleOwner.lifecycleScope.launch {
						runCatching { viewModel.linkTracking(service, target) }
							.onSuccess { showMessage(R.string.library_group_tracking_updated) }
							.onFailure { showMessage(R.string.library_group_tracking_error) }
					}
				}
			}
			.setNegativeButton(android.R.string.cancel, null)
			.show()
	}

	private fun syncTrackingProgress() {
		viewLifecycleOwner.lifecycleScope.launch {
			runCatching { viewModel.syncTrackingProgress() }
				.onSuccess { showMessage(R.string.library_group_tracking_synced) }
				.onFailure { showMessage(R.string.library_group_tracking_error) }
		}
	}

	private fun confirmDeleteGroup() {
		MaterialAlertDialogBuilder(requireContext())
			.setTitle(R.string.library_group_delete)
			.setMessage(R.string.library_group_delete_confirm)
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(R.string.library_group_delete) { _, _ ->
				viewLifecycleOwner.lifecycleScope.launch {
					runCatching { viewModel.deleteGroup() }
						.onSuccess { requireActivity().finish() }
						.onFailure { showMessage(R.string.library_group_error) }
				}
			}
			.show()
	}

	private fun openCategoryPlacement() {
		lifecycleScope.launch {
			val categories = runCatching { viewModel.getPlacementCategories() }
				.getOrElse { showMessage(R.string.library_group_placement_error); return@launch }
			if (categories.isEmpty()) {
				showMessage(R.string.library_group_placement_empty)
				return@launch
			}
			val group = viewModel.state.value.group ?: return@launch
			val checked = BooleanArray(categories.size) { index -> categories[index].id in group.categoryIds }
			MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.library_group_placement)
				.setMessage(R.string.library_group_placement_summary)
				.setMultiChoiceItems(categories.map { it.title }.toTypedArray(), checked) { _, which, isChecked ->
					if (which in checked.indices) checked[which] = isChecked
				}
				.setNegativeButton(android.R.string.cancel, null)
				.setPositiveButton(android.R.string.ok) { _, _ ->
					val selected = categories.indices.filter { checked[it] }.map { categories[it].id }
					lifecycleScope.launch {
						runCatching { viewModel.setCategoryPlacement(selected) }
							.onSuccess { showMessage(R.string.library_group_placement_saved) }
							.onFailure { showMessage(R.string.library_group_placement_error) }
					}
				}
				.show()
		}
	}

	private fun openTimelineEditor() {
		viewLifecycleOwner.lifecycleScope.launch {
			val items = runCatching { viewModel.prepareTimelineEditor() }
				.getOrElse { showMessage(R.string.library_group_timeline_error); return@launch }
			if (items.isEmpty()) {
				showMessage(R.string.library_group_timeline_empty)
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
				override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean =
					adapter.move(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
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
			dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener { adapter.naturalSort() }
			dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
				val saveButton = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
				saveButton.isEnabled = false
				viewLifecycleOwner.lifecycleScope.launch {
					runCatching { viewModel.saveTimeline(adapter.snapshot()) }
						.onSuccess { dialog.dismiss(); showMessage(R.string.library_group_timeline_saved) }
						.onFailure { saveButton.isEnabled = true; showMessage(R.string.library_group_timeline_error) }
				}
			}
		}
		dialog.show()
	}

	private fun showMessage(message: Int) {
		view?.let { Snackbar.make(it, message, Snackbar.LENGTH_SHORT).show() }
	}

	private data class EditGroupDraft(
		val title: TextInputEditText,
		val alternativeTitle: TextInputEditText,
		val author: TextInputEditText,
		val artist: TextInputEditText,
		val description: TextInputEditText,
		val cover: TextInputEditText,
		var metadataSource: Int?,
		var metadataTargetId: Long?,
	)

	private data class MetadataCandidate(
		val label: String,
		val value: String,
		val defaultChecked: Boolean,
		val applyValue: (String) -> Unit,
	)
}
