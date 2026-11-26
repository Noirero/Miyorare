package org.koitharu.kotatsu.local.domain

import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.download.domain.DownloadDestinationStore
import org.koitharu.kotatsu.download.domain.NormalDownloadMutationFence
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.favourites.domain.NormalDownloadObservationStore
import org.koitharu.kotatsu.favourites.domain.NormalDownloadScope
import org.koitharu.kotatsu.local.data.canRenameStandaloneChapterFile
import org.koitharu.kotatsu.local.data.chapterDownloadFile
import org.koitharu.kotatsu.local.data.renameStandaloneChapterFile
import org.koitharu.kotatsu.parsers.model.Manga
import java.io.File
import java.io.IOException
import javax.inject.Inject

/** Selection evidence, not a persistent permission or ownership receipt. */
internal data class NormalStandaloneSelection(
	val mangaId: Long,
	val source: String,
	val mangaUrl: String,
	val space: FavouriteSpace?,
	val generation: Long,
	val container: File,
	val file: File,
	val chapterId: Long,
	val chapterUri: String,
	val containerIdentity: NormalPathIdentity,
	val fileIdentity: NormalPathIdentity,
)

internal data class NormalPathIdentity(
	val device: Long, val inode: Long, val size: Long, val modified: Long, val changed: Long,
)

/**
 * Separate action-time proof. The user-selected path/ID must survive fresh scoped
 * discovery unchanged, even if another readable artifact exists. No ownership row
 * is created. The exact standalone proof does not require ZIP readability; the
 * existing rename guard separately rejects corrupt/indexed/unsupported formats.
 */
class NormalDownloadChapterAction @Inject internal constructor(
	private val scope: NormalDownloadScope,
	private val observations: NormalDownloadObservationStore,
	private val destinations: DownloadDestinationStore,
	private val fence: NormalDownloadMutationFence,
	private val mangaLock: MangaLock,
) {
	internal suspend fun appliesTo(manga: Manga, space: FavouriteSpace?) = scope.appliesTo(manga, space)

	internal suspend fun captureStandalone(
		manga: Manga, space: FavouriteSpace?, container: File, file: File, chapterId: Long, chapterUri: String,
	): NormalStandaloneSelection = withContext(Dispatchers.IO) {
		val roots = scope.resolveActiveLookup(manga, space) ?: throw IOException("Normal download context changed")
		val generation = roots.generation
		if (container != container.canonicalFile || file != file.canonicalFile ||
			chapterDownloadFile(container, chapterUri) != file ||
			!scope.isWithinActiveRoot(file, roots.normalRoot, roots.privateRoot)
		) throw IOException("Selected chapter is outside the verified destination")
		// Stamp both sides of discovery. A replacement during proof must not inherit
		// the old artifact's identity just because it uses the same filename.
		val parentIdentity = identity(container, directory = true)
		val fileIdentity = identity(file, directory = false)
		val result = observations.resolveForRead(manga, space) ?: throw IOException("Download is no longer verified")
		val candidate = result.uniqueCandidate ?: throw IOException("Download is no longer verified")
		val projection = result.directoryIdentity?.projection ?: throw IOException("Chapter identity is unresolved")
		if (candidate.file != container || projection.file != container ||
			projection.manga.chapters.orEmpty().none {
				it.id == chapterId && chapterDownloadFile(container, it.url) == file
			}
		) throw IOException("Selected chapter changed; refresh Details")
		destinations.withDestinationMutationLock {
			if (generation != destinations.normalScannerRootChanges.value ||
				parentIdentity != identity(container, directory = true) || fileIdentity != identity(file, directory = false)
			) throw IOException("Selected chapter changed; refresh Details")
			NormalStandaloneSelection(manga.id, manga.source.name, manga.url, space, generation,
				container, file, chapterId, chapterUri, parentIdentity, fileIdentity)
		}
	}

	internal suspend fun previewRename(
		manga: Manga, space: FavouriteSpace?, container: File, file: File, chapterId: Long, chapterUri: String,
	): NormalStandaloneSelection = withContext(Dispatchers.IO) {
		val selected = captureStandalone(manga, space, container, file, chapterId, chapterUri)
		check(canRenameStandaloneChapterFile(container, file)) { "Only standalone chapter downloads can be renamed" }
		selected
	}

	internal suspend fun rename(
		manga: Manga, space: FavouriteSpace?, approved: NormalStandaloneSelection, name: String,
	): Boolean = mangaLock.withLock(manga) {
		withContext(Dispatchers.IO) {
			fence.mutateVerified(manga, space, verify = {
				val actual = previewRename(manga, space, approved.container, approved.file, approved.chapterId, approved.chapterUri)
				check(actual == approved) { "Selected chapter changed; refresh Details" }
			}) {
				check(scope.appliesTo(manga, space)) { "Normal download context changed" }
				destinations.withDestinationMutationLock {
					check(approved.generation == destinations.normalScannerRootChanges.value &&
						approved.containerIdentity == identity(approved.container, directory = true) &&
						approved.fileIdentity == identity(approved.file, directory = false)
					) { "Selected chapter changed; refresh Details" }
					// Reuse the existing synchronous standalone format/collision guard and move.
					renameStandaloneChapterFile(approved.container, approved.file, name)
				}
			}
		}
	}

	private fun identity(file: File, directory: Boolean): NormalPathIdentity {
		if (file != file.canonicalFile) throw IOException("Selected path changed")
		val stat = Os.lstat(file.path)
		if (if (directory) !OsConstants.S_ISDIR(stat.st_mode) else !OsConstants.S_ISREG(stat.st_mode)) {
			throw IOException("Selected file type changed")
		}
		return NormalPathIdentity(stat.st_dev, stat.st_ino, stat.st_size, file.lastModified(), stat.st_ctime)
	}
}
