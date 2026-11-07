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

    // Shared palette for the source/target language pickers; no parallel color mapping.
    val languagePickerAccent: Int get() = accent
    val languagePickerOutline: Int get() = outline
    fun languagePickerBadgeFill(): Int = ColorUtils.blendARGB(surfaceHigh, accent, 0.18f)
    val languagePickerDivider: Int get() = ColorUtils.setAlphaComponent(outline, 75)
    val languagePickerIdleRadio: Int get() = ColorUtils.blendARGB(outline, onSurface, 0.50f)

    fun languagePickerDialogBackground(): GradientDrawable = gradient(
        dp(28),
        modern?.surfaceGradientStart ?: ColorUtils.blendARGB(surfaceHigh, accent, 0.045f),
        modern?.surfaceGradientEnd ?: surface,
        borderAccent, 0.43f,
    )

    fun languagePickerSearchBackground(focused: Boolean): GradientDrawable = gradient(
        dp(26),
        ColorUtils.blendARGB(surfaceHigh, accent, if (focused) 0.12f else 0.045f),
        ColorUtils.blendARGB(surfaceLow, accent, 0.035f),
        if (focused) accent else outline,
        if (focused) 0.75f else 0.50f,
    )

    fun languagePickerSelectedBackground(): GradientDrawable = gradient(
        dp(18),
        ColorUtils.blendARGB(surfaceHigh, accent, 0.17f),
        ColorUtils.blendARGB(surfaceLow, accent, 0.07f),
        borderAccent, 0.72f,
    )

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
            minHeight = dp(if (main) 54 else 48)
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
            setPadding(dp(5), dp(4), dp(5), dp(4))
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
            setPadding(dp(10), dp(10), dp(10), dp(10))
            minimumHeight = dp(60)
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

    /** Compact toggle card: switch sits on its own trailing line to avoid crushing the title at 360dp. */
    fun toggleCard(
        name: CharSequence,
        summary: CharSequence,
        iconRes: Int,
        toggle: View,
    ): LinearLayout {
        val wrapper = compactCard(name, iconRes)
        val footer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        footer.addView(label(summary, 11f, subdued = true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        footer.addView(toggle, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(48),
        ))
        wrapper.addView(footer, fullWidth().apply { topMargin = dp(3) })
        return wrapper
    }

    /** A real settings selector using the same persisted source as the existing advanced sheet. */
    fun selectorCard(
        name: CharSequence,
        iconRes: Int,
        selector: View,
    ): LinearLayout = compactCard(name, iconRes).apply {
        addView(selector, fullWidth().apply { topMargin = dp(3) })
    }

    private fun compactCard(name: CharSequence, iconRes: Int) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = cardBackground()
        minimumHeight = dp(108)
        setPadding(dp(9), dp(8), dp(9), dp(8))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(icon(iconRes).apply {
                layoutParams = LinearLayout.LayoutParams(dp(17), dp(17)).apply { marginEnd = dp(6) }
            })
            addView(label(name, 12f),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }, fullWidth())
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
        // On a normal phone begin in the correct layout, without flashing a one-column sheet.
        val initialWidth = context.resources.displayMetrics.widthPixels / density - 28f
        rebuild(if (NovelTranslationSheetLayoutPolicy.useTwoColumns(
            initialWidth, context.resources.configuration.fontScale,
        )) 2 else 1)
        grid.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            val widthDp = (right - left) / density
            val columns = if (NovelTranslationSheetLayoutPolicy.useTwoColumns(
                widthDp, context.resources.configuration.fontScale,
            )) 2 else 1
            if (columns != lastColumns) grid.post { if (columns != lastColumns) rebuild(columns) }
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
        availableWidthDp >= 310f * fontScale.coerceAtLeast(1f)
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
