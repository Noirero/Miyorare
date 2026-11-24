package org.koitharu.kotatsu.search.ui.multi

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.View
import androidx.core.graphics.ColorUtils
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.color.MaterialColors
import org.koitharu.kotatsu.core.ui.MiyorareVisualTokens
import org.koitharu.kotatsu.core.ui.miyorareViewPaletteFromPreferences
import kotlin.math.roundToInt
import androidx.appcompat.R as appcompatR
import com.google.android.material.R as materialR

/** Presentation-only chrome; does not own search state or cover geometry. */
internal class GlobalSearchChrome(private val view: View) {

	private val palette = view.context.miyorareViewPaletteFromPreferences()
	private val density = view.resources.displayMetrics.density
	private fun color(attr: Int) = MaterialColors.getColor(view, attr)

	val primary = palette?.primary ?: color(appcompatR.attr.colorPrimary)
	val onSurface = palette?.onSurface ?: color(materialR.attr.colorOnSurface)
	val muted = palette?.onSurfaceVariant ?: color(materialR.attr.colorOnSurfaceVariant)
	private val surface = palette?.surface ?: color(materialR.attr.colorSurface)
	private val card = palette?.surfaceContainer ?: color(materialR.attr.colorSurfaceContainer)
	private val outline = palette?.outlineVariant ?: color(materialR.attr.colorOutlineVariant)
	private val selected = palette?.primaryContainer ?: color(materialR.attr.colorPrimaryContainer)
	private val onSelected = palette?.onPrimaryContainer ?: color(materialR.attr.colorOnPrimaryContainer)
	private val error = color(appcompatR.attr.colorError)
	private val stroke = dp(1f)
	private fun dp(value: Float) = (value * density).roundToInt().coerceAtLeast(1)

	private fun shape(fill: Int, border: Int?, radius: Float): GradientDrawable =
		GradientDrawable().apply {
			setColor(fill)
			cornerRadius = radius * density
			if (border != null) setStroke(stroke, border)
		}

	fun headerBackground() = shape(
		ColorUtils.blendARGB(card, primary, 0.04f), outline, MiyorareVisualTokens.RADIUS_CARD_DP,
	)

	fun iconBackground() = shape(
		ColorUtils.blendARGB(card, primary, 0.12f), null, MiyorareVisualTokens.RADIUS_SMALL_DP,
	)

	fun toolbarBackground() = GradientDrawable(
		GradientDrawable.Orientation.TL_BR,
		intArrayOf(surface, ColorUtils.blendARGB(surface, primary, 0.08f)),
	)

	fun languageBackground() = shape(surface, outline, MiyorareVisualTokens.RADIUS_SMALL_DP)

	fun countBackground(active: Boolean) = shape(
		if (active) ColorUtils.blendARGB(card, primary, 0.09f) else surface,
		if (active) ColorUtils.blendARGB(outline, primary, 0.45f) else outline,
		MiyorareVisualTokens.RADIUS_SMALL_DP,
	)

	fun statusColor(status: SearchSourceStatus) = when (status) {
		SearchSourceStatus.LOADING -> muted
		SearchSourceStatus.ERROR -> error
		SearchSourceStatus.COMPLETE -> onSelected
	}

	fun statusBackground(status: SearchSourceStatus) = shape(
		if (status == SearchSourceStatus.COMPLETE) selected else surface,
		if (status == SearchSourceStatus.ERROR) error else null,
		MiyorareVisualTokens.RADIUS_SMALL_DP,
	)

	fun styleMore(button: MaterialButton) {
		button.cornerRadius = dp(MiyorareVisualTokens.RADIUS_CONTROL_DP)
		button.strokeWidth = stroke
		button.strokeColor = ColorStateList.valueOf(ColorUtils.blendARGB(outline, primary, 0.55f))
		button.backgroundTintList = ColorStateList.valueOf(ColorUtils.blendARGB(card, primary, 0.08f))
		button.setTextColor(primary)
		button.iconTint = ColorStateList.valueOf(primary)
	}

	fun styleChip(chip: Chip, emphasized: Boolean = false) {
		val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
		chip.chipBackgroundColor = ColorStateList(states, intArrayOf(selected, card))
		chip.chipStrokeWidth = stroke.toFloat()
		chip.chipStrokeColor = ColorStateList(states, intArrayOf(primary, if (emphasized) primary else outline))
		chip.setTextColor(ColorStateList(states, intArrayOf(onSelected, onSurface)))
		chip.chipIconTint = ColorStateList(states, intArrayOf(onSelected, primary))
		chip.shapeAppearanceModel = chip.shapeAppearanceModel.toBuilder()
			.setAllCornerSizes(MiyorareVisualTokens.RADIUS_CONTROL_DP * density)
			.build()
	}
}
