package org.koitharu.kotatsu.core.prefs

import android.content.Context
import androidx.preference.PreferenceManager
import dagger.Reusable
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Durability barrier for chapter read overrides during source migration.
 *
 * [AppSettings] remains the read/write authority and updates SharedPreferences in memory immediately.
 * Migration additionally needs the destination value to be on disk before the Room transaction can
 * commit, otherwise process death can strand the migrated manga without its read overrides. This
 * adapter rewrites the current AppSettings value with synchronous [android.content.SharedPreferences.Editor.commit].
 */
@Reusable
class ChapterReadOverridesMigrationStore @Inject constructor(
	@ApplicationContext context: Context,
	private val settings: AppSettings,
) {
	private val prefs = PreferenceManager.getDefaultSharedPreferences(context)

	fun persistCurrent(mangaId: Long) {
		val overrides = settings.getChapterReadOverrides(mangaId)
		val editor = prefs.edit()
		if (overrides.isEmpty()) {
			editor.remove(key(mangaId))
		} else {
			editor.putStringSet(
				key(mangaId),
				overrides.mapTo(LinkedHashSet(overrides.size)) { (chapterId, isRead) ->
					"$chapterId:" + if (isRead) "1" else "0"
				},
			)
		}
		check(editor.commit()) { "Cannot persist chapter read override migration state" }
	}

	private fun key(mangaId: Long) = "chapter_read_overrides_$mangaId"
}
