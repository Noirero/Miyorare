package org.koitharu.kotatsu.core.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import androidx.core.graphics.ColorUtils
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import org.koitharu.kotatsu.R

/**
 * Modern-only color bridge for the shared Manga/Novel segmented control in Downloads and History.
 * Geometry and selection behavior stay owned by view_manga_novel_toggle.xml.
 *
 * XML theme attributes can stay on Material's default purple even when the user selects a
 * Miyorare preset or Custom/adaptive accent; use the resolved View palette just like Favourites.
 */
fun MaterialButtonToggleGroup.applyMangaNovelTogglePalette(palette: MiyorareViewPalette) {
	val density = resources.displayMetrics.density
	background = GradientDrawable(
		GradientDrawable.Orientation.LEFT_RIGHT,
		intArrayOf(
			ColorUtils.blendARGB(palette.surfaceContainer, palette.primary, 0.14f),
			ColorUtils.blendARGB(palette.surfaceContainer, palette.accent, 0.09f),
			ColorUtils.blendARGB(palette.surfaceContainer, palette.primary, 0.06f),
		),
	).apply { cornerRadius = 24f * density }

	val states = arrayOf(
		intArrayOf(android.R.attr.state_checked, android.R.attr.state_enabled),
		intArrayOf(-android.R.attr.state_enabled),
		intArrayOf(),
	)
	val fills = ColorStateList(
		states,
		intArrayOf(
			ColorUtils.blendARGB(palette.surfaceContainer, palette.primaryContainer, 0.68f),
			Color.TRANSPARENT,
			Color.TRANSPARENT,
		),
	)
	val texts = ColorStateList(
		states,
		intArrayOf(
			palette.onPrimaryContainer,
			ColorUtils.setAlphaComponent(palette.onSurfaceVariant, 112),
			palette.onSurfaceVariant,
		),
	)
	val ripple = ColorStateList.valueOf(ColorUtils.setAlphaComponent(palette.primary, 36))
	for (id in intArrayOf(R.id.button_section_manga, R.id.button_section_novel)) {
		findViewById<MaterialButton>(id)?.apply {
			backgroundTintList = fills
			setTextColor(texts)
			rippleColor = ripple
		}
	}
}
