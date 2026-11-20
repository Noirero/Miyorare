package org.koitharu.kotatsu.settings.search

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.list.OnListItemClickListener
import org.koitharu.kotatsu.core.util.ext.textAndVisible
import org.koitharu.kotatsu.databinding.ItemPreferenceBinding
import org.koitharu.kotatsu.databinding.ItemSettingsSectionCardBinding
import org.koitharu.kotatsu.list.ui.model.ListModel
import com.google.android.material.R as materialR

fun settingsSectionCardAD(
	listener: OnListItemClickListener<SettingsItem>,
) = adapterDelegateViewBinding<SettingsSectionCard, ListModel, ItemSettingsSectionCardBinding>(
	{ layoutInflater, parent -> ItemSettingsSectionCardBinding.inflate(layoutInflater, parent, false) },
) {

	val breadcrumbsSeparator = getString(R.string.breadcrumbs_separator)

	bind {
		val density = context.resources.displayMetrics.density
		val surfaceContainer = MaterialColors.getColor(
			context,
			materialR.attr.colorSurfaceContainer,
			MaterialColors.getColor(context, materialR.attr.colorSurface, Color.TRANSPARENT),
		)
		binding.root.background = GradientDrawable().apply {
			cornerRadius = 16 * density
			setColor(surfaceContainer)
		}

		val colors = SettingsSectionColors.resolve(context, item.paletteKey)
		binding.imageViewIcon.background = GradientDrawable().apply {
			cornerRadius = 9 * density
			setColor(colors.bubble)
		}
		if (item.iconRes != 0) {
			binding.imageViewIcon.setImageResource(item.iconRes)
		} else {
			binding.imageViewIcon.setImageDrawable(null)
		}
		binding.imageViewIcon.imageTintList = ColorStateList.valueOf(colors.glyph)
		binding.textViewTitle.text = item.title
		binding.textViewTitle.setTextColor(colors.title)

		val inflater = LayoutInflater.from(context)
		binding.containerItems.removeAllViews()
		for (child in item.items) {
			val row = ItemPreferenceBinding.inflate(inflater, binding.containerItems, false)
			row.textViewTitle.text = child.title
			row.textViewSummary.textAndVisible = child.breadcrumbs.drop(1)
				.joinToString(breadcrumbsSeparator)
				.ifEmpty { null }
			row.root.setOnClickListener { view -> listener.onItemClick(child, view) }
			binding.containerItems.addView(row.root)
		}
	}
}

/**
 * Category colors for the section cards. Mirrors the container/onContainer pairs of
 * CategoryPalette (settings/compose/CategoryColors.kt), which is Compose-only; keep the
 * values in sync with that palette. The bubble always uses the pale color of the pair and
 * the glyph the deep one, in both themes; the title uses onContainer (deep on light
 * themes, pale on dark ones).
 */
private object SettingsSectionColors {

	class Resolved(val bubble: Int, val glyph: Int, val title: Int)

	// key -> (container, onContainer)
	private val light = mapOf(
		"appearance" to (0xFFE9DDFF.toInt() to 0xFF22005D.toInt()),
		"extensions" to (0xFFFFD8E4.toInt() to 0xFF31111D.toInt()),
		"reader" to (0xFFD1E4FF.toInt() to 0xFF001D36.toInt()),
		"storage" to (0xFFB8F4D6.toInt() to 0xFF002111.toInt()),
		"downloads" to (0xFFFFE0B2.toInt() to 0xFF2E1500.toInt()),
		"backup" to (0xFFCDE9CB.toInt() to 0xFF002201.toInt()),
		"private" to (0xFFE7D9F7.toInt() to 0xFF2D1644.toInt()),
		"tracker" to (0xFFFFDAD6.toInt() to 0xFF410002.toInt()),
		"services" to (0xFFE0E0FF.toInt() to 0xFF0F1761.toInt()),
		"about" to (0xFFDDE2EB.toInt() to 0xFF1B1B1F.toInt()),
		"sync" to (0xFF5F6368.toInt() to 0xFFFFFFFF.toInt()),
	)

	private val dark = mapOf(
		"appearance" to (0xFF4F378B.toInt() to 0xFFE9DDFF.toInt()),
		"extensions" to (0xFF7D5260.toInt() to 0xFFFFD8E4.toInt()),
		"reader" to (0xFF00497F.toInt() to 0xFFD1E4FF.toInt()),
		"storage" to (0xFF005233.toInt() to 0xFFB8F4D6.toInt()),
		"downloads" to (0xFF6B4100.toInt() to 0xFFFFE0B2.toInt()),
		"backup" to (0xFF255A24.toInt() to 0xFFCDE9CB.toInt()),
		"private" to (0xFF4A315F.toInt() to 0xFFE7D9F7.toInt()),
		"tracker" to (0xFF93000A.toInt() to 0xFFFFDAD6.toInt()),
		"services" to (0xFF333DB1.toInt() to 0xFFE0E0FF.toInt()),
		"about" to (0xFF45464F.toInt() to 0xFFDDE2EB.toInt()),
		"sync" to (0xFF5F6368.toInt() to 0xFFFFFFFF.toInt()),
	)

	fun resolve(context: Context, key: String): Resolved {
		val surface = MaterialColors.getColor(context, materialR.attr.colorSurface, Color.WHITE)
		val isDark = ColorUtils.calculateLuminance(surface) < 0.5
		val pair = (if (isDark) dark else light)[key]
			?: (if (isDark) dark else light).getValue("about")
		return if (isDark) {
			Resolved(bubble = pair.second, glyph = pair.first, title = pair.second)
		} else {
			Resolved(bubble = pair.first, glyph = pair.second, title = pair.second)
		}
	}
}
