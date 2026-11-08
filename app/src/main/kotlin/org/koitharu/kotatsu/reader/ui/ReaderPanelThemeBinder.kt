package org.koitharu.kotatsu.reader.ui

import android.content.res.ColorStateList
import android.view.View
import android.widget.ImageView
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import org.koitharu.kotatsu.core.ui.MiyorareViewPalette
import org.koitharu.kotatsu.core.ui.miyorareViewPaletteFromPreferences

/**
 * Rebind only the legacy Reader control panels. Inflated Material Views consult the XML
 * theme, unlike Compose and the explicit getThemeColor bridge.
 *
 * Returning null preserves the existing Classic styling. No playback/timer state is touched.
 */
internal fun View.bindModernReaderPanel(
    icon: ImageView,
    section: View,
    closeButton: MaterialButton,
    sliders: List<Slider>,
): MiyorareViewPalette? {
    val palette = context.miyorareViewPaletteFromPreferences() ?: return null
    backgroundTintList = ColorStateList.valueOf(palette.surfaceContainer)
    section.backgroundTintList = ColorStateList.valueOf(palette.surfaceContainerHigh)
    icon.imageTintList = ColorStateList.valueOf(palette.primary)
    closeButton.backgroundTintList = ColorStateList.valueOf(palette.primaryContainer)
    closeButton.iconTint = ColorStateList.valueOf(palette.onPrimaryContainer)
    sliders.forEach { slider ->
        slider.thumbTintList = ColorStateList.valueOf(palette.primary)
        slider.trackActiveTintList = ColorStateList.valueOf(palette.primary)
        slider.trackInactiveTintList = ColorStateList.valueOf(palette.outlineVariant)
        slider.haloTintList = ColorStateList.valueOf(palette.primaryContainer)
    }
    return palette
}
