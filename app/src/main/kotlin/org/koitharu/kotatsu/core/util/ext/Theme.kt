package org.koitharu.kotatsu.core.util.ext

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.content.res.Resources
import android.content.res.TypedArray
import android.graphics.Color
import android.graphics.drawable.Drawable
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.annotation.FloatRange
import androidx.annotation.Px
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import androidx.core.content.res.use
import androidx.core.graphics.ColorUtils
import androidx.preference.PreferenceManager
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.MiyorareAppearance
import org.koitharu.kotatsu.core.prefs.MiyorareDesignStyle
import org.koitharu.kotatsu.core.prefs.MiyorareThemePreset
import org.koitharu.kotatsu.core.prefs.VisualEffectLevel
import org.koitharu.kotatsu.core.prefs.VisualEffectPreferences
import org.koitharu.kotatsu.core.ui.MiyorareThemeColors
import org.koitharu.kotatsu.core.ui.miyorareThemeColors
import androidx.appcompat.R as appcompatR
import com.google.android.material.R as materialR

val Resources.isNightMode: Boolean
	get() = configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

fun Context.getThemeDrawable(
	@AttrRes resId: Int,
) = obtainStyledAttributes(intArrayOf(resId)).use {
	it.getDrawable(0)
}

/**
 * Resolve semantic Modern colors from the same preset engine used by Compose before falling back to
 * the Android theme. Classic never enters this path, so its existing theme resolution is untouched.
 *
 * The XML Modern overlay stays as a safe inflation fallback, while code-driven View surfaces now
 * react to Sakura/Violet/Cyan/Emerald/Amber/Custom instead of always reading the static Miyorare
 * violet/cyan resources.
 */
@ColorInt
fun Context.getThemeColor(
	@AttrRes resId: Int,
	@ColorInt fallback: Int = Color.TRANSPARENT,
): Int {
	getMiyorareModernThemeColor(resId)?.let { return it }
	return obtainStyledAttributes(intArrayOf(resId)).use {
		it.getColor(0, fallback)
	}
}

@Px
fun Context.getThemeDimensionPixelOffset(
	@AttrRes resId: Int,
	@Px fallback: Int = 0,
) = obtainStyledAttributes(intArrayOf(resId)).use {
	it.getDimensionPixelOffset(0, fallback)
}

@ColorInt
fun Context.getThemeColor(
	@AttrRes resId: Int,
	@FloatRange(from = 0.0, to = 1.0) alphaFactor: Float,
	@ColorInt fallback: Int = Color.TRANSPARENT,
): Int {
	if (alphaFactor <= 0f) {
		return Color.TRANSPARENT
	}
	val color = getThemeColor(resId, fallback)
	if (alphaFactor >= 1f) {
		return color
	}
	return ColorUtils.setAlphaComponent(color, (0xFF * alphaFactor).toInt())
}

fun Context.getThemeColorStateList(
	@AttrRes resId: Int,
): ColorStateList? {
	getMiyorareModernThemeColor(resId)?.let { return ColorStateList.valueOf(it) }
	return obtainStyledAttributes(intArrayOf(resId)).use {
		it.getColorStateList(0)
	}
}

fun Context.getThemeResId(
	@AttrRes resId: Int,
	fallback: Int
): Int = obtainStyledAttributes(intArrayOf(resId)).use {
	it.getResourceId(0, fallback)
}

fun TypedArray.getDrawableCompat(context: Context, index: Int): Drawable? {
	val resId = getResourceId(index, 0)
	return if (resId != 0) ContextCompat.getDrawable(context, resId) else null
}

private data class ModernThemePaletteKey(
	val preset: MiyorareThemePreset,
	val customAccent: String,
	val darkTheme: Boolean,
	val amoled: Boolean,
	val effectLevel: VisualEffectLevel,
)

private object ModernThemePaletteCache {
	var key: ModernThemePaletteKey? = null
	var colors: MiyorareThemeColors? = null
}

private fun Context.getMiyorareModernThemeColor(@AttrRes resId: Int): Int? {
	val colors = getMiyorareModernThemeColors() ?: return null
	val scheme = colors.colorScheme
	return when (resId) {
		android.R.attr.colorBackground -> scheme.background.toArgb()
		appcompatR.attr.colorPrimary -> scheme.primary.toArgb()
		appcompatR.attr.colorAccent -> scheme.primary.toArgb()
		materialR.attr.colorOnPrimary -> scheme.onPrimary.toArgb()
		materialR.attr.colorPrimaryContainer -> scheme.primaryContainer.toArgb()
		materialR.attr.colorOnPrimaryContainer -> scheme.onPrimaryContainer.toArgb()
		materialR.attr.colorSecondary -> scheme.secondary.toArgb()
		materialR.attr.colorOnSecondary -> scheme.onSecondary.toArgb()
		materialR.attr.colorSecondaryContainer -> scheme.secondaryContainer.toArgb()
		materialR.attr.colorOnSecondaryContainer -> scheme.onSecondaryContainer.toArgb()
		materialR.attr.colorTertiary -> scheme.tertiary.toArgb()
		materialR.attr.colorOnTertiary -> scheme.onTertiary.toArgb()
		materialR.attr.colorSurface -> scheme.surface.toArgb()
		materialR.attr.colorOnSurface -> scheme.onSurface.toArgb()
		materialR.attr.colorSurfaceVariant -> scheme.surfaceVariant.toArgb()
		materialR.attr.colorOnSurfaceVariant -> scheme.onSurfaceVariant.toArgb()
		materialR.attr.colorSurfaceContainer -> scheme.surfaceContainer.toArgb()
		materialR.attr.colorSurfaceContainerHigh -> scheme.surfaceContainerHigh.toArgb()
		materialR.attr.colorOutline -> scheme.outline.toArgb()
		materialR.attr.colorOutlineVariant -> scheme.outlineVariant.toArgb()
		else -> null
	}
}

private fun Context.getMiyorareModernThemeColors(): MiyorareThemeColors? {
	val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
	val designStyle = prefs.getString(
		MiyorareAppearance.KEY_DESIGN_STYLE,
		MiyorareDesignStyle.CLASSIC.name,
	)
	if (designStyle != MiyorareDesignStyle.MODERN.name) return null

	val preset = MiyorareThemePreset.entries.firstOrNull {
		it.name == prefs.getString(MiyorareAppearance.KEY_THEME_PRESET, null)
	} ?: MiyorareThemePreset.MIYORARE
	val customAccent = prefs.getString(
		MiyorareAppearance.KEY_CUSTOM_ACCENT,
		MiyorareAppearance.DEFAULT_CUSTOM_ACCENT,
	).orEmpty().ifBlank { MiyorareAppearance.DEFAULT_CUSTOM_ACCENT }
	val effectLevel = VisualEffectLevel.entries.firstOrNull {
		it.name == prefs.getString(VisualEffectPreferences.KEY_LEVEL, null)
	} ?: VisualEffectLevel.BALANCED
	val key = ModernThemePaletteKey(
		preset = preset,
		customAccent = customAccent,
		darkTheme = resources.isNightMode,
		amoled = prefs.getBoolean(AppSettings.KEY_THEME_AMOLED, false),
		effectLevel = effectLevel,
	)

	synchronized(ModernThemePaletteCache) {
		if (ModernThemePaletteCache.key == key) {
			return ModernThemePaletteCache.colors
		}
		val colors = miyorareThemeColors(
			preset = key.preset,
			customAccent = key.customAccent,
			darkTheme = key.darkTheme,
			amoled = key.amoled,
			effectLevel = key.effectLevel,
		)
		ModernThemePaletteCache.key = key
		ModernThemePaletteCache.colors = colors
		return colors
	}
}
