package org.koitharu.kotatsu.favourites.domain

import android.database.DatabaseUtils.sqlEscapeString
import dagger.Reusable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.model.isNovelContentPath
import org.koitharu.kotatsu.download.domain.DownloadDestinationStore
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.local.data.LocalMangaRepository
import org.koitharu.kotatsu.local.data.LocalStorageManager
import org.koitharu.kotatsu.local.data.findSavedMangaInRoot
import org.koitharu.kotatsu.local.data.index.LocalMangaIndexEntity
import org.koitharu.kotatsu.local.data.output.LocalMangaOutput
import org.koitharu.kotatsu.parsers.model.Manga
import java.io.File
import javax.inject.Inject
import javax.inject.Provider

/** Lightweight classifier for items that belong to the virtual Downloaded shelf. */
@Reusable
class DownloadedContentClassifier @Inject constructor(
	private val db: MangaDatabase,
	private val storageManager: LocalStorageManager,
	private val downloadDestinationStore: DownloadDestinationStore,
	private val localMangaRepositoryProvider: Provider<LocalMangaRepository>,
) {
	/**
	 * Return only downloads physically owned by [space]. Normal and Private may both be present in
	 * local_index, but dedicated destinations must never be merged into one virtual Downloaded shelf.
	 * Legacy roots remain associated with the space that previously owned them.
	 *
	 * This method backs the virtual Downloaded shelf and intentionally retains its existing pipeline.
	 */
	suspend fun getDownloadedIds(space: FavouriteSpace): Set<Long> {
		val downloadRoots = getDownloadRoots(space)
		return db.getLocalMangaIndexDao().findAllEntries()
			.filterToDownloadRoots(downloadRoots)
			.mapTo(HashSet()) { it.mangaId }
	}

	/**
	 * Fast batch lookup used by ordinary Normal/Private favourites cards.
	 *
	 * Only the requested ids are read from Room, so a visible page never performs one database query
	 * per card and never walks the whole filesystem.
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
	 * Exact, bounded resolver for ordinary favourites.
	 *
	 * The global LocalMangaIndex stores one preferred path per manga id, while Miyorare permits the
	 * same remote manga to have a Normal and a Private copy simultaneously. Start with the batched DB
	 * result, then probe only unresolved requested manga directly inside this space's destination roots.
	 * This is not a filesystem scan: no directory tree is enumerated, and at most the current bounded
	 * query/page candidates are checked. Parallelism stays deliberately small to protect slower SD cards.
	 */
	suspend fun getDownloadedIdsExact(space: FavouriteSpace, manga: Collection<Manga>): Set<Long> {
		if (manga.isEmpty()) return emptySet()
		val unique = manga.distinctBy { it.id }
		val result = getDownloadedIds(space, unique.map { it.id }).toMutableSet()
		if (result.size == unique.size) return result
		val roots = downloadDestinationStore.readableRoots(space)
		if (roots.isEmpty()) return result
		val unresolved = unique.filterNot { it.id in result }
		val repository = localMangaRepositoryProvider.get()
		val dispatcher = Dispatchers.IO.limitedParallelism(EXACT_LOOKUP_PARALLELISM)
		coroutineScope {
			unresolved.map { item ->
				async(dispatcher) {
					val found = roots.any { root ->
						repository.findSavedMangaInRoot(item, root, withDetails = false) != null
					}
					item.id.takeIf { found }
				}
			}.awaitAll().filterNotNullTo(result)
		}
		return result
	}

	/**
	 * Path-scoped SQL predicate for ordinary favourites queries. It is an efficient coarse condition:
	 * exact dual-copy verification is performed only for the bounded result window in the ViewModel.
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

	/** Any indexed copy, regardless of which space owns its currently preferred global path. */
	fun getAnyDownloadedCondition(mangaIdColumn: String): String =
		"EXISTS(SELECT 1 FROM local_index WHERE local_index.manga_id = $mangaIdColumn)"

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
		const val EXACT_LOOKUP_PARALLELISM = 4
	}
}
