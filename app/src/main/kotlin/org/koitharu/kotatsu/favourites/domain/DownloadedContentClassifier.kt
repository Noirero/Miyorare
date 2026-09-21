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

/**
 * Index-backed classifier for the virtual Downloaded shelf.
 *
 * Interactive library code must stay filesystem-free. DownloadWorker, LocalMangaIndex and
 * FavouriteDownloadOwnershipIndex maintain the durable indexes when storage changes; list rendering
 * only reads those indexes. This mirrors Mihon's download-cache principle: filesystem discovery is
 * maintenance work, never work triggered by scrolling a new card into view.
 */
@Reusable
class DownloadedContentClassifier @Inject constructor(
	private val db: MangaDatabase,
	private val storageManager: LocalStorageManager,
	private val downloadDestinationStore: DownloadDestinationStore,
) {

	/**
	 * Return downloads physically owned by [space] from persisted indexes only.
	 *
	 * favourite_download_index carries explicit Normal/Private ownership for app downloads. local_index
	 * is also accepted when its path lives under one of the space's readable roots, which keeps an
	 * already-built local index useful after a destination move without starting any storage scan.
	 */
	suspend fun getDownloadedIds(space: FavouriteSpace): Set<Long> {
		val allowedRoots = getDownloadRoots(space)
		if (allowedRoots.isEmpty()) return emptySet()

		val result = db.getFavouriteDownloadIndexDao()
			.findEntries(space.dbValue)
			.asSequence()
			.filter { File(it.path).isInsideAny(allowedRoots) }
			.mapTo(HashSet()) { it.mangaId }

		findIndexedEntriesInRoots(allowedRoots).mapTo(result) { it.mangaId }
		return result
	}

	/**
	 * Bounded batch lookup for library cards, filters and counts.
	 *
	 * There is deliberately no alias probing, title matching, chapter materialization or filesystem
	 * reconciliation here. A missing index row is simply unknown/not-downloaded until normal storage
	 * maintenance records it.
	 */
	suspend fun getDownloadedIds(space: FavouriteSpace, mangaIds: Collection<Long>): Set<Long> {
		if (mangaIds.isEmpty()) return emptySet()
		val allowedRoots = getDownloadRoots(space)
		if (allowedRoots.isEmpty()) return emptySet()

		val ids = mangaIds.toSet()
		val ownershipDao = db.getFavouriteDownloadIndexDao()
		val result = HashSet<Long>(minOf(ids.size, 256))

		for (chunk in ids.chunked(INDEX_QUERY_CHUNK_SIZE)) {
			ownershipDao.findEntries(space.dbValue, chunk)
				.asSequence()
				.filter { File(it.path).isInsideAny(allowedRoots) }
				.mapTo(result) { it.mangaId }
		}
		if (result.size == ids.size) return result

		val localDao = db.getLocalMangaIndexDao()
		for (chunk in (ids - result).chunked(INDEX_QUERY_CHUNK_SIZE)) {
			localDao.findEntries(chunk)
				.filterToDownloadRoots(allowedRoots)
				.mapTo(result) { it.mangaId }
		}
		return result
	}

	/** Fast alias used by interactive counts/cards; always index-only and filesystem-free. */
	suspend fun getKnownDownloadedIds(space: FavouriteSpace, mangaIds: Collection<Long>): Set<Long> =
		getDownloadedIds(space, mangaIds)

	/**
	 * Space-scoped persisted-download predicate used as a coarse SQL filter.
	 *
	 * Both local_index and favourite_download_index are durable state. Legacy storage probing is not
	 * part of this expression and is never started as a side effect of a library query.
	 */
	fun getDownloadedCondition(space: FavouriteSpace, mangaIdColumn: String): String {
		val rootPaths = getDownloadRoots(space)
			.map { it.normalizedAbsolutePath().trimEnd(File.separatorChar) }
			.distinct()

		val localCondition = buildSqlPathExists(
			table = "local_index",
			idColumn = "local_index.manga_id",
			pathColumn = "local_index.path",
			mangaIdColumn = mangaIdColumn,
			rootPaths = rootPaths,
		)
		val ownershipPathCondition = if (rootPaths.isEmpty()) {
			"0"
		} else {
			rootPaths.joinToString(separator = " OR ") { rootPath ->
				val root = sqlEscapeString(rootPath)
				val childPrefix = sqlEscapeString(rootPath + File.separator)
				"(fdi.path = $root OR instr(fdi.path, $childPrefix) = 1)"
			}
		}
		val ownershipCondition =
			"EXISTS(SELECT 1 FROM favourite_download_index fdi " +
				"WHERE fdi.space = ${space.dbValue} AND fdi.manga_id = $mangaIdColumn " +
				"AND ($ownershipPathCondition))"
		return "($localCondition OR $ownershipCondition)"
	}

	private fun buildSqlPathExists(
		table: String,
		idColumn: String,
		pathColumn: String,
		mangaIdColumn: String,
		rootPaths: List<String>,
	): String {
		if (rootPaths.isEmpty()) return "0"
		val pathCondition = rootPaths.joinToString(separator = " OR ") { rootPath ->
			val root = sqlEscapeString(rootPath)
			val childPrefix = sqlEscapeString(rootPath + File.separator)
			"($pathColumn = $root OR instr($pathColumn, $childPrefix) = 1)"
		}
		return "EXISTS(SELECT 1 FROM $table WHERE $idColumn = $mangaIdColumn AND ($pathCondition))"
	}

	/**
	 * Keep the database query broad for Downloaded-only pagination, then apply the exact persisted
	 * space-owned index snapshot to the bounded result window. This is still database-only.
	 */
	@Suppress("UNUSED_PARAMETER")
	fun getAnyDownloadedCondition(mangaIdColumn: String): String = "1"

	/**
	 * LOCAL-source helpers used for content-type classification. They read the persisted local index
	 * under configured roots and never scan the filesystem.
	 */
	suspend fun getLocalDownloadedIds(): Set<Long> {
		val downloadRoots = storageManager.getConfiguredDirs().map {
			File(it, LocalMangaOutput.DOWNLOADS_DIR_NAME)
		}
		return findIndexedEntriesInRoots(downloadRoots)
			.mapTo(HashSet()) { it.mangaId }
	}

	suspend fun getLocalNovelIds(): Set<Long> {
		val result = HashSet<Long>()
		val downloadRoots = storageManager.getConfiguredDirs().map {
			File(it, LocalMangaOutput.DOWNLOADS_DIR_NAME)
		}
		for (entry in findIndexedEntriesInRoots(downloadRoots)) {
			if (entry.path.isNovelContentPath()) {
				result += entry.mangaId
			}
		}
		return result
	}

	private fun File.isInsideAny(roots: Collection<File>): Boolean {
		val path = normalizedAbsolutePath()
		return roots.any { root ->
			val rootPath = root.normalizedAbsolutePath().trimEnd(File.separatorChar)
			path == rootPath || path.startsWith(rootPath + File.separator)
		}
	}

	private fun getDownloadRoots(space: FavouriteSpace): List<File> =
		downloadDestinationStore.readableRoots(space).map {
			File(it, LocalMangaOutput.DOWNLOADS_DIR_NAME)
		}

	private suspend fun findIndexedEntriesInRoots(downloadRoots: List<File>): List<LocalMangaIndexEntity> {
		if (downloadRoots.isEmpty()) return emptyList()
		val dao = db.getLocalMangaIndexDao()
		val result = LinkedHashMap<Long, LocalMangaIndexEntity>()
		for (rootPath in downloadRoots.map { it.normalizedAbsolutePath().trimEnd(File.separatorChar) }.distinct()) {
			for (entry in dao.findEntriesUnderRoot(rootPath, rootPath + File.separator)) {
				result.putIfAbsent(entry.mangaId, entry)
			}
		}
		return result.values.toList()
	}

	private fun List<LocalMangaIndexEntity>.filterToDownloadRoots(
		downloadRoots: List<File>,
	): List<LocalMangaIndexEntity> {
		if (isEmpty() || downloadRoots.isEmpty()) return emptyList()
		val rootPaths = downloadRoots.map { it.normalizedAbsolutePath().trimEnd(File.separatorChar) }
		return filter { entry ->
			val path = File(entry.path).normalizedAbsolutePath()
			rootPaths.any { rootPath ->
				path == rootPath || path.startsWith(rootPath + File.separator)
			}
		}
	}

	private fun File.normalizedAbsolutePath(): String =
		absoluteFile.path.trimEnd(File.separatorChar)

	private companion object {
		const val INDEX_QUERY_CHUNK_SIZE = 500
	}
}
