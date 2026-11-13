package org.koitharu.kotatsu.local.domain

import androidx.core.net.toFile
import org.koitharu.kotatsu.core.db.MangaDatabase
import androidx.core.net.toUri
import org.koitharu.kotatsu.core.model.isLocal
import org.koitharu.kotatsu.download.domain.DownloadDestinationStore
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.favourites.domain.FavouritesRepository
import org.koitharu.kotatsu.favourites.domain.FavouriteDownloadOwnershipIndex
import org.koitharu.kotatsu.history.data.HistoryRepository
import org.koitharu.kotatsu.local.data.ExhentaiDownloadedCopy
import org.koitharu.kotatsu.local.data.LocalMangaRepository
import org.koitharu.kotatsu.local.data.input.LocalMangaParser
import org.koitharu.kotatsu.local.data.output.LocalMangaOutput
import org.koitharu.kotatsu.local.data.index.LocalMangaIndex
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import java.io.File
import java.io.IOException
import javax.inject.Inject

class DeleteLocalMangaUseCase @Inject constructor(
	private val localMangaRepository: LocalMangaRepository,
	private val localMangaIndex: LocalMangaIndex,
	private val mangaDataRepository: MangaDataRepository,
	private val downloadedMangaResolver: DownloadedMangaResolver,
	private val historyRepository: HistoryRepository,
	private val favouriteDownloadOwnershipIndex: FavouriteDownloadOwnershipIndex,
	private val database: MangaDatabase,
	private val downloadDestinationStore: DownloadDestinationStore,
	private val favouritesRepository: FavouritesRepository,
	private val mangaLock: MangaLock,
) {

	suspend operator fun invoke(manga: Manga) {
		if (ExhentaiDownloadedCopy.isOfficial(manga)) {
			check(deleteExhentaiCopy(manga, FavouriteSpace.NORMAL)) { "Downloaded ExHentai gallery is unavailable" }
			return
		}
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


	/** A compatibility read match is not enough evidence to delete a remote gallery's file. */
	internal suspend fun deleteExhentaiCopy(
		remote: Manga,
		space: FavouriteSpace,
		expectedFile: File? = null,
		chapterIds: Set<Long>? = null,
	): Boolean = mangaLock.withLock(remote) {
		require(ExhentaiDownloadedCopy.isOfficial(remote))
		val saved = downloadedMangaResolver.findSavedManga(remote, space) ?: return@withLock false
		val file = saved.file.canonicalFile
		val proven = ExhentaiDownloadedCopy.find(remote, file, withDetails = true)
		if (!file.isFile || proven?.verified != true || proven.local.file.canonicalFile != file ||
			(expectedFile != null && expectedFile.canonicalFile != file)) {
			throw IOException("Cannot verify which ExHentai file belongs to this gallery. Files were kept.")
		}
		if (chapterIds != null && proven.local.manga.chapters?.singleOrNull()?.let { setOf(it.id) } != chapterIds) {
			throw IOException("The selected ExHentai chapter could not be verified. Files were kept.")
		}
		val owners = database.getFavouriteDownloadIndexDao().findEntriesByPath(file.path)
		if (owners.any { it.space != space.dbValue || it.mangaId != remote.id }) {
			throw IOException("This file is shared with another library entry. Files were kept.")
		}
		invoke(proven.local.manga)
		true
	}

	/**
	 * The Downloads removal menu must delete the exact container written for the selected
	 * WorkManager destination, never a global-index match for the same manga id.
	 *
	 * This intentionally fails closed for legacy/unindexed or ambiguous shared-space files.
	 * Users can still remove their WorkManager history without touching any physical files.
	 */
	suspend fun deleteOwnedDownloadAtRoot(remoteManga: Manga, selectedRoot: File, space: FavouriteSpace) {
		require(!remoteManga.isLocal) { "Only remote download tasks may delete their downloaded container" }
		// The downloader locks a chapter-hydrated Manga from the repository. Use the same
		// representation here rather than a metadata-only Downloads row.
		val lockedManga = mangaDataRepository.findMangaById(remoteManga.id, withChapters = true)
			?: throw IOException("Cannot verify the selected manga in the library. Files were kept.")
		mangaLock.withLock(lockedManga) {
			val entry = database.getFavouriteDownloadIndexDao()
				.findEntry(space.dbValue, remoteManga.id)
				?: throw IOException("Cannot verify this download's file ownership. Files were kept.")
			val file = File(entry.path).canonicalFile
			if (!isInsideDownloadRoot(selectedRoot, file) || !file.exists()) {
				throw IOException("The selected download's original file is unavailable. Files were kept.")
			}
			val resolvedPath = if (ExhentaiDownloadedCopy.isOfficial(lockedManga)) {
				val exact = ExhentaiDownloadedCopy.find(lockedManga, file, withDetails = false)
					?.takeIf { file.isFile && it.verified }
					?: throw IOException("Cannot verify the selected ExHentai archive. Files were kept.")
				exact.local.file.canonicalFile
			} else {
				val output = LocalMangaOutput.get(selectedRoot, lockedManga)
					?: throw IOException("Cannot match the selected download to its saved file. Files were kept.")
				try {
					output.rootFile.canonicalFile
				} finally {
					output.close()
				}
			}
			if (resolvedPath != file) {
				throw IOException("Download storage has changed since this task. Files were kept.")
			}
			val owners = database.getFavouriteDownloadIndexDao().findEntriesByPath(entry.path)
			if (owners.any { it.mangaId != remoteManga.id || it.space != space.dbValue }) {
				throw IOException("This file is shared with another library space. Files were kept.")
			}
			// A missing older ownership row must not make a shared Normal/Private root safe.
			val otherSpace = if (space == FavouriteSpace.NORMAL) FavouriteSpace.PRIVATE else FavouriteSpace.NORMAL
			val otherOwnsManga = favouritesRepository.isFavorite(remoteManga.id, otherSpace)
			val otherCanSeeFile = downloadDestinationStore.readableRoots(otherSpace).any {
				isInsideDownloadRoot(it, file)
			}
			val otherEntry = database.getFavouriteDownloadIndexDao()
				.findEntry(otherSpace.dbValue, remoteManga.id)
			// Even if an older index stored a non-canonical spelling of the same path,
			// do not delete a file explicitly owned by the other library space.
			if (otherEntry != null && File(otherEntry.path).canonicalFile == file) {
				throw IOException("This file is owned by another library space. Files were kept.")
			}
			if (otherOwnsManga && otherCanSeeFile && otherEntry == null) {
				throw IOException("This file could belong to both Normal and Private. Files were kept.")
			}
			val local = LocalMangaParser.getOrNull(file)?.getManga(withDetails = false)
				?: throw IOException("Cannot verify the selected downloaded file. Files were kept.")
			if (!local.manga.isLocal || local.file.canonicalFile != file) {
				throw IOException("The selected download does not resolve to a local file. Files were kept.")
			}
			invoke(local.manga)
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

	/**
	 * Delete downloaded copies only from the favourites space that initiated the action.
	 *
	 * A remote manga may have independent Normal and Private containers. The global Local index can
	 * point at either one, so favourites must resolve through the same space-aware ownership boundary
	 * used by Details before deleting any physical file.
	 */
	suspend operator fun invoke(ids: Set<Long>, favouriteSpace: FavouriteSpace): Int {
		if (ids.isEmpty()) return 0
		var removed = 0
		for (mangaId in ids) {
			val manga = mangaDataRepository.findMangaById(mangaId, withChapters = true) ?: continue
			if (ExhentaiDownloadedCopy.isOfficial(manga)) {
				if (deleteExhentaiCopy(manga, favouriteSpace)) removed++
				continue
			}
			val target = if (manga.isLocal) {
				manga
			} else {
				downloadedMangaResolver.findSavedManga(manga, favouriteSpace)?.manga ?: continue
			}
			invoke(target)
			removed++
		}
		return removed
	}
}

/** Permit only files located strictly below the selected task's downloads directory. */
internal fun isInsideDownloadRoot(root: File, target: File): Boolean {
	val downloads = File(root, LocalMangaOutput.DOWNLOADS_DIR_NAME).canonicalFile.path.trimEnd(File.separatorChar)
	val path = target.canonicalFile.path
	return path.startsWith(downloads + File.separator)
}
