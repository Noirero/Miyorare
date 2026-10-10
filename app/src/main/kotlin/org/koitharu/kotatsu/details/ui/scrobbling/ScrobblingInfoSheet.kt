package org.koitharu.kotatsu.details.ui.scrobbling

import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.widget.PopupMenu
import androidx.core.text.method.LinkMovementMethodCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import com.google.android.material.chip.Chip
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.ui.dialog.buildAlertDialog
import org.koitharu.kotatsu.core.ui.sheet.BaseAdaptiveSheet
import org.koitharu.kotatsu.core.ui.widgets.StarRatingView
import org.koitharu.kotatsu.core.util.ext.adjustPopupMenuIcons
import org.koitharu.kotatsu.core.util.ext.consume
import org.koitharu.kotatsu.core.util.ext.getDisplayMessage
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.core.util.ext.sanitize
import org.koitharu.kotatsu.core.util.ext.setOptionalIconsVisibleCompat
import org.koitharu.kotatsu.databinding.SheetScrobblingBinding
import org.koitharu.kotatsu.details.ui.DetailsViewModel
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblingInfo
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblingStatus
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService

@AndroidEntryPoint
class ScrobblingInfoSheet :
	BaseAdaptiveSheet<SheetScrobblingBinding>(),
	View.OnClickListener,
	PopupMenu.OnMenuItemClickListener {

	private val viewModel by activityViewModels<DetailsViewModel>()
	private var scrobblerIndex: Int = -1

	private var menu: PopupMenu? = null

	// Guard so programmatically reflecting the loaded status doesn't fire an update back to the server.
	private var isBindingStatus = false

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		scrobblerIndex = requireArguments().getInt(AppRouter.KEY_INDEX, scrobblerIndex)
	}

	override fun onCreateViewBinding(inflater: LayoutInflater, container: ViewGroup?): SheetScrobblingBinding {
		return SheetScrobblingBinding.inflate(inflater, container, false)
	}

	override fun onViewBindingCreated(binding: SheetScrobblingBinding, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)
		viewModel.scrobblingInfo.observe(viewLifecycleOwner, ::onScrobblingInfoChanged)
		viewModel.onError.observeEvent(viewLifecycleOwner) {
			Toast.makeText(binding.root.context, it.getDisplayMessage(binding.root.resources), Toast.LENGTH_SHORT)
				.show()
		}

		buildStatusChips(binding)
		binding.ratingBar.onRatingChangeListener = ::onRatingChanged
		binding.buttonMenu.setOnClickListener(this)
		binding.buttonMangaupdatesVolume.setOnClickListener(this)
		viewModel.trackerVolume.observe(viewLifecycleOwner) { state ->
			binding.buttonMangaupdatesVolume.isVisible = state.isRequested && viewModel.scrobblingInfo.value.getOrNull(scrobblerIndex)?.scrobbler == ScrobblerService.MANGAUPDATES
			binding.buttonMangaupdatesVolume.isEnabled = !state.isLoading
			binding.buttonMangaupdatesVolume.text = when {
				state.isError -> getString(R.string.mangaupdates_volume_unavailable)
				state.volume != null -> getString(R.string.mangaupdates_volume, state.volume)
				else -> getString(R.string.mangaupdates_volume_edit)
			}
		}
		binding.imageViewCover.setOnClickListener(this)
		binding.textViewDescription.movementMethod = LinkMovementMethodCompat.getInstance()

		menu = PopupMenu(binding.root.context, binding.buttonMenu).apply {
			inflate(R.menu.opt_scrobbling)
			setForceShowIcon(true)
			this.menu.setOptionalIconsVisibleCompat(true)
			this.menu.adjustPopupMenuIcons(binding.root.resources)
			setOnMenuItemClickListener(this@ScrobblingInfoSheet)
		}
	}

	override fun onDestroyView() {
		viewModel.clearTrackerVolume()
		super.onDestroyView()
		menu = null
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val typeMask = WindowInsetsCompat.Type.systemBars()
		viewBinding?.root?.updatePadding(
			bottom = insets.getInsets(typeMask).bottom,
		)
		return insets.consume(v, typeMask, bottom = true)
	}

	private fun onRatingChanged(rating: Float) {
		viewBinding?.textViewRatingValue?.text = formatRating(rating)
		viewModel.updateScrobbling(
			index = scrobblerIndex,
			rating = rating / StarRatingView.MAX_RATING,
			status = currentStatus(),
		)
	}

	override fun onClick(v: View) {
		when (v.id) {
			R.id.button_mangaupdates_volume -> {
				val state = viewModel.trackerVolume.value
				if (state.isError || state.volume == null) viewModel.requestTrackerVolume(force = true) else showVolumeEditor(state.volume)
			}
			R.id.button_menu -> menu?.show()
			R.id.imageView_cover -> router.openImage(
				url = viewModel.scrobblingInfo.value.getOrNull(scrobblerIndex)?.coverUrl ?: return,
				source = null,
				anchor = v,
			)
		}
	}

	private fun buildStatusChips(binding: SheetScrobblingBinding) {
		val inflater = LayoutInflater.from(binding.chipGroupStatus.context)
		for (status in ScrobblingStatus.entries) {
			val chip = inflater.inflate(
				R.layout.chip_scrobbling_status,
				binding.chipGroupStatus,
				false,
			) as Chip
			chip.id = status.ordinal + 1
			chip.tag = status
			chip.setText(status.labelResId)
			chip.setChipIconResource(status.iconResId)
			binding.chipGroupStatus.addView(chip)
		}
		binding.chipGroupStatus.setOnCheckedStateChangeListener { _, checkedIds ->
			if (isBindingStatus) {
				return@setOnCheckedStateChangeListener
			}
			val status = checkedIds.firstOrNull()?.let { ScrobblingStatus.entries.getOrNull(it - 1) } ?: return@setOnCheckedStateChangeListener
			viewModel.updateScrobbling(
				index = scrobblerIndex,
				rating = binding.ratingBar.rating / StarRatingView.MAX_RATING,
				status = status,
			)
		}
	}

	private fun currentStatus(): ScrobblingStatus? {
		val checkedId = viewBinding?.chipGroupStatus?.checkedChipId ?: return null
		return ScrobblingStatus.entries.getOrNull(checkedId - 1)
	}

	private fun onScrobblingInfoChanged(scrobblings: List<ScrobblingInfo>) {
		val scrobbling = scrobblings.getOrNull(scrobblerIndex)
		if (scrobbling == null) {
			dismissAllowingStateLoss()
			return
		}
		val binding = viewBinding ?: return
		val mangaUpdates = scrobbling.scrobbler == ScrobblerService.MANGAUPDATES
		binding.buttonMangaupdatesVolume.isVisible = mangaUpdates && viewModel.trackerVolume.value.isRequested
		binding.chipGroupStatus.findViewById<Chip>(ScrobblingStatus.RE_READING.ordinal + 1)?.isVisible = !mangaUpdates
		if (mangaUpdates) viewModel.requestTrackerVolume()
		binding.textViewTitle.text = scrobbling.title
		binding.textViewService.setText(scrobbling.scrobbler.titleResId)
		binding.ratingBar.rating = scrobbling.rating * StarRatingView.MAX_RATING
		binding.textViewRatingValue.text = formatRating(binding.ratingBar.rating)
		binding.textViewDescription.text = scrobbling.description?.sanitize()
		isBindingStatus = true
		val statusId = scrobbling.status?.let { it.ordinal + 1 }
		if (statusId != null) {
			binding.chipGroupStatus.check(statusId)
		} else {
			binding.chipGroupStatus.clearCheck()
		}
		isBindingStatus = false
		binding.imageViewLogo.contentDescription = getString(scrobbling.scrobbler.titleResId)
		binding.imageViewLogo.setImageResource(scrobbling.scrobbler.iconResId)
		binding.imageViewCover.setImageAsync(scrobbling.coverUrl)
	}

	private fun formatRating(stars: Float): String {
		return if (stars <= 0f) {
			"–"
		} else {
			"%.1f".format(stars)
		}
	}

	private fun showVolumeEditor(volume: Int) {
		val input = EditText(requireContext()).apply { inputType = InputType.TYPE_CLASS_NUMBER; setText(volume.toString()); selectAll() }
		val dialog = buildAlertDialog(requireContext()) {
			setTitle(R.string.mangaupdates_volume_edit)
			setView(input)
			setPositiveButton(android.R.string.ok, null)
			setNegativeButton(android.R.string.cancel, null)
		}
		dialog.setOnShowListener {
			dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
				val value = input.text.toString().toIntOrNull()?.takeIf { it >= 0 }
				if (value == null) input.error = getString(R.string.mangaupdates_volume_invalid) else { viewModel.updateTrackerVolume(value); dialog.dismiss() }
			}
		}
		dialog.show()
	}

	override fun onMenuItemClick(item: MenuItem): Boolean {
		when (item.itemId) {
			R.id.action_browser -> {
				val url = viewModel.scrobblingInfo.value.getOrNull(scrobblerIndex)?.externalUrl ?: return false
				if (!router.openExternalBrowser(url, getString(R.string.open_in_browser))) {
					Snackbar.make(
						viewBinding?.textViewDescription ?: return false,
						R.string.operation_not_supported,
						Snackbar.LENGTH_SHORT,
					).show()
				}
			}

			R.id.action_unregister -> {
				viewModel.unregisterScrobbling(scrobblerIndex)
				dismiss()
			}

			R.id.action_edit -> {
				val manga = viewModel.manga.value ?: return false
				val scrobblerService = viewModel.scrobblingInfo.value.getOrNull(scrobblerIndex)?.scrobbler
				activity?.router?.showScrobblingSelectorSheet(manga, scrobblerService)
				dismiss()
			}
		}
		return true
	}
}

