package org.koitharu.kotatsu.core.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.graphics.ColorUtils
import com.google.android.material.button.MaterialButton
import com.google.android.material.search.SearchBar
import kotlin.math.roundToInt
import org.koitharu.kotatsu.R

/**
 * Keeps MainActivity's search row on the same Modern glass geometry as Normal Favourites.
 *
 * Favourites still owns its richer decorative header, but search/settings/overflow are shared
 * MainActivity controls. Styling them here prevents Updates/History/Explore from falling back to
 * the old Material pill after the Favourites header detaches.
 */
fun View.applyMiyorareSharedMainChrome() {
    val palette = context.miyorareViewPaletteFromPreferences() ?: return
    val searchBar = findViewById<SearchBar>(R.id.search_bar) ?: return
    val searchRow = findViewById<LinearLayout>(R.id.layout_search)
    val density = resources.displayMetrics.density
    fun dp(value: Float) = (value * density).roundToInt()

    val glass = palette.neonGlass()
    val fill = ColorUtils.blendARGB(glass.surfaceStrong, glass.innerHighlight, 0.12f)
    val sideControlSize = dp(MiyorareFavouritesVisualSpec.SEARCH_SIDE_BUTTON_DP)
    val radius = dp(MiyorareFavouritesVisualSpec.SEARCH_RADIUS_DP).toFloat()

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

    searchBar.apply {
        val visualHeight = dp(MiyorareFavouritesVisualSpec.SEARCH_VISUAL_HEIGHT_DP)
        (layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
            val gap = dp(MiyorareFavouritesVisualSpec.SEARCH_CONTROL_GAP_DP)
            params.height = visualHeight
            params.leftMargin = 0
            params.rightMargin = gap
            params.marginStart = 0
            params.marginEnd = gap
            layoutParams = params
        } ?: run {
            layoutParams = layoutParams.apply { height = visualHeight }
        }
        minimumHeight = visualHeight
        backgroundTintList = ColorStateList.valueOf(fill)
        foreground = createSharedMainGlassOutline(glass, radius, density)
        elevation = 0f
    }

    for (id in intArrayOf(R.id.button_settings, R.id.button_overflow)) {
        findViewById<MaterialButton>(id)?.apply {
            layoutParams = layoutParams.apply {
                width = sideControlSize
                height = sideControlSize
                if (this is FrameLayout.LayoutParams) gravity = Gravity.CENTER
            }
            minimumWidth = sideControlSize
            minimumHeight = sideControlSize
            backgroundTintList = ColorStateList.valueOf(fill)
            iconTint = ColorStateList.valueOf(glass.content)
            cornerRadius = radius.roundToInt()
            strokeWidth = 0
            strokeColor = ColorStateList.valueOf(Color.TRANSPARENT)
            foreground = createSharedMainGlassOutline(glass, radius, density)
            elevation = 0f
        }
    }
}

private fun createSharedMainGlassOutline(
    glass: MiyorareNeonGlassColors,
    radius: Float,
    density: Float,
): Drawable {
    val outer = GradientDrawable().apply {
        setColor(Color.TRANSPARENT)
        cornerRadius = radius
        setStroke(
            (12f * density).roundToInt().coerceAtLeast(1),
            ColorUtils.setAlphaComponent(
                glass.glow,
                (Color.alpha(glass.glow) * 0.15f).roundToInt(),
            ),
        )
    }
    val mid = GradientDrawable().apply {
        setColor(Color.TRANSPARENT)
        cornerRadius = radius
        setStroke(
            (6.5f * density).roundToInt().coerceAtLeast(1),
            ColorUtils.setAlphaComponent(
                glass.glow,
                (Color.alpha(glass.glow) * 0.28f).roundToInt(),
            ),
        )
    }
    val near = GradientDrawable().apply {
        setColor(Color.TRANSPARENT)
        cornerRadius = radius
        setStroke(
            (2f * density).roundToInt().coerceAtLeast(1),
            ColorUtils.setAlphaComponent(
                glass.glow,
                (Color.alpha(glass.glow) * 0.44f).roundToInt(),
            ),
        )
    }
    val edge = GradientDrawable().apply {
        setColor(Color.TRANSPARENT)
        cornerRadius = (radius - density).coerceAtLeast(0f)
        setStroke(density.roundToInt().coerceAtLeast(1), glass.borderStrong)
    }
    return LayerDrawable(
        arrayOf(
            outer,
            mid,
            near,
            InsetDrawable(edge, density.roundToInt().coerceAtLeast(1)),
        ),
    )
}
