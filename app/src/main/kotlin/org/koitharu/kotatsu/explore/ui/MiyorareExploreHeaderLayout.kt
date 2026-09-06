package org.koitharu.kotatsu.explore.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.widget.LinearLayout
import androidx.core.graphics.ColorUtils
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.tabs.TabLayout
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.MiyorareHeaderShapeDrawable
import org.koitharu.kotatsu.core.ui.MiyorareVisualTokens
import org.koitharu.kotatsu.core.ui.miyorareViewPaletteFromPreferences
import kotlin.math.roundToInt

/**
 * Preset-aware Semi/Clean presentation shell for Explore's existing header.
 *
 * Classic is deliberately a no-op. Modern keeps Explore close to its existing layout while giving
 * the content filter, manga/novel rail and Manage action a restrained semantic tint plus one subtle
 * curved shape layer. All clicks, filtering, pager behavior and source data remain owned by
 * [ExploreFragment].
 */
class MiyorareExploreHeaderLayout @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
	defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

	override fun onFinishInflate() {
		super.onFinishInflate()
		applyModernPresentationIfNeeded()
	}

	override fun onAttachedToWindow() {
		super.onAttachedToWindow()
		applyModernPresentationIfNeeded()
	}

	private fun applyModernPresentationIfNeeded() {
		val palette = context.miyorareViewPaletteFromPreferences() ?: return
		val density = resources.displayMetrics.density
		val radius = MiyorareVisualTokens.RADIUS_CONTROL_DP * density
		val strokeWidth = density.roundToInt().coerceAtLeast(1)

		// Shape Language v2 stays intentionally restrained here: one soft formed surface with a
		// cropped orbital accent. The extension grid and existing Explore hierarchy remain unchanged.
		background = MiyorareHeaderShapeDrawable(
			palette = palette,
			variant = MiyorareHeaderShapeDrawable.Variant.EXPLORE,
			density = density,
		)
		elevation = 0f

		styleContentFilter(
			findViewById(R.id.toggle_content_filter),
			primary = palette.primary,
			primaryContainer = palette.primaryContainer,
			onPrimaryContainer = palette.onPrimaryContainer,
			surfaceContainer = palette.surfaceContainer,
			onSurfaceVariant = palette.onSurfaceVariant,
			outlineVariant = palette.outlineVariant,
			radius = radius.roundToInt(),
			strokeWidth = strokeWidth,
		)

		findViewById<TabLayout>(R.id.tabs_kind)?.apply {
			setSelectedTabIndicatorColor(palette.primary)
			setTabTextColors(palette.onSurfaceVariant, palette.primary)
			setTabRippleColor(ColorStateList.valueOf(ColorUtils.setAlphaComponent(palette.primary, 22)))
			background = GradientDrawable().apply {
				setColor(palette.surfaceContainer)
				cornerRadius = radius
				setStroke(strokeWidth, ColorUtils.setAlphaComponent(palette.outlineVariant, 118))
			}
		}

		findViewById<MaterialButton>(R.id.button_manage)?.apply {
			backgroundTintList = ColorStateList.valueOf(palette.surfaceContainer)
			setTextColor(palette.primary)
			iconTint = ColorStateList.valueOf(palette.primary)
			cornerRadius = radius.roundToInt()
			this.strokeWidth = strokeWidth
			strokeColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(palette.outlineVariant, 110))
		}
	}

	private fun styleContentFilter(
		group: MaterialButtonToggleGroup?,
		primary: Int,
		primaryContainer: Int,
		onPrimaryContainer: Int,
		surfaceContainer: Int,
		onSurfaceVariant: Int,
		outlineVariant: Int,
		radius: Int,
		strokeWidth: Int,
	) {
		if (group == null) return
		val states = arrayOf(
			intArrayOf(android.R.attr.state_checked, android.R.attr.state_enabled),
			intArrayOf(-android.R.attr.state_enabled),
			intArrayOf(),
		)
		val backgrounds = ColorStateList(
			states,
			intArrayOf(
				primaryContainer,
				ColorUtils.blendARGB(surfaceContainer, onSurfaceVariant, 0.05f),
				surfaceContainer,
			),
		)
		val textColors = ColorStateList(
			states,
			intArrayOf(
				onPrimaryContainer,
				ColorUtils.setAlphaComponent(onSurfaceVariant, 110),
				onSurfaceVariant,
			),
		)
		val strokeColors = ColorStateList(
			states,
			intArrayOf(
				ColorUtils.setAlphaComponent(primary, 132),
				ColorUtils.setAlphaComponent(outlineVariant, 64),
				ColorUtils.setAlphaComponent(outlineVariant, 118),
			),
		)
		for (index in 0 until group.childCount) {
			(group.getChildAt(index) as? MaterialButton)?.apply {
				backgroundTintList = backgrounds
				setTextColor(textColors)
				strokeColor = strokeColors
				this.strokeWidth = strokeWidth
				cornerRadius = radius
			}
		}
	}
}
