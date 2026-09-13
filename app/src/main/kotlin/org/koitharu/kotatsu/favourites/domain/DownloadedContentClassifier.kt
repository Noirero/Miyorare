package org.koitharu.kotatsu.favourites.domain

import dagger.Reusable
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.model.isNovelContentPath
import org.koitharu.kotatsu.download.domain.DownloadDestinationStore
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.local.data.LocalStorageManager
import org.koitharu.kotatsu.local.data.output.LocalMangaOutput
import java.io.File
import javax.inject.Inject

/** Lightweight DB/path classifier for entries that belong to the virtual Downloaded shelf. */
@Reusable
class DownloadedContentClassifier @Inject constructor(
	private val db: MangaDatabase,
	private val storageManager: LocalStorageManager,
	private val destinationStore: DownloadDestinationStore,
) {
	/**
	 * Returns indexed downloaded ids inside the requested favourites space. This only reads the local
	 * index and compares paths; it never walks manga folders per item, so separating Normal/Private
	 * does not add UI-time disk scans.
	 *
	 * A null [space] preserves the old global classification for callers that intentionally operate
	 * across every configured local root.
	 */
	suspend fun getLocalDownloadedIds(space: FavouriteSpace? = null): Set<Long> {
		return db.getLocalMangaIndexDao().findAllEntries()
			.filterToDownloadRoots(downloadRoots(space))
			.mapTo(HashSet()) { it.mangaId }
	}

	suspend fun getLocalNovelIds(space: FavouriteSpace? = null): Set<Long> {
		val result = HashSet<Long>()
		for (entry in db.getLocalMangaIndexDao().findAllEntries().filterToDownloadRoots(downloadRoots(space))) {
			if (entry.path.isNovelContentPath()) {
				result += entry.mangaId
			}
		}
		return result
	}

	private suspend fun downloadRoots(space: FavouriteSpace?): List<File> {
		val roots = if (space == null) {
			storageManager.getConfiguredDirs()
		} else {
			destinationStore.readableRoots(space)
		}
		return roots.map { File(it, LocalMangaOutput.DOWNLOADS_DIR_NAME) }
	}

	private fun List<org.koitharu.kotatsu.local.data.index.LocalMangaIndexEntity>.filterToDownloadRoots(
		downloadRoots: List<File>,
	) = filter { entry ->
		val path = File(entry.path).absolutePath
		downloadRoots.any { root ->
			val rootPath = root.absolutePath.trimEnd(File.separatorChar)
			path == rootPath || path.startsWith(rootPath + File.separator)
		}
	}
}
