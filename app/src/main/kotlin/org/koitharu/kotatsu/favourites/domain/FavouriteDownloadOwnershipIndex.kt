package org.koitharu.kotatsu.favourites.domain

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.toEntities
import org.koitharu.kotatsu.core.db.entity.toEntity
import org.koitharu.kotatsu.download.domain.DownloadDestinationStore
import org.koitharu.kotatsu.favourites.data.FavouriteDownloadIndexEntity
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.local.data.output.LocalMangaOutput
import org.koitharu.kotatsu.local.domain.model.LocalManga
import org.koitharu.kotatsu.sources.compat.ExhentaiChapterOwnership
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
		if (entries.isEmpty()) return
		// The worker already records the scope of a receipt-backed ExHentai archive. A current or
		// historical folder can be visible from another scope and must not override the receipt.
		val evidence = withContext(Dispatchers.IO) { ExhentaiChapterOwnership.inspectDestination(value.file) }
		if ((evidence as? ExhentaiChapterOwnership.Evidence.Owned)?.receipt != null) return
		db.withTransaction {
			// BaseApp collects ownership and LocalMangaIndex independently. Either consumer can win
			// the completion event, so persist a missing parent atomically with its ownership rows.
			// Existing remote/local metadata must not be replaced by this secondary index writer.
			if (value.manga.id !in db.getMangaDao()) {
				val tags = value.manga.tags.toEntities()
				db.getTagsDao().upsert(tags)
				db.getMangaDao().upsert(value.manga.toEntity(), tags)
			}
			val dao = db.getFavouriteDownloadIndexDao()
			val existingBySpace = dao.findEntries(listOf(value.manga.id)).associateBy { it.space }
			val changed = entries.filter { entry -> existingBySpace[entry.space]?.path != entry.path }
			if (changed.isNotEmpty()) {
				dao.upsert(changed)
			}
		}
	}

	internal suspend fun hasExhentaiOwnershipConflict(
		mangaId: Long,
		space: FavouriteSpace?,
		file: File,
		verified: Boolean,
		physicalMangaId: Long? = null,
		receipt: ExhentaiChapterOwnership.Receipt? = null,
	): Boolean {
		val physical = file.canonicalFile
		val paths = if (verified) listOf(physical.path)
		else listOfNotNull(physical.path, physical.takeIf(File::isFile)?.parentFile?.path)
		val hasMatchingReceipt = verified && space != null && receipt?.space == space
		return db.getFavouriteDownloadIndexDao().findEntriesByPaths(paths).any {
			// Older installs also indexed the physical Local id from the generic storage event.
			val localAlias = verified && it.mangaId == physicalMangaId && it.mangaId != mangaId
			// Earlier builds could index the same gallery and file in both spaces from a storage
			// event. Only a verified receipt can disambiguate those rows for the requested space.
			val foreignSpace = space != null && it.space != space.dbValue && !hasMatchingReceipt
			!localAlias && (it.mangaId != mangaId || foreignSpace)
		}
	}

	/** Only an exact artifact row is proof; a directory alias cannot authorize a shared-root CBZ. */
	internal suspend fun ownsExactExhentaiFile(mangaId: Long, space: FavouriteSpace, file: File): Boolean =
		db.getFavouriteDownloadIndexDao().findEntry(space.dbValue, mangaId)?.path == file.canonicalPath

	internal suspend fun pathsForManga(mangaId: Long): List<File> =
		db.getFavouriteDownloadIndexDao().findEntries(listOf(mangaId)).map { File(it.path) }

	suspend fun removePath(file: File) {
		db.getFavouriteDownloadIndexDao().deleteByPath(file.canonicalOrAbsolute())
	}

	/**
	 * A confirmed physical CBZ delete can leave an old gallery-specific index row
	 * pointing to its parent directory. Remove only the deleted gallery's rows,
	 * not other galleries sharing the physical directory.
	 */
	suspend fun removeMangaPaths(mangaId: Long, files: Collection<File>) {
		val paths = files.mapTo(HashSet()) { it.canonicalOrAbsolute() }
		val dao = db.getFavouriteDownloadIndexDao()
		for (entry in dao.findEntries(listOf(mangaId))) {
			if (entry.path in paths) dao.deleteIfPath(entry.space, entry.mangaId, entry.path)
		}
	}

	private fun File.isInside(root: File): Boolean {
		val rootPath = root.canonicalOrAbsolute().trimEnd(File.separatorChar)
		val filePath = canonicalOrAbsolute()
		return filePath == rootPath || filePath.startsWith(rootPath + File.separator)
	}

	private fun File.canonicalOrAbsolute(): String = runCatching { canonicalPath }.getOrDefault(absolutePath)
}
