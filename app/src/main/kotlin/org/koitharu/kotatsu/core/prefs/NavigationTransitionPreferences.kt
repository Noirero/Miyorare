package org.koitharu.kotatsu.core.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

internal enum class NavigationMotion(val storedValue: String) {
	CLASSIC_GLIDE("classic_glide"),
	ELASTIC_GLIDE("elastic_glide"),
	FLUID_LENS("fluid_lens");

	companion object {
		fun parse(value: Any?): NavigationMotion =
			entries.firstOrNull { it.storedValue == value } ?: CLASSIC_GLIDE
	}
}

internal enum class ScreenTransition(val storedValue: String) {
	DIRECTIONAL_SLIDE("directional_slide");

	companion object {
		fun parse(value: Any?): ScreenTransition =
			entries.firstOrNull { it.storedValue == value } ?: DIRECTIONAL_SLIDE
	}
}

/** The settings screen and main navigation use the same two independent preferences. */
internal class NavigationTransitionPreferences(context: Context) {
	private val prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

	val motion: NavigationMotion
		get() = NavigationMotion.parse(read(KEY_MOTION))

	val transition: ScreenTransition
		get() = ScreenTransition.parse(read(KEY_TRANSITION))

	fun setMotion(value: NavigationMotion) {
		prefs.edit().putString(KEY_MOTION, value.storedValue).apply()
	}

	fun setTransition(value: ScreenTransition) {
		prefs.edit().putString(KEY_TRANSITION, value.storedValue).apply()
	}

	fun reset() {
		prefs.edit().remove(KEY_MOTION).remove(KEY_TRANSITION).apply()
	}

	fun subscribe(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
		prefs.registerOnSharedPreferenceChangeListener(listener)

	fun unsubscribe(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
		prefs.unregisterOnSharedPreferenceChangeListener(listener)

	private fun read(key: String): String? = try {
		prefs.getString(key, null)
	} catch (_: ClassCastException) {
		null
	}

	companion object {
		const val KEY_MOTION = "navigation_motion"
		const val KEY_TRANSITION = "screen_transition"
	}
}
