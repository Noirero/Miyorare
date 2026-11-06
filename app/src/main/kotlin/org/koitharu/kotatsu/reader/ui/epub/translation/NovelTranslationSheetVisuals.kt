package org.koitharu.kotatsu.reader.ui.epub.translation

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import org.koitharu.kotatsu.core.ui.miyorareViewPaletteFromPreferences
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import androidx.appcompat.R as appcompatR
import com.google.android.material.R as materialR

/** Sheet-only visual layer; Modern reads the active Miyorare palette, Classic reads theme attrs. */
internal class NovelTranslationSheetVisuals(private val context: Context) {
    private val modern = context.miyorareViewPaletteFromPreferences()
    private val density get() = context.resources.displayMetrics.density

    private val surface = modern?.surface ?: context.getThemeColor(materialR.attr.colorSurface)
    private val surfaceLow = modern?.surfaceContainerLow ?: context.getThemeColor(materialR.attr.colorSurfaceContainerLow, surface)
    private val surfaceHigh = modern?.surfaceContainerHigh ?: context.getThemeColor(materialR.attr.colorSurfaceContainerHigh, surface)
    val onSurface = modern?.onSurface ?: context.getThemeColor(materialR.attr.colorOnSurface)
    val muted = modern?.onSurfaceVariant ?: context.getThemeColor(materialR.attr.colorOnSurfaceVariant, onSurface)
    private val accent = modern?.primary ?: context.getThemeColor(appcompatR.attr.colorPrimary)
    private val outline = modern?.outlineVariant ?: context.getThemeColor(materialR.attr.colorOutlineVariant, muted)
    private val buttonColor = modern?.button ?: accent
    private val onButton = modern?.onButton ?: context.getThemeColor(materialR.attr.colorOnPrimary)
    private val borderAccent = modern?.borderHighlight ?: accent

    fun dp(value: Int) = (value * density + .5f).toInt()

    fun panelBackground(): GradientDrawable = gradient(
        dp(30), modern?.surfaceGradientStart ?: ColorUtils.blendARGB(surface, accent, 0.045f),
        modern?.surfaceGradientEnd ?: surface, borderAccent, 0.32f,
    )

    fun cardBackground(selected: Boolean = false): GradientDrawable = gradient(
        dp(20),
        if (selected) ColorUtils.blendARGB(surfaceHigh, accent, 0.11f) else surfaceLow,
        if (selected) ColorUtils.blendARGB(surface, accent, 0.04f) else surfaceHigh,
        if (selected) borderAccent else outline,
        if (selected) 0.60f else 0.33f,
    )

    fun segmentBackground(): GradientDrawable = gradient(dp(23), surfaceLow, surfaceHigh, outline, 0.45f)

    private fun gradient(radius: Int, start: Int, end: Int, stroke: Int, opacity: Float) =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(start, end)).apply {
            cornerRadius = radius.toFloat()
            setStroke(dp(1).coerceAtLeast(1), ColorUtils.setAlphaComponent(stroke, (opacity * 255).toInt()))
        }

    enum class ButtonKind { PRIMARY, SECONDARY, SELECTOR, SEGMENT_SELECTED, SEGMENT_IDLE }

    fun styleButton(button: MaterialButton, kind: ButtonKind) {
        val active = kind == ButtonKind.SEGMENT_SELECTED
        val main = kind == ButtonKind.PRIMARY
        val outlined = kind == ButtonKind.SECONDARY
        val fill = when { main -> buttonColor; active -> accent; else -> surfaceHigh }
        val foreground = when {
            main -> onButton
            active -> modern?.onPrimary ?: context.getThemeColor(materialR.attr.colorOnPrimary)
            else -> onSurface
        }
        button.apply {
            isAllCaps = false
            cornerRadius = dp(if (main || kind == ButtonKind.SECONDARY) 29 else 20)
            minHeight = dp(if (main) 58 else if (active || kind == ButtonKind.SEGMENT_IDLE) 52 else 50)
            insetTop = 0
            insetBottom = 0
            setTextColor(foreground)
            iconTint = ColorStateList.valueOf(foreground)
            textSize = if (active || kind == ButtonKind.SEGMENT_IDLE) 12f else 14f
            maxLines = 2
            backgroundTintList = ColorStateList.valueOf(fill)
            strokeWidth = dp(1)
            strokeColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(
                if (main || active || outlined) accent else outline, if (main || active) 180 else if (outlined) 145 else 110,
            ))
            elevation = dp(if (main || active) 3 else 0).toFloat()
            setPadding(dp(7), dp(7), dp(7), dp(7))
        }
    }

    fun label(text: CharSequence, sizeSp: Float = 14f, subdued: Boolean = false): TextView =
        TextView(context).apply {
            this.text = text
            textSize = sizeSp
            setTextColor(if (subdued) muted else onSurface)
        }

    fun icon(resource: Int): ImageView = ImageView(context).apply {
        setImageResource(resource)
        imageTintList = ColorStateList.valueOf(accent)
        layoutParams = LinearLayout.LayoutParams(dp(21), dp(21)).apply { marginEnd = dp(10) }
        contentDescription = null
    }

    fun card(
        name: CharSequence,
        summary: CharSequence?,
        iconRes: Int,
        control: View? = null,
        footer: View? = null,
    ): LinearLayout {
        val wrapper = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            background = cardBackground()
            setPadding(dp(14), dp(14), dp(14), dp(14))
            minimumHeight = dp(90)
        }
        val heading = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        heading.addView(icon(iconRes))
        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(label(name, 14f), fullWidth())
        if (summary != null) texts.addView(label(summary, 12f, subdued = true), fullWidth())
        heading.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (control != null) heading.addView(control)
        wrapper.addView(heading, fullWidth())
        if (footer != null) wrapper.addView(footer, fullWidth().apply { topMargin = dp(10) })
        return wrapper
    }

    fun optionsGrid(cards: List<View>): LinearLayout {
        val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        var lastColumns = -1
        fun rebuild(columns: Int) {
            if (columns == lastColumns) return
            lastColumns = columns
            cards.forEach { (it.parent as? ViewGroup)?.removeView(it) }
            grid.removeAllViews()
            if (columns == 1) {
                cards.forEach { grid.addView(it, fullWidth().apply { bottomMargin = dp(10) }) }
            } else {
                cards.chunked(2).forEach { pair ->
                    val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                    pair.forEachIndexed { index, item ->
                        row.addView(item, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                            if (index == 0) marginEnd = dp(6) else marginStart = dp(6)
                        })
                    }
                    grid.addView(row, fullWidth().apply { bottomMargin = dp(10) })
                }
            }
        }
        rebuild(1)
        grid.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            val widthDp = (right - left) / density
            val columns = if (NovelTranslationSheetLayoutPolicy.useTwoColumns(
                widthDp, context.resources.configuration.fontScale,
            )) 2 else 1
            if (columns != lastColumns) grid.post { rebuild(columns) }
        }
        return grid
    }

    fun applyInsetPadding(scroll: ScrollView) {
        scroll.clipToPadding = false
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()).bottom
            view.setPadding(0, 0, 0, bottom)
            insets
        }
    }

    private fun fullWidth() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
}

internal object NovelTranslationSheetLayoutPolicy {
    fun useTwoColumns(availableWidthDp: Float, fontScale: Float): Boolean =
        availableWidthDp >= 516f * fontScale.coerceAtLeast(1f)
}


/** A selected Original view never becomes a translation output mode. Use the remembered preference. */
internal object NovelTranslationSheetModePolicy {
    fun translatedMode(
        selected: NovelTranslationDisplayMode,
        rememberedBilingual: Boolean,
    ): NovelTranslationDisplayMode = when (selected) {
        NovelTranslationDisplayMode.ORIGINAL -> if (rememberedBilingual) {
            NovelTranslationDisplayMode.BILINGUAL
        } else NovelTranslationDisplayMode.TRANSLATED_ONLY
        else -> selected
    }
}
