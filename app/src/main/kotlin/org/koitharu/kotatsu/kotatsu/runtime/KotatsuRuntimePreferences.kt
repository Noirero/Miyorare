package org.koitharu.kotatsu.kotatsu.runtime

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** User-owned policy for the independently updatable Kotatsu parser runtime. */
@Singleton
class KotatsuRuntimePreferences @Inject constructor(
	@ApplicationContext context: Context,
) {
	private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

	var isAutoUpdateEnabled: Boolean
		get() = prefs.getBoolean(KEY_AUTO_UPDATE, true)
		set(value) {
			prefs.edit().putBoolean(KEY_AUTO_UPDATE, value).apply()
		}

	private companion object {
		const val PREFS_NAME = "kotatsu_runtime_settings"
		const val KEY_AUTO_UPDATE = "auto_update"
	}
}
