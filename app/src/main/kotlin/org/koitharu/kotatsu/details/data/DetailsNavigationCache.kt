package org.koitharu.kotatsu.details.data

import org.koitharu.kotatsu.core.model.MangaHistory
import org.koitharu.kotatsu.core.model.isLocal
import org.koitharu.kotatsu.parsers.model.Manga
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Small process-local handoff cache for Details navigation.
 *
 * Remote chapter snapshots deliberately live in Room now. Keeping up to 24 chapter-bearing remote
 * Manga objects here duplicated Room/ViewModel state and forced Favourites to materialize chapter
 * lists before the user tapped anything. Only Local content keeps a chapter-bearing snapshot because
 * opening a Local container otherwise requires parsing the filesystem on demand.
 *
 * Reading history stays as a tiny process-local hint so the Details action state can be seeded
 * immediately while the History Room flow starts.
 */
@Singleton
class DetailsNavigationCache @Inject constructor() {

	private data class Snapshot(
		val localManga: Manga? = null,
		val history: MangaHistory? = null,
	)

	private val snapshots = object : LinkedHashMap<Long, Snapshot>(MAX_ENTRIES, 0.75f, true) {
		override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Snapshot>?): Boolean =
			size > MAX_ENTRIES
	}

	@Synchronized
	fun getLocalManga(mangaId: Long): Manga? = snapshots[mangaId]?.localManga

	@Synchronized
	fun getHistory(mangaId: Long): MangaHistory? = snapshots[mangaId]?.history

	@Synchronized
	fun containsLocalManga(mangaId: Long): Boolean = snapshots[mangaId]?.localManga != null

	@Synchronized
	fun updateHistory(mangaIds: Collection<Long>, history: (Long) -> MangaHistory?) {
		for (mangaId in mangaIds) {
			val value = history(mangaId)
			val current = snapshots[mangaId]
			when {
				value != null -> snapshots[mangaId] = (current ?: Snapshot()).copy(history = value)
				current?.localManga != null -> snapshots[mangaId] = current.copy(history = null)
				current != null -> snapshots.remove(mangaId)
			}
		}
	}

	@Synchronized
	fun putLocalAll(manga: Collection<Manga>) {
		for (item in manga) {
			if (!item.isLocal || item.chapters.isNullOrEmpty()) continue
			val current = snapshots[item.id]
			snapshots[item.id] = (current ?: Snapshot()).copy(localManga = item)
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
