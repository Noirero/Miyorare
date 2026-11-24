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
import org.koitharu.kotatsu.favourites.domain.ExhentaiPresenceDiscovery
import org.koitharu.kotatsu.favourites.domain.FavouriteDownloadOwnershipIndex
import org.koitharu.kotatsu.history.data.HistoryRepository
import org.koitharu.kotatsu.local.data.ExhentaiDownloadedCopy
import org.koitharu.kotatsu.local.data.LocalMangaRepository
import org.koitharu.kotatsu.local.data.input.LocalMangaParser
import org.koitharu.kotatsu.local.data.output.LocalMangaOutput
import org.koitharu.kotatsu.sources.compat.EhentaiSourceFamily
import org.koitharu.kotatsu.sources.compat.EhentaiLegacyDownloadResolver
import org.koitharu.kotatsu.local.domain.model.LocalManga
import org.koitharu.kotatsu.sources.compat.ExhentaiChapterOwnership
import org.koitharu.kotatsu.local.data.index.LocalMangaIndex
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.attribute.BasicFileAttributeView
import android.util.Base64
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import javax.inject.Inject

/** Rejection before any file or index mutation. */
internal class UnverifiedDownloadRemovalException(
	message: String = "Cannot verify ownership of the selected download. Files were kept.",
	val missing: Boolean = false,
) : IOException(message)

/**
 * Snapshot explicitly approved by the user. Never use this as proof of download ownership.
 * Revalidate the physical target, destination and file identity immediately before unlinking.
 */
data class ExhentaiDeletionApproval(
	val sourceName: String,
	val mangaId: Long,
	val space: FavouriteSpace,
	val canonicalPath: String,
	val length: Long,
	val lastModified: Long,
	val sha256: String,
	val sharedWithOtherSpace: Boolean,
	val activeRoot: String,
	val otherRoot: String?,
	val device: Long,
	val inode: Long,
)

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
	private val exhentaiPresenceDiscovery: ExhentaiPresenceDiscovery,
) {

	suspend operator fun invoke(manga: Manga) {
		if (!manga.isLocal && EhentaiSourceFamily.supportsTitleMatchedDownloads(manga.source.name) &&
			!ExhentaiDownloadedCopy.isOfficial(manga)
		) {
			val saved = downloadedMangaResolver.findSavedManga(manga, FavouriteSpace.NORMAL)
				?: throw UnverifiedDownloadRemovalException("No source-owned ExHentai download was found.")
			requireSourceOwnedExhentaiDeletion(manga, FavouriteSpace.NORMAL, saved.file)
			invoke(saved.manga)
			return
		}
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


	/**
	 * A Gekko/other ExHentai title fallback is readable but never grants delete rights.
	 * Require the exact source's download row and reject a foreign Main-owned CBZ even
	 * when a generic downloader previously adopted the same directory.
	 */
	internal suspend fun requireSourceOwnedExhentaiDeletion(
		remote: Manga,
		space: FavouriteSpace,
		expectedFile: File,
	) {
		require(EhentaiSourceFamily.supportsTitleMatchedDownloads(remote.source.name) &&
			!ExhentaiDownloadedCopy.isOfficial(remote))
		val row = database.getFavouriteDownloadIndexDao().findEntry(space.dbValue, remote.id)
			?: throw UnverifiedDownloadRemovalException("A title-only ExHentai match cannot be deleted. Files were kept.")
		val owned = File(row.path).canonicalFile
		val expected = expectedFile.canonicalFile
		if (owned != expected && !(owned.isDirectory && expected.parentFile == owned)) {
			throw UnverifiedDownloadRemovalException("The selected file is not the source-owned download. Files were kept.")
		}
		if (downloadDestinationStore.readableRoots(space).none { isInsideDownloadRoot(it, owned) }) {
			throw UnverifiedDownloadRemovalException("The source-owned download is outside its configured storage. Files were kept.")
		}
		val archive = if (owned.isDirectory) {
			owned.listFiles { file -> file.isFile && file.extension.equals("cbz", ignoreCase = true) }
				?.singleOrNull()
		} else owned.takeIf { it.isFile && it.extension.equals("cbz", ignoreCase = true) }
			?: throw UnverifiedDownloadRemovalException("The ExHentai chapter is ambiguous or missing. Files were kept.")
		if (archive == null || ExhentaiChapterOwnership.inspectDestination(archive) != ExhentaiChapterOwnership.Evidence.Unmarked) {
			throw UnverifiedDownloadRemovalException("This ExHentai chapter is not verified as source-owned. Files were kept.")
		}
		val owners = database.getFavouriteDownloadIndexDao().findEntriesByPaths(
			listOf(owned.path, archive.canonicalPath),
		)
		if (owners.any { it.mangaId != remote.id || it.space != space.dbValue }) {
			throw UnverifiedDownloadRemovalException("Another source or space owns this file. Files were kept.")
		}
		val otherSpace = if (space == FavouriteSpace.NORMAL) FavouriteSpace.PRIVATE else FavouriteSpace.NORMAL
		if (favouritesRepository.isFavorite(remote.id, otherSpace) &&
			downloadDestinationStore.readableRoots(otherSpace).any { isInsideDownloadRoot(it, owned) }
		) {
			throw UnverifiedDownloadRemovalException("The file may belong to both favourite spaces. Files were kept.")
		}
	}

	private fun isExhentaiConfirmationSource(remote: Manga): Boolean =
		remote.source.name == EhentaiSourceFamily.OFFICIAL_SOURCE_NAME ||
			remote.source.name == GEKKO_EXHENTAI_SOURCE_NAME

	/** Ensure the confirmed CBZ belongs to a recognized source folder directly under the
	 * selected downloads root (or its historical pre-downloads layout).
	 */
	private fun isApprovedExhentaiSourceFolder(remote: Manga, space: FavouriteSpace, source: File?): Boolean {
		if (source == null) return false
		val folder = source.canonicalFile
		return downloadDestinationStore.activeExhentaiRoots(space).any { root ->
			val active = root.canonicalFile
			val downloads = File(active, LocalMangaOutput.DOWNLOADS_DIR_NAME).canonicalFile
			val parent = folder.parentFile
			val knownLegacy = folder.name.equals("ExHentai (OTHER)", ignoreCase = true) ||
				EhentaiLegacyDownloadResolver.isLegacySourceDirectoryName(folder.name)
			val currentSource = LocalMangaOutput.getSourceDirectory(downloads, remote).canonicalFile
			(knownLegacy && (parent == active || parent == downloads)) ||
				(folder == currentSource && currentSource != downloads)
		}
	}

	/**
	 * Deletion eligibility is independent of ZIP readability and historical ownership.
	 * Only an exact, visible CBZ in a recognized, title-matched gallery layout is eligible.
	 */
	private fun isEligibleExhentaiFile(remote: Manga, space: FavouriteSpace, file: File): Boolean {
		if (!isExhentaiConfirmationSource(remote) || !file.isFile || file.isHidden ||
			!file.extension.equals("cbz", ignoreCase = true) ||
			file.canonicalFile != file.absoluteFile.normalize() ||
			!downloadDestinationStore.isVisibleInActiveExhentaiDestination(space, file)
		) return false
		val parent = file.parentFile ?: return false
		return (EhentaiLegacyDownloadResolver.titlesMatch(remote.title, parent.name) &&
			isApprovedExhentaiSourceFolder(remote, space, parent.parentFile)) ||
			(EhentaiLegacyDownloadResolver.titlesMatch(remote.title, file.nameWithoutExtension) &&
				isApprovedExhentaiSourceFolder(remote, space, parent))
	}

	suspend fun listExhentaiPhysicalDeletionTargets(remote: Manga, space: FavouriteSpace): List<File> =
		withContext(Dispatchers.IO) {
			if (!isExhentaiConfirmationSource(remote)) return@withContext emptyList()
			downloadDestinationStore.activeExhentaiRoots(space)
				.flatMap { EhentaiLegacyDownloadResolver.findReadChoiceCandidates(it, remote.title) }
				.distinct()
				.filter { runCatching { isEligibleExhentaiFile(remote, space, it) }.getOrDefault(false) }
		}

	suspend fun previewExhentaiPhysicalDeletion(
		remote: Manga,
		space: FavouriteSpace,
		local: LocalManga,
	): ExhentaiDeletionApproval? = previewExhentaiPhysicalDeletion(remote, space, local.file)

	suspend fun previewExhentaiPhysicalDeletion(
		remote: Manga,
		space: FavouriteSpace,
		file: File,
	): ExhentaiDeletionApproval? = withContext(Dispatchers.IO) {
		runCatching { snapshotExhentaiDeletion(remote, space, file) }.getOrNull()
	}

	private fun snapshotExhentaiDeletion(remote: Manga, space: FavouriteSpace, file: File): ExhentaiDeletionApproval? {
		if (!isEligibleExhentaiFile(remote, space, file)) return null
		val root = downloadDestinationStore.effectiveRoot(space)?.canonicalPath ?: return null
		val otherSpace = if (space == FavouriteSpace.NORMAL) FavouriteSpace.PRIVATE else FavouriteSpace.NORMAL
		val otherRoot = downloadDestinationStore.effectiveRoot(otherSpace)?.canonicalPath
		val initial = Os.lstat(file.path)
		if (!OsConstants.S_ISREG(initial.st_mode)) return null
		val modified = file.lastModified()
		val hash = sha256Of(file)
		val final = Os.lstat(file.path)
		if (initial.st_dev != final.st_dev || initial.st_ino != final.st_ino ||
			initial.st_size != final.st_size || modified != file.lastModified() ||
			!isEligibleExhentaiFile(remote, space, file) ||
			root != downloadDestinationStore.effectiveRoot(space)?.canonicalPath ||
			otherRoot != downloadDestinationStore.effectiveRoot(otherSpace)?.canonicalPath
		) return null
		return ExhentaiDeletionApproval(
			sourceName = remote.source.name,
			mangaId = remote.id,
			space = space,
			canonicalPath = file.canonicalPath,
			length = initial.st_size,
			lastModified = modified,
			sha256 = hash,
			sharedWithOtherSpace = downloadDestinationStore.isVisibleInActiveExhentaiDestination(otherSpace, file),
			activeRoot = root,
			otherRoot = otherRoot,
			device = initial.st_dev,
			inode = initial.st_ino,
		)
	}

	private fun sha256Of(file: File): String {
		val digest = MessageDigest.getInstance("SHA-256")
		file.inputStream().buffered(65536).use { input ->
			val buffer = ByteArray(65536)
			while (true) {
				val count = input.read(buffer)
				if (count < 0) break
				if (count > 0) digest.update(buffer, 0, count)
			}
		}
		return Base64.encodeToString(digest.digest(), Base64.NO_WRAP)
	}

	/**
	 * User-authorized physical deletion is deliberately separate from strict automatic removal.
	 * A title match alone never authorizes this method, and approval of one CBZ cannot delete
	 * another same-title copy, directory, stale destination or concurrently replaced file.
	 */
	suspend fun deleteConfirmedExhentaiPhysicalFile(
		remote: Manga,
		space: FavouriteSpace,
		local: LocalManga,
		approval: ExhentaiDeletionApproval,
	): Boolean {
		if (local.file.absoluteFile.normalize().path != approval.canonicalPath) {
			throw UnverifiedDownloadRemovalException("The selected ExHentai file changed. Files were kept.")
		}
		return deleteConfirmedExhentaiPhysicalFile(remote, space, approval)
	}

	suspend fun deleteConfirmedExhentaiPhysicalFile(
		remote: Manga,
		space: FavouriteSpace,
		approval: ExhentaiDeletionApproval,
	): Boolean = deleteConfirmedExhentaiPhysicalFile(remote, space, approval) {}

	// Deterministic instrumentation seam: production uses the same path with a no-op callback.
	@androidx.annotation.VisibleForTesting(otherwise = androidx.annotation.VisibleForTesting.PRIVATE)
	internal suspend fun deleteConfirmedExhentaiPhysicalFile(
		remote: Manga,
		space: FavouriteSpace,
		approval: ExhentaiDeletionApproval,
		beforePhysicalRemoval: () -> Unit,
	): Boolean = mangaLock.withLock(remote) {
		// Match the source-owned removal lock order: manga first, then physical removal.
		localMangaRepository.exhentaiRemovalMutex.withLock {
			withContext(Dispatchers.IO) {
				val file = File(approval.canonicalPath)
				val fresh = runCatching { snapshotExhentaiDeletion(remote, space, file) }.getOrNull()
				if (fresh == null || fresh != approval) {
					throw UnverifiedDownloadRemovalException(
						"The selected ExHentai file or destination changed after confirmation. Files were kept.",
					)
				}
				val parent = file.parentFile
				val titleFolder = parent != null && EhentaiLegacyDownloadResolver.titlesMatch(remote.title, parent.name) &&
					isApprovedExhentaiSourceFolder(remote, space, parent.parentFile)
				// Do not use a recursive deletion utility. Unlink only the approved regular file.
				val stat = Os.lstat(file.path)
				if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_dev != approval.device ||
					stat.st_ino != approval.inode || stat.st_size != approval.length ||
					file.lastModified() != approval.lastModified || !isEligibleExhentaiFile(remote, space, file)
				) throw UnverifiedDownloadRemovalException("The selected ExHentai file changed. Files were kept.")
				beforePhysicalRemoval()
				// Linearize destination publication and removal. The hash stays outside this
				// critical section; a concurrent root setter either wins first (reject) or waits.
				// This lock does not serialize external filesystem changes.
				downloadDestinationStore.withDestinationMutationLock {
					val otherSpace = if (space == FavouriteSpace.NORMAL) FavouriteSpace.PRIVATE else FavouriteSpace.NORMAL
					if (downloadDestinationStore.effectiveRoot(space)?.canonicalPath != approval.activeRoot ||
						downloadDestinationStore.effectiveRoot(otherSpace)?.canonicalPath != approval.otherRoot ||
						!downloadDestinationStore.isVisibleInActiveExhentaiDestination(space, file)
					) throw UnverifiedDownloadRemovalException("The selected ExHentai destination changed. Files were kept.")
					deleteFileOnly(file)
				}
				withContext(NonCancellable) {
					val removedPaths = linkedSetOf(approval.canonicalPath)
					// Clear the confirmed manga's legacy directory ownership, not other manga's rows.
					// Use the exact validated parent path; do not resolve it again after removal.
					if (titleFolder && parent != null) {
						for (targetSpace in FavouriteSpace.entries) {
							database.getFavouriteDownloadIndexDao().deleteIfPath(targetSpace.dbValue, remote.id, parent.path)
						}
					}
					// Keep other manga's parent-directory rows while sibling CBZs remain or listing fails.
					if (titleFolder && parent != null && parent.listFiles()?.none {
							it.isFile && it.extension.equals("cbz", ignoreCase = true)
						} == true) removedPaths.add(parent.path)
					for (path in removedPaths) database.getFavouriteDownloadIndexDao().deleteByPath(path)
					localMangaIndex.removeExactPaths(removedPaths)
					exhentaiPresenceDiscovery.forgetConfirmedDeletedPath(approval.canonicalPath)
				}
				true
			}
		}
	}

	/**
	 * Android's remove(3) also removes empty directories. The default Unix secure stream
	 * uses file-only unlinkat instead. Never fall back to remove/deleteRecursively.
	 * This does not make the approved content identity atomic against external writers.
	 */
	private fun deleteFileOnly(file: File) {
		val path = file.toPath()
		try {
			Files.newDirectoryStream(path.parent).use { stream ->
				val secure = stream as? SecureDirectoryStream<Path>
					?: throw UnverifiedDownloadRemovalException("File-only removal is not supported by this storage. Files were kept.")
				val attributes = secure.getFileAttributeView(
					path.fileName, BasicFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS,
				)?.readAttributes()
				if (attributes?.isRegularFile != true) {
					throw UnverifiedDownloadRemovalException("The selected ExHentai target is not a regular file. Files were kept.")
				}
				secure.deleteFile(path.fileName)
			}
		} catch (e: UnverifiedDownloadRemovalException) {
			throw e
		} catch (e: IOException) {
			throw UnverifiedDownloadRemovalException("Cannot remove the selected ExHentai file.").apply { initCause(e) }
		}
	}

	private companion object {
		const val GEKKO_EXHENTAI_SOURCE_NAME = "TSUKI:GEKKOUSHI:gekkoushi:EXHENTAI"
	}

	/** Reading a legacy title path alone does not authorize deletion. */
	internal suspend fun deleteExhentaiCopy(
		remote: Manga,
		space: FavouriteSpace,
		expectedFile: File? = null,
		chapterIds: Set<Long>? = null,
	): Boolean = mangaLock.withLock(remote) {
		require(ExhentaiDownloadedCopy.isOfficial(remote))
		val saved = downloadedMangaResolver.findSavedManga(remote, space) ?: return@withLock false
		val file = saved.file.canonicalFile
		val proven = localMangaRepository.findExhentaiAtPath(remote, file, withDetails = true, space = space)
		if (!file.isFile || proven?.verified != true || proven.local.file.canonicalFile != file ||
			(expectedFile != null && expectedFile.canonicalFile != file)) {
			throw IOException("Cannot verify which ExHentai file belongs to this gallery. Files were kept.")
		}
		if (chapterIds != null && proven.local.manga.chapters?.singleOrNull()?.let { setOf(it.id) } != chapterIds) {
			throw IOException("The selected ExHentai chapter could not be verified. Files were kept.")
		}
		val victim = proven.local.manga
		val original = localMangaRepository.getRemoteManga(victim)
		if (!localMangaRepository.deleteExhentaiDestinationCopy(proven.local)) return@withLock false
		// A physical Local entry can have its own reading history. Keep the remote history intact.
		if (victim.id != remote.id) {
			runCatchingCancellable { historyRepository.deleteOrSwap(victim, original) }
				.onFailure(Throwable::printStackTraceDebug)
		}
		true
	}

	/**
	 * The Downloads removal menu must delete the exact container written for the selected
	 * WorkManager destination, never a global-index match for the same manga id.
	 *
	 * This intentionally fails closed for legacy/unindexed or ambiguous shared-space files.
	 * Users can still remove their WorkManager history without touching any physical files.
	 */
	suspend fun deleteOwnedDownloadAtRoot(
		remoteManga: Manga,
		selectedRoot: File,
		space: FavouriteSpace,
		expectedFile: File? = null,
		expectedRequest: java.util.UUID? = null,
	) {
		require(!remoteManga.isLocal) { "Only remote download tasks may delete their downloaded container" }
		// The downloader locks a chapter-hydrated Manga from the repository. Use the same
		// representation here rather than a metadata-only Downloads row.
		val lockedManga = mangaDataRepository.findMangaById(remoteManga.id, withChapters = true)
			?: throw UnverifiedDownloadRemovalException("Cannot verify the selected manga in the library. Files were kept.")
		mangaLock.withLock(lockedManga) {
			val entry = database.getFavouriteDownloadIndexDao()
				.findEntry(space.dbValue, remoteManga.id)
				?: throw UnverifiedDownloadRemovalException("Cannot verify this download's file ownership. Files were kept.")
			val file = File(entry.path).canonicalFile
			if (!isInsideDownloadRoot(selectedRoot, file)) {
				throw UnverifiedDownloadRemovalException("The selected download is outside its original folder. Files were kept.")
			}
			if (!file.exists()) {
				throw UnverifiedDownloadRemovalException(
					"The selected download's original file is unavailable. Files were kept.", missing = true,
				)
			}
			val resolvedPath = if (ExhentaiDownloadedCopy.isOfficial(lockedManga)) {
				if (expectedFile == null || expectedRequest == null || expectedFile.canonicalFile != file) {
					throw UnverifiedDownloadRemovalException("The selected job does not own the current ExHentai archive. Files were kept.")
				}
				val exact = ExhentaiDownloadedCopy.find(lockedManga, file, withDetails = false, space = space, request = expectedRequest)
					?.takeIf { file.isFile && it.verified }
					?: throw UnverifiedDownloadRemovalException("Cannot verify the selected ExHentai archive. Files were kept.")
				exact.local.file.canonicalFile
			} else {
				if (EhentaiSourceFamily.supportsTitleMatchedDownloads(lockedManga.source.name)) {
					requireSourceOwnedExhentaiDeletion(lockedManga, space, file)
				}
				val output = LocalMangaOutput.get(selectedRoot, lockedManga)
					?: throw UnverifiedDownloadRemovalException("Cannot match the selected download to its saved file. Files were kept.")
				try {
					output.rootFile.canonicalFile
				} finally {
					output.close()
				}
			}
			if (resolvedPath != file) {
				throw UnverifiedDownloadRemovalException("Download storage has changed since this task. Files were kept.")
			}
			val owners = database.getFavouriteDownloadIndexDao().findEntriesByPath(entry.path)
			if (owners.any { it.mangaId != remoteManga.id || it.space != space.dbValue }) {
				throw UnverifiedDownloadRemovalException("This file is shared with another library space. Files were kept.")
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
				throw UnverifiedDownloadRemovalException("This file is owned by another library space. Files were kept.")
			}
			if (otherOwnsManga && otherCanSeeFile && otherEntry == null) {
				throw UnverifiedDownloadRemovalException("This file could belong to both Normal and Private. Files were kept.")
			}
			val local = LocalMangaParser.getOrNull(file)?.getManga(withDetails = false)
				?: throw UnverifiedDownloadRemovalException("Cannot verify the selected downloaded file. Files were kept.")
			if (!local.manga.isLocal || local.file.canonicalFile != file) {
				throw UnverifiedDownloadRemovalException("The selected download does not resolve to a local file. Files were kept.")
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
			if (EhentaiSourceFamily.supportsTitleMatchedDownloads(manga.source.name)) {
				val candidate = downloadedMangaResolver.findSavedManga(manga, favouriteSpace) ?: continue
				requireSourceOwnedExhentaiDeletion(manga, favouriteSpace, candidate.file)
				invoke(candidate.manga)
				removed++
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
