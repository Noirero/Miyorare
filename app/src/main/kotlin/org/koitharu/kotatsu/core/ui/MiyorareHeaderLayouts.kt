package org.koitharu.kotatsu.core.ui

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
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
	private var modernPresentationPosted = false
	private var decoratedAppBar: AppBarLayout? = null
	private var decoratedSearchBar: SearchBar? = null
	private var originalAppBarBackground: Drawable? = null
	private var originalSearchBackgroundTint: ColorStateList? = null
	private var originalSearchForeground: Drawable? = null
	private var originalSearchElevation: Float? = null
	private var originalSearchGeometry: SearchBarGeometry? = null
	private var originalSearchRowGeometry: SearchRowGeometry? = null
	private val originalIconButtonChrome = HashMap<Int, IconButtonChrome>()

	private data class SearchBarGeometry(
		val layoutHeight: Int,
		val minimumHeight: Int,
		val marginEnd: Int,
	)

	private data class SearchRowGeometry(
		val paddingStart: Int,
		val paddingTop: Int,
		val paddingEnd: Int,
		val paddingBottom: Int,
		val startFrameWidth: Int,
		val startFrameHeight: Int,
		val startFrameMarginEnd: Int,
		val endFrameWidth: Int,
		val endFrameHeight: Int,
	)

	private data class IconButtonChrome(
		val backgroundTint: ColorStateList?,
		val iconTint: ColorStateList?,
		val strokeColor: ColorStateList?,
		val strokeWidth: Int,
		val cornerRadius: Int,
		val elevation: Float,
		val foreground: Drawable?,
		val layoutWidth: Int,
		val layoutHeight: Int,
		val minimumWidth: Int,
		val minimumHeight: Int,
		val layoutGravity: Int,
	)

	override fun onFinishInflate() {
		super.onFinishInflate()
		updateModernOnlyCopyVisibility()
	}

	/**
	 * Re-applies the single Normal-Favourites presentation owner after a theme/effect preference change.
	 * Callers request a refresh only; they must not style the same controls independently.
	 */
	fun refreshModernPresentation() = scheduleModernPresentation()

	private fun scheduleModernPresentation() {
		if (!isAttachedToWindow || modernPresentationPosted) return
		modernPresentationPosted = true
		post {
			modernPresentationPosted = false
			applyModernPresentation()
		}
	}

	override fun onAttachedToWindow() {
		super.onAttachedToWindow()
		scheduleModernPresentation()
	}

	override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
		super.onWindowFocusChanged(hasWindowFocus)
		if (hasWindowFocus && isShown) {
			scheduleModernPresentation()
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
			scheduleModernPresentation()
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
		scheduleModernPresentation()
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
			if (!privateFavourites) {
				setShadowLayer(
					3.2f * density,
					0f,
					0f,
					glass!!.selectedGlow,
				)
			} else if (useLightHeroForeground) {
				setShadowLayer(2.4f * density, 0f, 1f * density, ColorUtils.setAlphaComponent(Color.BLACK, 150))
			} else {
				setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
			}
			textSize = MiyorareFavouritesVisualSpec.TITLE_TEXT_SP
			letterSpacing = -0.014f
			if (!privateFavourites) {
				// Keep the heart attached to the title instead of placing it at the far edge of a
				// match-parent TextView.
				layoutParams = layoutParams.apply { width = ViewGroup.LayoutParams.WRAP_CONTENT }
				val heart = context.getDrawable(R.drawable.ic_heart_outline)?.mutate()?.apply {
					setTint(glass!!.selectedBorder)
					val size = dp(MiyorareFavouritesVisualSpec.TITLE_HEART_DP)
					setBounds(0, 0, size, size)
				}
				setCompoundDrawablesRelative(null, null, heart, null)
				compoundDrawablePadding = dp(MiyorareFavouritesVisualSpec.TITLE_HEART_GAP_DP)
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
		setPadding(
			0,
			dp(MiyorareFavouritesVisualSpec.HEADER_TOP_PADDING_DP),
			0,
			dp(MiyorareFavouritesVisualSpec.HEADER_BOTTOM_PADDING_DP),
		)
		if (!privateFavourites) {
			applyNormalHeaderGeometry(
				palette = palette,
				glass = checkNotNull(glass),
				density = density,
			)
		}

		findViewById<MaterialButtonToggleGroup>(R.id.toggle_content_type)?.apply {
			val toggleInset = dp(MiyorareFavouritesVisualSpec.CONTENT_TOGGLE_INSET_DP)
			setPadding(toggleInset, toggleInset, toggleInset, toggleInset)
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
					if (privateFavourites) palette.onPrimaryContainer else glass!!.content,
					ColorUtils.setAlphaComponent(palette.onSurfaceVariant, 110),
					if (privateFavourites) palette.onSurfaceVariant else glass!!.contentMuted,
				),
			)
			for (index in 0 until childCount) {
				(getChildAt(index) as? MaterialButton)?.apply {
					backgroundTintList = fills
					setTextColor(text)
					if (!privateFavourites) iconTint = text
					cornerRadius = controlRadius
					if (privateFavourites) {
						this.strokeWidth = 0
					} else {
						this.strokeWidth = dp(1.5f).coerceAtLeast(1)
						strokeColor = ColorStateList(
							states,
							intArrayOf(glass!!.selectedBorder, Color.TRANSPARENT, Color.TRANSPARENT),
						)
						// Keep the crisp Material stroke, then add only a broad low-alpha checked
						// bloom. This reads as illumination instead of the old stacked outline.
						foreground = createCheckedGlassBloom(
							glass = glass!!,
							radius = controlRadius.toFloat(),
							density = density,
						)
						elevation = 0f
					}
				}
			}
		}

		findViewById<TabLayout>(R.id.tabs)?.apply {
			setSelectedTabIndicatorColor(Color.TRANSPARENT)
			setTabTextColors(
				if (privateFavourites) {
					if (useLightHeroForeground) ColorUtils.setAlphaComponent(Color.WHITE, 218)
					else ColorUtils.setAlphaComponent(palette.onSurfaceVariant, 216)
				} else {
					glass!!.contentMuted
				},
				if (privateFavourites) {
					if (useLightHeroForeground) Color.WHITE else palette.primary
				} else {
					glass!!.content
				},
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
					glass!!.railSurface
				},
			)
			setTextColor(if (privateFavourites) palette.onSurface else glass!!.content)
			iconTint = ColorStateList.valueOf(if (privateFavourites) palette.primary else glass!!.selectedBorder)
			cornerRadius = controlRadius
			this.strokeWidth = if (privateFavourites) strokeWidth else 0
			strokeColor = ColorStateList.valueOf(
				if (privateFavourites) ColorUtils.setAlphaComponent(palette.outlineVariant, 132) else Color.TRANSPARENT,
			)
			if (!privateFavourites) {
				foreground = createNormalGlassOutline(
					glass = glass!!,
					radius = controlRadius.toFloat(),
					density = density,
				)
				elevation = 0f
			}
		}
	}

	private fun applyNormalHeaderGeometry(
		palette: MiyorareViewPalette,
		glass: MiyorareNeonGlassColors,
		density: Float,
	) {
		fun dp(value: Float) = (value * density).roundToInt()
		findViewById<android.widget.TextView>(R.id.text_favourites_title)?.apply {
			textSize = MiyorareFavouritesVisualSpec.TITLE_TEXT_SP
			includeFontPadding = false
			(layoutParams as? LinearLayout.LayoutParams)?.let { params ->
				params.marginStart = dp(MiyorareFavouritesVisualSpec.TITLE_HORIZONTAL_MARGIN_DP)
				params.marginEnd = dp(MiyorareFavouritesVisualSpec.TITLE_HORIZONTAL_MARGIN_DP)
				params.topMargin = 0
				params.bottomMargin = 0
				layoutParams = params
			}
		}
		findViewById<android.widget.TextView>(R.id.text_favourites_subtitle)?.apply {
			textSize = MiyorareFavouritesVisualSpec.SUBTITLE_TEXT_SP
			includeFontPadding = false
			(layoutParams as? LinearLayout.LayoutParams)?.let { params ->
				params.marginStart = dp(MiyorareFavouritesVisualSpec.TITLE_HORIZONTAL_MARGIN_DP)
				params.marginEnd = dp(MiyorareFavouritesVisualSpec.TITLE_HORIZONTAL_MARGIN_DP)
				params.topMargin = dp(MiyorareFavouritesVisualSpec.TITLE_SUBTITLE_GAP_DP)
				params.bottomMargin = 0
				layoutParams = params
			}
		}
		findViewById<MaterialButtonToggleGroup>(R.id.toggle_content_type)?.apply {
			val toggleInset = dp(MiyorareFavouritesVisualSpec.CONTENT_TOGGLE_INSET_DP)
			setPadding(toggleInset, toggleInset, toggleInset, toggleInset)
			(layoutParams as? LinearLayout.LayoutParams)?.let { params ->
				params.width = ViewGroup.LayoutParams.MATCH_PARENT
				params.height = dp(MiyorareFavouritesVisualSpec.CONTENT_TOGGLE_HEIGHT_DP)
				params.marginStart = dp(MiyorareFavouritesVisualSpec.CONTENT_TOGGLE_HORIZONTAL_MARGIN_DP)
				params.marginEnd = dp(MiyorareFavouritesVisualSpec.CONTENT_TOGGLE_HORIZONTAL_MARGIN_DP)
				params.topMargin = dp(MiyorareFavouritesVisualSpec.CONTENT_TOGGLE_TOP_GAP_DP)
				params.bottomMargin = dp(MiyorareFavouritesVisualSpec.CONTENT_TOGGLE_BOTTOM_GAP_DP)
				layoutParams = params
			}
		}
		for (buttonId in intArrayOf(R.id.button_content_manga, R.id.button_content_novel)) {
			findViewById<MaterialButton>(buttonId)?.apply {
				minimumHeight = dp(MiyorareFavouritesVisualSpec.CONTENT_TOGGLE_BUTTON_MIN_HEIGHT_DP)
				setPaddingRelative(paddingStart, 0, paddingEnd, 0)
				textSize = MiyorareFavouritesVisualSpec.CONTENT_TOGGLE_TEXT_SP
				setIconResource(
					if (buttonId == R.id.button_content_manga) R.drawable.ic_book_page else R.drawable.ic_novel_book,
				)
				iconSize = dp(MiyorareFavouritesVisualSpec.CONTENT_TOGGLE_ICON_DP)
				iconPadding = dp(MiyorareFavouritesVisualSpec.CONTENT_TOGGLE_ICON_GAP_DP)
				iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
			}
		}
		findViewById<TabLayout>(R.id.tabs)?.apply {
			(layoutParams as? LinearLayout.LayoutParams)?.let { params ->
				params.width = ViewGroup.LayoutParams.MATCH_PARENT
				params.height = dp(MiyorareFavouritesVisualSpec.CATEGORY_RAIL_HEIGHT_DP)
				params.marginStart = dp(MiyorareFavouritesVisualSpec.SCREEN_HORIZONTAL_MARGIN_DP)
				params.marginEnd = dp(MiyorareFavouritesVisualSpec.SCREEN_HORIZONTAL_MARGIN_DP)
				params.topMargin = dp(MiyorareFavouritesVisualSpec.CATEGORY_RAIL_TOP_GAP_DP)
				params.bottomMargin = dp(MiyorareFavouritesVisualSpec.CATEGORY_RAIL_BOTTOM_GAP_DP)
				layoutParams = params
			}
			minimumHeight = dp(MiyorareFavouritesVisualSpec.CATEGORY_RAIL_HEIGHT_DP)
			background = createNormalGlassSurface(
				glass = glass,
				radius = MiyorareVisualTokens.RADIUS_SURFACE_DP * density,
				density = density,
				selected = false,
			)
			setPadding(
				dp(MiyorareFavouritesVisualSpec.CATEGORY_RAIL_INSET_DP),
				dp(1f),
				dp(MiyorareFavouritesVisualSpec.CATEGORY_RAIL_INSET_DP),
				dp(1f),
			)
			elevation = 0f
			(getChildAt(0) as? LinearLayout)?.apply {
				showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
				dividerDrawable = GradientDrawable().apply {
					setColor(ColorUtils.setAlphaComponent(palette.onSurfaceVariant, 84))
					setSize(dp(1f).coerceAtLeast(1), dp(20f))
				}
				dividerPadding = dp(7f)
			}
		}
	}

	private fun createNormalGlassOutline(
		glass: MiyorareNeonGlassColors,
		radius: Float,
		density: Float,
	): Drawable {
		val outerGlowStroke = (4.5f * density).roundToInt().coerceAtLeast(1)
		val edgeStroke = density.roundToInt().coerceAtLeast(1)
		val inset = density.roundToInt().coerceAtLeast(1)
		val outerGlowLayer = GradientDrawable().apply {
			setColor(Color.TRANSPARENT)
			cornerRadius = radius
			setStroke(
				outerGlowStroke,
				ColorUtils.setAlphaComponent(glass.glow, (Color.alpha(glass.glow) * 0.46f).roundToInt()),
			)
		}
		val edgeLayer = GradientDrawable().apply {
			setColor(Color.TRANSPARENT)
			cornerRadius = (radius - density).coerceAtLeast(0f)
			setStroke(edgeStroke, glass.borderStrong)
		}
		val innerHighlightLayer = GradientDrawable().apply {
			setColor(Color.TRANSPARENT)
			cornerRadius = (radius - 2f * density).coerceAtLeast(0f)
			setStroke(edgeStroke, glass.innerHighlight)
		}
		return LayerDrawable(
			arrayOf(
				outerGlowLayer,
				InsetDrawable(edgeLayer, inset),
				InsetDrawable(innerHighlightLayer, inset * 2),
			),
		)
	}

	private fun createCheckedGlassBloom(
		glass: MiyorareNeonGlassColors,
		radius: Float,
		density: Float,
	): Drawable = StateListDrawable().apply {
		val glowLayer = GradientDrawable().apply {
			setColor(Color.TRANSPARENT)
			cornerRadius = radius
			setStroke(
				(5f * density).roundToInt().coerceAtLeast(1),
				ColorUtils.setAlphaComponent(
					glass.selectedGlow,
					(Color.alpha(glass.selectedGlow) * 0.48f).roundToInt(),
				),
			)
		}
		val centerHighlight = GradientDrawable(
			GradientDrawable.Orientation.LEFT_RIGHT,
			intArrayOf(
				ColorUtils.setAlphaComponent(glass.selectedBorder, 6),
				ColorUtils.setAlphaComponent(Color.WHITE, 34),
				ColorUtils.setAlphaComponent(glass.selectedBorder, 10),
			),
		).apply {
			cornerRadius = (radius - density).coerceAtLeast(0f)
		}
		val innerEdge = GradientDrawable().apply {
			setColor(Color.TRANSPARENT)
			cornerRadius = (radius - 2f * density).coerceAtLeast(0f)
			setStroke(density.roundToInt().coerceAtLeast(1), glass.innerHighlight)
		}
		val checked = LayerDrawable(
			arrayOf(
				glowLayer,
				InsetDrawable(centerHighlight, density.roundToInt().coerceAtLeast(1)),
				InsetDrawable(innerEdge, (2f * density).roundToInt().coerceAtLeast(1)),
			),
		)
		addState(intArrayOf(android.R.attr.state_checked), checked)
		addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
	}

	private fun createNormalGlassSurface(
		glass: MiyorareNeonGlassColors,
		radius: Float,
		density: Float,
		selected: Boolean,
	): Drawable {
		val activeGlow = if (selected) glass.selectedGlow else glass.glow
		val outerGlowStroke = ((if (selected) 5f else 4f) * density).roundToInt().coerceAtLeast(1)
		val edgeStroke = density.roundToInt().coerceAtLeast(1)
		val inset = density.roundToInt().coerceAtLeast(1)
		val outerGlowLayer = GradientDrawable().apply {
			setColor(Color.TRANSPARENT)
			cornerRadius = radius
			setStroke(
				outerGlowStroke,
				ColorUtils.setAlphaComponent(activeGlow, (Color.alpha(activeGlow) * 0.44f).roundToInt()),
			)
		}
		val fillLayer = GradientDrawable(
			GradientDrawable.Orientation.LEFT_RIGHT,
			if (selected) {
				intArrayOf(
					glass.selectedSurface,
					ColorUtils.blendARGB(glass.selectedSurface, Color.WHITE, 0.10f),
					glass.selectedSurface,
				)
			} else {
				intArrayOf(glass.railSurface, glass.surfaceStrong, glass.railSurface)
			},
		).apply {
			cornerRadius = (radius - density).coerceAtLeast(0f)
			setStroke(edgeStroke, if (selected) glass.selectedBorder else glass.borderStrong)
		}
		val innerHighlightLayer = GradientDrawable().apply {
			setColor(Color.TRANSPARENT)
			cornerRadius = (radius - 2f * density).coerceAtLeast(0f)
			setStroke(edgeStroke, glass.innerHighlight)
		}
		return LayerDrawable(
			arrayOf(
				outerGlowLayer,
				InsetDrawable(fillLayer, inset),
				InsetDrawable(innerHighlightLayer, inset * 2),
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
				extendFavouritesArtwork = !privateFavourites &&
					variant == MiyorareHeaderShapeDrawable.Variant.FAVOURITES_BODY,
			)
		}
	}

	private fun applyGlobalAppBarChrome(palette: MiyorareViewPalette, privateFavourites: Boolean) {
		val appBar = rootView.findViewById<AppBarLayout>(R.id.appbar) ?: return
		val searchBar = rootView.findViewById<SearchBar>(R.id.search_bar)
		val searchRow = rootView.findViewById<LinearLayout>(R.id.layout_search)
		if (decoratedAppBar !== appBar) {
			restoreGlobalAppBarChrome()
			decoratedAppBar = appBar
			decoratedSearchBar = searchBar
			originalAppBarBackground = appBar.background
			originalSearchBackgroundTint = searchBar?.backgroundTintList
			originalSearchForeground = searchBar?.foreground
			originalSearchElevation = searchBar?.elevation
			originalSearchGeometry = searchBar?.let {
				SearchBarGeometry(
					layoutHeight = it.layoutParams.height,
					minimumHeight = it.minimumHeight,
					marginEnd = (it.layoutParams as? ViewGroup.MarginLayoutParams)?.marginEnd ?: 0,
				)
			}
			originalSearchRowGeometry = searchRow?.takeIf { it.childCount >= 3 }?.let { row ->
				val startFrame = row.getChildAt(0)
				val endFrame = row.getChildAt(row.childCount - 1)
				SearchRowGeometry(
					paddingStart = row.paddingStart,
					paddingTop = row.paddingTop,
					paddingEnd = row.paddingEnd,
					paddingBottom = row.paddingBottom,
					startFrameWidth = startFrame.layoutParams.width,
					startFrameHeight = startFrame.layoutParams.height,
					startFrameMarginEnd = (startFrame.layoutParams as? ViewGroup.MarginLayoutParams)?.marginEnd ?: 0,
					endFrameWidth = endFrame.layoutParams.width,
					endFrameHeight = endFrame.layoutParams.height,
				)
			}
			for (id in intArrayOf(R.id.button_settings, R.id.button_overflow)) {
				rootView.findViewById<MaterialButton>(id)?.let { button ->
					originalIconButtonChrome[id] = IconButtonChrome(
						backgroundTint = button.backgroundTintList,
						iconTint = button.iconTint,
						strokeColor = button.strokeColor,
						strokeWidth = button.strokeWidth,
						cornerRadius = button.cornerRadius,
						elevation = button.elevation,
						foreground = button.foreground,
						layoutWidth = button.layoutParams.width,
						layoutHeight = button.layoutParams.height,
						minimumWidth = button.minimumWidth,
						minimumHeight = button.minimumHeight,
						layoutGravity = (button.layoutParams as? FrameLayout.LayoutParams)?.gravity
							?: Gravity.NO_GRAVITY,
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
		val sideControlSize = dp(MiyorareFavouritesVisualSpec.SEARCH_SIDE_BUTTON_DP)
		searchRow?.takeIf { it.childCount >= 3 }?.apply {
			setPaddingRelative(
				dp(MiyorareFavouritesVisualSpec.SEARCH_ROW_HORIZONTAL_MARGIN_DP),
				paddingTop,
				dp(MiyorareFavouritesVisualSpec.SEARCH_ROW_HORIZONTAL_MARGIN_DP),
				dp(MiyorareFavouritesVisualSpec.SEARCH_ROW_BOTTOM_PADDING_DP),
			)
			(getChildAt(0).layoutParams as? LinearLayout.LayoutParams)?.let { params ->
				params.width = sideControlSize
				params.height = sideControlSize
				params.marginEnd = dp(MiyorareFavouritesVisualSpec.SEARCH_CONTROL_GAP_DP)
				getChildAt(0).layoutParams = params
			}
			(getChildAt(childCount - 1).layoutParams as? LinearLayout.LayoutParams)?.let { params ->
				params.width = sideControlSize
				params.height = sideControlSize
				getChildAt(childCount - 1).layoutParams = params
			}
		}
		searchBar?.apply {
			val visualHeight = dp(MiyorareFavouritesVisualSpec.SEARCH_VISUAL_HEIGHT_DP)
			layoutParams = layoutParams.apply {
				height = visualHeight
				if (this is ViewGroup.MarginLayoutParams) {
					marginEnd = dp(MiyorareFavouritesVisualSpec.SEARCH_CONTROL_GAP_DP)
				}
			}
			minimumHeight = visualHeight
			backgroundTintList = ColorStateList.valueOf(glass.surfaceStrong)
			foreground = createNormalGlassOutline(
				glass = glass,
				radius = dp(MiyorareFavouritesVisualSpec.SEARCH_RADIUS_DP).toFloat(),
				density = density,
			)
			elevation = 0f
		}
		for (id in intArrayOf(R.id.button_settings, R.id.button_overflow)) {
			rootView.findViewById<MaterialButton>(id)?.apply {
				val visualSize = sideControlSize
				layoutParams = layoutParams.apply {
					width = visualSize
					height = visualSize
					if (this is FrameLayout.LayoutParams) gravity = Gravity.CENTER
				}
				minimumWidth = visualSize
				minimumHeight = visualSize
				backgroundTintList = ColorStateList.valueOf(glass.surfaceStrong)
				iconTint = ColorStateList.valueOf(palette.onSurface)
				cornerRadius = dp(MiyorareFavouritesVisualSpec.SEARCH_RADIUS_DP)
				strokeWidth = 0
				strokeColor = ColorStateList.valueOf(Color.TRANSPARENT)
				foreground = createNormalGlassOutline(
					glass = glass,
					radius = dp(MiyorareFavouritesVisualSpec.SEARCH_RADIUS_DP).toFloat(),
					density = density,
				)
				elevation = 0f
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
			originalSearchGeometry?.let { geometry ->
				layoutParams = layoutParams.apply {
					height = geometry.layoutHeight
					if (this is ViewGroup.MarginLayoutParams) marginEnd = geometry.marginEnd
				}
				minimumHeight = geometry.minimumHeight
			}
		}
		rootView.findViewById<LinearLayout>(R.id.layout_search)?.let { row ->
			originalSearchRowGeometry?.let { geometry ->
				row.setPaddingRelative(
					geometry.paddingStart,
					geometry.paddingTop,
					geometry.paddingEnd,
					geometry.paddingBottom,
				)
				if (row.childCount >= 3) {
					(row.getChildAt(0).layoutParams as? LinearLayout.LayoutParams)?.let { params ->
						params.width = geometry.startFrameWidth
						params.height = geometry.startFrameHeight
						params.marginEnd = geometry.startFrameMarginEnd
						row.getChildAt(0).layoutParams = params
					}
					(row.getChildAt(row.childCount - 1).layoutParams as? LinearLayout.LayoutParams)?.let { params ->
						params.width = geometry.endFrameWidth
						params.height = geometry.endFrameHeight
						row.getChildAt(row.childCount - 1).layoutParams = params
					}
				}
			}
		}
		for ((id, chrome) in originalIconButtonChrome) {
			rootView.findViewById<MaterialButton>(id)?.apply {
				backgroundTintList = chrome.backgroundTint
				iconTint = chrome.iconTint
				strokeColor = chrome.strokeColor
				strokeWidth = chrome.strokeWidth
				cornerRadius = chrome.cornerRadius
				elevation = chrome.elevation
				foreground = chrome.foreground
				layoutParams = layoutParams.apply {
					width = chrome.layoutWidth
					height = chrome.layoutHeight
					if (this is FrameLayout.LayoutParams) gravity = chrome.layoutGravity
				}
				minimumWidth = chrome.minimumWidth
				minimumHeight = chrome.minimumHeight
			}
		}
		originalIconButtonChrome.clear()
		decoratedAppBar = null
		decoratedSearchBar = null
		originalAppBarBackground = null
		originalSearchBackgroundTint = null
		originalSearchForeground = null
		originalSearchElevation = null
		originalSearchGeometry = null
		originalSearchRowGeometry = null
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
