package org.koitharu.kotatsu.local.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koitharu.kotatsu.core.model.isLocal
import org.koitharu.kotatsu.core.model.isNovelContent
import org.koitharu.kotatsu.core.util.AlphanumComparator
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.download.domain.DownloadDestinationStore
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.favourites.domain.FavouritesRepository
import org.koitharu.kotatsu.local.data.input.LocalMangaParser
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Backing store for the system "Lokal" favourites category.
 *
 * When the active Normal or Private destination contains a direct `local`/`lokal` folder, that
 * folder is authoritative for that space. Normal therefore cannot accidentally show a Private
 * destination's Local files, and changing a destination changes the virtual Lokal shelf without
 * moving or deleting any files.
 *
 * For backward compatibility, Normal keeps the legacy configured-root scan only when its active
 * destination has no Local folder. Private keeps the previous membership-based Local projection in
 * that same no-folder case. EPUB remains excluded from the filesystem-backed manga shelf.
 */
@Singleton
class LocalFavouritesRepository @Inject constructor(
	private val storageManager: LocalStorageManager,
	private val favouritesRepository: FavouritesRepository,
	private val downloadDestinationStore: DownloadDestinationStore,
) {

	private val mutex = Mutex()
	private val rawItems = FavouriteSpace.entries.associateWith { MutableStateFlow<List<Manga>>(emptyList()) }
	private val initializedSpaces = HashSet<FavouriteSpace>()

	fun items(space: FavouriteSpace): Flow<List<Manga>> {
		val raw = rawItems.getValue(space)
		if (space == FavouriteSpace.PRIVATE) return raw.distinctUntilChanged()
		return combine(
			raw,
			favouritesRepository.observeFavouritesChanges(FavouriteSpace.PRIVATE),
			favouritesRepository.observeFavouritesChanges(FavouriteSpace.NORMAL),
		) { localManga, _, _ ->
			if (localManga.isEmpty()) return@combine localManga
			val privateIds = favouritesRepository.getMemberships(FavouriteSpace.PRIVATE)
				.mapTo(HashSet()) { it.mangaId }
			if (privateIds.isEmpty()) return@combine localManga
			val normalIds = favouritesRepository.getMemberships(FavouriteSpace.NORMAL)
				.mapTo(HashSet()) { it.mangaId }
			localManga.filterNot { manga -> manga.id in privateIds && manga.id !in normalIds }
		}.distinctUntilChanged()
	}

	suspend fun ensureInitialized(space: FavouriteSpace) = mutex.withLock {
		if (space !in initializedSpaces) refreshLocked(space)
	}

	suspend fun refresh(space: FavouriteSpace) = mutex.withLock {
		refreshLocked(space)
	}

	private suspend fun refreshLocked(space: FavouriteSpace) {
		val destinationLocalRoots = downloadDestinationStore.localRoots(space)
		if (destinationLocalRoots.isEmpty() && space == FavouriteSpace.PRIVATE) {
			// Preserve the old Private Local shelf until the user creates/chooses a destination
			// containing local/lokal. Once such a folder exists, the filesystem becomes authoritative.
			val fallback = favouritesRepository.getAllManga(FavouriteSpace.PRIVATE)
				.filter { manga -> manga.source.isLocal && !manga.isNovelContent }
			publish(space, fallback)
			initializedSpaces += space
			return
		}

		val scanRoots = if (destinationLocalRoots.isNotEmpty()) {
			destinationLocalRoots
		} else {
			// Legacy Normal behaviour for users who keep Local manga in separately configured roots.
			storageManager.getReadableDirs()
		}
		val mangaFolders = runInterruptible(Dispatchers.IO) {
			findMangaFolders(scanRoots).sortedWith(compareBy(AlphanumComparator()) { it.name })
		}
		if (mangaFolders.isEmpty()) {
			publish(space, emptyList())
			initializedSpaces += space
			return
		}

		val parsed = ArrayList<Manga>(mangaFolders.size)
		val publishProgressively = rawItems.getValue(space).value.isEmpty()
		val dispatcher = Dispatchers.IO.limitedParallelism(LOCAL_PARSE_PARALLELISM)
		coroutineScope {
			val results = Channel<Manga?>(Channel.UNLIMITED)
			for (folder in mangaFolders) {
				launch(dispatcher) {
					val manga = runCatchingCancellable {
						LocalMangaParser.getOrNull(folder)?.getManga(withDetails = false)?.manga
					}.onFailure {
						it.printStackTraceDebug()
					}.getOrNull()
					results.send(manga)
				}
			}
			repeat(mangaFolders.size) {
				results.receive()?.let(parsed::add)
				if (
					publishProgressively && parsed.isNotEmpty() &&
					(parsed.size == 1 || parsed.size % LOCAL_PUBLISH_BATCH_SIZE == 0)
				) {
					publish(space, parsed)
				}
			}
			results.close()
		}
		publish(space, parsed)
		initializedSpaces += space
	}

	private fun publish(space: FavouriteSpace, items: List<Manga>) {
		rawItems.getValue(space).value = items
			.distinctBy { it.url }
			.sortedWith(compareBy(AlphanumComparator()) { it.title })
	}

	private fun findMangaFolders(roots: List<File>): List<File> {
		val result = LinkedHashMap<String, File>()
		for (root in roots) {
			val localRoots = ArrayList<File>()
			if (root.isDirectory && root.name.isLocalFolderName()) {
				localRoots += root
			}
			root.listFiles()?.filterTo(localRoots) {
				it.isDirectory && it.name.isLocalFolderName()
			}

			for (localRoot in localRoots) {
				localRoot.listFiles()?.forEach { mangaFolder ->
					if (
						mangaFolder.isDirectory &&
						!mangaFolder.isHidden &&
						mangaFolder.hasSupportedMangaChapters()
					) {
						result.putIfAbsent(mangaFolder.absolutePath, mangaFolder)
					}
				}
			}
		}
		return result.values.toList()
	}

	private fun String.isLocalFolderName(): Boolean =
		equals(LOCAL_FOLDER_NAME, ignoreCase = true) || equals(LOCAL_FOLDER_NAME_ID, ignoreCase = true)

	private fun File.hasSupportedMangaChapters(): Boolean {
		var hasSupportedChapter = false
		for (file in listFiles().orEmpty()) {
			if (!file.isFile) continue
			when {
				file.extension.equals("epub", ignoreCase = true) -> return false
				file.extension.equals("cbz", ignoreCase = true) ||
					file.extension.equals("zip", ignoreCase = true) ||
					file.extension.equals("pdf", ignoreCase = true) -> hasSupportedChapter = true
			}
		}
		return hasSupportedChapter
	}

	private companion object {
		const val LOCAL_FOLDER_NAME = "local"
		const val LOCAL_FOLDER_NAME_ID = "lokal"
		const val LOCAL_PARSE_PARALLELISM = 4
		const val LOCAL_PUBLISH_BATCH_SIZE = 8
	}
}
