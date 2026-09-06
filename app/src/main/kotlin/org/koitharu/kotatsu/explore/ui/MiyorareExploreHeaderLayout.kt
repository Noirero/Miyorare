package org.koitharu.kotatsu.explore.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.widget.LinearLayout
import androidx.core.graphics.ColorUtils
import androidx.preference.PreferenceManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.tabs.TabLayout
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.MiyorareAppearance
import org.koitharu.kotatsu.core.prefs.MiyorareDesignStyle
import org.koitharu.kotatsu.core.ui.MiyorareVisualTokens
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import kotlin.math.roundToInt
import androidx.appcompat.R as appcompatR
import com.google.android.material.R as materialR

/**
 * Preset-aware presentation shell for Explore's existing header.
 *
 * Classic is deliberately a no-op. Modern keeps the screen readable/clean and only gives the
 * content filter, manga/novel rail and Manage action a restrained semantic tint. All clicks,
 * filtering, pager behavior and source data remain owned by [ExploreFragment].
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
		val prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
		if (
			prefs.getString(MiyorareAppearance.KEY_DESIGN_STYLE, MiyorareDesignStyle.CLASSIC.name) !=
			MiyorareDesignStyle.MODERN.name
		) {
			return
		}

		val density = resources.displayMetrics.density
		val primary = context.getThemeColor(appcompatR.attr.colorPrimary, Color.WHITE)
		val primaryContainer = context.getThemeColor(materialR.attr.colorPrimaryContainer, primary)
		val onPrimaryContainer = context.getThemeColor(materialR.attr.colorOnPrimaryContainer, primary)
		val surfaceContainer = context.getThemeColor(materialR.attr.colorSurfaceContainer, Color.TRANSPARENT)
		val onSurfaceVariant = context.getThemeColor(materialR.attr.colorOnSurfaceVariant, primary)
		val outlineVariant = context.getThemeColor(materialR.attr.colorOutlineVariant, onSurfaceVariant)
		val radius = MiyorareVisualTokens.RADIUS_CONTROL_DP * density
		val strokeWidth = density.roundToInt().coerceAtLeast(1)

		styleContentFilter(
			findViewById(R.id.toggle_content_filter),
			primary = primary,
			primaryContainer = primaryContainer,
			onPrimaryContainer = onPrimaryContainer,
			surfaceContainer = surfaceContainer,
			onSurfaceVariant = onSurfaceVariant,
			outlineVariant = outlineVariant,
			radius = radius.roundToInt(),
			strokeWidth = strokeWidth,
		)

		findViewById<TabLayout>(R.id.tabs_kind)?.apply {
			setSelectedTabIndicatorColor(primary)
			setTabTextColors(onSurfaceVariant, primary)
			setTabRippleColor(ColorStateList.valueOf(ColorUtils.setAlphaComponent(primary, 22)))
			background = GradientDrawable().apply {
				setColor(surfaceContainer)
				cornerRadius = radius
				setStroke(strokeWidth, ColorUtils.setAlphaComponent(outlineVariant, 118))
			}
		}

		findViewById<MaterialButton>(R.id.button_manage)?.apply {
			backgroundTintList = ColorStateList.valueOf(surfaceContainer)
			setTextColor(primary)
			iconTint = ColorStateList.valueOf(primary)
			cornerRadius = radius.roundToInt()
			this.strokeWidth = strokeWidth
			strokeColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(outlineVariant, 110))
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
