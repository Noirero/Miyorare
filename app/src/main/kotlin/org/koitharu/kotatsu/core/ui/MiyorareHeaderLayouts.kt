package org.koitharu.kotatsu.core.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.widget.LinearLayout
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.tabs.TabLayout
import org.koitharu.kotatsu.R
import kotlin.math.roundToInt

/** Locked automatic header policy for Miyorare Modern. There is deliberately no user selector. */
enum class MiyorareHeaderStyle {
	DECORATIVE,
	SEMI_DECORATIVE,
	CLEAN,
}

object MiyorareHeaderPolicy {
	val favourites = MiyorareHeaderStyle.DECORATIVE
	val details = MiyorareHeaderStyle.SEMI_DECORATIVE
	val explore = MiyorareHeaderStyle.SEMI_DECORATIVE
	val downloads = MiyorareHeaderStyle.CLEAN
	val settings = MiyorareHeaderStyle.CLEAN
}

/**
 * Decorative Modern shell for Favourites.
 *
 * The existing fragment still owns navigation and interaction. This View only owns presentation.
 * It deliberately re-applies its preset-aware styling after the fragment's legacy attribute-based
 * visual pass, which otherwise collapses every Modern preset back to the static XML overlay colors.
 */
class MiyorareFavouritesHeaderLayout @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
	defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

	private var applyingModernBackground = false

	override fun onFinishInflate() {
		super.onFinishInflate()
		updateModernOnlyCopyVisibility()
	}

	override fun onAttachedToWindow() {
		super.onAttachedToWindow()
		post(::applyModernPresentation)
	}

	override fun setBackground(background: Drawable?) {
		if (
			applyingModernBackground ||
			!isAttachedToWindow ||
			context.miyorareViewPaletteFromPreferences() == null
		) {
			super.setBackground(background)
			return
		}
		// FavouritesContainerFragment still performs its legacy visual pass. Let that pass finish,
		// then restore the preset-aware Decorative header instead of accepting static theme attrs.
		post(::applyModernPresentation)
	}

	private fun updateModernOnlyCopyVisibility() {
		val modern = context.miyorareViewPaletteFromPreferences() != null
		findViewById<android.widget.TextView>(R.id.text_favourites_title)?.isVisible = modern
		findViewById<android.widget.TextView>(R.id.text_favourites_subtitle)?.isVisible = modern
	}

	private fun applyModernPresentation() {
		val palette = context.miyorareViewPaletteFromPreferences()
		if (palette == null) {
			updateModernOnlyCopyVisibility()
			return
		}

		val density = resources.displayMetrics.density
		fun dp(value: Float) = (value * density).roundToInt()
		val surfaceRadius = MiyorareVisualTokens.RADIUS_SURFACE_DP * density
		val controlRadius = dp(MiyorareVisualTokens.RADIUS_CONTROL_DP)
		val strokeWidth = dp(1f).coerceAtLeast(1)

		findViewById<android.widget.TextView>(R.id.text_favourites_title)?.apply {
			isVisible = true
			setTextColor(palette.onSurface)
		}
		findViewById<android.widget.TextView>(R.id.text_favourites_subtitle)?.apply {
			isVisible = true
			setTextColor(palette.onSurfaceVariant)
		}

		val header = GradientDrawable(
			GradientDrawable.Orientation.TL_BR,
			intArrayOf(
				palette.surfaceGradientStart,
				palette.surfaceGradientMiddle,
				palette.surfaceGradientEnd,
			),
		).apply {
			cornerRadii = floatArrayOf(
				0f, 0f,
				0f, 0f,
				surfaceRadius, surfaceRadius,
				surfaceRadius, surfaceRadius,
			)
			setStroke(strokeWidth, palette.borderHighlight)
		}
		applyingModernBackground = true
		try {
			super.setBackground(header)
		} finally {
			applyingModernBackground = false
		}
		elevation = 1.5f * density
		setPadding(0, dp(8f), 0, dp(8f))

		findViewById<MaterialButtonToggleGroup>(R.id.toggle_content_type)?.apply {
			setPadding(dp(2f), dp(2f), dp(2f), dp(2f))
			background = GradientDrawable(
				GradientDrawable.Orientation.LEFT_RIGHT,
				intArrayOf(
					ColorUtils.blendARGB(palette.surfaceContainer, palette.primary, 0.10f),
					ColorUtils.blendARGB(palette.surfaceContainer, palette.accent, 0.07f),
					palette.surfaceContainer,
				),
			).apply {
				cornerRadius = surfaceRadius
				setStroke(strokeWidth, ColorUtils.setAlphaComponent(palette.outlineVariant, 112))
			}
			val states = arrayOf(
				intArrayOf(android.R.attr.state_checked, android.R.attr.state_enabled),
				intArrayOf(-android.R.attr.state_enabled),
				intArrayOf(),
			)
			val fills = ColorStateList(
				states,
				intArrayOf(
					palette.primaryContainer,
					ColorUtils.setAlphaComponent(palette.surfaceContainerHigh, 150),
					Color.TRANSPARENT,
				),
			)
			val text = ColorStateList(
				states,
				intArrayOf(
					palette.onPrimaryContainer,
					ColorUtils.setAlphaComponent(palette.onSurfaceVariant, 110),
					palette.onSurfaceVariant,
				),
			)
			for (index in 0 until childCount) {
				(getChildAt(index) as? MaterialButton)?.apply {
					backgroundTintList = fills
					setTextColor(text)
					cornerRadius = controlRadius
					this.strokeWidth = 0
				}
			}
		}

		findViewById<TabLayout>(R.id.tabs)?.apply {
			setSelectedTabIndicatorColor(Color.TRANSPARENT)
			setTabTextColors(palette.onSurfaceVariant, palette.primary)
			setTabRippleColor(ColorStateList.valueOf(ColorUtils.setAlphaComponent(palette.primary, 24)))
		}

		findViewById<MaterialButton>(R.id.button_category_picker)?.apply {
			backgroundTintList = ColorStateList.valueOf(palette.surfaceContainer)
			setTextColor(palette.onSurface)
			iconTint = ColorStateList.valueOf(palette.primary)
			cornerRadius = controlRadius
			this.strokeWidth = strokeWidth
			strokeColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(palette.outlineVariant, 120))
		}
	}
}

/** Preset-aware Semi Decorative top chrome for the Compose manga-details hero. */
class MiyorareDetailsHeaderAppBarLayout @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
	defStyleAttr: Int = 0,
) : AppBarLayout(context, attrs, defStyleAttr) {

	override fun onAttachedToWindow() {
		super.onAttachedToWindow()
		post(::applyModernPresentation)
	}

	private fun applyModernPresentation() {
		val palette = context.miyorareViewPaletteFromPreferences() ?: return
		background = GradientDrawable(
			GradientDrawable.Orientation.TOP_BOTTOM,
			intArrayOf(
				ColorUtils.setAlphaComponent(palette.surfaceGradientStart, 232),
				ColorUtils.setAlphaComponent(palette.surfaceGradientMiddle, 150),
				Color.TRANSPARENT,
			),
		)
		elevation = 0f
		findViewById<MaterialToolbar>(R.id.toolbar)?.apply {
			setBackgroundColor(Color.TRANSPARENT)
			setTitleTextColor(palette.onSurface)
			navigationIcon?.setTint(palette.onSurface)
			overflowIcon?.setTint(palette.onSurfaceVariant)
		}
	}
}
