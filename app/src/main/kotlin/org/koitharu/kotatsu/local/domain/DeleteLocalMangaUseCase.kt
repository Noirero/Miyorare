package org.koitharu.kotatsu.local.domain

import org.koitharu.kotatsu.core.model.isLocal
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.history.data.HistoryRepository
import org.koitharu.kotatsu.local.data.LocalMangaRepository
import org.koitharu.kotatsu.local.data.index.LocalMangaIndex
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import java.io.IOException
import javax.inject.Inject

class DeleteLocalMangaUseCase @Inject constructor(
	private val localMangaRepository: LocalMangaRepository,
	private val localMangaIndex: LocalMangaIndex,
	private val historyRepository: HistoryRepository,
) {

	suspend operator fun invoke(manga: Manga) {
		val victim = if (manga.isLocal) manga else localMangaRepository.findSavedManga(manga)?.manga
		checkNotNull(victim) { "Cannot find saved manga for ${manga.title}" }
		val original = if (manga.isLocal) localMangaRepository.getRemoteManga(manga) else manga
		localMangaRepository.delete(victim) || throw IOException("Unable to delete file")
		runCatchingCancellable {
			historyRepository.deleteOrSwap(victim, original)
		}.onFailure {
			it.printStackTraceDebug()
		}
	}

	/**
	 * Deletes only local/downloaded copies whose ids are requested. Missing downloads are ignored:
	 * callers may pass a whole favourites selection where only a subset is actually downloaded.
	 *
	 * Use the full local index instead of LocalMangaRepository.getList(), which is paged to 100 rows
	 * and therefore cannot safely service large (for example 16k-title) bulk selections.
	 *
	 * @return number of downloaded manga containers removed.
	 */
	suspend operator fun invoke(ids: Set<Long>): Int {
		if (ids.isEmpty()) return 0
		val targets = localMangaIndex.getAll().filter { it.manga.id in ids }
		var removed = 0
		for (target in targets) {
			invoke(target.manga)
			removed++
		}
		return removed
	}
}
