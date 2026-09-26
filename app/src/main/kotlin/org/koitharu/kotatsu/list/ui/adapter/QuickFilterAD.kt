package org.koitharu.kotatsu.list.ui.adapter

import android.content.res.ColorStateList
import android.content.res.Configuration
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
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import androidx.core.graphics.ColorUtils
import androidx.core.view.children
import androidx.core.view.doOnLayout
import androidx.preference.PreferenceManager
import com.google.android.material.chip.Chip
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.MiyorareAppearance
import org.koitharu.kotatsu.core.prefs.MiyorareDesignStyle
import org.koitharu.kotatsu.core.ui.MiyorareFavouritesVisualSpec
import org.koitharu.kotatsu.core.ui.MiyorareNeonGlassColors
import org.koitharu.kotatsu.core.ui.MiyorareVisualTokens
import org.koitharu.kotatsu.core.ui.miyorareViewPaletteFromPreferences
import org.koitharu.kotatsu.core.ui.neonGlass
import org.koitharu.kotatsu.core.ui.widgets.ChipsView
import org.koitharu.kotatsu.core.util.ext.findActivity
import org.koitharu.kotatsu.core.util.ext.getEnumValue
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import org.koitharu.kotatsu.databinding.ItemQuickFilterBinding
import org.koitharu.kotatsu.favourites.data.EXTRA_FAVOURITE_SPACE
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.list.domain.ListFilterOption
import org.koitharu.kotatsu.readerjourney.theme.RankThemeId
import org.koitharu.kotatsu.readerjourney.theme.RankThemeSignatureRegistry
import org.koitharu.kotatsu.list.ui.model.ExtensionFilter
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.QuickFilter
import com.google.android.material.R as materialR
import kotlin.math.roundToInt

fun quickFilterAD(
	listener: QuickFilterClickListener,
) = adapterDelegateViewBinding<QuickFilter, ListModel, ItemQuickFilterBinding>(
	{ layoutInflater, parent -> ItemQuickFilterBinding.inflate(layoutInflater, parent, false) }
) {

	binding.chipsTags.onChipClickListener = ChipsView.OnChipClickListener { chip, data ->
		when (data) {
			is ListFilterOption -> listener.onFilterOptionClick(data)
			is ExtensionFilter -> ExtensionFilterPopup.show(chip, data, listener)
		}
	}

	bind {
		binding.chipsTags.setChips(item.items)
		binding.applyMiyorareModernQuickFilterStyle(item)
	}
}

/**
 * Modern main lists share one glass quick-filter language. Favourites keeps its special equal-width
 * geometry, while Updates/History/Feed preserve their natural scrolling widths and only inherit the
 * approved glass shape, border, glow and state colors.
 */
private fun ItemQuickFilterBinding.applyMiyorareModernQuickFilterStyle(item: QuickFilter) {
	val isFavouritesQuickFilter = item.items.any { it.titleResId == R.string.favorites_continue_reading } &&
		item.items.any { it.titleResId == R.string.favorites_filter }

	val preferences = PreferenceManager.getDefaultSharedPreferences(root.context)
	val designStyle = preferences.getEnumValue(
		MiyorareAppearance.KEY_DESIGN_STYLE,
		MiyorareDesignStyle.CLASSIC,
	)
	if (designStyle != MiyorareDesignStyle.MODERN) return

	val isPrivate = root.context.findActivity()?.intent?.getIntExtra(
		EXTRA_FAVOURITE_SPACE,
		FavouriteSpace.NORMAL.dbValue,
	) == FavouriteSpace.PRIVATE.dbValue

	// Private keeps its established quick-filter treatment. Normal Modern screens share the same
	// glass chip language as Favourites so Updates/History/Feed no longer fall back to the old box.
	if (isPrivate && !isFavouritesQuickFilter) return

	if (!isPrivate && isFavouritesQuickFilter) {
		val density = root.resources.displayMetrics.density
		val outerPadding = (MiyorareFavouritesVisualSpec.QUICK_FILTER_OUTER_PADDING_DP * density).roundToInt()
		root.setPaddingRelative(outerPadding, root.paddingTop, outerPadding, root.paddingBottom)
		chipsTags.setPaddingRelative(
			chipsTags.paddingStart,
			(MiyorareFavouritesVisualSpec.QUICK_FILTER_TOP_PADDING_DP * density).roundToInt(),
			chipsTags.paddingEnd,
			(MiyorareFavouritesVisualSpec.QUICK_FILTER_BOTTOM_PADDING_DP * density).roundToInt(),
		)
		root.doOnLayout { host ->
			val gap = (MiyorareFavouritesVisualSpec.QUICK_FILTER_GAP_DP * density).roundToInt()
			val contentWidth = host.width - host.paddingStart - host.paddingEnd
			val actionCount = chipsTags.childCount.coerceAtLeast(1)
			val actionWidth = (
				(contentWidth - gap * (actionCount - 1)) / actionCount.toFloat()
			).roundToInt().coerceAtLeast(1)
			chipsTags.post { chipsTags.setFixedChildWidth(actionWidth) }
		}
	}

	chipsTags.applyMiyorareFavouritesQuickFilterStyle(
		normalNeon = !isPrivate,
		subtleGlassFill = !isPrivate,
	)
}

private fun ChipsView.applyMiyorareFavouritesQuickFilterStyle(
	normalNeon: Boolean,
	subtleGlassFill: Boolean,
) {
	val density = resources.displayMetrics.density
	val primary = context.getThemeColor(androidx.appcompat.R.attr.colorPrimary, Color.WHITE)
	val surface = context.getThemeColor(materialR.attr.colorSurfaceContainer, Color.DKGRAY)
	val surfaceHigh = context.getThemeColor(materialR.attr.colorSurfaceContainerHighest, surface)
	val onSurface = context.getThemeColor(materialR.attr.colorOnSurface, Color.WHITE)
	val onSurfaceVariant = context.getThemeColor(materialR.attr.colorOnSurfaceVariant, onSurface)
	val outline = context.getThemeColor(materialR.attr.colorOutlineVariant, primary)
	val normalPalette = if (normalNeon) context.miyorareViewPaletteFromPreferences() else null
	val glass = normalPalette?.neonGlass()
	val celestialSignature = when (normalPalette?.rankThemeId) {
		RankThemeId.IMPERIAL_AURORA.stableId -> RankThemeSignatureRegistry.resolve(RankThemeId.IMPERIAL_AURORA)
		RankThemeId.ETERNAL_LIBRARY.stableId -> RankThemeSignatureRegistry.resolve(RankThemeId.ETERNAL_LIBRARY)
		else -> null
	}
	val celestialBorderStops = celestialSignature?.borderStops?.map(Long::toInt)?.toIntArray()
	val celestialSelectedStops = celestialSignature?.selectedStops?.map(Long::toInt)?.toIntArray()
	val darkTheme = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
		Configuration.UI_MODE_NIGHT_YES
	val controlHeight = (if (normalNeon) MiyorareFavouritesVisualSpec.QUICK_FILTER_HEIGHT_DP else 32f) * density
	val controlRadius = (if (normalNeon) MiyorareFavouritesVisualSpec.QUICK_FILTER_RADIUS_DP else 16f) * density
	val iconSize = (if (normalNeon) MiyorareFavouritesVisualSpec.QUICK_FILTER_ICON_DP else 16f) * density
	val horizontalPadding = (if (normalNeon) MiyorareFavouritesVisualSpec.QUICK_FILTER_HORIZONTAL_PADDING_DP else 8f) * density
	val textPadding = (if (normalNeon) MiyorareFavouritesVisualSpec.QUICK_FILTER_TEXT_GAP_DP else 3.5f) * density

	chipSpacingHorizontal = ((if (normalNeon) MiyorareFavouritesVisualSpec.QUICK_FILTER_GAP_DP else 5f) * density).toInt()
	children.forEachIndexed { index, child ->
		val chip = child as? Chip ?: return@forEachIndexed
		val selected = chip.isChecked
		val container = if (normalNeon && glass != null) {
			val glassBase = if (selected) {
				ColorUtils.blendARGB(glass.selectedSurface, glass.innerHighlight, 0.18f)
			} else {
				ColorUtils.blendARGB(glass.surfaceStrong, glass.innerHighlight, 0.10f)
			}
			if (subtleGlassFill) {
				// Modern quick filters should read as one glass control, not a filled chip
				// nested inside the neon chrome. Light mode uses an especially quiet tint because
				// the bright wallpaper already supplies separation; dark mode can carry a little
				// more glass density without turning into a second inner pill.
				val tintAlpha = when {
					darkTheme && selected -> 0.16f
					darkTheme -> 0.10f
					selected -> 0.12f
					else -> 0.08f
				}
				ColorUtils.setAlphaComponent(
					glassBase,
					(tintAlpha * 255f).roundToInt().coerceIn(0, 255),
				)
			} else {
				glassBase
			}
		} else if (selected) {
			ColorUtils.blendARGB(surfaceHigh, primary, MiyorareVisualTokens.ACTIVE_GRADIENT_MIX * 0.34f)
		} else {
			ColorUtils.blendARGB(surface, primary, MiyorareVisualTokens.GLOW_ALPHA_LIGHT * 0.75f)
		}
		val stroke = if (normalNeon && glass != null) {
			if (selected) glass.selectedBorder else glass.borderStrong
		} else {
			val strokeBase = if (selected) primary else outline
			val strokeAlpha = if (selected) {
				MiyorareVisualTokens.BORDER_ALPHA_BALANCED * 0.66f
			} else {
				MiyorareVisualTokens.BORDER_ALPHA_LIGHT * 0.85f
			}
			ColorUtils.setAlphaComponent(
				strokeBase,
				(strokeAlpha * 255f).toInt().coerceIn(0, 255),
			)
		}
		val contentColor = if (normalNeon && glass != null) {
			if (darkTheme) {
				if (selected) glass.content else glass.contentMuted
			} else if (selected) {
				ColorUtils.blendARGB(onSurface, primary, 0.26f)
			} else {
				onSurfaceVariant
			}
		} else if (selected) {
			ColorUtils.blendARGB(onSurface, primary, 0.32f)
		} else {
			onSurfaceVariant
		}

		chip.chipMinHeight = controlHeight
		chip.chipCornerRadius = controlRadius
		chip.chipIconSize = iconSize
		chip.closeIconSize = iconSize
		chip.chipStartPadding = horizontalPadding
		chip.chipEndPadding = horizontalPadding
		chip.textStartPadding = textPadding
		chip.textEndPadding = textPadding
		chip.setTextSize(
			TypedValue.COMPLEX_UNIT_SP,
			if (normalNeon) MiyorareFavouritesVisualSpec.QUICK_FILTER_TEXT_SP else 13f,
		)
		chip.chipBackgroundColor = ColorStateList.valueOf(container)
		if (normalNeon && glass != null) {
			// Material owns the fill only; one foreground chrome owns both halo and crisp edge.
			// This avoids the old double-outline stack while keeping a single luminous perimeter
			// for Favourites, Updates, History and Feed quick filters.
			chip.chipStrokeWidth = 0f
			chip.chipStrokeColor = ColorStateList.valueOf(Color.TRANSPARENT)
			chip.foreground = createMiyorareFavouritesActionChrome(
				glass = glass,
				radius = controlRadius,
				density = density,
				selected = selected,
				darkTheme = darkTheme,
				signatureBorderStops = celestialBorderStops,
				signatureSelectedStops = celestialSelectedStops,
			)
		} else {
			chip.chipStrokeWidth = density * if (selected) 0.75f else 0.6f
			chip.chipStrokeColor = ColorStateList.valueOf(stroke)
			chip.foreground = null
		}
		chip.setTextColor(contentColor)
		chip.tintInlineCounters(contentColor)
		chip.chipIconTint = ColorStateList.valueOf(contentColor)
		chip.closeIconTint = ColorStateList.valueOf(contentColor)
		chip.rippleColor = ColorStateList.valueOf(
			if (normalNeon && glass != null) {
				glass.glow
			} else {
				ColorUtils.setAlphaComponent(
					primary,
					(MiyorareVisualTokens.GLOW_ALPHA_BALANCED * 0.80f * 255f).toInt(),
				)
			},
		)
		// Avoid black Material elevation shadows; the luminous alpha stroke carries depth.
		chip.elevation = 0f
	}
}

private fun createMiyorareFavouritesActionChrome(
	glass: MiyorareNeonGlassColors,
	radius: Float,
	density: Float,
	selected: Boolean,
	darkTheme: Boolean,
	signatureBorderStops: IntArray? = null,
	signatureSelectedStops: IntArray? = null,
): LayerDrawable {
	// One soft halo + one crisp edge. This removes the previous outer/mid/near stack that
	// looked like multiple nested pills on bright backgrounds while preserving the neon identity.
	val activeGlow = if (selected) glass.selectedGlow else glass.glow
	val glowWidthDp = when {
		darkTheme && selected -> 7f
		darkTheme -> 6f
		selected -> 5.5f
		else -> 4.5f
	}
	val glowAlphaFactor = when {
		darkTheme && selected -> 0.16f
		darkTheme -> 0.11f
		selected -> 0.10f
		else -> 0.07f
	}
	val edgeBase = if (selected) glass.selectedBorder else glass.borderStrong
	val edgeAlphaFactor = when {
		darkTheme && selected -> 0.96f
		darkTheme -> 0.90f
		selected -> 0.76f
		else -> 0.66f
	}

	val halo = GradientDrawable().apply {
		setColor(Color.TRANSPARENT)
		cornerRadius = radius
		setStroke(
			(glowWidthDp * density).roundToInt().coerceAtLeast(1),
			ColorUtils.setAlphaComponent(
				activeGlow,
				(Color.alpha(activeGlow) * glowAlphaFactor).roundToInt(),
			),
		)
	}
	val authoredStops = if (selected) signatureSelectedStops ?: signatureBorderStops else signatureBorderStops
	val edge: Drawable = if (authoredStops != null && authoredStops.size >= 2) {
		QuickFilterPrismStrokeDrawable(
			colors = authoredStops,
			cornerRadius = (radius - density).coerceAtLeast(0f),
			strokeWidth = density.roundToInt().coerceAtLeast(1).toFloat(),
			alphaScale = edgeAlphaFactor,
		)
	} else {
		GradientDrawable().apply {
			setColor(Color.TRANSPARENT)
			cornerRadius = (radius - density).coerceAtLeast(0f)
			setStroke(
				density.roundToInt().coerceAtLeast(1),
				ColorUtils.setAlphaComponent(
					edgeBase,
					(Color.alpha(edgeBase) * edgeAlphaFactor).roundToInt(),
				),
			)
		}
	}
	return LayerDrawable(
		arrayOf(
			halo,
			InsetDrawable(edge, density.roundToInt().coerceAtLeast(1)),
		),
	)
}

private class QuickFilterPrismStrokeDrawable(
	private val colors: IntArray,
	private val cornerRadius: Float,
	private val strokeWidth: Float,
	private val alphaScale: Float,
) : Drawable() {
	private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		style = Paint.Style.STROKE
		this.strokeWidth = this@QuickFilterPrismStrokeDrawable.strokeWidth
	}
	private val rect = RectF()
	private var drawableAlpha = 255

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
		rect.set(bounds.left + half, bounds.top + half, bounds.right - half, bounds.bottom - half)
		paint.alpha = (drawableAlpha * alphaScale).roundToInt().coerceIn(0, 255)
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

/** ChipsView renders counters with an explicit ForegroundColorSpan, which overrides setTextColor(). */
private fun Chip.tintInlineCounters(color: Int) {
	val current = text as? Spanned ?: return
	val spans = current.getSpans(0, current.length, ForegroundColorSpan::class.java)
	if (spans.isEmpty()) return
	val styled = SpannableString(current)
	for (span in spans) {
		val start = current.getSpanStart(span)
		val end = current.getSpanEnd(span)
		if (start < 0 || end <= start) continue
		val flags = current.getSpanFlags(span)
		styled.removeSpan(span)
		styled.setSpan(ForegroundColorSpan(color), start, end, flags)
	}
	text = styled
}
