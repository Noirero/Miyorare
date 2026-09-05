package org.koitharu.kotatsu.list.ui.adapter

import android.content.res.ColorStateList
import android.graphics.Color
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import androidx.core.graphics.ColorUtils
import androidx.core.view.children
import androidx.preference.PreferenceManager
import com.google.android.material.chip.Chip
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.MiyorareAppearance
import org.koitharu.kotatsu.core.prefs.MiyorareDesignStyle
import org.koitharu.kotatsu.core.ui.MiyorareVisualTokens
import org.koitharu.kotatsu.core.ui.widgets.ChipsView
import org.koitharu.kotatsu.core.util.ext.getEnumValue
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import org.koitharu.kotatsu.databinding.ItemQuickFilterBinding
import org.koitharu.kotatsu.list.domain.ListFilterOption
import org.koitharu.kotatsu.list.ui.model.ExtensionFilter
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.QuickFilter
import com.google.android.material.R as materialR

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

	chipsTags.applyMiyorareFavouritesQuickFilterStyle()
}

private fun ChipsView.applyMiyorareFavouritesQuickFilterStyle() {
	val density = resources.displayMetrics.density
	val primary = context.getThemeColor(androidx.appcompat.R.attr.colorPrimary, Color.WHITE)
	val surface = context.getThemeColor(materialR.attr.colorSurfaceContainer, Color.DKGRAY)
	val surfaceHigh = context.getThemeColor(materialR.attr.colorSurfaceContainerHighest, surface)
	val onSurface = context.getThemeColor(materialR.attr.colorOnSurface, Color.WHITE)
	val onSurfaceVariant = context.getThemeColor(materialR.attr.colorOnSurfaceVariant, onSurface)
	val outline = context.getThemeColor(materialR.attr.colorOutlineVariant, primary)
	val controlHeight = 32f * density
	val controlRadius = 16f * density
	val iconSize = 16f * density
	val horizontalPadding = 8f * density
	val textPadding = 3.5f * density

	chipSpacingHorizontal = (5f * density).toInt()
	children.forEach { child ->
		val chip = child as? Chip ?: return@forEach
		val selected = chip.isChecked
		val container = if (selected) {
			ColorUtils.blendARGB(surfaceHigh, primary, MiyorareVisualTokens.ACTIVE_GRADIENT_MIX * 0.34f)
		} else {
			ColorUtils.blendARGB(surface, primary, MiyorareVisualTokens.GLOW_ALPHA_LIGHT * 0.75f)
		}
		val strokeBase = if (selected) primary else outline
		val strokeAlpha = if (selected) {
			MiyorareVisualTokens.BORDER_ALPHA_BALANCED * 0.66f
		} else {
			MiyorareVisualTokens.BORDER_ALPHA_LIGHT * 0.85f
		}
		val stroke = ColorUtils.setAlphaComponent(
			strokeBase,
			(strokeAlpha * 255f).toInt().coerceIn(0, 255),
		)
		val contentColor = if (selected) {
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
		chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
		chip.chipStrokeWidth = density * if (selected) 0.75f else 0.6f
		chip.chipBackgroundColor = ColorStateList.valueOf(container)
		chip.chipStrokeColor = ColorStateList.valueOf(stroke)
		chip.setTextColor(contentColor)
		chip.tintInlineCounters(contentColor)
		chip.chipIconTint = ColorStateList.valueOf(contentColor)
		chip.closeIconTint = ColorStateList.valueOf(contentColor)
		chip.rippleColor = ColorStateList.valueOf(
			ColorUtils.setAlphaComponent(
				primary,
				(MiyorareVisualTokens.GLOW_ALPHA_BALANCED * 0.80f * 255f).toInt(),
			),
		)
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
