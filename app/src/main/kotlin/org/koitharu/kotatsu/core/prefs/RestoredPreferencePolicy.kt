package org.koitharu.kotatsu.core.prefs

import android.content.SharedPreferences

/** Validate known Boolean settings at the native restore/sync boundary without dropping other native keys. */
internal object RestoredPreferencePolicy {

    private val booleanKeys = setOf(
        AppSettings.KEY_AUTO_UPDATE_EXTENSIONS,
        AppSettings.KEY_READER_FULLSCREEN,
        AppSettings.KEY_READER_SCREEN_ON,
        AppSettings.KEY_READER_VOLUME_BUTTONS,
    )

    fun filterNative(values: Map<String, *>): Map<String, *> =
        values.filter { (key, value) -> key !in booleanKeys || value is Boolean }

    /**
     * Called before normal application consumers/listeners start. Only repair the proven corrupt key.
     * A missing key or an already valid Boolean must not be rewritten.
     * Returns false if the repaired value could not be persisted; SharedPreferences still updates memory.
     */
    fun repairAutoUpdateExtensions(prefs: SharedPreferences): Boolean {
        val raw = prefs.all[AppSettings.KEY_AUTO_UPDATE_EXTENSIONS]
        if (raw == null || raw is Boolean) return true
        return prefs.edit().putBoolean(AppSettings.KEY_AUTO_UPDATE_EXTENSIONS, false).commit()
    }
}
