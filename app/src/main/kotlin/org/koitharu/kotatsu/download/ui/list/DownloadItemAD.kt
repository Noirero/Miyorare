package org.koitharu.kotatsu.download.ui.list

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
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
import org.koitharu.kotatsu.core.ui.miyorareViewPaletteFromPreferences
import org.koitharu.kotatsu.core.util.FileSize
import org.koitharu.kotatsu.core.util.ext.findActivity
import org.koitharu.kotatsu.core.util.ext.getQuantityStringSafe
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import org.koitharu.kotatsu.core.util.ext.setContentDescriptionAndTooltip
import org.koitharu.kotatsu.core.util.ext.textAndVisible
import org.koitharu.kotatsu.databinding.ItemDownloadBinding
import org.koitharu.kotatsu.download.ui.list.chapters.DownloadChapter
import org.koitharu.kotatsu.download.ui.list.chapters.downloadChapterAD
import org.koitharu.kotatsu.favourites.data.EXTRA_FAVOURITE_SPACE
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
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
	val privateDownloads = context.findActivity()?.intent?.getIntExtra(
		EXTRA_FAVOURITE_SPACE,
		FavouriteSpace.NORMAL.dbValue,
	) == FavouriteSpace.PRIVATE.dbValue
	val modernPalette = context.miyorareViewPaletteFromPreferences(privateFavourites = privateDownloads)
	val modernSurface = modernPalette?.surfaceContainerHigh
		?: context.getThemeColor(materialR.attr.colorSurface, Color.TRANSPARENT)
	val modernPrimary = modernPalette?.primary
		?: context.getThemeColor(appcompatR.attr.colorPrimary, modernSurface)
	val modernSecondary = modernPalette?.secondary
		?: context.getThemeColor(materialR.attr.colorSecondary, modernPrimary)
	val modernAccent = modernPalette?.accent
		?: context.getThemeColor(materialR.attr.colorTertiary, modernSecondary)
	val modernError = context.getThemeColor(android.R.attr.colorError, Color.RED)
	val modernOnSurface = modernPalette?.onSurface
		?: context.getThemeColor(materialR.attr.colorOnSurface, modernPrimary)
	val modernOnSurfaceVariant = modernPalette?.onSurfaceVariant
		?: context.getThemeColor(materialR.attr.colorOnSurfaceVariant, modernOnSurface)
	val modernOutline = modernPalette?.outlineVariant
		?: context.getThemeColor(materialR.attr.colorOutlineVariant, modernOnSurfaceVariant)
	val modernButton = modernPalette?.button ?: modernPrimary
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
		binding.textViewTitle.setTextColor(modernOnSurface)
		binding.textViewDetails.setTextColor(modernOnSurfaceVariant)
		binding.buttonExpand.imageTintList = ColorStateList.valueOf(modernAccent)
		binding.buttonPause.cornerRadius = modernControlRadius
		binding.buttonResume.cornerRadius = modernControlRadius
		binding.buttonSkip.cornerRadius = modernControlRadius
		binding.buttonSkipAll.cornerRadius = modernControlRadius
		binding.buttonCancel.cornerRadius = modernControlRadius
		val primarySurface = ColorUtils.blendARGB(
			ColorUtils.setAlphaComponent(modernSurface, 224),
			modernButton,
			0.18f,
		)
		for (button in arrayOf(binding.buttonPause, binding.buttonResume)) {
			button.backgroundTintList = ColorStateList.valueOf(primarySurface)
			button.strokeWidth = modernStrokeWidth
			button.strokeColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(modernButton, 184))
			button.setTextColor(modernButton)
			button.iconTint = ColorStateList.valueOf(modernButton)
		}
		for (button in arrayOf(binding.buttonSkip, binding.buttonSkipAll, binding.buttonCancel)) {
			button.backgroundTintList = ColorStateList.valueOf(ColorUtils.setAlphaComponent(modernSurface, 214))
			button.strokeWidth = modernStrokeWidth
			button.strokeColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(modernOutline, 154))
			button.setTextColor(modernOnSurface)
			button.iconTint = ColorStateList.valueOf(modernOnSurface)
		}
	}
	fun alphaColor(color: Int, alpha: Float): Int =
		ColorUtils.setAlphaComponent(color, (255f * alpha).roundToInt().coerceIn(0, 255))

	fun applyModernGeometry(item: DownloadItemModel) {
		if (!isModernDownloads) return
		val hero = item.workState == WorkInfo.State.RUNNING
		val widthDp = if (hero) 100 else 82
		val heightDp = if (hero) 148 else 86
		binding.constraintLayout.minimumHeight = ((if (hero) 202 else 108) * density).roundToInt()
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

		// Download state owns the main semantic colour. A paused job stays paused-coloured even when
		// an error message is present; the error is rendered separately instead of replacing state.
		val stateColor = when {
			item.workState == WorkInfo.State.RUNNING && item.isPaused -> modernPrimary
			item.workState == WorkInfo.State.RUNNING && hasError -> modernError
			item.workState == WorkInfo.State.RUNNING -> modernSecondary
			item.workState == WorkInfo.State.SUCCEEDED -> modernSecondary
			item.workState == WorkInfo.State.FAILED || item.workState == WorkInfo.State.CANCELLED -> modernError
			else -> modernOnSurfaceVariant
		}
		val hero = item.workState == WorkInfo.State.RUNNING
		val cardBase = ColorUtils.setAlphaComponent(modernSurface, if (hero) 226 else 214)
		val ambient = if (hero) {
			ColorUtils.blendARGB(modernPrimary, modernSecondary, 0.48f)
		} else {
			modernAccent
		}
		binding.root.strokeColor = alphaColor(modernOutline, if (hero) 0.58f else 0.34f)
		binding.root.setCardBackgroundColor(ColorUtils.blendARGB(cardBase, ambient, if (hero) 0.045f else 0.018f))
		binding.downloadLocalGlow.background = GradientDrawable().apply {
			shape = GradientDrawable.OVAL
			gradientType = GradientDrawable.RADIAL_GRADIENT
			colors = intArrayOf(alphaColor(stateColor, if (hero) 0.18f else 0.09f), Color.TRANSPARENT)
			gradientRadius = 112f * density
			setGradientCenter(0.78f, 0.20f)
		}
		binding.textViewTitle.setTextColor(modernOnSurface)
		binding.textViewStatus.setTextColor(stateColor)
		binding.textViewStatus.backgroundTintList = ColorStateList.valueOf(alphaColor(stateColor, 0.15f))
		binding.textViewDetails.setTextColor(if (hasError) modernError else modernOnSurfaceVariant)
		binding.textViewPercent.setTextColor(if (hero) modernSecondary else stateColor)
		binding.textViewPercent.backgroundTintList = ColorStateList.valueOf(
			alphaColor(if (hero) modernSecondary else stateColor, 0.12f),
		)
		binding.textViewProgressPercent.setTextColor(modernOnSurface)
		for (meta in arrayOf(binding.textViewMetaPrimary, binding.textViewMetaSecondary, binding.textViewMetaTertiary)) {
			meta.setTextColor(modernOnSurfaceVariant)
			meta.compoundDrawableTintList = ColorStateList.valueOf(modernOnSurfaceVariant)
		}
		binding.downloadDivider.setBackgroundColor(alphaColor(modernOutline, 0.34f))
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
			binding.progressBar.setIndicatorColor(modernSecondary)
			binding.progressBar.trackColor = alphaColor(modernOutline, 0.18f)
		}
	}

	fun resetModernMetadata() {
		if (!isModernDownloads) return
		binding.downloadMetadataRow.isVisible = false
		binding.textViewMetaPrimary.isVisible = false
		binding.textViewMetaSecondary.isVisible = false
		binding.textViewMetaTertiary.isVisible = false
		binding.textViewProgressPercent.isVisible = false
		binding.downloadDivider.isVisible = false
		binding.textViewDetails.isVisible = false
	}

	fun showModernMetadata(
		primary: CharSequence?,
		secondary: CharSequence?,
		tertiary: CharSequence?,
		tertiaryIcon: Int = R.drawable.ic_timer,
	) {
		if (!isModernDownloads) return
		binding.textViewMetaPrimary.textAndVisible = primary
		binding.textViewMetaSecondary.textAndVisible = secondary
		binding.textViewMetaTertiary.textAndVisible = tertiary
		binding.textViewMetaTertiary.setCompoundDrawablesRelativeWithIntrinsicBounds(tertiaryIcon, 0, 0, 0)
		binding.downloadMetadataRow.isVisible = primary != null || secondary != null || tertiary != null
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
