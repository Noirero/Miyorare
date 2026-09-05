package org.koitharu.kotatsu.core.prefs

import androidx.annotation.StringRes
import org.koitharu.kotatsu.R
import java.util.Locale

enum class MiyorareDesignStyle(@StringRes val titleResId: Int) {
	CLASSIC(R.string.miyorare_design_classic),
	MODERN(R.string.miyorare_design_modern),
}

enum class MiyorareThemePreset(
	@StringRes val titleResId: Int,
	val accentArgb: Int,
) {
	MIYORARE(R.string.miyorare_theme_miyorare, 0xFF5B6CFF.toInt()),
	SAKURA(R.string.miyorare_theme_sakura, 0xFFE85D9E.toInt()),
	VIOLET(R.string.miyorare_theme_violet, 0xFF7C5CFF.toInt()),
	CYAN(R.string.miyorare_theme_cyan, 0xFF16AFC4.toInt()),
	EMERALD(R.string.miyorare_theme_emerald, 0xFF2FA97D.toInt()),
	AMBER(R.string.miyorare_theme_amber, 0xFFC47B1C.toInt()),
	CUSTOM(R.string.miyorare_theme_custom, 0xFF5B6CFF.toInt()),
}

/** Shared keys and validation for the single Miyorare appearance preference source. */
object MiyorareAppearance {
	const val KEY_DESIGN_STYLE = "miyorare_design_style"
	const val KEY_THEME_PRESET = "miyorare_theme_preset"
	const val KEY_CUSTOM_ACCENT = "miyorare_custom_accent"
	const val DEFAULT_CUSTOM_ACCENT = "#5B6CFF"

	fun parseAccentArgb(value: String): Int? {
		val raw = value.trim().removePrefix("#")
		if (raw.length != 6 || raw.any { it.digitToIntOrNull(16) == null }) return null
		return ((raw.toLong(16) and 0x00FFFFFFL) or 0xFF000000L).toInt()
	}

	fun normalizeAccent(value: String): String? {
		val argb = parseAccentArgb(value) ?: return null
		return String.format(Locale.ROOT, "#%06X", argb and 0x00FFFFFF)
	}
}
