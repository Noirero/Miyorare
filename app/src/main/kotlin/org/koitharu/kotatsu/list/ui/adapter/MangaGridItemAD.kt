package org.koitharu.kotatsu.list.ui.adapter

import android.content.res.ColorStateList
import android.graphics.Color
import androidx.core.content.ContextCompat
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
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Size
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.MiyorareAppearance
import org.koitharu.kotatsu.core.prefs.MiyorareDesignStyle
import org.koitharu.kotatsu.core.prefs.MiyorareFavouritesCardBorderStyle
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
import org.koitharu.kotatsu.list.ui.model.ModernCoverContext
import org.koitharu.kotatsu.list.ui.model.ModernCoverState
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
	coverContext: ModernCoverContext = ModernCoverContext.LEGACY,
	localCinematic: Boolean = false,
	normalFavouritesCardBorder: Boolean = false,
	debugHistoryOverlayBypass: (() -> Boolean)? = null,
) = adapterDelegateViewBinding<MangaGridModel, ListModel, ItemMangaGridBinding>(
	{ inflater, parent -> ItemMangaGridBinding.inflate(inflater, parent, false) },
) {

	AdapterDelegateClickListenerAdapter(this, clickListener).attach(itemView)
	if (titleClickListener != null) {
		val onTitleClick: (View) -> Unit = { view -> titleClickListener.onItemClick(item, view) }
		binding.textViewTitleOverlay.attachTitleClickToRead(itemView, onTitleClick)
		binding.textViewTitle.attachTitleClickToRead(itemView, onTitleClick)
		binding.favouriteCoverOverlays.titleView.attachTitleClickToRead(itemView, onTitleClick)
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
	val designStyle = appearancePreferences.getEnumValue(
		MiyorareAppearance.KEY_DESIGN_STYLE,
		MiyorareDesignStyle.CLASSIC,
	)
	val usesModernCover = !localCinematic && coverContext.usesOverlay(designStyle)
	// Restore the original cover indicators on On-device Files without applying the
	// global Modern card geometry, palette or spacing to its cinematic cards.
	val usesCoverOverlay = usesModernCover || localCinematic
	val hasCollectionBorder = !localCinematic && coverContext.usesCollectionBorder(designStyle)
	val isPrivateFavouritesHost = context.findActivity()?.intent?.getIntExtra(
		EXTRA_FAVOURITE_SPACE,
		FavouriteSpace.NORMAL.dbValue,
	) == FavouriteSpace.PRIVATE.dbValue
	// Geometry must follow the Modern Normal-Favourites spec independently from whether a palette
	// bridge is available at this exact bind moment. Palette lookup only controls colour/glass data;
	// falling back to the legacy 2dp grid margin here made canonical cards ~130.5dp wide.
	val isNormalModernFavourites = hasCollectionBorder && !isPrivateFavouritesHost
	val normalBorderStyle = if (isNormalModernFavourites && normalFavouritesCardBorder) {
		appearancePreferences.getEnumValue(
			MiyorareAppearance.KEY_FAVOURITES_CARD_BORDER_STYLE,
			MiyorareFavouritesCardBorderStyle.DEFAULT,
		)
	} else {
		MiyorareFavouritesCardBorderStyle.DEFAULT
	}
	val normalGlass = if (hasCollectionBorder) {
		context.miyorareViewPaletteFromPreferences()?.neonGlass()
	} else {
		null
	}
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

	val coverBorderState = if (isNormalModernFavourites && normalFavouritesCardBorder) {
		FavouritesCoverBorderState()
	} else null

	fun syncLoadingBorder() {
		val state = coverBorderState ?: return
		val hidden = state.isPending
		binding.imageViewCover.strokeWidth = if (!hidden && normalBorderStyle.hasStroke) {
			MiyorareFavouritesVisualSpec.MANGA_CARD_BORDER_WIDTH_DP * density
		} else 0f
		if ((itemView.getTag(R.id.tag_favourites_cover_pending) == true) != hidden) {
			itemView.setTag(R.id.tag_favourites_cover_pending, hidden)
			(itemView.parent as? RecyclerView)?.invalidate()
		}
	}

	if (coverBorderState != null) {
		// One listener per holder. A payload rebind can reuse the current Coil request.
		binding.imageViewCover.addImageRequestListener(object : ImageRequest.Listener {
			override fun onStart(request: ImageRequest) {
				coverBorderState.start(request.data, request)
				syncLoadingBorder()
			}

			override fun onSuccess(request: ImageRequest, result: SuccessResult) {
				coverBorderState.finish(request)
				syncLoadingBorder()
			}

			override fun onError(request: ImageRequest, result: ErrorResult) {
				coverBorderState.finish(request)
				syncLoadingBorder()
			}
		})
	}

	fun applyGridAppearance(isModern: Boolean, visualScale: Float) {
		binding.imageViewCover.setAspectRatioOverride(
			(MiyorareFavouritesVisualSpec.MANGA_CARD_ASPECT_RATIO / visualScale)
				.takeIf { isNormalModernFavourites },
		)
		if (isModern) {
			val normalNeon = normalGlass != null
			binding.imageViewCover.shapeAppearanceModel = modernCoverShape
			binding.imageViewCover.strokeColor = if (hasCollectionBorder) {
				if (normalNeon) normalBorderTint else modernBorderTint
			} else defaultCoverStrokeColor
			binding.imageViewCover.strokeWidth = when {
				isNormalModernFavourites && normalFavouritesCardBorder && !normalBorderStyle.hasStroke -> 0f
				hasCollectionBorder -> MiyorareFavouritesVisualSpec.MANGA_CARD_BORDER_WIDTH_DP * density
				else -> defaultCoverStrokeWidth
			}
			syncLoadingBorder()
			binding.viewScrim.background = modernScrim
			binding.textViewTitle.setTextColor(if (normalNeon) normalGlass!!.content else onSurface)
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

	fun applyGridSizing(margin: Int, visualScale: Float) {
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
			(coverWidth / MiyorareFavouritesVisualSpec.MANGA_CARD_ASPECT_RATIO * visualScale).roundToInt()
		} else {
			0
		}
		val coverHeight = if (referenceHeight > 0) referenceHeight else coverWidth * 18 / 13
		if (usesModernCover && coverHeight > 0) {
			binding.viewScrim.updateLayoutParams<FrameLayout.LayoutParams> {
				height = minOf(coverHeight, (MiyorareFavouritesVisualSpec.MANGA_CARD_SCRIM_HEIGHT_DP * density).roundToInt())
			}
		}
		binding.imageViewCover.exactImageSize = if (coverWidth > 0) {
			Size(
				coverWidth,
				coverHeight,
			)
		} else {
			null
		}
	}

	bind { payloads ->
		coverBorderState?.bind(item.coverUrl)
		itemView.setTooltipCompat(item.getSummary(context))
		val visualScaleProvider = gridVisualScaleProvider
		val visualScale = visualScaleProvider?.invoke()?.coerceIn(MIN_FIXED_GRID_SCALE, MAX_FIXED_GRID_SCALE)
			?: DEFAULT_FIXED_GRID_SCALE
		applyGridAppearance(usesModernCover, visualScale)
		if (localCinematic) {
			val gold = ContextCompat.getColor(context, R.color.smart_local_gold)
			val cream = ContextCompat.getColor(context, R.color.smart_local_cream)
			binding.imageViewCover.shapeAppearanceModel = defaultCoverShape.toBuilder()
				.setAllCornerSizes(18f * density).build()
			binding.imageViewCover.strokeColor = ColorStateList.valueOf(gold)
			binding.imageViewCover.strokeWidth = density
			binding.textViewTitle.setTextColor(cream)
			itemView.background = ContextCompat.getDrawable(context, R.drawable.bg_smart_local_cinematic_selectable)
		}
		val baseMargin = if (item.isGridSpacingIncreased) gridMarginIncreased else gridMargin
		val styledBaseMargin = if (localCinematic) {
			(7f * density).roundToInt()
		} else if (isNormalModernFavourites) {
			val marginDp = if (item.isGridSpacingIncreased) {
				MiyorareFavouritesVisualSpec.GRID_ITEM_MARGIN_INCREASED_DP
			} else {
				MiyorareFavouritesVisualSpec.GRID_ITEM_MARGIN_DP
			}
			(marginDp * density).roundToInt()
		} else {
			baseMargin
		}
		applyGridSizing(styledBaseMargin, visualScale)
		if (visualScaleProvider != null) {
			val boundId = item.id
			itemView.doOnLayout {
				if (item.id != boundId) return@doOnLayout
				val currentScale = visualScaleProvider.invoke()
					.coerceIn(MIN_FIXED_GRID_SCALE, MAX_FIXED_GRID_SCALE)
				applyGridAppearance(usesModernCover, currentScale)
				applyGridSizing(styledBaseMargin, currentScale)
			}
		}

		val displayTitle = item.displayTitle(binding.root.context)
		val isTitleOverCover = item.isTitleOverCover && !item.isTitleHidden
		// Debug A/B: isolate Modern overlay cost without changing pagination, image requests or data.
		// Only History supplies a probe callback; default and all release builds remain unchanged.
		val historyProbe = BuildConfig.DEBUG && debugHistoryOverlayBypass != null
		val effectiveCoverOverlay = resolveHistoryCoverOverlay(
			configured = usesCoverOverlay,
			modern = usesModernCover,
			probeEnabled = historyProbe,
			bypassRequested = historyProbe && debugHistoryOverlayBypass?.invoke() == true,
		)
		binding.favouriteCoverOverlays.debugTraceHistoryMeasure = historyProbe
		binding.favouriteCoverOverlays.isVisible = effectiveCoverOverlay
		if (effectiveCoverOverlay) {
			binding.layoutIndicators.apply {
				translationX = 0f
				translationY = 0f
				alpha = 1f
			}
			val tracingOverlayBind = historyProbe && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q && android.os.Trace.isEnabled()
			if (tracingOverlayBind) android.os.Trace.beginSection("HistoryModernOverlayBind")
			try {
			binding.favouriteCoverOverlays.bind(
				ModernCoverState.from(item, if (localCinematic) ModernCoverContext.COLLECTION else coverContext)
					.copy(title = displayTitle)
					.let { if (localCinematic) it.copy(legacyCounter = item.counter, isLocalSource = true, languageLabel = null) else it },
			) { view ->
				clickListener.onReadClick(item.toMangaWithOverride(), view)
			}
			} finally {
				if (tracingOverlayBind) android.os.Trace.endSection()
			}
		}
		binding.textViewTitleOverlay.text = displayTitle
		binding.textViewTitle.text = displayTitle
		binding.textViewTitleOverlay.isVisible = isTitleOverCover && !effectiveCoverOverlay
		binding.viewScrim.isVisible = isTitleOverCover
		binding.textViewTitle.isVisible = !item.isTitleHidden && !isTitleOverCover
		binding.progressView.setProgress(item.progress, PAYLOAD_PROGRESS_CHANGED in payloads)
		binding.imageViewPin.isVisible = item.isPinned && !effectiveCoverOverlay
		binding.textViewLanguage.text = item.languageLabel
		binding.textViewLanguage.isVisible = !item.languageLabel.isNullOrBlank() && !effectiveCoverOverlay

		binding.imageViewContinue.isVisible = item.showContinueReading && !effectiveCoverOverlay
		if (item.showContinueReading && !effectiveCoverOverlay) {
			binding.imageViewContinue.setOnClickListener { view ->
				clickListener.onReadClick(item.toMangaWithOverride(), view)
			}
		} else {
			binding.imageViewContinue.setOnClickListener(null)
		}
		binding.layoutIndicators.updateLayoutParams<FrameLayout.LayoutParams> {
			gravity = Gravity.END or if (isTitleOverCover || item.isPinned) Gravity.TOP else Gravity.BOTTOM
		}
		binding.iconsView.bindGrid(
			item.isSaved,
			item.isLocalSource,
			item.isFavorite,
			item.counter,
			useModernCoverOverlay = effectiveCoverOverlay,
		)
		// Legacy Corner Mark offsets belong only to the legacy renderer.
		binding.layoutIndicators.updateLayoutParams<FrameLayout.LayoutParams> {
			topMargin = context.resources.getDimensionPixelSize(R.dimen.card_indicator_offset) +
				if (!effectiveCoverOverlay && (item.isSaved || item.isFavorite) && (isTitleOverCover || item.isPinned)) {
					context.resources.getDimensionPixelSize(R.dimen.manga_status_ribbon_height) +
						context.resources.getDimensionPixelSize(R.dimen.library_indicator_spacing)
				} else 0
		}
		binding.imageViewCover.setImageAsync(
			item.coverUrl,
			item.manga,
			persistCover = coverContext == ModernCoverContext.COLLECTION,
		)
		binding.badge.number = item.counter
		binding.badge.isVisible = item.counter > 0 && !effectiveCoverOverlay
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
