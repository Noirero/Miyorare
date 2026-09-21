package org.koitharu.kotatsu.details.data

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Small shared accessor for the per-manga notes used by the expressive details screen.
 *
 * Keep the preference file/key format identical to the original Beta Notes implementation so
 * notes already written by a Beta install remain readable when other screens (such as Favourites)
 * need to search them.
 */
@Singleton
class MangaNotesRepository @Inject constructor(
	@ApplicationContext context: Context,
) {
	private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

	fun get(mangaId: Long): String? = preferences
		.getString(mangaId.toString(), null)
		?.trim()
		?.takeIf { it.isNotEmpty() }

	fun set(mangaId: Long, note: String?) {
		preferences.edit {
			if (note.isNullOrBlank()) remove(mangaId.toString()) else putString(mangaId.toString(), note.trim())
		}
	}


	/**
	 * Persist a destination copy before the Room migration commits. The source note remains readable
	 * until [finishPreparedMove], so a process death between persistence layers cannot strand the note
	 * on the obsolete manga id. Existing destination content always wins.
	 *
	 * @return true only when this call created the destination copy and therefore owns rollback.
	 */
	fun prepareMove(oldMangaId: Long, newMangaId: Long): Boolean {
		if (oldMangaId == newMangaId) return false
		val oldNote = get(oldMangaId) ?: return false
		if (get(newMangaId) != null) return false
		check(
			preferences.edit().putString(newMangaId.toString(), oldNote).commit(),
		) { "Cannot persist manga note migration preparation" }
		return true
	}

	/** Remove the source copy only after the Room migration has committed. */
	fun finishPreparedMove(oldMangaId: Long, newMangaId: Long) {
		if (oldMangaId == newMangaId || get(oldMangaId) == null) return
		check(
			preferences.edit().remove(oldMangaId.toString()).commit(),
		) { "Cannot finalize manga note migration" }
	}

	/** Roll back only a destination copy created by [prepareMove]. */
	fun rollbackPreparedMove(newMangaId: Long) {
		check(
			preferences.edit().remove(newMangaId.toString()).commit(),
		) { "Cannot roll back manga note migration preparation" }
	}

	/**
	 * Returns one in-memory snapshot for bulk search. Calling SharedPreferences#getString once per
	 * favourite is cheap for a handful of items but becomes avoidable overhead for 10k-30k libraries
	 * on every query. Only keys that are valid manga ids and non-blank String notes are retained.
	 */
	fun snapshot(): Map<Long, String> {
		val all = preferences.all
		if (all.isEmpty()) return emptyMap()
		val result = HashMap<Long, String>(all.size)
		for ((key, value) in all) {
			val id = key.toLongOrNull() ?: continue
			val note = (value as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
			result[id] = note
		}
		return result
	}

	companion object {
		private const val PREFERENCES_NAME = "manga_notes"
	}
}
