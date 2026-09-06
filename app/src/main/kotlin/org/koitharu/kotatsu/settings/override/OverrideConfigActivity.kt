package org.koitharu.kotatsu.settings.override

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.filterNotNull
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.BaseActivity
import org.koitharu.kotatsu.core.ui.model.MangaOverride
import org.koitharu.kotatsu.core.util.ext.consumeAll
import org.koitharu.kotatsu.core.util.ext.getDisplayMessage
import org.koitharu.kotatsu.core.util.ext.isHttpUrl
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.core.util.ext.tryLaunch
import org.koitharu.kotatsu.databinding.ActivityOverrideEditBinding
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.util.ifNullOrEmpty
import org.koitharu.kotatsu.picker.ui.PageImagePickContract
import com.google.android.material.R as materialR

@AndroidEntryPoint
class OverrideConfigActivity : BaseActivity<ActivityOverrideEditBinding>(), View.OnClickListener,
	ActivityResultCallback<Uri?> {

	private val viewModel: OverrideConfigViewModel by viewModels()
	private var originalTitle: String? = null

	private val pickCoverFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument(), this)
	private val pickPageLauncher = registerForActivityResult(PageImagePickContract(), this)
	private val pickCoverGalleryLauncher = registerForActivityResult(
		ActivityResultContracts.PickVisualMedia(),
	) { uri -> if (uri != null) viewModel.updateCover(uri.toString()) }

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(ActivityOverrideEditBinding.inflate(layoutInflater))
		setDisplayHomeAsUp(isEnabled = true, showUpAsClose = true)
		viewBinding.buttonDone.setOnClickListener(this)
		viewBinding.buttonFetchTrackerMetadata.setOnClickListener(this)
		viewBinding.buttonPickGallery.setOnClickListener(this)
		viewBinding.buttonPickFile.setOnClickListener(this)
		viewBinding.buttonPickPage.setOnClickListener(this)
		viewBinding.buttonPickUrl.setOnClickListener(this)
		viewBinding.buttonResetCover.setOnClickListener(this)
		viewBinding.layoutName.setEndIconOnClickListener { viewBinding.editName.text?.clear() }
		viewBinding.layoutAuthor.setEndIconOnClickListener { viewBinding.editAuthor.text?.clear() }
		viewBinding.layoutArtist.setEndIconOnClickListener { viewBinding.editArtist.text?.clear() }
		viewBinding.layoutDescription.setEndIconOnClickListener { viewBinding.editDescription.text?.clear() }
		viewBinding.editName.doAfterTextChanged { updateOriginalNamePreview() }
		viewModel.data.filterNotNull().observe(this, ::onDataChanged)
		viewModel.onSaved.observeEvent(this) { onDataSaved() }
		viewModel.onTrackerMetadata.observeEvent(this, ::onTrackerMetadataLoaded)
		viewModel.onTrackerMetadataUnavailable.observeEvent(this) {
			Snackbar.make(
				viewBinding.buttonFetchTrackerMetadata,
				R.string.tracker_metadata_not_available,
				Snackbar.LENGTH_LONG,
			).show()
		}
		viewModel.isLoading.observe(this, ::onLoadingStateChanged)
		viewModel.onError.observeEvent(this, ::onError)
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val typeMask = WindowInsetsCompat.Type.systemBars()
		val barsInsets = insets.getInsets(typeMask)
		viewBinding.root.setPadding(barsInsets.left, barsInsets.top, barsInsets.right, barsInsets.bottom)
		return insets.consumeAll(typeMask)
	}

	override fun onActivityResult(result: Uri?) {
		if (result != null) {
			if (result.host?.startsWith(packageName) != true) {
				contentResolver.takePersistableUriPermission(result, Intent.FLAG_GRANT_READ_URI_PERMISSION)
			}
			viewModel.updateCover(result.toString())
		}
	}

	override fun onClick(v: View) {
		when (v.id) {
			R.id.button_done -> viewModel.save(
				title = viewBinding.editName.text?.toString()?.trim(),
				author = viewBinding.editAuthor.text?.toString()?.trim(),
				artist = viewBinding.editArtist.text?.toString()?.trim(),
				description = viewBinding.editDescription.text?.toString()?.trim(),
			)
			R.id.button_fetch_tracker_metadata -> viewModel.fetchTrackerMetadata()
			R.id.button_reset_cover -> viewModel.updateCover(null)
			R.id.button_pick_gallery -> {
				if (!pickCoverGalleryLauncher.tryLaunch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))) {
					Snackbar.make(viewBinding.imageViewCover, R.string.operation_not_supported, Snackbar.LENGTH_SHORT).show()
				}
			}
			R.id.button_pick_file -> {
				if (!pickCoverFileLauncher.tryLaunch(arrayOf("image/*"))) {
					Snackbar.make(viewBinding.imageViewCover, R.string.operation_not_supported, Snackbar.LENGTH_SHORT).show()
				}
			}
			R.id.button_pick_page -> pickPageLauncher.launch(viewModel.data.value?.first)
			R.id.button_pick_url -> showCoverUrlDialog()
		}
	}

	private fun showCoverUrlDialog() {
		val editText = com.google.android.material.textfield.TextInputEditText(this).apply {
			setHint(R.string.url)
			inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
			setText(viewModel.data.value?.second?.coverUrl?.takeIf { it.isHttpUrl() })
		}
		val padding = resources.getDimensionPixelOffset(R.dimen.margin_normal)
		val container = android.widget.FrameLayout(this).apply {
			setPadding(padding, padding / 2, padding, 0)
			addView(editText)
		}
		MaterialAlertDialogBuilder(this)
			.setTitle(R.string.pick_cover_url)
			.setView(container)
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(android.R.string.ok) { _, _ ->
				val url = editText.text?.toString()?.trim().orEmpty()
				if (url.isHttpUrl()) viewModel.updateCover(url)
				else Snackbar.make(viewBinding.imageViewCover, R.string.invalid_url, Snackbar.LENGTH_SHORT).show()
			}
			.show()
	}

	private fun onTrackerMetadataLoaded(candidates: List<TrackerMetadataCandidate>) {
		if (candidates.isEmpty()) return
		if (candidates.size == 1) {
			showTrackerMetadataPreview(candidates.first())
			return
		}
		val services = candidates.map { getString(it.service.titleResId) }.toTypedArray()
		MaterialAlertDialogBuilder(this)
			.setTitle(R.string.tracker_metadata_choose_service)
			.setItems(services) { _, which ->
				candidates.getOrNull(which)?.let(::showTrackerMetadataPreview)
			}
			.setNegativeButton(android.R.string.cancel, null)
			.show()
	}

	private fun showTrackerMetadataPreview(candidate: TrackerMetadataCandidate) {
		val fields = buildList {
			candidate.title?.let {
				add(MetadataPreviewField(MetadataField.TITLE, R.string.tracker_metadata_field_title, it))
			}
			candidate.author?.let {
				add(MetadataPreviewField(MetadataField.AUTHOR, R.string.tracker_metadata_field_author, it))
			}
			candidate.artist?.let {
				add(MetadataPreviewField(MetadataField.ARTIST, R.string.tracker_metadata_field_artist, it))
			}
			candidate.description?.let {
				add(MetadataPreviewField(MetadataField.DESCRIPTION, R.string.tracker_metadata_field_description, it))
			}
			candidate.coverUrl?.let {
				add(MetadataPreviewField(MetadataField.COVER, R.string.tracker_metadata_field_cover, it))
			}
		}
		if (fields.isEmpty()) {
			Snackbar.make(
				viewBinding.buttonFetchTrackerMetadata,
				R.string.tracker_metadata_not_available,
				Snackbar.LENGTH_LONG,
			).show()
			return
		}
		val checked = BooleanArray(fields.size) { true }
		val labels = fields.map { field ->
			"${getString(field.labelRes)}\n${field.value.toMetadataPreview()}"
		}.toTypedArray()
		MaterialAlertDialogBuilder(this)
			.setTitle(
				getString(
					R.string.tracker_metadata_preview_title,
					getString(candidate.service.titleResId),
				),
			)
			.setMessage(R.string.tracker_metadata_preview_message)
			.setMultiChoiceItems(labels, checked) { _, which, isChecked ->
				if (which in checked.indices) checked[which] = isChecked
			}
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(R.string.tracker_metadata_apply_selected) { _, _ ->
				fields.forEachIndexed { index, field ->
					if (checked[index]) applyTrackerMetadataField(field)
				}
				Snackbar.make(
					viewBinding.buttonFetchTrackerMetadata,
					R.string.tracker_metadata_applied,
					Snackbar.LENGTH_LONG,
				).show()
			}
			.show()
	}

	private fun applyTrackerMetadataField(field: MetadataPreviewField) {
		when (field.kind) {
			MetadataField.TITLE -> viewBinding.editName.setText(field.value)
			MetadataField.AUTHOR -> viewBinding.editAuthor.setText(field.value)
			MetadataField.ARTIST -> viewBinding.editArtist.setText(field.value)
			MetadataField.DESCRIPTION -> viewBinding.editDescription.setText(field.value)
			MetadataField.COVER -> viewModel.updateCover(field.value)
		}
	}

	private fun String.toMetadataPreview(): String {
		val compact = replace(Regex("\\s+"), " ").trim()
		return if (compact.length <= METADATA_PREVIEW_LENGTH) {
			compact
		} else {
			compact.take(METADATA_PREVIEW_LENGTH - 1) + "…"
		}
	}

	private fun onDataChanged(data: Pair<Manga, MangaOverride>) {
		val (manga, override) = data
		originalTitle = manga.title
		viewBinding.imageViewCover.setImageAsync(override.coverUrl.ifNullOrEmpty { manga.coverUrl }, manga)
		viewBinding.layoutName.placeholderText = manga.title
		viewBinding.layoutAuthor.placeholderText = manga.authors.joinToString(", ").takeIf { it.isNotBlank() }
		if (viewBinding.editName.tag == null) {
			viewBinding.editName.setText(override.title)
			viewBinding.editAuthor.setText(override.author)
			viewBinding.editArtist.setText(override.artist)
			viewBinding.editDescription.setText(override.description)
			viewBinding.editName.tag = true
		}
		val hasCustomCover = !override.coverUrl.isNullOrEmpty()
		viewBinding.buttonResetCover.isEnabled = hasCustomCover
		viewBinding.layoutOriginalCover.isVisible = hasCustomCover
		if (hasCustomCover) viewBinding.imageViewOriginalCover.setImageAsync(manga.coverUrl, manga)
		updateOriginalNamePreview()
	}

	private fun updateOriginalNamePreview() {
		val original = originalTitle?.trim().orEmpty()
		val current = viewBinding.editName.text?.toString()?.trim().orEmpty()
		val changed = original.isNotEmpty() && current.isNotEmpty() && current != original
		setNameResetEnabled(changed)
		viewBinding.textViewOriginalName.isVisible = changed
		if (changed) {
			viewBinding.textViewOriginalName.text = getString(
				R.string.inline_preference_pattern,
				getString(R.string.original_name),
				original,
			)
		}
	}

	private fun setNameResetEnabled(isEnabled: Boolean) {
		viewBinding.layoutName.findViewById<View>(materialR.id.text_input_end_icon)?.let { icon ->
			icon.isEnabled = isEnabled
			icon.alpha = if (isEnabled) 1f else DISABLED_ICON_ALPHA
		}
	}

	private fun onError(e: Throwable) {
		viewBinding.textViewError.text = e.getDisplayMessage(resources)
		viewBinding.textViewError.isVisible = true
	}

	private fun onLoadingStateChanged(isLoading: Boolean) {
		viewBinding.buttonDone.isEnabled = !isLoading
		viewBinding.buttonFetchTrackerMetadata.isEnabled = !isLoading
		viewBinding.editName.isEnabled = !isLoading
		viewBinding.editAuthor.isEnabled = !isLoading
		viewBinding.editArtist.isEnabled = !isLoading
		viewBinding.editDescription.isEnabled = !isLoading
		if (isLoading) viewBinding.textViewError.isVisible = false
	}

	private fun onDataSaved() {
		setResult(RESULT_OK)
		finish()
	}

	private enum class MetadataField {
		TITLE,
		AUTHOR,
		ARTIST,
		DESCRIPTION,
		COVER,
	}

	private data class MetadataPreviewField(
		val kind: MetadataField,
		val labelRes: Int,
		val value: String,
	)

	private companion object {
		const val DISABLED_ICON_ALPHA = 0.38f
		const val METADATA_PREVIEW_LENGTH = 180
	}
}
