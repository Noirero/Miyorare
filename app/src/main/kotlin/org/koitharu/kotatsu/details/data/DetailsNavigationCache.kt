package org.koitharu.kotatsu.details.data

import org.koitharu.kotatsu.core.model.MangaHistory
import org.koitharu.kotatsu.core.model.isLocal
import org.koitharu.kotatsu.parsers.model.Manga
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Small process-local handoff cache for Details navigation.
 *
 * Remote chapter snapshots deliberately live in Room now. Keeping chapter-bearing remote Manga
 * objects here duplicated Room/ViewModel state and forced Favourites to materialize chapter lists
 * before the user tapped anything. Only Local content keeps a chapter-bearing snapshot because
 * opening a Local container otherwise requires parsing the filesystem on demand.
 *
 * Reading history is kept in a separate tiny LRU. Remote-list history churn therefore cannot evict
 * an expensive Local snapshot that was prefetched specifically to keep Local Details responsive.
 */
@Singleton
class DetailsNavigationCache @Inject constructor() {

	private val localSnapshots = object : LinkedHashMap<Long, Manga>(MAX_LOCAL_ENTRIES, 0.75f, true) {
		override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Manga>?): Boolean =
			size > MAX_LOCAL_ENTRIES
	}

	private val histories = object : LinkedHashMap<Long, MangaHistory>(MAX_HISTORY_ENTRIES, 0.75f, true) {
		override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, MangaHistory>?): Boolean =
			size > MAX_HISTORY_ENTRIES
	}

	@Synchronized
	fun getLocalManga(mangaId: Long): Manga? = localSnapshots[mangaId]

	@Synchronized
	fun getHistory(mangaId: Long): MangaHistory? = histories[mangaId]

	@Synchronized
	fun containsLocalManga(mangaId: Long): Boolean = localSnapshots.containsKey(mangaId)

	@Synchronized
	fun updateHistory(mangaIds: Collection<Long>, history: (Long) -> MangaHistory?) {
		for (mangaId in mangaIds) {
			val value = history(mangaId)
			if (value == null) {
				histories.remove(mangaId)
			} else {
				histories[mangaId] = value
			}
		}
	}

	@Synchronized
	fun putLocalAll(manga: Collection<Manga>) {
		for (item in manga) {
			if (!item.isLocal || item.chapters.isNullOrEmpty()) continue
			localSnapshots[item.id] = item
		}
	}

	@Synchronized
	fun clear() {
		localSnapshots.clear()
		histories.clear()
	}

	private companion object {
		const val MAX_LOCAL_ENTRIES = 24
		const val MAX_HISTORY_ENTRIES = 24
	}
}
