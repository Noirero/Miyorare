package org.koitharu.kotatsu.explore.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.widget.LinearLayout
import androidx.core.graphics.ColorUtils
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.tabs.TabLayout
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.MiyorareNeonGlassColors
import org.koitharu.kotatsu.core.ui.MiyorareVisualTokens
import org.koitharu.kotatsu.core.ui.miyorareViewPaletteFromPreferences
import org.koitharu.kotatsu.core.ui.neonGlass
import kotlin.math.roundToInt

/**
 * Preset-aware Semi/Clean presentation shell for Explore's compact browsing controls.
 *
 * Classic remains visually untouched. Source view mode is intentionally kept out of this header and
 * lives in Explore's overflow menu, leaving Manga / Novel / Extensions and content filters as the
 * primary visible controls.
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

	override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
		super.onWindowFocusChanged(hasWindowFocus)
		if (hasWindowFocus && isAttachedToWindow && isShown) {
			applyModernPresentationIfNeeded()
		}
	}

	private fun applyModernPresentationIfNeeded() {
		val palette = context.miyorareViewPaletteFromPreferences() ?: return
		val density = resources.displayMetrics.density
		val radius = MiyorareVisualTokens.RADIUS_CONTROL_DP * density
		val strokeWidth = density.roundToInt().coerceAtLeast(1)

		// MainActivity already owns the blurred wallpaper. Explore must not paint a second opaque
		// header canvas over it; only the individual controls carry glass.
		setBackgroundColor(Color.TRANSPARENT)
		elevation = 0f
		val glass = palette.neonGlass()

		styleContentFilter(
			findViewById(R.id.toggle_source_view),
			glass = glass,
			radius = radius.roundToInt(),
			strokeWidth = strokeWidth,
		)
		styleContentFilter(
			findViewById(R.id.toggle_content_filter),
			glass = glass,
			radius = radius.roundToInt(),
			strokeWidth = strokeWidth,
		)

		findViewById<LinearLayout>(R.id.kind_rail)?.background = GradientDrawable().apply {
			setColor(ColorUtils.blendARGB(glass.railSurface, glass.innerHighlight, 0.10f))
			cornerRadius = radius
			setStroke(strokeWidth, glass.borderStrong)
		}
		findViewById<TabLayout>(R.id.tabs_kind)?.apply {
			setSelectedTabIndicatorColor(glass.selectedBorder)
			setTabTextColors(glass.contentMuted, glass.content)
			setTabRippleColor(ColorStateList.valueOf(glass.glow))
			background = ColorDrawable(Color.TRANSPARENT)
		}

		findViewById<MaterialButton>(R.id.button_manage)?.apply {
			// Extensions remains the third segment of the shared rail.
			backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
			setTextColor(glass.content)
			iconTint = ColorStateList.valueOf(glass.content)
			cornerRadius = 0
			this.strokeWidth = 0
		}
	}

	private fun styleContentFilter(
		group: MaterialButtonToggleGroup?,
		glass: MiyorareNeonGlassColors,
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
				glass.selectedSurface,
				ColorUtils.setAlphaComponent(glass.surfaceStrong, 118),
				ColorUtils.blendARGB(glass.surfaceStrong, glass.innerHighlight, 0.08f),
			),
		)
		val textColors = ColorStateList(
			states,
			intArrayOf(
				glass.content,
				ColorUtils.setAlphaComponent(glass.contentMuted, 110),
				glass.contentMuted,
			),
		)
		val strokeColors = ColorStateList(
			states,
			intArrayOf(
				glass.selectedBorder,
				ColorUtils.setAlphaComponent(glass.borderStrong, 64),
				glass.borderStrong,
			),
		)
		for (index in 0 until group.childCount) {
			(group.getChildAt(index) as? MaterialButton)?.apply {
				backgroundTintList = backgrounds
				setTextColor(textColors)
				strokeColor = strokeColors
				this.strokeWidth = strokeWidth
				cornerRadius = radius
				elevation = 0f
			}
		}
	}}
