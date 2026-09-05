package org.koitharu.kotatsu.core.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.StringRes
import androidx.preference.PreferenceManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koitharu.kotatsu.R
import javax.inject.Inject
import javax.inject.Singleton

enum class VisualEffectLevel(
	@StringRes val titleResId: Int,
	val surfaceTintFraction: Float,
	val headerElevationDp: Float,
	val outlineAlpha: Int,
) {
	LIGHT(
		R.string.visual_effects_light,
		surfaceTintFraction = 0.025f,
		headerElevationDp = 0f,
		outlineAlpha = 24,
	),
	BALANCED(
		R.string.visual_effects_balanced,
		surfaceTintFraction = 0.12f,
		headerElevationDp = 3f,
		outlineAlpha = 80,
	),
	FULL(
		R.string.visual_effects_full,
		surfaceTintFraction = 0.20f,
		headerElevationDp = 6f,
		outlineAlpha = 120,
	),
}

/**
 * Global visual-intensity preference for the Miyorare visual layer. The preference deliberately
 * changes decoration only; layout, features and data behavior stay identical at every level.
 */
@Singleton
class VisualEffectPreferences @Inject constructor(
	@ApplicationContext context: Context,
) {
	private val prefs = PreferenceManager.getDefaultSharedPreferences(context)

	private val mutableLevel = MutableStateFlow(readLevel())

	val level: StateFlow<VisualEffectLevel> = mutableLevel.asStateFlow()

	private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
		if (key == KEY_LEVEL) mutableLevel.value = readLevel()
	}

	init {
		prefs.registerOnSharedPreferenceChangeListener(listener)
	}

	fun setLevel(value: VisualEffectLevel) {
		prefs.edit().putString(KEY_LEVEL, value.name).apply()
	}

	private fun readLevel(): VisualEffectLevel {
		val raw = prefs.getString(KEY_LEVEL, null)
		return VisualEffectLevel.entries.firstOrNull { it.name == raw } ?: VisualEffectLevel.BALANCED
	}

	companion object {
		const val KEY_LEVEL = "visual_effect_level"
	}
}
