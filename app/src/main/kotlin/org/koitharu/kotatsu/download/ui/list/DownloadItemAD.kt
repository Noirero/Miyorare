package org.koitharu.kotatsu.download.ui.list

import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.core.graphics.ColorUtils
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.transition.ChangeBounds
import androidx.transition.TransitionManager
import androidx.work.WorkInfo
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.BaseListAdapter
import org.koitharu.kotatsu.core.ui.MiyorareVisualTokens
import org.koitharu.kotatsu.core.util.ext.getQuantityStringSafe
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import org.koitharu.kotatsu.core.util.ext.setContentDescriptionAndTooltip
import org.koitharu.kotatsu.core.util.ext.textAndVisible
import org.koitharu.kotatsu.databinding.ItemDownloadBinding
import org.koitharu.kotatsu.download.ui.list.chapters.DownloadChapter
import org.koitharu.kotatsu.download.ui.list.chapters.downloadChapterAD
import org.koitharu.kotatsu.list.ui.ListModelDiffCallback
import org.koitharu.kotatsu.list.ui.adapter.ListItemType
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.parsers.util.format
import java.util.UUID
import kotlin.math.roundToInt
import androidx.appcompat.R as appcompatR
import com.google.android.material.R as materialR

fun downloadItemAD(
	lifecycleOwner: LifecycleOwner,
	listener: DownloadItemListener,
	isModernDownloads: Boolean,
) = adapterDelegateViewBinding<DownloadItemModel, ListModel, ItemDownloadBinding>(
	{ inflater, parent -> ItemDownloadBinding.inflate(inflater, parent, false) },
) {

	val percentPattern = context.resources.getString(R.string.percent_string_pattern)
	val density = context.resources.displayMetrics.density
	val modernStrokeWidth = density.roundToInt().coerceAtLeast(1)
	val modernControlRadius = (MiyorareVisualTokens.RADIUS_CONTROL_DP * density).roundToInt()
	val modernCardRadius = MiyorareVisualTokens.RADIUS_CARD_DP * density
	val modernSurface = context.getThemeColor(materialR.attr.colorSurface, Color.TRANSPARENT)
	val modernPrimary = context.getThemeColor(appcompatR.attr.colorPrimary, modernSurface)
	val modernTertiary = context.getThemeColor(materialR.attr.colorTertiary, modernPrimary)
	val modernError = context.getThemeColor(android.R.attr.colorError, Color.RED)
	val modernOnSurfaceVariant = context.getThemeColor(materialR.attr.colorOnSurfaceVariant, modernPrimary)
	var chaptersJob: Job? = null
	// Tracks the last bound expanded state for THIS view holder so we only animate a real
	// user toggle, not the initial bind or a recycle.
	var lastExpanded: Boolean? = null
	var lastModernVisualId: UUID? = null
	var lastModernVisualState: WorkInfo.State? = null
	var lastModernVisualPaused = false
	var lastModernVisualHasError = false

	if (isModernDownloads) {
		binding.root.radius = modernCardRadius
		binding.root.strokeWidth = modernStrokeWidth
		binding.buttonPause.cornerRadius = modernControlRadius
		binding.buttonResume.cornerRadius = modernControlRadius
		binding.buttonSkip.cornerRadius = modernControlRadius
		binding.buttonSkipAll.cornerRadius = modernControlRadius
		binding.buttonCancel.cornerRadius = modernControlRadius
	}

	fun alphaColor(color: Int, alpha: Float): Int =
		ColorUtils.setAlphaComponent(color, (255f * alpha).roundToInt().coerceIn(0, 255))

	fun applyModernStateVisuals(item: DownloadItemModel) {
		if (!isModernDownloads) return
		val hasError = item.error != null
		if (
			lastModernVisualId == item.id &&
			lastModernVisualState == item.workState &&
			lastModernVisualPaused == item.isPaused &&
			lastModernVisualHasError == hasError
		) {
			return
		}
		lastModernVisualId = item.id
		lastModernVisualState = item.workState
		lastModernVisualPaused = item.isPaused
		lastModernVisualHasError = hasError

		val stateColor = when {
			item.workState == WorkInfo.State.RUNNING && hasError -> modernError
			item.workState == WorkInfo.State.RUNNING && item.isPaused -> modernTertiary
			item.workState == WorkInfo.State.RUNNING -> modernPrimary
			item.workState == WorkInfo.State.SUCCEEDED -> modernTertiary
			item.workState == WorkInfo.State.FAILED -> modernError
			else -> modernOnSurfaceVariant
		}
		val strokeAlpha = when {
			item.workState == WorkInfo.State.FAILED || hasError -> 0.52f
			item.workState == WorkInfo.State.RUNNING && !item.isPaused -> 0.42f
			item.workState == WorkInfo.State.RUNNING && item.isPaused -> 0.34f
			item.workState == WorkInfo.State.SUCCEEDED -> 0.24f
			else -> 0.18f
		}
		val surfaceMix = when {
			item.workState == WorkInfo.State.RUNNING && !item.isPaused -> 0.08f
			item.workState == WorkInfo.State.RUNNING || item.workState == WorkInfo.State.FAILED -> 0.06f
			item.workState == WorkInfo.State.SUCCEEDED -> 0.04f
			else -> 0.02f
		}
		binding.root.strokeColor = alphaColor(stateColor, strokeAlpha)
		binding.root.setCardBackgroundColor(ColorUtils.blendARGB(modernSurface, stateColor, surfaceMix))
		binding.textViewStatus.setTextColor(stateColor)
		binding.textViewPercent.setTextColor(
			if (item.workState == WorkInfo.State.RUNNING && !item.isPaused) modernPrimary else stateColor,
		)
		if (binding.progressBar.isVisible) {
			binding.progressBar.setIndicatorColor(stateColor)
			binding.progressBar.trackColor = alphaColor(modernOnSurfaceVariant, 0.12f)
		}
	}

	val clickListener = object : View.OnClickListener, View.OnLongClickListener {
		override fun onClick(v: View) {
			when (v.id) {
				R.id.button_cancel -> listener.onCancelClick(item)
				R.id.button_resume -> listener.onResumeClick(item)
				R.id.button_skip -> listener.onSkipClick(item)
				R.id.button_skip_all -> listener.onSkipAllClick(item)
				R.id.button_pause -> listener.onPauseClick(item)
				R.id.button_expand -> listener.onExpandClick(item)
				else -> listener.onItemClick(item, v)
			}
		}

		override fun onLongClick(v: View): Boolean {
			return listener.onItemLongClick(item, v)
		}
	}
	val chaptersAdapter = BaseListAdapter<DownloadChapter>()
		.addDelegate(ListItemType.CHAPTER_LIST, downloadChapterAD())

	binding.recyclerViewChapters.adapter = chaptersAdapter
	binding.buttonCancel.setOnClickListener(clickListener)
	binding.buttonPause.setOnClickListener(clickListener)
	binding.buttonResume.setOnClickListener(clickListener)
	binding.buttonSkip.setOnClickListener(clickListener)
	binding.buttonSkipAll.setOnClickListener(clickListener)
	binding.buttonExpand.setOnClickListener(clickListener)
	itemView.setOnClickListener(clickListener)
	itemView.setOnLongClickListener(clickListener)

	fun scrollToCurrentChapter() {
		val rv = binding.recyclerViewChapters
		if (!rv.isVisible) {
			return
		}
		val chapters = chaptersAdapter.items
		if (chapters.isEmpty()) {
			return
		}
		val targetPos = item.chaptersDownloaded.coerceIn(chapters.indices)
		(rv.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(targetPos, rv.height / 3)
	}

	onViewRecycled {
		lastExpanded = null
		lastModernVisualId = null
		lastModernVisualState = null
		chaptersJob?.cancel()
		chaptersJob = null
	}

	bind { payloads ->
		// Progress ticks use a lightweight payload; avoid restarting a cover request or rewriting a
		// stable title for every worker update.
		if (payloads.isEmpty()) {
			binding.textViewTitle.text = item.manga?.title ?: getString(R.string.unknown)
			binding.imageViewCover.setImageAsync(item.manga?.coverUrl, item.manga)
		}
		if (chaptersJob == null || payloads.isEmpty()) {
			chaptersJob?.cancel()
			chaptersJob = lifecycleOwner.lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) {
				item.chapters.collect { chapters ->
					binding.buttonExpand.isGone = chapters.isNullOrEmpty()
					chaptersAdapter.emit(chapters)
					scrollToCurrentChapter()
				}
			}
		} else if (ListModelDiffCallback.PAYLOAD_CHECKED_CHANGED in payloads) {
			binding.recyclerViewChapters.post {
				scrollToCurrentChapter()
			}
		}
		binding.buttonExpand.isChecked = item.isExpanded
		binding.buttonExpand.setContentDescriptionAndTooltip(if (item.isExpanded) R.string.collapse else R.string.expand)
		if (lastExpanded != null && lastExpanded != item.isExpanded) {
			// Animate only a user-triggered expand/collapse. Modern uses the shared finite motion token;
			// Classic keeps its existing timing.
			(itemView.parent as? ViewGroup)?.let { parent ->
				TransitionManager.beginDelayedTransition(
					parent,
					ChangeBounds().apply {
						duration = if (isModernDownloads) {
							MiyorareVisualTokens.MOTION_STANDARD_MS.toLong()
						} else {
							250L
						}
						interpolator = AccelerateDecelerateInterpolator()
					},
				)
			}
		}
		lastExpanded = item.isExpanded
		binding.recyclerViewChapters.isVisible = item.isExpanded
		when (item.workState) {
			WorkInfo.State.ENQUEUED,
			WorkInfo.State.BLOCKED -> {
				binding.textViewStatus.setText(R.string.queued)
				binding.progressBar.isIndeterminate = false
				binding.progressBar.isVisible = false
				binding.progressBar.isEnabled = true
				binding.textViewPercent.isVisible = false
				binding.textViewDetails.isVisible = false
				binding.buttonCancel.isVisible = true
				binding.buttonResume.isVisible = false
				binding.buttonSkip.isVisible = false
				binding.buttonSkipAll.isVisible = false
				binding.buttonPause.isVisible = false
			}

			WorkInfo.State.RUNNING -> {
				binding.textViewStatus.setText(
					if (item.isPaused) R.string.paused else R.string.manga_downloading_,
				)
				val hasKnownProgress = !item.isIndeterminate && item.max > 0
				binding.progressBar.isIndeterminate = item.isIndeterminate
				binding.progressBar.isVisible = true
				val safeMax = item.max.coerceAtLeast(1)
				val safeProgress = item.progress.coerceIn(0, safeMax)
				binding.progressBar.max = safeMax
				binding.progressBar.isEnabled = !item.isPaused
				binding.progressBar.setProgressCompat(safeProgress, payloads.isNotEmpty())
				binding.textViewPercent.isVisible = hasKnownProgress
				if (hasKnownProgress) {
					val safePercent = safeProgress / safeMax.toFloat()
					binding.textViewPercent.text = percentPattern.format((safePercent * 100f).format(1))
				}
				binding.textViewDetails.textAndVisible = when {
					item.isPaused -> item.getErrorMessage(context)
					item.isStuck -> context.getString(R.string.stuck)
					else -> item.getEtaString()
				}
				binding.buttonCancel.isVisible = true
				binding.buttonResume.isVisible = item.isPaused
				binding.buttonResume.setText(if (item.error == null) R.string.resume else R.string.retry)
				binding.buttonSkip.isVisible = item.isPaused && item.error != null
				binding.buttonSkipAll.isVisible = item.isPaused && item.error != null
				binding.buttonPause.isVisible = item.canPause
			}

			WorkInfo.State.SUCCEEDED -> {
				binding.textViewStatus.setText(R.string.download_complete)
				binding.progressBar.isIndeterminate = false
				binding.progressBar.isVisible = false
				binding.progressBar.isEnabled = true
				binding.textViewPercent.isVisible = false
				if (item.chaptersDownloaded > 0) {
					binding.textViewDetails.text = context.resources.getQuantityStringSafe(
						R.plurals.chapters,
						item.chaptersDownloaded,
						item.chaptersDownloaded,
					)
					binding.textViewDetails.isVisible = true
				} else {
					binding.textViewDetails.isVisible = false
				}
				binding.buttonCancel.isVisible = false
				binding.buttonResume.isVisible = false
				binding.buttonSkip.isVisible = false
				binding.buttonSkipAll.isVisible = false
				binding.buttonPause.isVisible = false
			}

			WorkInfo.State.FAILED -> {
				binding.textViewStatus.setText(R.string.error_occurred)
				binding.progressBar.isIndeterminate = false
				binding.progressBar.isVisible = false
				binding.progressBar.isEnabled = true
				binding.textViewPercent.isVisible = false
				binding.textViewDetails.textAndVisible = item.getErrorMessage(context)
				binding.buttonCancel.isVisible = false
				binding.buttonResume.isVisible = false
				binding.buttonSkip.isVisible = false
				binding.buttonSkipAll.isVisible = false
				binding.buttonPause.isVisible = false
			}

			WorkInfo.State.CANCELLED -> {
				binding.textViewStatus.setText(R.string.canceled)
				binding.progressBar.isIndeterminate = false
				binding.progressBar.isVisible = false
				binding.progressBar.isEnabled = true
				binding.textViewPercent.isVisible = false
				binding.textViewDetails.isVisible = false
				binding.buttonCancel.isVisible = false
				binding.buttonResume.isVisible = false
				binding.buttonSkip.isVisible = false
				binding.buttonSkipAll.isVisible = false
				binding.buttonPause.isVisible = false
			}
		}
		applyModernStateVisuals(item)
	}
}
