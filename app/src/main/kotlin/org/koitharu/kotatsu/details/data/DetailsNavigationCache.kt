package org.koitharu.kotatsu.details.data

import org.koitharu.kotatsu.core.model.MangaHistory
import org.koitharu.kotatsu.parsers.model.Manga
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Small process-local cache used to hand a chapter-bearing snapshot from a list to Details.
 *
 * Chapters are deliberately not placed in an Intent: a title with thousands of chapters can exceed
 * Android's Binder transaction limit. The database remains the fallback after process recreation.
 */
@Singleton
class DetailsNavigationCache @Inject constructor() {

	data class Snapshot(val manga: Manga, val history: MangaHistory?)

	private val snapshots = object : LinkedHashMap<Long, Snapshot>(MAX_ENTRIES, 0.75f, true) {
		override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Snapshot>?): Boolean =
			size > MAX_ENTRIES
	}

	@Synchronized
	fun get(mangaId: Long): Snapshot? = snapshots[mangaId]

	@Synchronized
	fun contains(mangaId: Long): Boolean = snapshots.containsKey(mangaId)

	@Synchronized
	fun updateHistory(mangaIds: Collection<Long>, history: (Long) -> MangaHistory?) {
		for (mangaId in mangaIds) {
			val current = snapshots[mangaId] ?: continue
			snapshots[mangaId] = current.copy(history = history(mangaId))
		}
	}

	@Synchronized
	fun putAll(manga: Collection<Manga>, history: (Long) -> MangaHistory?) {
		for (item in manga) {
			if (!item.chapters.isNullOrEmpty()) snapshots[item.id] = Snapshot(item, history(item.id))
		}
	}

	@Synchronized
	fun clear() {
		snapshots.clear()
	}

	private companion object {
		const val MAX_ENTRIES = 24
	}
}
