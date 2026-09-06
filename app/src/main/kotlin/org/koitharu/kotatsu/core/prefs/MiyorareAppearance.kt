package org.koitharu.kotatsu.core.prefs

import androidx.annotation.StringRes
import org.koitharu.kotatsu.R
import java.util.Locale

enum class MiyorareDesignStyle(@StringRes val titleResId: Int) {
	CLASSIC(R.string.miyorare_design_classic),
	MODERN(R.string.miyorare_design_modern),
}

/**
 * Curated Modern palette seeds. `accentArgb` is intentionally kept as the primary seed name for
 * preference/source compatibility; secondary and tertiary are independent so presets do not all
 * collapse back into Miyorare's cyan/pink identity.
 */
enum class MiyorareThemePreset(
	@StringRes val titleResId: Int,
	val accentArgb: Int,
	val secondaryArgb: Int,
	val tertiaryArgb: Int,
) {
	MIYORARE(
		R.string.miyorare_theme_miyorare,
		0xFF5B6CFF.toInt(),
		0xFF20C9E8.toInt(),
		0xFFFF5CC8.toInt(),
	),
	SAKURA(
		R.string.miyorare_theme_sakura,
		0xFFE85D9E.toInt(),
		0xFFF58AAB.toInt(),
		0xFFFFB38A.toInt(),
	),
	VIOLET(
		R.string.miyorare_theme_violet,
		0xFF7C5CFF.toInt(),
		0xFFA56EFF.toInt(),
		0xFFD994FF.toInt(),
	),
	CYAN(
		R.string.miyorare_theme_cyan,
		0xFF16AFC4.toInt(),
		0xFF35CFE2.toInt(),
		0xFF42A5FF.toInt(),
	),
	EMERALD(
		R.string.miyorare_theme_emerald,
		0xFF2FA97D.toInt(),
		0xFF39C7A0.toInt(),
		0xFF72E6BB.toInt(),
	),
	AMBER(
		R.string.miyorare_theme_amber,
		0xFFC47B1C.toInt(),
		0xFFE9A62A.toInt(),
		0xFFFFCC59.toInt(),
	),
	CUSTOM(
		R.string.miyorare_theme_custom,
		0xFF5B6CFF.toInt(),
		0xFF20C9E8.toInt(),
		0xFFFF5CC8.toInt(),
	),
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
