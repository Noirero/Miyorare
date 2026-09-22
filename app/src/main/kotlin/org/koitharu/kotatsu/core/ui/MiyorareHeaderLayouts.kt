package org.koitharu.kotatsu.core.ui

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.appcompat.widget.ActionMenuView
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.search.SearchBar
import com.google.android.material.tabs.TabLayout
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.util.ext.findActivity
import org.koitharu.kotatsu.favourites.data.EXTRA_FAVOURITE_SPACE
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
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

/** Decorative Modern shell for Favourites. */
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
	private var originalSearchForeground: Drawable? = null
	private var originalSearchElevation: Float? = null
	private val originalIconButtonChrome = HashMap<Int, IconButtonChrome>()

	private data class IconButtonChrome(
		val backgroundTint: ColorStateList?,
		val iconTint: ColorStateList?,
		val strokeColor: ColorStateList?,
		val strokeWidth: Int,
		val cornerRadius: Int,
		val elevation: Float,
	)

	override fun onFinishInflate() {
		super.onFinishInflate()
		updateModernOnlyCopyVisibility()
	}

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
		val privateFavourites = isPrivateFavouritesHost()
		if (
			applyingModernBackground ||
			!isAttachedToWindow ||
			context.miyorareViewPaletteFromPreferences(privateFavourites) == null
		) {
			super.setBackground(background)
			return
		}
		post(::applyModernPresentation)
	}

	private fun updateModernOnlyCopyVisibility() {
		val modern = context.miyorareViewPaletteFromPreferences(isPrivateFavouritesHost()) != null
		findViewById<android.widget.TextView>(R.id.text_favourites_title)?.isVisible = modern
		findViewById<android.widget.TextView>(R.id.text_favourites_subtitle)?.isVisible = modern
	}

	private fun applyModernPresentation() {
		val privateFavourites = isPrivateFavouritesHost()
		val palette = context.miyorareViewPaletteFromPreferences(privateFavourites)
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
		val glass = if (privateFavourites) null else palette.neonGlass()
		val isNightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
			Configuration.UI_MODE_NIGHT_YES
		// Normal Favourites uses authored hero artwork that remains dark even when the app is in light
		// mode. Keep hero copy independent from light-theme surface colors so it cannot become dark-on-dark.
		// Private Favourites has its own visual treatment and intentionally keeps its existing palette.
		val useLightHeroForeground = !privateFavourites && !isNightMode
		val heroTitleColor = if (useLightHeroForeground) Color.WHITE else palette.onSurface
		val heroSubtitleColor = if (useLightHeroForeground) {
			ColorUtils.setAlphaComponent(Color.WHITE, 224)
		} else {
			ColorUtils.setAlphaComponent(palette.onSurfaceVariant, 224)
		}

		applyGlobalAppBarChrome(palette, privateFavourites)

		findViewById<android.widget.TextView>(R.id.text_favourites_title)?.apply {
			isVisible = true
			if (privateFavourites) setText(R.string.private_favourites)
			setTextColor(heroTitleColor)
			if (useLightHeroForeground) {
				setShadowLayer(2.4f * density, 0f, 1f * density, ColorUtils.setAlphaComponent(Color.BLACK, 150))
			} else {
				setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
			}
			textSize = 27f
			letterSpacing = -0.012f
			if (!privateFavourites) {
				// Keep the heart attached to the title instead of placing it at the far edge of a
				// match-parent TextView.
				layoutParams = layoutParams.apply { width = ViewGroup.LayoutParams.WRAP_CONTENT }
				setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_heart_outline, 0)
				compoundDrawableTintList = ColorStateList.valueOf(palette.primary)
				compoundDrawablePadding = dp(7f)
			}
		}
		findViewById<android.widget.TextView>(R.id.text_favourites_subtitle)?.apply {
			isVisible = true
			setTextColor(heroSubtitleColor)
			if (useLightHeroForeground) {
				setShadowLayer(1.8f * density, 0f, 1f * density, ColorUtils.setAlphaComponent(Color.BLACK, 136))
			} else {
				setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
			}
		}

		applyingModernBackground = true
		try {
			super.setBackground(createFavouritesHeaderDrawable(palette, MiyorareHeaderShapeDrawable.Variant.FAVOURITES_BODY, privateFavourites))
		} finally {
			applyingModernBackground = false
		}
		// A full-width View elevation produced a dark horizontal seam under the category rail.
		// Keep the header flat; individual glass controls carry their own restrained depth.
		elevation = 0f
		setPadding(0, dp(14f), 0, dp(14f))

		findViewById<MaterialButtonToggleGroup>(R.id.toggle_content_type)?.apply {
			setPadding(dp(3f), dp(3f), dp(3f), dp(3f))
			background = if (privateFavourites) {
				GradientDrawable(
					GradientDrawable.Orientation.LEFT_RIGHT,
					intArrayOf(
						ColorUtils.blendARGB(palette.surfaceContainerHigh, palette.primary, 0.20f),
						ColorUtils.blendARGB(palette.surfaceContainer, palette.accent, 0.13f),
						palette.surfaceContainer,
					),
				).apply {
					cornerRadius = surfaceRadius
					setStroke(strokeWidth, ColorUtils.setAlphaComponent(palette.outlineVariant, 126))
				}
			} else {
				createNormalGlassSurface(
					glass = glass!!,
					radius = surfaceRadius,
					density = density,
					selected = false,
				)
			}
			val states = arrayOf(
				intArrayOf(android.R.attr.state_checked, android.R.attr.state_enabled),
				intArrayOf(-android.R.attr.state_enabled),
				intArrayOf(),
			)
			val fills = ColorStateList(
				states,
				intArrayOf(
					if (privateFavourites) {
						ColorUtils.blendARGB(palette.primaryContainer, palette.primary, 0.16f)
					} else {
						glass!!.selectedSurface
					},
					ColorUtils.setAlphaComponent(palette.surfaceContainerHigh, 150),
					Color.TRANSPARENT,
				),
			)
			val text = ColorStateList(
				states,
				intArrayOf(
					if (privateFavourites) palette.onPrimaryContainer else if (useLightHeroForeground) Color.WHITE else palette.onSurface,
					ColorUtils.setAlphaComponent(palette.onSurfaceVariant, 110),
					if (privateFavourites) palette.onSurfaceVariant else heroSubtitleColor,
				),
			)
			for (index in 0 until childCount) {
				(getChildAt(index) as? MaterialButton)?.apply {
					backgroundTintList = fills
					setTextColor(text)
					cornerRadius = controlRadius
					if (privateFavourites) {
						this.strokeWidth = 0
					} else {
						this.strokeWidth = strokeWidth
						strokeColor = ColorStateList(
							states,
							intArrayOf(glass!!.selectedBorder, Color.TRANSPARENT, Color.TRANSPARENT),
						)
						elevation = if (isChecked) dp(4f).toFloat() else 0f
					}
				}
			}
		}

		findViewById<TabLayout>(R.id.tabs)?.apply {
			setSelectedTabIndicatorColor(Color.TRANSPARENT)
			setTabTextColors(
				if (useLightHeroForeground) ColorUtils.setAlphaComponent(Color.WHITE, 218)
				else ColorUtils.setAlphaComponent(palette.onSurfaceVariant, 216),
				if (useLightHeroForeground) Color.WHITE else palette.primary,
			)
			setTabRippleColor(
				ColorStateList.valueOf(
					if (privateFavourites) ColorUtils.setAlphaComponent(palette.primary, 28) else glass!!.glow,
				),
			)
		}

		findViewById<MaterialButton>(R.id.button_category_picker)?.apply {
			backgroundTintList = ColorStateList.valueOf(
				if (privateFavourites) {
					ColorUtils.blendARGB(palette.surfaceContainer, palette.primary, 0.12f)
				} else {
					glass!!.surfaceStrong
				},
			)
			setTextColor(palette.onSurface)
			iconTint = ColorStateList.valueOf(palette.primary)
			cornerRadius = controlRadius
			this.strokeWidth = strokeWidth
			strokeColor = ColorStateList.valueOf(
				if (privateFavourites) ColorUtils.setAlphaComponent(palette.outlineVariant, 132) else glass!!.borderStrong,
			)
			if (!privateFavourites) elevation = dp(3f).toFloat()
		}
	}

	private fun createNormalGlassOutline(
		glass: MiyorareNeonGlassColors,
		radius: Float,
		density: Float,
	): Drawable {
		val glowStroke = (3f * density).roundToInt().coerceAtLeast(1)
		val edgeStroke = density.roundToInt().coerceAtLeast(1)
		val glowLayer = GradientDrawable().apply {
			setColor(Color.TRANSPARENT)
			cornerRadius = radius
			setStroke(glowStroke, glass.glow)
		}
		val edgeLayer = GradientDrawable().apply {
			setColor(Color.TRANSPARENT)
			cornerRadius = (radius - density).coerceAtLeast(0f)
			setStroke(edgeStroke, glass.borderStrong)
		}
		return LayerDrawable(
			arrayOf(
				glowLayer,
				InsetDrawable(edgeLayer, density.roundToInt().coerceAtLeast(1)),
			),
		)
	}

	private fun createNormalGlassSurface(
		glass: MiyorareNeonGlassColors,
		radius: Float,
		density: Float,
		selected: Boolean,
	): Drawable {
		val glowStroke = (3f * density).roundToInt().coerceAtLeast(1)
		val edgeStroke = density.roundToInt().coerceAtLeast(1)
		val glowLayer = GradientDrawable().apply {
			setColor(Color.TRANSPARENT)
			cornerRadius = radius
			setStroke(glowStroke, glass.glow)
		}
		val fillLayer = GradientDrawable().apply {
			setColor(if (selected) glass.selectedSurface else glass.surfaceStrong)
			cornerRadius = (radius - density).coerceAtLeast(0f)
			setStroke(edgeStroke, if (selected) glass.selectedBorder else glass.borderStrong)
		}
		return LayerDrawable(
			arrayOf(
				glowLayer,
				InsetDrawable(fillLayer, density.roundToInt().coerceAtLeast(1)),
			),
		)
	}

	private fun createFavouritesHeaderDrawable(
		palette: MiyorareViewPalette,
		variant: MiyorareHeaderShapeDrawable.Variant,
		privateFavourites: Boolean,
	): Drawable {
		val privateSpec = if (privateFavourites) context.privateFavouritesVisualSpecFromPreferences() else null
		return if (privateSpec != null) {
			MiyorarePrivateFavouritesHeaderDrawable(
				palette = palette,
				variant = variant,
				spec = privateSpec,
				density = resources.displayMetrics.density,
			)
		} else {
			MiyorareHeaderShapeDrawable(
				palette = palette,
				variant = variant,
				density = resources.displayMetrics.density,
			)
		}
	}

	private fun applyGlobalAppBarChrome(palette: MiyorareViewPalette, privateFavourites: Boolean) {
		val appBar = rootView.findViewById<AppBarLayout>(R.id.appbar) ?: return
		val searchBar = rootView.findViewById<SearchBar>(R.id.search_bar)
		if (decoratedAppBar !== appBar) {
			restoreGlobalAppBarChrome()
			decoratedAppBar = appBar
			decoratedSearchBar = searchBar
			originalAppBarBackground = appBar.background
			originalSearchBackgroundTint = searchBar?.backgroundTintList
			originalSearchForeground = searchBar?.foreground
			originalSearchElevation = searchBar?.elevation
			for (id in intArrayOf(R.id.button_settings, R.id.button_overflow)) {
				rootView.findViewById<MaterialButton>(id)?.let { button ->
					originalIconButtonChrome[id] = IconButtonChrome(
						backgroundTint = button.backgroundTintList,
						iconTint = button.iconTint,
						strokeColor = button.strokeColor,
						strokeWidth = button.strokeWidth,
						cornerRadius = button.cornerRadius,
						elevation = button.elevation,
					)
				}
			}
		}

		appBar.background = createFavouritesHeaderDrawable(
			palette,
			MiyorareHeaderShapeDrawable.Variant.FAVOURITES_TOP,
			privateFavourites,
		)
		appBar.elevation = 0f
		if (privateFavourites) {
			// Private keeps its exact pre-reskin chrome.
			searchBar?.backgroundTintList = ColorStateList.valueOf(
				ColorUtils.blendARGB(palette.surfaceContainerHigh, palette.primary, 0.12f),
			)
			return
		}

		val density = resources.displayMetrics.density
		fun dp(value: Float) = (value * density).roundToInt()
		val glass = palette.neonGlass()
		searchBar?.apply {
			backgroundTintList = ColorStateList.valueOf(glass.surfaceStrong)
			foreground = createNormalGlassOutline(
				glass = glass,
				radius = dp(28f).toFloat(),
				density = density,
			)
			elevation = dp(3f).toFloat()
		}
		for (id in intArrayOf(R.id.button_settings, R.id.button_overflow)) {
			rootView.findViewById<MaterialButton>(id)?.apply {
				backgroundTintList = ColorStateList.valueOf(glass.surfaceStrong)
				iconTint = ColorStateList.valueOf(palette.onSurface)
				cornerRadius = dp(24f)
				strokeWidth = dp(1f).coerceAtLeast(1)
				strokeColor = ColorStateList.valueOf(glass.borderStrong)
				elevation = dp(4f).toFloat()
			}
		}
	}

	private fun isPrivateFavouritesHost(): Boolean {
		val activity = context.findActivity() ?: return false
		return activity.intent?.getIntExtra(EXTRA_FAVOURITE_SPACE, FavouriteSpace.NORMAL.dbValue) ==
			FavouriteSpace.PRIVATE.dbValue
	}

	private fun restoreGlobalAppBarChrome() {
		decoratedAppBar?.background = originalAppBarBackground
		decoratedSearchBar?.apply {
			backgroundTintList = originalSearchBackgroundTint
			foreground = originalSearchForeground
			originalSearchElevation?.let { elevation = it }
		}
		for ((id, chrome) in originalIconButtonChrome) {
			rootView.findViewById<MaterialButton>(id)?.apply {
				backgroundTintList = chrome.backgroundTint
				iconTint = chrome.iconTint
				strokeColor = chrome.strokeColor
				strokeWidth = chrome.strokeWidth
				cornerRadius = chrome.cornerRadius
				elevation = chrome.elevation
			}
		}
		originalIconButtonChrome.clear()
		decoratedAppBar = null
		decoratedSearchBar = null
		originalAppBarBackground = null
		originalSearchBackgroundTint = null
		originalSearchForeground = null
		originalSearchElevation = null
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
		val density = resources.displayMetrics.density
		fun dp(value: Float) = (value * density).roundToInt()

		setBackgroundColor(Color.TRANSPARENT)
		elevation = 0f
		findViewById<MaterialToolbar>(R.id.toolbar)?.apply {
			setBackgroundColor(Color.TRANSPARENT)
			setTitleTextColor(palette.onSurface)
			setContentInsetsRelative(dp(12f), dp(12f))
			contentInsetStartWithNavigation = dp(12f)
			contentInsetEndWithActions = dp(12f)
			minimumHeight = dp(58f)
			setPadding(0, dp(3f), 0, dp(3f))
			navigationIcon?.setTint(palette.onSurface)
			overflowIcon?.setTint(palette.onSurface)
			for (index in 0 until menu.size()) {
				menu.getItem(index).icon?.setTint(palette.onSurface)
			}
			post { applyFloatingDetailsToolbar(this, palette, density) }
		}
	}

	private fun applyFloatingDetailsToolbar(
		toolbar: MaterialToolbar,
		palette: MiyorareViewPalette,
		density: Float,
	) {
		fun dp(value: Float) = (value * density).roundToInt()
		val stroke = dp(1f).coerceAtLeast(1)
		val glowAlpha = Color.alpha(palette.glow)
		val isFullEffect = glowAlpha >= 64
		val isBalancedEffect = !isFullEffect && glowAlpha >= 30
		val toolbarAccent = if (isFullEffect) {
			ColorUtils.blendARGB(palette.primary, palette.secondary, 0.28f)
		} else {
			palette.primary
		}
		val navigationButton = (0 until toolbar.childCount)
			.map { toolbar.getChildAt(it) }
			.filterIsInstance<ImageButton>()
			.firstOrNull { it.parent === toolbar }
		navigationButton?.apply {
			background = GradientDrawable().apply {
				shape = GradientDrawable.OVAL
				val fill = ColorUtils.blendARGB(
					palette.surfaceContainerHigh,
					toolbarAccent,
					if (isFullEffect) 0.045f else if (isBalancedEffect) 0.018f else 0.008f,
				)
				setColor(ColorUtils.setAlphaComponent(fill, if (isFullEffect) 204 else if (isBalancedEffect) 196 else 188))
				setStroke(
					stroke,
					ColorUtils.setAlphaComponent(toolbarAccent, if (isFullEffect) 156 else if (isBalancedEffect) 118 else 86),
				)
			}
			elevation = dp(if (isFullEffect) 7f else if (isBalancedEffect) 4f else 2f).toFloat()
			layoutParams = layoutParams.apply {
				width = dp(48f)
				height = dp(48f)
			}
			setPadding(dp(11f), dp(11f), dp(11f), dp(11f))
		}

		val actionMenu = (0 until toolbar.childCount)
			.map { toolbar.getChildAt(it) }
			.filterIsInstance<ActionMenuView>()
			.firstOrNull()
		actionMenu?.apply {
			background = GradientDrawable().apply {
				val fill = ColorUtils.blendARGB(
					palette.surfaceContainerHigh,
					toolbarAccent,
					if (isFullEffect) 0.040f else if (isBalancedEffect) 0.016f else 0.008f,
				)
				setColor(ColorUtils.setAlphaComponent(fill, if (isFullEffect) 204 else if (isBalancedEffect) 198 else 190))
				cornerRadius = dp(24f).toFloat()
				setStroke(
					stroke,
					ColorUtils.setAlphaComponent(toolbarAccent, if (isFullEffect) 148 else if (isBalancedEffect) 108 else 78),
				)
			}
			elevation = dp(if (isFullEffect) 7f else if (isBalancedEffect) 4f else 2f).toFloat()
			minimumHeight = dp(48f)
			setPadding(dp(4f), 0, dp(4f), 0)
		}
	}
}
