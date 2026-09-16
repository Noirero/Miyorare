package org.koitharu.kotatsu.favourites.domain

import kotlinx.coroutines.flow.FlowCollector
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.download.domain.DownloadDestinationStore
import org.koitharu.kotatsu.favourites.data.FavouriteDownloadIndexEntity
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.local.data.output.LocalMangaOutput
import org.koitharu.kotatsu.local.domain.model.LocalManga
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Incrementally records which favourites space owns a downloaded container.
 *
 * This deliberately listens to the same local-storage events as LocalMangaIndex instead of scanning
 * storage. A shared Normal/Private destination records both spaces; distinct destinations record only
 * the matching space. The virtual Downloaded shelf does not depend on this table.
 */
@Singleton
class FavouriteDownloadOwnershipIndex @Inject constructor(
	private val db: MangaDatabase,
	private val downloadDestinationStore: DownloadDestinationStore,
) : FlowCollector<LocalManga?> {

	override suspend fun emit(value: LocalManga?) {
		if (value == null) return
		val path = value.file.canonicalOrAbsolute()
		val entries = FavouriteSpace.entries.mapNotNull { space ->
			val ownsPath = downloadDestinationStore.readableRoots(space).any { destination ->
				value.file.isInside(File(destination, LocalMangaOutput.DOWNLOADS_DIR_NAME))
			}
			if (ownsPath) {
				FavouriteDownloadIndexEntity(
					mangaId = value.manga.id,
					space = space.dbValue,
					path = path,
				)
			} else {
				null
			}
		}
		if (entries.isNotEmpty()) {
			db.getFavouriteDownloadIndexDao().upsert(entries)
		}
	}

	suspend fun removePath(file: File) {
		db.getFavouriteDownloadIndexDao().deleteByPath(file.canonicalOrAbsolute())
	}

	private fun File.isInside(root: File): Boolean {
		val rootPath = root.canonicalOrAbsolute().trimEnd(File.separatorChar)
		val filePath = canonicalOrAbsolute()
		return filePath == rootPath || filePath.startsWith(rootPath + File.separator)
	}

	private fun File.canonicalOrAbsolute(): String = runCatching { canonicalPath }.getOrDefault(absolutePath)
}
