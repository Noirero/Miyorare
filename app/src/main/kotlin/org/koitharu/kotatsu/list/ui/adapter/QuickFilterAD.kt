package org.koitharu.kotatsu.list.ui.adapter

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import androidx.core.graphics.ColorUtils
import androidx.core.view.doOnLayout
import androidx.preference.PreferenceManager
import com.google.android.material.chip.Chip
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.MiyorareAppearance
import org.koitharu.kotatsu.core.prefs.MiyorareDesignStyle
import org.koitharu.kotatsu.core.ui.MiyorareFavouritesVisualSpec
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
		binding.applyMiyorareFavouritesQuickFilterStyle(item)
	}
}

/**
 * Keeps the shared quick-filter adapter neutral by default and applies the compact Miyorare treatment
 * only to the Favourites quick-filter row. The same adapter is used by other list screens, so neither
 * Classic Favourites nor unrelated quick filters should inherit this visual pass.
 */
private fun ItemQuickFilterBinding.applyMiyorareFavouritesQuickFilterStyle(item: QuickFilter) {
	val isFavouritesQuickFilter = item.items.any { it.titleResId == R.string.favorites_continue_reading } &&
		item.items.any { it.titleResId == R.string.favorites_filter }
	if (!isFavouritesQuickFilter) return

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
	if (!isPrivate) {
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
			chipsTags.setFixedChildWidth(actionWidth)
		}
	}
	chipsTags.applyMiyorareFavouritesQuickFilterStyle(normalNeon = !isPrivate)
}

private fun ChipsView.applyMiyorareFavouritesQuickFilterStyle(
	normalNeon: Boolean,
) {
	val density = resources.displayMetrics.density
	val primary = context.getThemeColor(androidx.appcompat.R.attr.colorPrimary, Color.WHITE)
	val surface = context.getThemeColor(materialR.attr.colorSurfaceContainer, Color.DKGRAY)
	val surfaceHigh = context.getThemeColor(materialR.attr.colorSurfaceContainerHighest, surface)
	val onSurface = context.getThemeColor(materialR.attr.colorOnSurface, Color.WHITE)
	val onSurfaceVariant = context.getThemeColor(materialR.attr.colorOnSurfaceVariant, onSurface)
	val outline = context.getThemeColor(materialR.attr.colorOutlineVariant, primary)
	val glass = if (normalNeon) context.miyorareViewPaletteFromPreferences()?.neonGlass() else null
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
			if (selected) glass.selectedSurface else glass.surfaceStrong
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
			if (selected) glass.content else glass.contentMuted
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
		chip.chipStrokeWidth = density * if (normalNeon) 1.0f else if (selected) 0.75f else 0.6f
		chip.chipBackgroundColor = ColorStateList.valueOf(container)
		chip.chipStrokeColor = ColorStateList.valueOf(stroke)
		if (normalNeon && glass != null) {
			// Chip owns one crisp Material stroke plus one soft halo and one inner highlight.
			// Do not stack a second neon edge over the Material stroke.
			val activeGlow = if (selected) glass.selectedGlow else glass.glow
			val outerGlowLayer = GradientDrawable().apply {
				setColor(Color.TRANSPARENT)
				cornerRadius = controlRadius
				setStroke(
					((if (selected) 5f else 3.5f) * density).toInt().coerceAtLeast(1),
					ColorUtils.setAlphaComponent(activeGlow, (Color.alpha(activeGlow) * 0.44f).toInt()),
				)
			}
			val highlightLayer = GradientDrawable().apply {
				setColor(Color.TRANSPARENT)
				cornerRadius = (controlRadius - 2f * density).coerceAtLeast(0f)
				setStroke(density.toInt().coerceAtLeast(1), glass.innerHighlight)
			}
			chip.foreground = LayerDrawable(
				arrayOf(
					outerGlowLayer,
					InsetDrawable(highlightLayer, (2f * density).toInt().coerceAtLeast(1)),
				),
			)
		} else {
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
