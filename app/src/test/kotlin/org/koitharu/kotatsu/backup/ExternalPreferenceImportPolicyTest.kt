package org.koitharu.kotatsu.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.backup.model.MihonBackupPreference
import org.koitharu.kotatsu.backup.model.MihonBooleanPreferenceValue
import org.koitharu.kotatsu.backup.model.MihonFloatPreferenceValue
import org.koitharu.kotatsu.backup.model.MihonIntPreferenceValue
import org.koitharu.kotatsu.backup.model.MihonLongPreferenceValue
import org.koitharu.kotatsu.backup.model.MihonStringPreferenceValue
import org.koitharu.kotatsu.backup.model.MihonStringSetPreferenceValue
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.RestoredPreferencePolicy

class ExternalPreferenceImportPolicyTest {

    @Test
    fun mapsOnlyCompatibleBooleansAndSkipsUnsafeEntries() {
        val entries = listOf(
            MihonBackupPreference("fullscreen", MihonBooleanPreferenceValue(true)),
            MihonBackupPreference("fullscreen", MihonBooleanPreferenceValue(false)),
            MihonBackupPreference("fullscreen", MihonIntPreferenceValue(2)),
            MihonBackupPreference("pref_keep_screen_on_key", MihonBooleanPreferenceValue(false)),
            MihonBackupPreference("reader_volume_keys", MihonBooleanPreferenceValue(true)),
            MihonBackupPreference("auto_update_extensions", MihonIntPreferenceValue(2)),
            MihonBackupPreference("auto_update_extensions", MihonBooleanPreferenceValue(true)),
            MihonBackupPreference("automatic_ext_updates", MihonBooleanPreferenceValue(true)),
            MihonBackupPreference(AppSettings.KEY_LOCAL_STORAGE, MihonStringPreferenceValue("/foreign")),
            MihonBackupPreference(AppSettings.KEY_READER_FULLSCREEN, MihonBooleanPreferenceValue(true)),
            MihonBackupPreference("unknown_key", MihonBooleanPreferenceValue(true)),
        ) + AppSettings.SENSITIVE_BACKUP_KEYS.map {
            MihonBackupPreference(it, MihonStringPreferenceValue("fixture"))
        }
        val result = ExternalPreferenceImportPolicy.plan(entries)
        assertEquals(mapOf(
            AppSettings.KEY_READER_FULLSCREEN to false,
            AppSettings.KEY_READER_SCREEN_ON to false,
            AppSettings.KEY_READER_VOLUME_BUTTONS to true,
        ), result.values)
        assertEquals(entries.size - 3, result.skippedCount)
    }

    @Test
    fun invalidTypesAndUnmappedAutoUpdatePolicyAreNotCoerced() {
        val invalid = listOf(
            MihonIntPreferenceValue(0), MihonIntPreferenceValue(1), MihonIntPreferenceValue(2),
            MihonLongPreferenceValue(1), MihonFloatPreferenceValue(1f),
            MihonStringPreferenceValue("true"), MihonStringSetPreferenceValue(setOf("true")),
        )
        val entries = invalid.flatMap {
            listOf(MihonBackupPreference("auto_update_extensions", it), MihonBackupPreference("fullscreen", it))
        }
        val result = ExternalPreferenceImportPolicy.plan(entries)
        assertTrue(result.values.isEmpty())
        assertEquals(entries.size, result.skippedCount)
        assertEquals(0, ExternalPreferenceImportPolicy.plan(emptyList()).skippedCount)
    }

    @Test
    fun nativeGuardPreservesValidAndUnknownNativeSettings() {
        val key = AppSettings.KEY_AUTO_UPDATE_EXTENSIONS
        val known = listOf(key, AppSettings.KEY_READER_FULLSCREEN, AppSettings.KEY_READER_SCREEN_ON, AppSettings.KEY_READER_VOLUME_BUTTONS)
        for (target in known) {
            for (invalid in listOf<Any?>(0, 1, 2, 1L, 1f, "true", setOf("true"), null)) {
                assertEquals(mapOf("native_other" to 7), RestoredPreferencePolicy.filterNative(mapOf(target to invalid, "native_other" to 7)))
            }
            for (value in listOf(false, true)) {
                val valid = mapOf(target to value, "native_other" to 7)
                assertEquals(valid, RestoredPreferencePolicy.filterNative(valid))
            }
        }
        assertTrue(RestoredPreferencePolicy.filterNative(emptyMap<String, Any>()).isEmpty())
    }
}
