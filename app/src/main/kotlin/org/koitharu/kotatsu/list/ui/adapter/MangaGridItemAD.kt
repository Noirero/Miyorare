package org.koitharu.kotatsu.list.ui.adapter

import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
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
import org.koitharu.kotatsu.core.ui.MiyorareFavouritesVisualSpec
import org.koitharu.kotatsu.core.ui.MiyorareVisualTokens
import org.koitharu.kotatsu.core.ui.list.AdapterDelegateClickListenerAdapter
import org.koitharu.kotatsu.core.ui.miyorareViewPaletteFromPreferences
import org.koitharu.kotatsu.core.ui.neonGlass
import org.koitharu.kotatsu.core.ui.list.OnListItemClickListener
import org.koitharu.kotatsu.core.util.ext.findActivity
import org.koitharu.kotatsu.core.util.ext.getEnumValue
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import org.koitharu.kotatsu.core.util.ext.setTooltipCompat
import org.koitharu.kotatsu.databinding.ItemMangaGridBinding
import org.koitharu.kotatsu.favourites.data.EXTRA_FAVOURITE_SPACE
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
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
	val isPrivateFavouritesHost = context.findActivity()?.intent?.getIntExtra(
		EXTRA_FAVOURITE_SPACE,
		FavouriteSpace.NORMAL.dbValue,
	) == FavouriteSpace.PRIVATE.dbValue
	// Geometry must follow the Modern Normal-Favourites spec independently from whether a palette
	// bridge is available at this exact bind moment. Palette lookup only controls colour/glass data;
	// falling back to the legacy 2dp grid margin here made canonical cards ~130.5dp wide.
	val isNormalModernFavourites = isModernFavouritesGrid && !isPrivateFavouritesHost
	val normalPalette = if (isNormalModernFavourites) {
		context.miyorareViewPaletteFromPreferences()
	} else {
		null
	}
	val normalGlass = normalPalette?.neonGlass()
	val finalRankBorder = normalPalette
		?.exclusiveTheme
		?.favourites
		?.cardBorderStops
		?.takeIf { it.size >= 2 }
		?.toIntArray()
	val modernBorderTint = ColorStateList.valueOf(modernBorder)
	val normalBorderTint = ColorStateList.valueOf(normalGlass?.borderStrong ?: modernBorder)
	val normalBadgeTint = ColorStateList.valueOf(normalGlass?.surfaceStrong ?: modernBadge)
	val normalLanguageTint = ColorStateList.valueOf(normalGlass?.railSurface ?: modernBadge)
	val normalIndicatorTint = ColorStateList.valueOf(normalGlass?.surface ?: modernIndicator)
	val modernBadgeTint = ColorStateList.valueOf(modernBadge)
	val modernIndicatorTint = ColorStateList.valueOf(modernIndicator)
	val onSurfaceVariantTint = ColorStateList.valueOf(onSurfaceVariant)
	val normalContentTint = ColorStateList.valueOf(normalGlass?.content ?: onSurface)
	val normalMutedTint = ColorStateList.valueOf(normalGlass?.contentMuted ?: onSurfaceVariant)
	val primaryTint = ColorStateList.valueOf(primary)

	val defaultCoverShape = binding.imageViewCover.shapeAppearanceModel
	val defaultCoverStrokeColor = binding.imageViewCover.strokeColor
	val defaultCoverStrokeWidth = binding.imageViewCover.strokeWidth
	val defaultCoverForeground = binding.imageViewCover.foreground
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
		binding.imageViewCover.setAspectRatioOverride(
			MiyorareFavouritesVisualSpec.MANGA_CARD_ASPECT_RATIO.takeIf { isNormalModernFavourites },
		)
		if (isModern) {
			val normalNeon = normalGlass != null
			binding.imageViewCover.shapeAppearanceModel = modernCoverShape
			if (finalRankBorder != null && finalRankBorder.size >= 2) {
				// Final-rank normal cards use their authored signature edge.
				// Keep it static and 1dp: no per-card animation or realtime blur.
				binding.imageViewCover.strokeColor = ColorStateList.valueOf(Color.TRANSPARENT)
				binding.imageViewCover.strokeWidth = 0f
				binding.imageViewCover.foreground = RankSignatureCoverBorderDrawable(
					colors = finalRankBorder,
					cornerRadius = modernCoverRadius,
					strokeWidth = 1f * density,
				)
			} else {
				binding.imageViewCover.foreground = defaultCoverForeground
				binding.imageViewCover.strokeColor = if (normalNeon) normalBorderTint else modernBorderTint
				binding.imageViewCover.strokeWidth = (
					if (isNormalModernFavourites) MiyorareFavouritesVisualSpec.MANGA_CARD_BORDER_WIDTH_DP else 0.5f
				) * density
			}
			binding.viewScrim.background = modernScrim
			binding.textViewTitle.setTextColor(onSurface)
			binding.textViewTitle.setTextSize(
				TypedValue.COMPLEX_UNIT_SP,
				MiyorareFavouritesVisualSpec.MANGA_CARD_TITLE_TEXT_SP,
			)
			binding.textViewTitleOverlay.setTextSize(
				TypedValue.COMPLEX_UNIT_SP,
				MiyorareFavouritesVisualSpec.MANGA_CARD_TITLE_TEXT_SP,
			)
			binding.textViewTitle.includeFontPadding = false
			binding.textViewTitleOverlay.includeFontPadding = false
			binding.textViewTitle.setLineSpacing(
				0f,
				MiyorareFavouritesVisualSpec.MANGA_CARD_TITLE_LINE_MULTIPLIER,
			)
			binding.textViewTitleOverlay.setLineSpacing(
				0f,
				MiyorareFavouritesVisualSpec.MANGA_CARD_TITLE_LINE_MULTIPLIER,
			)
			if (isNormalModernFavourites) {
				binding.textViewTitleOverlay.updateLayoutParams<FrameLayout.LayoutParams> {
					marginStart =
						(MiyorareFavouritesVisualSpec.MANGA_CARD_TITLE_HORIZONTAL_MARGIN_DP * density).roundToInt()
					marginEnd =
						(MiyorareFavouritesVisualSpec.MANGA_CARD_TITLE_HORIZONTAL_MARGIN_DP * density).roundToInt()
					bottomMargin =
						(MiyorareFavouritesVisualSpec.MANGA_CARD_TITLE_BOTTOM_MARGIN_DP * density).roundToInt()
				}
			}
			binding.badge.setTextColor(if (normalNeon) normalGlass!!.content else onSurface)
			binding.textViewLanguage.setTextColor(if (normalNeon) normalGlass!!.content else onSurfaceVariant)
			binding.textViewLanguage.alpha = 1f
			binding.layoutIndicators.alpha = 1f
			ViewCompat.setBackgroundTintList(binding.badge, if (normalNeon) normalBadgeTint else modernBadgeTint)
			ViewCompat.setBackgroundTintList(binding.textViewLanguage, if (normalNeon) normalLanguageTint else modernIndicatorTint)
			ViewCompat.setBackgroundTintList(binding.imageViewPin, if (normalNeon) normalIndicatorTint else modernIndicatorTint)
			ViewCompat.setBackgroundTintList(binding.imageViewContinue, if (normalNeon) normalBadgeTint else modernBadgeTint)
			ViewCompat.setBackgroundTintList(binding.iconsView, if (normalNeon) normalIndicatorTint else modernIndicatorTint)
			ImageViewCompat.setImageTintList(
				binding.imageViewPin,
				if (normalNeon) normalMutedTint else onSurfaceVariantTint,
			)
			ImageViewCompat.setImageTintList(
				binding.imageViewContinue,
				if (normalNeon) normalContentTint else primaryTint,
			)
		} else {
			binding.imageViewCover.shapeAppearanceModel = defaultCoverShape
			binding.imageViewCover.foreground = defaultCoverForeground
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
		val referenceHeight = if (isNormalModernFavourites && coverWidth > 0) {
			(coverWidth / MiyorareFavouritesVisualSpec.MANGA_CARD_ASPECT_RATIO).roundToInt()
		} else {
			0
		}
		if (isNormalModernFavourites && referenceHeight > 0) {
			binding.viewScrim.updateLayoutParams<FrameLayout.LayoutParams> {
				height = (MiyorareFavouritesVisualSpec.MANGA_CARD_SCRIM_HEIGHT_DP * density).roundToInt()
			}
		}
		binding.imageViewCover.exactImageSize = if (coverWidth > 0) {
			Size(
				coverWidth,
				if (referenceHeight > 0) referenceHeight else coverWidth * 18 / 13,
			)
		} else {
			null
		}
	}

	bind { payloads ->
		itemView.setTooltipCompat(item.getSummary(context))
		applyGridAppearance(isModernFavouritesGrid)
		val baseMargin = if (item.isGridSpacingIncreased) gridMarginIncreased else gridMargin
		val styledBaseMargin = if (isNormalModernFavourites) {
			val marginDp = if (item.isGridSpacingIncreased) {
				MiyorareFavouritesVisualSpec.GRID_ITEM_MARGIN_INCREASED_DP
			} else {
				MiyorareFavouritesVisualSpec.GRID_ITEM_MARGIN_DP
			}
			(marginDp * density).roundToInt()
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
		if (normalGlass != null) {
			// Keep enabled language badges above the cover/scrim stack. Visibility still follows the
			// existing user preference via item.languageLabel; this is presentation-only.
			binding.layoutIndicators.bringToFront()
		}
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


/**
 * Lightweight static gradient edge for final-rank manga covers.
 * This intentionally draws only a 1dp border; the existing RecyclerView decoration owns the halo.
 */
private class RankSignatureCoverBorderDrawable(
	private val colors: IntArray,
	private val cornerRadius: Float,
	private val strokeWidth: Float,
) : Drawable() {
	private val rect = RectF()
	private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		style = Paint.Style.STROKE
		this.strokeWidth = this@RankSignatureCoverBorderDrawable.strokeWidth
	}
	private var drawableAlpha: Int = 255

	override fun onBoundsChange(bounds: android.graphics.Rect) {
		super.onBoundsChange(bounds)
		paint.shader = LinearGradient(
			bounds.left.toFloat(),
			bounds.top.toFloat(),
			bounds.right.toFloat(),
			bounds.bottom.toFloat(),
			colors,
			null,
			Shader.TileMode.CLAMP,
		)
	}

	override fun draw(canvas: Canvas) {
		val half = strokeWidth / 2f
		rect.set(
			bounds.left + half,
			bounds.top + half,
			bounds.right - half,
			bounds.bottom - half,
		)
		paint.alpha = drawableAlpha
		val radius = (cornerRadius - half).coerceAtLeast(0f)
		canvas.drawRoundRect(rect, radius, radius, paint)
	}

	override fun setAlpha(alpha: Int) {
		drawableAlpha = alpha.coerceIn(0, 255)
		invalidateSelf()
	}

	override fun setColorFilter(colorFilter: ColorFilter?) {
		paint.colorFilter = colorFilter
		invalidateSelf()
	}

	@Deprecated("Deprecated in Android")
	override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
