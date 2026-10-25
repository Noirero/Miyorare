package org.koitharu.kotatsu.favourites.ui.container

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.MiyorareViewPalette
import org.koitharu.kotatsu.core.ui.MiyorareVisualTokens
import org.koitharu.kotatsu.core.ui.miyorareViewPaletteFromPreferences
import org.koitharu.kotatsu.core.util.ext.findActivity
import org.koitharu.kotatsu.favourites.data.EXTRA_FAVOURITE_SPACE
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import kotlin.math.roundToInt

/**
 * Presentation owner for the two stacked Category Sets navigation surfaces.
 * Data and interaction still belong to [FavouritesContainerFragment]; this class only keeps the
 * visual hierarchy aligned with the current Favourites glass language.
 */
class CategoryNavigationRow @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

	private var presentationPosted = false
	private var applyingPresentation = false

	override fun onFinishInflate() {
		super.onFinishInflate()
		findViewById<TextView>(R.id.button_category_set).apply {
			val size = dp(19f)
			val arrow = requireNotNull(context.getDrawable(R.drawable.ic_expand_more)).mutate().apply {
				setBounds(0, 0, size, size)
			}
			setCompoundDrawablesRelative(arrow, null, null, null)
			compoundDrawablePadding = dp(8f)
			ViewCompat.setAccessibilityDelegate(this, object : androidx.core.view.AccessibilityDelegateCompat() {
				override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
					super.onInitializeAccessibilityNodeInfo(host, info)
					info.className = android.widget.Button::class.java.name
				}
			})
		}
	}

	override fun onAttachedToWindow() {
		super.onAttachedToWindow()
		schedulePresentation()
	}

	override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
		super.onWindowFocusChanged(hasWindowFocus)
		if (hasWindowFocus && isShown) schedulePresentation()
	}

	/**
	 * MiyorareFavouritesHeaderLayout still refreshes the header as one unit. Intercept its legacy
	 * rail background assignment and re-apply this newer two-surface presentation afterward.
	 */
	override fun setBackground(background: Drawable?) {
		if (
			applyingPresentation ||
			!isAttachedToWindow ||
			context.miyorareViewPaletteFromPreferences(isPrivateFavouritesHost()) == null
		) {
			super.setBackground(background)
			return
		}
		schedulePresentation()
	}

	fun refreshPresentation() = schedulePresentation()

	private fun schedulePresentation() {
		if (!isAttachedToWindow || presentationPosted) return
		presentationPosted = true
		post {
			presentationPosted = false
			applyModernPresentation()
		}
	}

	private fun applyModernPresentation() {
		val palette = context.miyorareViewPaletteFromPreferences(isPrivateFavouritesHost()) ?: return
		val setButton = findViewById<TextView>(R.id.button_category_set)
		val tabs = findViewById<TabLayout>(R.id.tabs)
		val picker = findViewById<MaterialButton>(R.id.button_category_picker)

		// The reference hierarchy is two independent glass boxes, not one shared rail.
		applyingPresentation = true
		try {
			super.setBackground(null)
		} finally {
			applyingPresentation = false
		}
		setButton.apply {
			setTextColor(palette.onSurface)
			compoundDrawableTintList = ColorStateList.valueOf(palette.onSurfaceVariant)
			background = glassRipple(palette, selected = false)
		}
		tabs.apply {
			background = glassSurface(palette, selected = false)
			setTabTextColors(palette.onSurfaceVariant, palette.onSurface)
			setTabRippleColor(ColorStateList.valueOf(ColorUtils.setAlphaComponent(palette.primary, 34)))
		}
		picker.apply {
			backgroundTintList = null
			background = glassRipple(palette, selected = true)
			setTextColor(palette.onSurface)
			iconTint = ColorStateList.valueOf(palette.borderHighlight)
			strokeWidth = 0
			foreground = null
			elevation = 0f
		}
	}

	private fun isPrivateFavouritesHost(): Boolean = context.findActivity()?.intent?.getIntExtra(
		EXTRA_FAVOURITE_SPACE,
		FavouriteSpace.NORMAL.dbValue,
	) == FavouriteSpace.PRIVATE.dbValue

	private fun glassRipple(palette: MiyorareViewPalette, selected: Boolean): RippleDrawable {
		val radius = MiyorareVisualTokens.RADIUS_SURFACE_DP * resources.displayMetrics.density
		return RippleDrawable(
			ColorStateList.valueOf(ColorUtils.setAlphaComponent(palette.primary, 38)),
			glassSurface(palette, selected),
			GradientDrawable().apply {
				shape = GradientDrawable.RECTANGLE
				cornerRadius = radius
				setColor(Color.WHITE)
			},
		)
	}

	private fun glassSurface(palette: MiyorareViewPalette, selected: Boolean): LayerDrawable {
		val density = resources.displayMetrics.density
		val radius = MiyorareVisualTokens.RADIUS_SURFACE_DP * density
		val stroke = dp(if (selected) 1.35f else 1f).coerceAtLeast(1)
		val glowStroke = dp(if (selected) 4f else 2f).coerceAtLeast(1)
		val glowAlpha = if (selected) 118 else 64
		val surfaceAlpha = if (selected) 176 else 146
		val glow = GradientDrawable().apply {
			shape = GradientDrawable.RECTANGLE
			cornerRadius = radius
			setColor(Color.TRANSPARENT)
			setStroke(glowStroke, ColorUtils.setAlphaComponent(palette.glow, glowAlpha))
		}
		val surface = GradientDrawable(
			GradientDrawable.Orientation.LEFT_RIGHT,
			intArrayOf(
				ColorUtils.setAlphaComponent(
					if (selected) ColorUtils.blendARGB(palette.surfaceGradientStart, palette.primary, 0.16f)
					else palette.surfaceGradientStart,
					surfaceAlpha,
				),
				ColorUtils.setAlphaComponent(palette.surfaceGradientMiddle, (surfaceAlpha * 0.88f).roundToInt()),
				ColorUtils.setAlphaComponent(
					if (selected) ColorUtils.blendARGB(palette.surfaceGradientEnd, palette.secondary, 0.16f)
					else palette.surfaceGradientEnd,
					surfaceAlpha,
				),
			),
		).apply {
			cornerRadius = radius
			setStroke(
				stroke,
				ColorUtils.setAlphaComponent(
					if (selected) palette.borderHighlight else palette.outlineVariant,
					if (selected) 218 else 150,
				),
			)
		}
		return LayerDrawable(arrayOf(glow, surface)).apply {
			setLayerInset(1, dp(2f), dp(2f), dp(2f), dp(2f))
		}
	}

	private fun dp(value: Float): Int = (value * resources.displayMetrics.density).roundToInt()
}

/**
 * The latest owner-approved reference promotes the Set label back to a full contextual row.
 * The Fragment continues to own the raw Set title while this view owns its localized visible copy.
 */
class CategorySetButton @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
) : AppCompatTextView(context, attrs) {
	override fun setText(text: CharSequence?, type: BufferType?) {
		val value = text?.toString().orEmpty()
		super.setText(
			if (value.isBlank()) value else context.getString(R.string.category_set_selector, value),
			type,
		)
	}
}
