package org.koitharu.kotatsu.download.ui.list

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.util.TypedValue
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
	{ inflater, parent ->
		val layoutRes = if (isModernDownloads) R.layout.item_download else R.layout.item_download_legacy
		ItemDownloadBinding.bind(inflater.inflate(layoutRes, parent, false))
	},
) {

	val percentPattern = context.resources.getString(R.string.percent_string_pattern)
	val density = context.resources.displayMetrics.density
	val modernStrokeWidth = density.roundToInt().coerceAtLeast(1)
	val modernControlRadius = (24f * density).roundToInt()
	val modernCardRadius = 26f * density
	val modernSurface = context.getThemeColor(materialR.attr.colorSurface, Color.TRANSPARENT)
	val modernPrimary = context.getThemeColor(appcompatR.attr.colorPrimary, modernSurface)
	val modernError = context.getThemeColor(android.R.attr.colorError, Color.RED)
	val modernOnSurfaceVariant = context.getThemeColor(materialR.attr.colorOnSurfaceVariant, modernPrimary)
	val goldenAmber = Color.rgb(255, 182, 84)
	val goldenCyan = Color.rgb(69, 230, 244)
	val goldenRed = Color.rgb(255, 100, 122)
	val goldenText = Color.rgb(245, 243, 250)
	val goldenMuted = Color.rgb(168, 173, 191)
	val goldenGlass = Color.rgb(6, 13, 22)
	val goldenGlassMuted = Color.rgb(8, 12, 19)
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
		binding.textViewTitle.setTextColor(goldenText)
		binding.textViewDetails.setTextColor(goldenMuted)
		binding.buttonExpand.imageTintList = ColorStateList.valueOf(Color.rgb(157, 118, 255))
		binding.buttonPause.cornerRadius = modernControlRadius
		binding.buttonResume.cornerRadius = modernControlRadius
		binding.buttonSkip.cornerRadius = modernControlRadius
		binding.buttonSkipAll.cornerRadius = modernControlRadius
		binding.buttonCancel.cornerRadius = modernControlRadius
		val primarySurface = ColorUtils.blendARGB(goldenGlass, goldenAmber, 0.18f)
		for (button in arrayOf(binding.buttonPause, binding.buttonResume)) {
			button.backgroundTintList = ColorStateList.valueOf(primarySurface)
			button.strokeWidth = modernStrokeWidth
			button.strokeColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(goldenAmber, 184))
			button.setTextColor(goldenAmber)
			button.iconTint = ColorStateList.valueOf(goldenAmber)
		}
		for (button in arrayOf(binding.buttonSkip, binding.buttonSkipAll, binding.buttonCancel)) {
			button.backgroundTintList = ColorStateList.valueOf(ColorUtils.setAlphaComponent(goldenGlass, 214))
			button.strokeWidth = modernStrokeWidth
			button.strokeColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(Color.rgb(126, 158, 202), 143))
			button.setTextColor(goldenText)
			button.iconTint = ColorStateList.valueOf(goldenText)
		}
	}

	fun alphaColor(color: Int, alpha: Float): Int =
		ColorUtils.setAlphaComponent(color, (255f * alpha).roundToInt().coerceIn(0, 255))

	fun applyModernGeometry(item: DownloadItemModel) {
		if (!isModernDownloads) return
		val hero = item.workState == WorkInfo.State.RUNNING
		val widthDp = if (hero) 100 else 82
		val heightDp = if (hero) 148 else 86
		binding.constraintLayout.minimumHeight = ((if (hero) 196 else 108) * density).roundToInt()
		binding.imageViewCover.layoutParams = binding.imageViewCover.layoutParams.apply {
			width = (widthDp * density).roundToInt()
			height = (heightDp * density).roundToInt()
		}
		binding.textViewTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (hero) 21f else 17f)
		binding.textViewStatus.compoundDrawablePadding = (6f * density).roundToInt()
	}

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
			item.workState == WorkInfo.State.RUNNING && hasError -> goldenRed
			item.workState == WorkInfo.State.RUNNING && item.isPaused -> goldenAmber
			item.workState == WorkInfo.State.RUNNING -> goldenCyan
			item.workState == WorkInfo.State.SUCCEEDED -> goldenCyan
			item.workState == WorkInfo.State.FAILED || item.workState == WorkInfo.State.CANCELLED -> goldenRed
			else -> goldenMuted
		}
		val hero = item.workState == WorkInfo.State.RUNNING
		val cardBase = if (hero) goldenGlass else goldenGlassMuted
		val surfaceMix = when {
			hero -> 0.055f
			item.workState == WorkInfo.State.SUCCEEDED -> 0.035f
			item.workState == WorkInfo.State.FAILED || item.workState == WorkInfo.State.CANCELLED -> 0.028f
			else -> 0.02f
		}
		binding.root.strokeColor = alphaColor(stateColor, if (hero) 0.62f else 0.32f)
		binding.root.setCardBackgroundColor(ColorUtils.blendARGB(cardBase, stateColor, surfaceMix))
		binding.textViewTitle.setTextColor(goldenText)
		binding.textViewStatus.setTextColor(stateColor)
		binding.textViewStatus.backgroundTintList = ColorStateList.valueOf(alphaColor(stateColor, 0.16f))
		binding.textViewDetails.setTextColor(goldenMuted)
		binding.textViewPercent.setTextColor(if (hero) goldenCyan else stateColor)
		binding.textViewPercent.backgroundTintList = ColorStateList.valueOf(alphaColor(if (hero) goldenCyan else stateColor, 0.13f))
		binding.textViewStatus.compoundDrawableTintList = ColorStateList.valueOf(stateColor)
		val statusIcon = when {
			item.workState == WorkInfo.State.RUNNING && item.isPaused -> R.drawable.ic_action_pause
			item.workState == WorkInfo.State.RUNNING -> R.drawable.ic_downloading
			item.workState == WorkInfo.State.SUCCEEDED -> R.drawable.ic_check
			item.workState == WorkInfo.State.FAILED || item.workState == WorkInfo.State.CANCELLED -> R.drawable.ic_cancel_multiple
			else -> R.drawable.ic_downloading
		}
		binding.textViewStatus.setCompoundDrawablesRelativeWithIntrinsicBounds(statusIcon, 0, 0, 0)
		if (binding.progressBar.isVisible) {
			binding.progressBar.setIndicatorColor(goldenCyan)
			binding.progressBar.trackColor = alphaColor(Color.rgb(126, 158, 202), 0.16f)
		}
	}
	fun renderPendingAction(statusRes: Int) {
		binding.textViewStatus.setText(statusRes)
		binding.progressBar.isEnabled = false
		binding.textViewDetails.isVisible = false
		binding.buttonCancel.isVisible = false
		binding.buttonResume.isVisible = false
		binding.buttonSkip.isVisible = false
		binding.buttonSkipAll.isVisible = false
		binding.buttonPause.isVisible = false
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
		// Every Download item represents one or more chapters, so the expand affordance can be
		// rendered without resolving chapter metadata. Only subscribe to the expensive chapter flow
		// while the user has this row expanded. The previous behavior subscribed every visible row,
		// which could trigger remote getDetails() + Local lookup while the outer RecyclerView scrolled.
		binding.buttonExpand.isGone = false
		if (item.isExpanded) {
			if (chaptersJob == null || payloads.isEmpty()) {
				chaptersJob?.cancel()
				chaptersJob = lifecycleOwner.lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) {
					item.chapters.collect { chapters ->
						chaptersAdapter.emit(chapters)
						scrollToCurrentChapter()
					}
				}
			} else if (ListModelDiffCallback.PAYLOAD_CHECKED_CHANGED in payloads) {
				binding.recyclerViewChapters.post {
					scrollToCurrentChapter()
				}
			}
		} else {
			chaptersJob?.cancel()
			chaptersJob = null
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
					binding.textViewPercent.text = if (isModernDownloads) {
						"$safeProgress / $safeMax"
					} else {
						val safePercent = safeProgress / safeMax.toFloat()
						percentPattern.format((safePercent * 100f).format(1))
					}
				}
				val detailsParts = ArrayList<CharSequence>(3)
				if (hasKnownProgress) {
					val pages = context.getString(R.string.pages).lowercase()
					detailsParts += "$safeProgress $pages"
					detailsParts += "$safeMax $pages"
				}
				when {
					item.isPaused && item.error != null -> item.getErrorMessage(context)?.let(detailsParts::add)
					item.isStuck -> detailsParts += context.getString(R.string.stuck)
					else -> item.getEtaString()?.let(detailsParts::add)
				}
				binding.textViewDetails.textAndVisible = detailsParts
					.takeIf { it.isNotEmpty() }
					?.joinToString("   •   ")
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
				if (item.chaptersDownloaded > 0 || item.max > 0) {
					val parts = ArrayList<CharSequence>(2)
					if (item.chaptersDownloaded > 0) {
						parts += context.resources.getQuantityStringSafe(
							R.plurals.chapters,
							item.chaptersDownloaded,
							item.chaptersDownloaded,
						)
					}
					if (item.max > 0) parts += "${item.max} ${context.getString(R.string.pages).lowercase()}"
					binding.textViewDetails.text = parts.joinToString("   •   ")
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
				val failureMeta = buildString {
					if (item.max > 0) {
						append(item.progress.coerceAtLeast(0))
						append(" / ")
						append(item.max)
						append(' ')
						append(context.getString(R.string.pages).lowercase())
					}
					item.getErrorMessage(context)?.let { error ->
						if (isNotEmpty()) append("   •   ")
						append(error)
					}
				}
				binding.textViewDetails.textAndVisible = failureMeta.takeIf { it.isNotEmpty() }
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
				if (item.max > 0) {
					binding.textViewDetails.text = "${item.progress.coerceAtLeast(0)} / ${item.max} ${context.getString(R.string.pages).lowercase()}"
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
		}
		when (item.uiAction) {
			DownloadUiAction.PAUSING -> renderPendingAction(R.string.download_pausing)
			DownloadUiAction.RESUMING -> renderPendingAction(R.string.download_resuming)
			DownloadUiAction.CANCELLING -> renderPendingAction(R.string.download_cancelling)
			null -> Unit
		}
		applyModernGeometry(item)
		applyModernStateVisuals(item)
	}
}
