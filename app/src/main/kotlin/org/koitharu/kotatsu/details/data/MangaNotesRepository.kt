package org.koitharu.kotatsu.details.data

import android.content.Context
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
