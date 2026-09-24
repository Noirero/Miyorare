package org.koitharu.kotatsu.core.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.DrawableCompat
import com.google.android.material.button.MaterialButton
import kotlin.math.roundToInt

data class MiyorareMenuEntry(
    val title: CharSequence,
    val icon: Drawable? = null,
    val enabled: Boolean = true,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val onClick: () -> Unit,
)

enum class MiyorarePopupPlacement {
    DROPDOWN_END,
    TOP_END,
}

/**
 * Shared View-system overlay used by Modern popup menus and filter panels.
 * Static color math only: no runtime blur or continuously running shader.
 */
fun Context.createMiyorareOverlayBackground(radiusDp: Float = 28f): Drawable {
    val palette = miyorareViewPaletteFromPreferences()
    val density = resources.displayMetrics.density
    val radius = radiusDp * density
    if (palette == null) {
        return GradientDrawable().apply {
            setColor(ColorUtils.setAlphaComponent(Color.BLACK, 232))
            cornerRadius = radius
        }
    }
    val light = ColorUtils.calculateLuminance(palette.background) >= 0.5
    val base = if (light) {
        ColorUtils.blendARGB(palette.surfaceContainerHigh, Color.WHITE, 0.10f)
    } else {
        ColorUtils.blendARGB(palette.surfaceContainerHigh, Color.BLACK, 0.16f)
    }
    val tint = ColorUtils.blendARGB(palette.primary, palette.secondary, if (light) 0.34f else 0.46f)
    val fill = ColorUtils.blendARGB(base, tint, if (light) 0.055f else 0.075f)
    val fillAlpha = if (light) 236 else 232
    val borderAlpha = if (light) 102 else 142
    val glowAlpha = if (light) 54 else 80

    val glow = GradientDrawable().apply {
        setColor(Color.TRANSPARENT)
        cornerRadius = radius
        setStroke((5.5f * density).roundToInt().coerceAtLeast(1), ColorUtils.setAlphaComponent(palette.glow, glowAlpha))
    }
    val body = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(
            ColorUtils.setAlphaComponent(ColorUtils.blendARGB(fill, palette.primary, 0.035f), fillAlpha),
            ColorUtils.setAlphaComponent(fill, fillAlpha),
            ColorUtils.setAlphaComponent(ColorUtils.blendARGB(fill, palette.accent, 0.045f), fillAlpha),
        ),
    ).apply {
        cornerRadius = radius
        setStroke(
            density.roundToInt().coerceAtLeast(1),
            ColorUtils.setAlphaComponent(ColorUtils.blendARGB(palette.borderHighlight, palette.primary, 0.24f), borderAlpha),
        )
    }
    return LayerDrawable(arrayOf(glow, body))
}

fun View.showMiyorareGlassMenu(
    entries: List<MiyorareMenuEntry>,
    placement: MiyorarePopupPlacement = MiyorarePopupPlacement.DROPDOWN_END,
    onDismiss: (() -> Unit)? = null,
) {
    if (entries.isEmpty()) return
    val palette = context.miyorareViewPaletteFromPreferences()
    val density = resources.displayMetrics.density
    fun dp(value: Int) = (value * density).roundToInt()
    val light = palette?.let { ColorUtils.calculateLuminance(it.background) >= 0.5 } ?: false
    val contentColor = palette?.onSurface ?: if (light) Color.BLACK else Color.WHITE
    val mutedColor = palette?.onSurfaceVariant ?: ColorUtils.setAlphaComponent(contentColor, 170)
    val selectedColor = palette?.primary ?: contentColor
    val fill = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(8), dp(10), dp(8), dp(10))
        background = context.createMiyorareOverlayBackground()
    }

    entries.forEach { entry ->
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(54)
            setPadding(dp(10), 0, dp(10), 0)
            isEnabled = entry.enabled
            alpha = if (entry.enabled) 1f else 0.48f
            isClickable = entry.enabled
            isFocusable = entry.enabled
        }
        entry.icon?.let { source ->
            val icon = source.constantState?.newDrawable()?.mutate() ?: source.mutate()
            DrawableCompat.setTint(icon, if (entry.checked) selectedColor else mutedColor)
            val button = MaterialButton(context).apply {
                iconTint = ColorStateList.valueOf(if (entry.checked) selectedColor else mutedColor)
                setIcon(icon)
                iconSize = dp(24)
                minimumWidth = dp(38)
                minimumHeight = dp(38)
                setPadding(0, 0, 0, 0)
                backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
                isClickable = false
                isFocusable = false
            }
            row.addView(button, LinearLayout.LayoutParams(dp(42), dp(42)))
        }
        val title = TextView(context).apply {
            text = entry.title
            textSize = 18f
            setTextColor(if (entry.checked) selectedColor else contentColor)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
            maxLines = 2
        }
        row.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        if (entry.checkable) {
            val check = TextView(context).apply {
                text = if (entry.checked) "✓" else "○"
                textSize = if (entry.checked) 22f else 20f
                setTextColor(if (entry.checked) selectedColor else mutedColor)
                gravity = Gravity.CENTER
            }
            row.addView(check, LinearLayout.LayoutParams(dp(34), ViewGroup.LayoutParams.MATCH_PARENT))
        }
        fill.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        row.setOnClickListener {
            if (!entry.enabled) return@setOnClickListener
            entry.onClick()
            (fill.parent?.parent as? PopupWindow)
        }
    }

    val maxWidth = minOf((resources.displayMetrics.widthPixels * 0.82f).roundToInt(), dp(360))
    val scroll = ScrollView(context).apply {
        isFillViewport = false
        clipToPadding = false
        addView(fill, ViewGroup.LayoutParams(maxWidth, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    val popup = PopupWindow(scroll, maxWidth, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
        setBackgroundDrawable(null)
        isOutsideTouchable = true
        elevation = 0f
        setOnDismissListener { onDismiss?.invoke() }
    }

    // Re-bind click dismissal now that PopupWindow exists.
    for (index in 0 until fill.childCount) {
        val row = fill.getChildAt(index)
        val entry = entries[index]
        row.setOnClickListener {
            if (!entry.enabled) return@setOnClickListener
            popup.dismiss()
            entry.onClick()
        }
    }

    when (placement) {
        MiyorarePopupPlacement.DROPDOWN_END -> popup.showAsDropDown(this, width - maxWidth, dp(6), Gravity.NO_GRAVITY)
        MiyorarePopupPlacement.TOP_END -> popup.showAtLocation(
            rootView,
            Gravity.TOP or Gravity.END,
            dp(20),
            dp(72),
        )
    }
}
