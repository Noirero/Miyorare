package org.koitharu.kotatsu.core.prefs

import android.content.Context
import androidx.preference.PreferenceManager
import dagger.Reusable
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Durability barrier for chapter read overrides during source migration.
 *
 * [AppSettings] remains the semantic read/write authority. Normal AppSettings writes update the
 * SharedPreferences memory image immediately but may reach disk asynchronously. Migration calls this
 * after such a write to synchronously persist that exact raw preference value before Room may commit.
 * This class deliberately does not parse or re-encode override entries.
 */
@Reusable
class ChapterReadOverridesMigrationStore @Inject constructor(
	@ApplicationContext context: Context,
) {
	private val prefs = PreferenceManager.getDefaultSharedPreferences(context)

	fun persistCurrent(mangaId: Long) {
		val key = key(mangaId)
		val current = prefs.all[key]
		val editor = prefs.edit()
		when (current) {
			null -> editor.remove(key)
			is Set<*> -> {
				val strings = current.filterIsInstance<String>().toSet()
				check(strings.size == current.size) { "Invalid chapter read override preference value" }
				editor.putStringSet(key, strings)
			}
			else -> error("Invalid chapter read override preference type")
		}
		check(editor.commit()) { "Cannot persist chapter read override migration state" }
	}

	private fun key(mangaId: Long) = "chapter_read_overrides_$mangaId"
}
