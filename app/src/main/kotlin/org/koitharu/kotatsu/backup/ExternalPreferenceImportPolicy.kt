package org.koitharu.kotatsu.backup

import org.koitharu.kotatsu.backup.model.MihonBackupPreference
import org.koitharu.kotatsu.backup.model.MihonBooleanPreferenceValue
import org.koitharu.kotatsu.core.prefs.AppSettings

/** External application preferences are not a Miyorare settings snapshot. */
internal object ExternalPreferenceImportPolicy {

    // Mihon/Yokai reader preferences with matching Boolean semantics. Do not infer mappings
    // from identical names: auto_update_extensions, for example, can be an integer enum.
    private val booleanMappings = mapOf(
        "fullscreen" to AppSettings.KEY_READER_FULLSCREEN,
        "pref_keep_screen_on_key" to AppSettings.KEY_READER_SCREEN_ON,
        "reader_volume_keys" to AppSettings.KEY_READER_VOLUME_BUTTONS,
    )

    data class Plan(val values: Map<String, Boolean>, val skippedCount: Int)

    fun plan(preferences: List<MihonBackupPreference>): Plan {
        val values = LinkedHashMap<String, Boolean>()
        for (preference in preferences) {
            if (preference.key in AppSettings.SENSITIVE_BACKUP_KEYS) continue
            val target = booleanMappings[preference.key] ?: continue
            if (target in AppSettings.SENSITIVE_BACKUP_KEYS) continue
            val value = preference.value as? MihonBooleanPreferenceValue ?: continue
            // Last valid value wins; an invalid duplicate never erases an accepted value.
            values[target] = value.value
        }
        // Count unique restored settings and skipped entries (including superseded duplicates).
        return Plan(values, preferences.size - values.size)
    }
}
