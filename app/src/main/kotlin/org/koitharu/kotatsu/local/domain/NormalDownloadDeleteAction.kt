package org.koitharu.kotatsu.local.domain

import android.system.Os
import android.system.OsConstants
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.download.domain.DownloadDestinationStore
import org.koitharu.kotatsu.download.domain.NormalDownloadMutationFence
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.favourites.domain.NormalDownloadObservationStore
import org.koitharu.kotatsu.favourites.domain.NormalDownloadScope
import org.koitharu.kotatsu.favourites.domain.NormalObservationContext
import org.koitharu.kotatsu.local.data.LocalMangaRepository
import org.koitharu.kotatsu.local.data.MangaIndex
import org.koitharu.kotatsu.local.data.output.LocalMangaOutput
import org.koitharu.kotatsu.local.data.output.LocalMangaZipOutput
import org.koitharu.kotatsu.parsers.model.Manga
import java.io.File
import java.io.IOException
import java.io.Serializable
import javax.inject.Inject

/** Ephemeral confirmation snapshot. Neither a read projection nor this snapshot authorizes unlink. */
internal data class NormalDeleteApproval(
	val mangaId: Long,
	val source: String,
	val url: String,
	val space: FavouriteSpace?,
	val generation: Long,
	val context: NormalObservationContext,
	val container: File,
	val containerStamp: NormalDeleteStamp,
	val selected: Map<Long, String>?,
	val victims: Map<File, NormalDeleteStamp>,
	val removeContainer: Boolean,
	val embeddedIds: Set<Long>,
) : Serializable

internal data class NormalDeleteStamp(
	val device: Long, val inode: Long, val size: Long, val modified: Long, val changed: Long,
	val directory: Boolean,
) : Serializable

/** Selected chapter, whole-container and bulk actions use the same current Normal proof. */
class NormalDownloadDeleteAction @Inject internal constructor(
	private val scope: NormalDownloadScope,
	private val observations: NormalDownloadObservationStore,
	private val destinations: DownloadDestinationStore,
	private val fence: NormalDownloadMutationFence,
	private val mangaLock: MangaLock,
	private val local: LocalMangaRepository,
) {
	internal suspend fun appliesTo(manga: Manga, space: FavouriteSpace?) = scope.appliesTo(manga, space)

	/** Null is confirmed absence for an unbound whole-title selection only. */
	internal suspend fun preview(
		manga: Manga, space: FavouriteSpace?, container: File?, selected: Map<Long, String>? = null,
	): NormalDeleteApproval? = withContext(Dispatchers.IO) {
		check(scope.appliesTo(manga, space)) { "Normal download context changed" }
		if (container != null) return@withContext capture(manga, space, container, selected)
		check(selected == null) { "Selected chapter locator is missing" }
		val result = observations.resolveForRead(manga, space) ?: throw IOException("Download destination unavailable")
		check(result.inventoryComplete && result.unresolvedPaths.isEmpty() && result.verified.size <= 1) {
			"Cannot verify the selected Normal download"
		}
		val physical = result.uniqueCandidate ?: return@withContext null
		capture(manga, space, physical.file, null)
	}

	private suspend fun capture(
		manga: Manga, space: FavouriteSpace?, container: File, selected: Map<Long, String>?,
	): NormalDeleteApproval {
		val context = observations.actionContext(manga, space) ?: throw IOException("Normal download context changed")
		val roots = scope.resolveActiveLookup(manga, space) ?: throw IOException("Normal download context changed")
		check(container == container.canonicalFile && scope.isWithinActiveRoot(container, roots.normalRoot, roots.privateRoot)) {
			"Selected download is outside the active destination"
		}
		val parent = stamp(container)
		val listing = if (parent.directory) checkNotNull(container.listFiles()).toSet() else emptySet()
		// Capture regular files before discovery, not after a same-name replacement.
		val before = listing.filter { it.isFile && it == it.canonicalFile }.associateWith(::stamp)
		val result = observations.resolveForRead(manga, space) ?: throw IOException("Download destination unavailable")
		val physical = result.uniqueCandidate ?: throw IOException("Cannot verify the selected Normal download")
		check(physical.file == container) { "Selected download changed; refresh Details" }
		val projection = if (parent.directory) {
			result.directoryIdentity?.projection ?: throw IOException("Chapter identity is unresolved")
		} else physical
		check(projection.file == container)
		val chapters = projection.manga.chapters.orEmpty()
		check(chapters.isNotEmpty() && chapters.map { it.id }.toSet().size == chapters.size)
		check(selected == null || selected.isNotEmpty())
		val victims = linkedMapOf<File, NormalDeleteStamp>()
		var removeContainer: Boolean
		var embeddedIds: Set<Long> = emptySet()
		if (parent.directory) {
			val files = chapters.associate { chapter ->
				chapter.id to (chapterFile(container, chapter.url) ?: throw IOException("Unsupported chapter locator"))
			}
			check(files.values.toSet().size == files.size && before.keys.containsAll(files.values))
			val requested = if (selected == null) files.values.toSet() else selected.map { (id, uri) ->
				val file = files[id] ?: throw IOException("Selected chapter changed; refresh Details")
				check(chapterFile(container, uri) == file) { "Selected chapter locator changed" }
				file
			}.toSet()
			check(selected == null || requested.size == selected.size)
			for (file in requested) victims[file] = before.getValue(file)
			// Only proven chapter files and exact validated sidecar/cover are eligible for
			// container removal. Never recursively remove arbitrary siblings, hidden files or dirs.
			val allowed = files.values.toMutableSet()
			val sidecar = File(container, LocalMangaOutput.ENTRY_NAME_INDEX)
			if (sidecar in listing) {
				val index = MangaIndex.read(sidecar) ?: throw IOException("Cannot verify download metadata")
				allowed += sidecar
				index.getCoverEntry()?.let { name ->
					val cover = File(container, name)
					if (cover.parentFile == container && cover in before) allowed += cover
				}
			}
			removeContainer = requested == files.values.toSet() && listing == allowed && before.keys.containsAll(allowed)
			if (selected == null) check(removeContainer) { "Unverified content remains in the selected folder; files were kept" }
			if (removeContainer) for (file in allowed - requested) victims[file] = before.getValue(file)
		} else {
			check(container.extension.equals("cbz", true) || container.extension.equals("zip", true))
			val logical = result.migratedArchiveIdentity?.selectorsByRemoteId ?: chapters.associateBy { it.id }
			embeddedIds = if (selected == null) chapters.mapTo(linkedSetOf()) { it.id } else selected.map { (id, uri) ->
				val chapter = logical[id] ?: throw IOException("Selected embedded chapter changed")
				check(archiveFile(uri) == container && archiveFile(chapter.url) == container) {
					"Selected archive locator changed"
				}
				chapter.id
			}.toSet()
			check(selected == null || embeddedIds.size == selected.size)
			removeContainer = embeddedIds == chapters.mapTo(hashSetOf()) { it.id }
			if (removeContainer) victims[container] = parent
		}
		val approval = NormalDeleteApproval(manga.id, manga.source.name, manga.url, space, roots.generation, context,
			container, parent, selected?.toMap(), victims, removeContainer, embeddedIds)
		check(observations.actionContext(manga, space) == context) { "Download context changed" }
		destinations.withDestinationMutationLock { verifyPhysical(approval) }
		return approval
	}

	internal suspend fun execute(manga: Manga, space: FavouriteSpace?, approved: NormalDeleteApproval): Boolean =
		mangaLock.withLock(manga) {
			local.exhentaiRemovalMutex.withLock {
				withContext(Dispatchers.IO) {
					fence.mutateVerified(manga, space, verify = {
						check(capture(manga, space, approved.container, approved.selected) == approved) { "Selected download changed; files were kept" }
					}) {
						val removed = linkedSetOf<String>()
						var staging: File? = null
						try {
							if (!approved.containerStamp.directory && !approved.removeContainer) {
								// Reuse existing archive filtering but never overwrite the worker's .tmp.
								val temp = File.createTempFile(".normal-delete-", ".tmp", approved.container.parentFile)
								staging = temp
								LocalMangaZipOutput.stageVerifiedChapterFilter(approved.container, temp, approved.embeddedIds)
							}
							check(observations.actionContext(manga, space) == approved.context) { "Normal download context changed" }
							destinations.withDestinationMutationLock {
								verifyPhysical(approved)
								val temp = staging
								if (temp != null) {
									// Same-directory atomic replacement; a failed rename keeps the original.
									// Do not copy over a live selected archive while the root lock is held.
									Os.rename(temp.path, approved.container.path)
								} else {
									for ((file, expected) in approved.victims) {
										check(stamp(file) == expected) { "Selected file changed; files were kept" }
										Os.unlink(file.path)
										removed += file.path
									}
									if (approved.containerStamp.directory && approved.removeContainer) {
										// File-only unlink above; rmdir cannot remove an unapproved new child.
										Os.rmdir(approved.container.path)
										removed += approved.container.path
									}
								}
							}
							true
						} finally {
							withContext(NonCancellable) {
								staging?.delete()
								if (removed.isNotEmpty()) local.publishNormalDeletion(manga.id, removed)
							}
						}
					}
				}
			}
		}

	private fun verifyPhysical(approval: NormalDeleteApproval) {
		check(approval.generation == destinations.normalScannerRootChanges.value &&
			stamp(approval.container) == approval.containerStamp &&
			approval.victims.all { (file, expected) -> stamp(file) == expected }) {
			"Selected download changed; files were kept"
		}
		if (!approval.containerStamp.directory) {
			check(!File(approval.container.path + LocalMangaOutput.SUFFIX_TMP).exists() &&
				!File(approval.container.path + ".bak" + LocalMangaOutput.SUFFIX_TMP).exists()) {
				"Download is changing; files were kept"
			}
		} else {
			if (approval.removeContainer) check(approval.container.listFiles()?.toSet() == approval.victims.keys) {
				"Unverified content remains in the selected folder; files were kept"
			}
			for (file in approval.victims.keys) {
				check(!File(file.path + LocalMangaOutput.SUFFIX_TMP).exists() &&
					!File(file.path + ".bak" + LocalMangaOutput.SUFFIX_TMP).exists()) {
					"Download is changing; files were kept"
				}
			}
		}
	}

	private fun stamp(file: File): NormalDeleteStamp {
		check(file == file.canonicalFile) { "Selected path changed" }
		val stat = Os.lstat(file.path)
		val dir = OsConstants.S_ISDIR(stat.st_mode)
		check(dir || OsConstants.S_ISREG(stat.st_mode)) { "Unsupported selected file type" }
		return NormalDeleteStamp(stat.st_dev, stat.st_ino, stat.st_size, file.lastModified(), stat.st_ctime, dir)
	}

	private fun archiveFile(url: String): File? {
		val uri = url.toUri()
		if (uri.scheme != "file" || !uri.authority.isNullOrEmpty() || uri.query != null || uri.fragment != null) return null
		return uri.path?.let(::File)
	}

	private fun chapterFile(root: File, url: String): File? {
		val uri = url.toUri()
		if (uri.scheme != "file" || !uri.authority.isNullOrEmpty() || uri.query != null) return null
		val base = uri.path?.let(::File) ?: return null
		val fragment = uri.fragment
		val file = if (fragment == null) base else {
			if (base != root || fragment != File(fragment).name || fragment.isBlank()) return null
			File(root, fragment)
		}
		return file.takeIf { it.parentFile == root && (it.extension.equals("cbz", true) || it.extension.equals("zip", true)) }
	}
}
