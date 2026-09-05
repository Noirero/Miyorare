package org.koitharu.kotatsu.favourites.domain

import dagger.Reusable
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.local.data.LocalStorageManager
import org.koitharu.kotatsu.local.data.output.LocalMangaOutput
import java.io.File
import javax.inject.Inject

/** Lightweight classifier for LOCAL items that belong to the virtual Downloaded novel shelf. */
@Reusable
class DownloadedContentClassifier @Inject constructor(
	private val db: MangaDatabase,
	private val storageManager: LocalStorageManager,
) {
	suspend fun getLocalDownloadedIds(): Set<Long> {
		val downloadRoots = storageManager.getConfiguredDirs().map {
			File(it, LocalMangaOutput.DOWNLOADS_DIR_NAME)
		}
		return db.getLocalMangaIndexDao().findAllEntries()
			.filterToDownloadRoots(downloadRoots)
			.mapTo(HashSet()) { it.mangaId }
	}

	suspend fun getLocalNovelIds(): Set<Long> {
		val result = HashSet<Long>()
		val downloadRoots = storageManager.getConfiguredDirs().map {
			File(it, LocalMangaOutput.DOWNLOADS_DIR_NAME)
		}
		for (entry in db.getLocalMangaIndexDao().findAllEntries().filterToDownloadRoots(downloadRoots)) {
			val path = entry.path.replace('\\', '/')
			val cleanPath = path.substringBefore('#').substringBefore('?')
			if (
				path.contains("/00.Novel/", ignoreCase = true) ||
				cleanPath.endsWith(".epub", ignoreCase = true)
			) {
				result += entry.mangaId
			}
		}
		return result
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
