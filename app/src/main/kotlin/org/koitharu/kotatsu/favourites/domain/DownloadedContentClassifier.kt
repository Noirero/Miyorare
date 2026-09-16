package org.koitharu.kotatsu.favourites.domain

import android.database.DatabaseUtils.sqlEscapeString
import dagger.Reusable
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.model.isNovelContentPath
import org.koitharu.kotatsu.download.domain.DownloadDestinationStore
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.local.data.LocalStorageManager
import org.koitharu.kotatsu.local.data.index.LocalMangaIndexEntity
import org.koitharu.kotatsu.local.data.output.LocalMangaOutput
import java.io.File
import javax.inject.Inject

/** Lightweight classifier for items that belong to the virtual Downloaded shelf. */
@Reusable
class DownloadedContentClassifier @Inject constructor(
	private val db: MangaDatabase,
	private val storageManager: LocalStorageManager,
	private val downloadDestinationStore: DownloadDestinationStore,
) {
	/**
	 * Return only downloads physically owned by [space]. Normal and Private may both be present in
	 * local_index, but dedicated destinations must never be merged into one virtual Downloaded shelf.
	 * Legacy roots remain associated with the space that previously owned them.
	 */
	suspend fun getDownloadedIds(space: FavouriteSpace): Set<Long> {
		val downloadRoots = getDownloadRoots(space)
		return db.getLocalMangaIndexDao().findAllEntries()
			.filterToDownloadRoots(downloadRoots)
			.mapTo(HashSet()) { it.mangaId }
	}

	/**
	 * Batch lookup used by ordinary Normal/Private favourites lists.
	 *
	 * Only the requested ids are read from Room, so a visible page never performs one database query
	 * per card and never walks the whole filesystem. The path check also keeps Normal and Private
	 * destinations isolated from each other. The virtual Downloaded shelf deliberately keeps using
	 * [getDownloadedIds] because it has its own list pipeline.
	 */
	suspend fun getDownloadedIds(space: FavouriteSpace, mangaIds: Collection<Long>): Set<Long> {
		if (mangaIds.isEmpty()) return emptySet()
		val downloadRoots = getDownloadRoots(space)
		if (downloadRoots.isEmpty()) return emptySet()
		val ids = mangaIds.toSet()
		val result = HashSet<Long>(minOf(ids.size, 256))
		for (chunk in ids.chunked(INDEX_QUERY_CHUNK_SIZE)) {
			db.getLocalMangaIndexDao().findEntries(chunk)
				.filterToDownloadRoots(downloadRoots)
				.mapTo(result) { it.mangaId }
		}
		return result
	}

	/**
	 * SQL predicate for ordinary favourites queries. Keeping the path scope in SQL means a very large
	 * library can still use LIMIT/pagination efficiently: "Belum diunduh" never has to materialize
	 * thousands of rejected favourites in the UI just to discover that they are already downloaded.
	 */
	fun getDownloadedCondition(space: FavouriteSpace, mangaIdColumn: String): String {
		val rootPaths = getDownloadRoots(space)
			.map { it.canonicalOrAbsolute().trimEnd(File.separatorChar) }
			.distinct()
		if (rootPaths.isEmpty()) return "0"
		val pathCondition = rootPaths.joinToString(separator = " OR ") { rootPath ->
			val root = sqlEscapeString(rootPath)
			val childPrefix = sqlEscapeString(rootPath + File.separator)
			"(local_index.path = $root OR instr(local_index.path, $childPrefix) = 1)"
		}
		return "EXISTS(SELECT 1 FROM local_index WHERE local_index.manga_id = $mangaIdColumn AND ($pathCondition))"
	}

	/**
	 * LOCAL-source helper retained for content-type classification. It intentionally spans all
	 * configured roots; callers first scope Downloaded entries to their FavouriteSpace.
	 */
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
			if (entry.path.isNovelContentPath()) {
				result += entry.mangaId
			}
		}
		return result
	}

	private fun getDownloadRoots(space: FavouriteSpace): List<File> =
		downloadDestinationStore.readableRoots(space).map {
			File(it, LocalMangaOutput.DOWNLOADS_DIR_NAME)
		}

	private fun List<LocalMangaIndexEntity>.filterToDownloadRoots(downloadRoots: List<File>): List<LocalMangaIndexEntity> {
		if (isEmpty() || downloadRoots.isEmpty()) return emptyList()
		val rootPaths = downloadRoots.map { it.canonicalOrAbsolute().trimEnd(File.separatorChar) }
		return filter { entry ->
			val path = File(entry.path).canonicalOrAbsolute()
			rootPaths.any { rootPath ->
				path == rootPath || path.startsWith(rootPath + File.separator)
			}
		}
	}

	private fun File.canonicalOrAbsolute(): String = runCatching { canonicalPath }.getOrDefault(absolutePath)

	private companion object {
		const val INDEX_QUERY_CHUNK_SIZE = 500
	}
}
