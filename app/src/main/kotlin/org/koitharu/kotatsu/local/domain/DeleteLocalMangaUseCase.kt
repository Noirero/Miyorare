package org.koitharu.kotatsu.local.domain

import androidx.core.net.toFile
import androidx.core.net.toUri
import org.koitharu.kotatsu.core.model.isLocal
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.favourites.domain.FavouriteDownloadOwnershipIndex
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
	private val favouriteDownloadOwnershipIndex: FavouriteDownloadOwnershipIndex,
) {

	suspend operator fun invoke(manga: Manga) {
		val victim = if (manga.isLocal) manga else localMangaRepository.findSavedManga(manga)?.manga
		checkNotNull(victim) { "Cannot find saved manga for ${manga.title}" }
		val victimFile = victim.url.toUri().toFile()
		val original = if (manga.isLocal) localMangaRepository.getRemoteManga(manga) else manga
		localMangaRepository.delete(victim) || throw IOException("Unable to delete file")
		// Remove only the deleted physical container. A second copy in the other favourites space keeps
		// its own ownership row and therefore remains immediately visible as downloaded there.
		favouriteDownloadOwnershipIndex.removePath(victimFile)
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
	 * Resolve only indexed/aliased targets for the requested ids. A favourites delete must never
	 * force a full Local snapshot, filesystem prune or index rebuild just to discover its targets.
	 *
	 * @return number of downloaded manga containers removed.
	 */
	suspend operator fun invoke(ids: Set<Long>): Int {
		if (ids.isEmpty()) return 0
		val targets = localMangaIndex.getDeleteTargets(ids)
		var removed = 0
		for (target in targets) {
			invoke(target.manga)
			removed++
		}
		return removed
	}
}
