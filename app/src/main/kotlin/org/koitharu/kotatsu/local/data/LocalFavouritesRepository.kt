package org.koitharu.kotatsu.local.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koitharu.kotatsu.core.util.AlphanumComparator
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.local.data.input.LocalMangaParser
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Backing store for the system "Lokal" favourites category.
 *
 * The category is intentionally virtual: nothing is written to the favourites database. Its items
 * are reconstructed from `<configured manga root>/local/<title>/` and therefore disappear naturally
 * when their files are removed. Folder-name matching is case-insensitive so local/Local/LOCAL are
 * treated the same.
 *
 * EPUB is deliberately excluded for now. A title folder is eligible when it contains at least one
 * direct CBZ/ZIP/PDF chapter and no direct EPUB file, keeping this category manga-only while also
 * allowing local PDF chapters supported by LocalMangaParser.
 */
@Singleton
class LocalFavouritesRepository @Inject constructor(
	private val storageManager: LocalStorageManager,
) {

	private val mutex = Mutex()
	private val _items = MutableStateFlow<List<Manga>>(emptyList())
	@Volatile
	private var isInitialized = false

	val items: StateFlow<List<Manga>> = _items.asStateFlow()

	suspend fun ensureInitialized() {
		if (isInitialized) return
		mutex.withLock {
			if (!isInitialized) refreshLocked()
		}
	}

	suspend fun refresh() = mutex.withLock {
		refreshLocked()
	}

	private suspend fun refreshLocked() {
		val roots = storageManager.getReadableDirs()
		val mangaFolders = runInterruptible(Dispatchers.IO) {
			findMangaFolders(roots).sortedWith(compareBy(AlphanumComparator()) { it.name })
		}
		if (mangaFolders.isEmpty()) {
			_items.value = emptyList()
			isInitialized = true
			return
		}

		val parsed = ArrayList<Manga>(mangaFolders.size)
		val publishProgressively = _items.value.isEmpty()
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
					publish(parsed)
				}
			}
			results.close()
		}
		publish(parsed)
		isInitialized = true
	}

	private fun publish(items: List<Manga>) {
		_items.value = items
			.distinctBy { it.url }
			.sortedWith(compareBy(AlphanumComparator()) { it.title })
	}

	private fun findMangaFolders(roots: List<File>): List<File> {
		val result = LinkedHashMap<String, File>()
		for (root in roots) {
			val localRoots = ArrayList<File>()
			if (root.isDirectory && root.name.equals(LOCAL_FOLDER_NAME, ignoreCase = true)) {
				localRoots += root
			}
			root.listFiles()?.filterTo(localRoots) {
				it.isDirectory && it.name.equals(LOCAL_FOLDER_NAME, ignoreCase = true)
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
		const val LOCAL_PARSE_PARALLELISM = 4
		const val LOCAL_PUBLISH_BATCH_SIZE = 8
	}
}
