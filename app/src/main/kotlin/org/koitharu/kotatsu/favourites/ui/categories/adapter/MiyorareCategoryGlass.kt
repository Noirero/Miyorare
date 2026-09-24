package org.koitharu.kotatsu.favourites.ui.categories.adapter

import android.content.res.ColorStateList
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import org.koitharu.kotatsu.core.ui.createMiyorareOverlayBackground
import org.koitharu.kotatsu.core.ui.miyorareViewPaletteFromPreferences

internal fun View.applyModernCategoryGlass(
    title: TextView,
    subtitle: TextView,
    icons: List<ImageView>,
) {
    val palette = context.miyorareViewPaletteFromPreferences() ?: return
    background = context.createMiyorareOverlayBackground(radiusDp = 24f)
    title.setTextColor(palette.onSurface)
    subtitle.setTextColor(palette.onSurfaceVariant)
    val iconTint = ColorStateList.valueOf(palette.onSurfaceVariant)
    icons.forEach { it.imageTintList = iconTint }
}
