package org.koitharu.kotatsu.list.ui.adapter

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.widget.ImageViewCompat
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil3.size.Size
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.MiyorareAppearance
import org.koitharu.kotatsu.core.prefs.MiyorareDesignStyle
import org.koitharu.kotatsu.core.ui.MiyorareVisualTokens
import org.koitharu.kotatsu.core.ui.list.AdapterDelegateClickListenerAdapter
import org.koitharu.kotatsu.core.ui.list.OnListItemClickListener
import org.koitharu.kotatsu.core.util.ext.getEnumValue
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import org.koitharu.kotatsu.core.util.ext.setTooltipCompat
import org.koitharu.kotatsu.databinding.ItemMangaGridBinding
import org.koitharu.kotatsu.list.ui.ListModelDiffCallback.Companion.PAYLOAD_PROGRESS_CHANGED
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.MangaGridModel
import org.koitharu.kotatsu.list.ui.model.MangaListModel
import org.koitharu.kotatsu.list.ui.size.ItemSizeResolver
import kotlin.math.roundToInt
import androidx.appcompat.R as appcompatR
import com.google.android.material.R as materialR

fun mangaGridItemAD(
	sizeResolver: ItemSizeResolver,
	clickListener: MangaDetailsClickListener,
	titleClickListener: OnListItemClickListener<MangaListModel>? = null,
	gridVisualScaleProvider: (() -> Float)? = null,
) = adapterDelegateViewBinding<MangaGridModel, ListModel, ItemMangaGridBinding>(
	{ inflater, parent -> ItemMangaGridBinding.inflate(inflater, parent, false) },
) {

	AdapterDelegateClickListenerAdapter(this, clickListener).attach(itemView)
	if (titleClickListener != null) {
		val onTitleClick: (View) -> Unit = { view -> titleClickListener.onItemClick(item, view) }
		binding.textViewTitleOverlay.attachTitleClickToRead(itemView, onTitleClick)
		binding.textViewTitle.attachTitleClickToRead(itemView, onTitleClick)
	}
	sizeResolver.attachToView(itemView, binding.textViewTitleOverlay, binding.progressView)

	val density = context.resources.displayMetrics.density
	val gridMargin = context.resources.getDimensionPixelOffset(R.dimen.grid_spacing_outer)
	val gridMarginIncreased = context.resources.getDimensionPixelOffset(R.dimen.grid_spacing_outer_large)
	val appearancePreferences = PreferenceManager.getDefaultSharedPreferences(context)
	val primary = context.getThemeColor(appcompatR.attr.colorPrimary, Color.WHITE)
	val tertiary = context.getThemeColor(materialR.attr.colorTertiary, primary)
	val surface = context.getThemeColor(materialR.attr.colorSurface, Color.BLACK)
	val surfaceHigh = context.getThemeColor(materialR.attr.colorSurfaceContainerHighest, surface)
	val onSurface = context.getThemeColor(materialR.attr.colorOnSurface, Color.WHITE)
	val onSurfaceVariant = context.getThemeColor(materialR.attr.colorOnSurfaceVariant, onSurface)
	val accent = ColorUtils.blendARGB(primary, tertiary, 0.30f)
	val darkAccent = ColorUtils.blendARGB(primary, Color.BLACK, 0.78f)
	val modernScrimBase = ColorUtils.blendARGB(accent, Color.BLACK, 0.74f)
	val modernIndicator = ColorUtils.blendARGB(surfaceHigh, accent, 0.16f)
	val modernBadge = ColorUtils.blendARGB(surfaceHigh, accent, 0.30f)
	val modernBorder = ColorUtils.setAlphaComponent(
		accent,
		((MiyorareVisualTokens.BORDER_ALPHA_LIGHT + MiyorareVisualTokens.GLOW_ALPHA_LIGHT) * 0.70f * 255f)
			.toInt()
			.coerceIn(0, 255),
	)
	val modernCoverRadius = MiyorareVisualTokens.RADIUS_COVER_DP * density
	val isModernFavouritesGrid = gridVisualScaleProvider != null && appearancePreferences.getEnumValue(
		MiyorareAppearance.KEY_DESIGN_STYLE,
		MiyorareDesignStyle.CLASSIC,
	) == MiyorareDesignStyle.MODERN
	val modernBorderTint = ColorStateList.valueOf(modernBorder)
	val modernBadgeTint = ColorStateList.valueOf(modernBadge)
	val modernIndicatorTint = ColorStateList.valueOf(modernIndicator)
	val onSurfaceVariantTint = ColorStateList.valueOf(onSurfaceVariant)
	val primaryTint = ColorStateList.valueOf(primary)

	val defaultCoverShape = binding.imageViewCover.shapeAppearanceModel
	val defaultCoverStrokeColor = binding.imageViewCover.strokeColor
	val defaultCoverStrokeWidth = binding.imageViewCover.strokeWidth
	val defaultTitleColors = binding.textViewTitle.textColors
	val defaultTitleTextSizePx = binding.textViewTitle.textSize
	val defaultOverlayTextSizePx = binding.textViewTitleOverlay.textSize
	val defaultTitleIncludeFontPadding = binding.textViewTitle.includeFontPadding
	val defaultOverlayIncludeFontPadding = binding.textViewTitleOverlay.includeFontPadding
	val defaultBadgeColors = binding.badge.textColors
	val defaultLanguageColors = binding.textViewLanguage.textColors
	val defaultBadgeBackgroundTint = ViewCompat.getBackgroundTintList(binding.badge)
	val defaultLanguageBackgroundTint = ViewCompat.getBackgroundTintList(binding.textViewLanguage)
	val defaultPinBackgroundTint = ViewCompat.getBackgroundTintList(binding.imageViewPin)
	val defaultContinueBackgroundTint = ViewCompat.getBackgroundTintList(binding.imageViewContinue)
	val defaultIconsBackgroundTint = ViewCompat.getBackgroundTintList(binding.iconsView)
	val defaultPinImageTint = ImageViewCompat.getImageTintList(binding.imageViewPin)
	val defaultContinueImageTint = ImageViewCompat.getImageTintList(binding.imageViewContinue)

	val classicScrim = GradientDrawable(
		GradientDrawable.Orientation.BOTTOM_TOP,
		intArrayOf(
			ColorUtils.setAlphaComponent(darkAccent, 0xF2),
			ColorUtils.setAlphaComponent(darkAccent, 0xC0),
			ColorUtils.setAlphaComponent(darkAccent, 0x00),
		),
	).apply {
		val r = 16f * density
		cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r)
	}
	val modernScrim = GradientDrawable(
		GradientDrawable.Orientation.BOTTOM_TOP,
		intArrayOf(
			ColorUtils.setAlphaComponent(modernScrimBase, 0xC8),
			ColorUtils.setAlphaComponent(modernScrimBase, 0x68),
			ColorUtils.setAlphaComponent(modernScrimBase, 0x08),
			Color.TRANSPARENT,
		),
	).apply {
		cornerRadii = floatArrayOf(
			0f, 0f,
			0f, 0f,
			modernCoverRadius, modernCoverRadius,
			modernCoverRadius, modernCoverRadius,
		)
	}
	val modernCoverShape = defaultCoverShape.toBuilder()
		.setAllCornerSizes(modernCoverRadius)
		.build()

	fun applyGridAppearance(isModern: Boolean) {
		if (isModern) {
			binding.imageViewCover.shapeAppearanceModel = modernCoverShape
			binding.imageViewCover.strokeColor = modernBorderTint
			binding.imageViewCover.strokeWidth = 0.5f * density
			binding.viewScrim.background = modernScrim
			binding.textViewTitle.setTextColor(onSurface)
			binding.textViewTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
			binding.textViewTitleOverlay.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
			binding.textViewTitle.includeFontPadding = false
			binding.textViewTitleOverlay.includeFontPadding = false
			binding.textViewTitle.setLineSpacing(0f, 0.96f)
			binding.textViewTitleOverlay.setLineSpacing(0f, 0.96f)
			binding.badge.setTextColor(onSurface)
			binding.textViewLanguage.setTextColor(onSurfaceVariant)
			ViewCompat.setBackgroundTintList(binding.badge, modernBadgeTint)
			ViewCompat.setBackgroundTintList(binding.textViewLanguage, modernIndicatorTint)
			ViewCompat.setBackgroundTintList(binding.imageViewPin, modernIndicatorTint)
			ViewCompat.setBackgroundTintList(binding.imageViewContinue, modernBadgeTint)
			ViewCompat.setBackgroundTintList(binding.iconsView, modernIndicatorTint)
			ImageViewCompat.setImageTintList(binding.imageViewPin, onSurfaceVariantTint)
			ImageViewCompat.setImageTintList(binding.imageViewContinue, primaryTint)
		} else {
			binding.imageViewCover.shapeAppearanceModel = defaultCoverShape
			binding.imageViewCover.strokeColor = defaultCoverStrokeColor
			binding.imageViewCover.strokeWidth = defaultCoverStrokeWidth
			binding.viewScrim.background = classicScrim
			binding.textViewTitle.setTextColor(defaultTitleColors)
			binding.textViewTitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, defaultTitleTextSizePx)
			binding.textViewTitleOverlay.setTextSize(TypedValue.COMPLEX_UNIT_PX, defaultOverlayTextSizePx)
			binding.textViewTitle.includeFontPadding = defaultTitleIncludeFontPadding
			binding.textViewTitleOverlay.includeFontPadding = defaultOverlayIncludeFontPadding
			binding.textViewTitle.setLineSpacing(0f, 1f)
			binding.textViewTitleOverlay.setLineSpacing(0f, 1f)
			binding.badge.setTextColor(defaultBadgeColors)
			binding.textViewLanguage.setTextColor(defaultLanguageColors)
			ViewCompat.setBackgroundTintList(binding.badge, defaultBadgeBackgroundTint)
			ViewCompat.setBackgroundTintList(binding.textViewLanguage, defaultLanguageBackgroundTint)
			ViewCompat.setBackgroundTintList(binding.imageViewPin, defaultPinBackgroundTint)
			ViewCompat.setBackgroundTintList(binding.imageViewContinue, defaultContinueBackgroundTint)
			ViewCompat.setBackgroundTintList(binding.iconsView, defaultIconsBackgroundTint)
			ImageViewCompat.setImageTintList(binding.imageViewPin, defaultPinImageTint)
			ImageViewCompat.setImageTintList(binding.imageViewContinue, defaultContinueImageTint)
		}
	}

	binding.viewScrim.background = classicScrim

	fun applyGridSizing(margin: Int) {
		itemView.updateLayoutParams<ViewGroup.MarginLayoutParams> {
			if (
				leftMargin != margin ||
				topMargin != margin ||
				rightMargin != margin ||
				bottomMargin != margin
			) {
				setMargins(margin, margin, margin, margin)
			}
		}
		val coverWidth = resolveActualCoverWidth(itemView, sizeResolver.cellWidth, margin)
		binding.imageViewCover.exactImageSize = if (coverWidth > 0) {
			Size(coverWidth, coverWidth * 18 / 13)
		} else {
			null
		}
	}

	bind { payloads ->
		itemView.setTooltipCompat(item.getSummary(context))
		applyGridAppearance(isModernFavouritesGrid)
		val baseMargin = if (item.isGridSpacingIncreased) gridMarginIncreased else gridMargin
		val styledBaseMargin = if (isModernFavouritesGrid) {
			baseMargin + (1.5f * density).roundToInt().coerceAtLeast(1)
		} else {
			baseMargin
		}
		val visualScaleProvider = gridVisualScaleProvider
		val initialMargin = visualScaleProvider?.invoke()?.let { scale ->
			resolveFixedGridMargin(itemView, styledBaseMargin, scale)
		} ?: styledBaseMargin
		applyGridSizing(initialMargin)
		if (visualScaleProvider != null) {
			val boundId = item.id
			itemView.doOnLayout {
				if (item.id != boundId) return@doOnLayout
				applyGridSizing(
					resolveFixedGridMargin(itemView, styledBaseMargin, visualScaleProvider.invoke()),
				)
			}
		}

		val isTitleOverCover = item.isTitleOverCover && !item.isTitleHidden
		binding.textViewTitleOverlay.text = item.title
		binding.textViewTitle.text = item.title
		binding.textViewTitleOverlay.isVisible = isTitleOverCover
		binding.viewScrim.isVisible = isTitleOverCover
		binding.textViewTitle.isVisible = !item.isTitleHidden && !isTitleOverCover
		binding.progressView.setProgress(item.progress, PAYLOAD_PROGRESS_CHANGED in payloads)
		binding.imageViewPin.isVisible = item.isPinned
		binding.textViewLanguage.text = item.languageLabel
		binding.textViewLanguage.isVisible = !item.languageLabel.isNullOrBlank()
		binding.imageViewContinue.isVisible = item.showContinueReading
		if (item.showContinueReading) {
			binding.imageViewContinue.setOnClickListener { view ->
				clickListener.onReadClick(item.toMangaWithOverride(), view)
			}
		} else {
			binding.imageViewContinue.setOnClickListener(null)
		}
		binding.layoutIndicators.updateLayoutParams<FrameLayout.LayoutParams> {
			gravity = Gravity.END or if (isTitleOverCover || item.isPinned) Gravity.TOP else Gravity.BOTTOM
		}
		with(binding.iconsView) {
			clearIcons()
			if (item.isSaved) addIcon(R.drawable.ic_storage)
			if (item.isLocalSource) addIcon(R.drawable.ic_manga_source)
			if (item.isFavorite) addIcon(R.drawable.ic_heart_outline)
			isVisible = iconsCount > 0
		}
		binding.imageViewCover.setImageAsync(item.coverUrl, item.manga)
		binding.badge.number = item.counter
		binding.badge.isVisible = item.counter > 0
		binding.iconsView.updateLayoutParams<FrameLayout.LayoutParams> {
			topMargin = if (item.counter > 0) {
				(32f * density).toInt()
			} else {
				(16f * density).toInt()
			}
		}
	}
}

private fun resolveFixedGridMargin(itemView: View, baseMargin: Int, requestedScale: Float): Int {
	val scale = requestedScale.coerceIn(MIN_FIXED_GRID_SCALE, MAX_FIXED_GRID_SCALE)
	if (scale == DEFAULT_FIXED_GRID_SCALE) return baseMargin

	val recyclerView = itemView.parent as? RecyclerView
	val layoutManager = recyclerView?.layoutManager as? GridLayoutManager
	val slotWidth = if (recyclerView != null && layoutManager != null && layoutManager.spanCount > 0) {
		(recyclerView.width - recyclerView.paddingStart - recyclerView.paddingEnd) / layoutManager.spanCount
	} else {
		0
	}

	return if (scale < DEFAULT_FIXED_GRID_SCALE) {
		val maxMargin = if (slotWidth > 0) {
			(slotWidth * MAX_FIXED_GRID_MARGIN_FRACTION).roundToInt().coerceAtLeast(baseMargin)
		} else {
			baseMargin
		}
		val progress = (scale - MIN_FIXED_GRID_SCALE) /
			(DEFAULT_FIXED_GRID_SCALE - MIN_FIXED_GRID_SCALE)
		(maxMargin + (baseMargin - maxMargin) * progress).roundToInt()
	} else {
		val minMargin = (baseMargin * MIN_FIXED_GRID_MARGIN_FACTOR).roundToInt().coerceAtLeast(1)
		val progress = (scale - DEFAULT_FIXED_GRID_SCALE) /
			(MAX_FIXED_GRID_SCALE - DEFAULT_FIXED_GRID_SCALE)
		(baseMargin + (minMargin - baseMargin) * progress).roundToInt()
	}
}

private fun resolveActualCoverWidth(itemView: View, fallbackWidth: Int, margin: Int): Int {
	val recyclerView = itemView.parent as? RecyclerView
	val layoutManager = recyclerView?.layoutManager as? GridLayoutManager
	val slotWidth = if (recyclerView != null && layoutManager != null && layoutManager.spanCount > 0) {
		(recyclerView.width - recyclerView.paddingStart - recyclerView.paddingEnd) / layoutManager.spanCount
	} else {
		fallbackWidth
	}
	return slotWidth - margin * 2
}

private const val MIN_FIXED_GRID_SCALE = 0.5f
private const val DEFAULT_FIXED_GRID_SCALE = 1f
private const val MAX_FIXED_GRID_SCALE = 1.5f
private const val MAX_FIXED_GRID_MARGIN_FRACTION = 0.2f
private const val MIN_FIXED_GRID_MARGIN_FACTOR = 0.25f
