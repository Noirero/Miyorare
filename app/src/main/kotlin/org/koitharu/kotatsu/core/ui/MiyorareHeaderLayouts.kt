package org.koitharu.kotatsu.core.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.search.SearchBar
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
 * While Favourites is visible it also gives the shared Main search app bar the matching upper half
 * of the Decorative treatment. Classic never receives this chrome and the original shared app-bar
 * state is restored when this screen becomes hidden or detached.
 */
class MiyorareFavouritesHeaderLayout @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
	defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

	private var applyingModernBackground = false
	private var decoratedAppBar: AppBarLayout? = null
	private var decoratedSearchBar: SearchBar? = null
	private var originalAppBarBackground: Drawable? = null
	private var originalSearchBackgroundTint: ColorStateList? = null

	override fun onFinishInflate() {
		super.onFinishInflate()
		updateModernOnlyCopyVisibility()
	}

	override fun onAttachedToWindow() {
		super.onAttachedToWindow()
		post(::applyModernPresentation)
	}

	/**
	 * Theme preset changes happen in Settings while this View can stay attached behind that Activity.
	 * Re-read preferences when the app regains focus so returning to Miyorare/Sakura/etc immediately
	 * redraws the correct motif instead of leaving a stale or missing background.
	 */
	override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
		super.onWindowFocusChanged(hasWindowFocus)
		if (hasWindowFocus && isAttachedToWindow && isShown) {
			post(::applyModernPresentation)
		}
	}

	override fun onDetachedFromWindow() {
		restoreGlobalAppBarChrome()
		super.onDetachedFromWindow()
	}

	override fun onVisibilityChanged(changedView: View, visibility: Int) {
		super.onVisibilityChanged(changedView, visibility)
		if (!isAttachedToWindow) return
		if (visibility == View.VISIBLE && isShown) {
			post(::applyModernPresentation)
		} else {
			restoreGlobalAppBarChrome()
		}
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
			restoreGlobalAppBarChrome()
			return
		}
		if (!isShown) {
			restoreGlobalAppBarChrome()
			return
		}

		val density = resources.displayMetrics.density
		fun dp(value: Float) = (value * density).roundToInt()
		val surfaceRadius = MiyorareVisualTokens.RADIUS_SURFACE_DP * density
		val controlRadius = dp(MiyorareVisualTokens.RADIUS_CONTROL_DP)
		val strokeWidth = dp(1f).coerceAtLeast(1)

		applyGlobalAppBarChrome(palette)

		findViewById<android.widget.TextView>(R.id.text_favourites_title)?.apply {
			isVisible = true
			setTextColor(palette.onSurface)
			textSize = 27f
			letterSpacing = -0.012f
		}
		findViewById<android.widget.TextView>(R.id.text_favourites_subtitle)?.apply {
			isVisible = true
			setTextColor(ColorUtils.setAlphaComponent(palette.onSurfaceVariant, 224))
		}

		// Reference-matched theme artwork lives in the body panel; the shared app bar stays quiet.
		val header = MiyorareHeaderShapeDrawable(
			palette = palette,
			variant = MiyorareHeaderShapeDrawable.Variant.FAVOURITES_BODY,
			density = density,
		)
		applyingModernBackground = true
		try {
			super.setBackground(header)
		} finally {
			applyingModernBackground = false
		}
		elevation = 2f * density
		setPadding(0, dp(14f), 0, dp(14f))

		findViewById<MaterialButtonToggleGroup>(R.id.toggle_content_type)?.apply {
			setPadding(dp(3f), dp(3f), dp(3f), dp(3f))
			background = GradientDrawable(
				GradientDrawable.Orientation.LEFT_RIGHT,
				intArrayOf(
					ColorUtils.blendARGB(palette.surfaceContainerHigh, palette.primary, 0.14f),
					ColorUtils.blendARGB(palette.surfaceContainer, palette.accent, 0.09f),
					palette.surfaceContainer,
				),
			).apply {
				cornerRadius = surfaceRadius
				setStroke(strokeWidth, ColorUtils.setAlphaComponent(palette.outlineVariant, 92))
			}
			val states = arrayOf(
				intArrayOf(android.R.attr.state_checked, android.R.attr.state_enabled),
				intArrayOf(-android.R.attr.state_enabled),
				intArrayOf(),
			)
			val fills = ColorStateList(
				states,
				intArrayOf(
					ColorUtils.blendARGB(palette.primaryContainer, palette.primary, 0.08f),
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
			setTabTextColors(
				ColorUtils.setAlphaComponent(palette.onSurfaceVariant, 216),
				palette.primary,
			)
			setTabRippleColor(ColorStateList.valueOf(ColorUtils.setAlphaComponent(palette.primary, 28)))
		}

		findViewById<MaterialButton>(R.id.button_category_picker)?.apply {
			backgroundTintList = ColorStateList.valueOf(
				ColorUtils.blendARGB(palette.surfaceContainer, palette.primary, 0.06f),
			)
			setTextColor(palette.onSurface)
			iconTint = ColorStateList.valueOf(palette.primary)
			cornerRadius = controlRadius
			this.strokeWidth = strokeWidth
			strokeColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(palette.outlineVariant, 100))
		}
	}

	private fun applyGlobalAppBarChrome(palette: MiyorareViewPalette) {
		val appBar = rootView.findViewById<AppBarLayout>(R.id.appbar) ?: return
		val searchBar = rootView.findViewById<SearchBar>(R.id.search_bar)
		if (decoratedAppBar !== appBar) {
			restoreGlobalAppBarChrome()
			decoratedAppBar = appBar
			decoratedSearchBar = searchBar
			originalAppBarBackground = appBar.background
			originalSearchBackgroundTint = searchBar?.backgroundTintList
		}

		appBar.background = MiyorareHeaderShapeDrawable(
			palette = palette,
			variant = MiyorareHeaderShapeDrawable.Variant.FAVOURITES_TOP,
			density = resources.displayMetrics.density,
		)
		appBar.elevation = 0f
		searchBar?.backgroundTintList = ColorStateList.valueOf(
			ColorUtils.blendARGB(palette.surfaceContainerHigh, palette.primary, 0.07f),
		)
	}

	private fun restoreGlobalAppBarChrome() {
		decoratedAppBar?.background = originalAppBarBackground
		decoratedSearchBar?.backgroundTintList = originalSearchBackgroundTint
		decoratedAppBar = null
		decoratedSearchBar = null
		originalAppBarBackground = null
		originalSearchBackgroundTint = null
	}
}

/** Preset-aware Semi Decorative top chrome for the Compose manga-details hero. */
class MiyorareDetailsHeaderAppBarLayout @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
	defStyleAttr: Int = com.google.android.material.R.attr.appBarLayoutStyle,
) : AppBarLayout(context, attrs, defStyleAttr) {

	override fun onAttachedToWindow() {
		super.onAttachedToWindow()
		post(::applyModernPresentation)
	}

	override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
		super.onWindowFocusChanged(hasWindowFocus)
		if (hasWindowFocus && isAttachedToWindow && isShown) {
			post(::applyModernPresentation)
		}
	}

	private fun applyModernPresentation() {
		val palette = context.miyorareViewPaletteFromPreferences() ?: return
		background = MiyorareHeaderShapeDrawable(
			palette = palette,
			variant = MiyorareHeaderShapeDrawable.Variant.DETAILS,
			density = resources.displayMetrics.density,
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
