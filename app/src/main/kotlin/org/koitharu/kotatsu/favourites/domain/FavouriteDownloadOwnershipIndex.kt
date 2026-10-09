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
 * Incrementally records which favourites space owns a downloaded container when the physical path
 * itself proves that ownership. Explicit Normal/Private ownership for new downloads is written by
 * DownloadWorker before the local-storage event is published.
 *
 * A shared destination is intentionally ignored here: the same path belongs to both readable-root
 * sets and therefore cannot identify which destination the user selected. Treating it as evidence
 * would manufacture cross-space ownership every time Details or Local storage emitted an update.
 */
@Singleton
class FavouriteDownloadOwnershipIndex @Inject constructor(
	private val db: MangaDatabase,
	private val downloadDestinationStore: DownloadDestinationStore,
) : FlowCollector<LocalManga?> {

	override suspend fun emit(value: LocalManga?) {
		if (value == null) return
		if (!downloadDestinationStore.privateUsesOwnRoot() || downloadDestinationStore.rootsOverlap()) return
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
			val dao = db.getFavouriteDownloadIndexDao()
			val existingBySpace = dao.findEntries(listOf(value.manga.id)).associateBy { it.space }
			val changed = entries.filter { entry -> existingBySpace[entry.space]?.path != entry.path }
			if (changed.isNotEmpty()) {
				dao.upsert(changed)
			}
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
