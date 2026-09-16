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
import org.koitharu.kotatsu.favourites.data.FavouriteDownloadIndexEntity
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
	 * Persisted space ownership wins. Missing ownership rows are lazily bootstrapped from the existing
	 * global local_index in bounded chunks, without scanning storage.
	 */
	suspend fun getDownloadedIds(space: FavouriteSpace, mangaIds: Collection<Long>): Set<Long> {
		if (mangaIds.isEmpty()) return emptySet()
		val ids = mangaIds.toSet()
		val ownershipDao = db.getFavouriteDownloadIndexDao()
		val result = HashSet<Long>(minOf(ids.size, 256))
		for (chunk in ids.chunked(INDEX_QUERY_CHUNK_SIZE)) {
			ownershipDao.findEntries(space.dbValue, chunk).mapTo(result) { it.mangaId }
		}
		if (result.size == ids.size) return result

		val downloadRoots = getDownloadRoots(space)
		if (downloadRoots.isEmpty()) return result
		val discovered = ArrayList<FavouriteDownloadIndexEntity>()
		for (chunk in (ids - result).chunked(INDEX_QUERY_CHUNK_SIZE)) {
			val entries = db.getLocalMangaIndexDao().findEntries(chunk).filterToDownloadRoots(downloadRoots)
			for (entry in entries) {
				result += entry.mangaId
				discovered += entry.toOwnership(space)
			}
		}
		if (discovered.isNotEmpty()) ownershipDao.upsert(discovered)
		return result
	}

	/**
	 * Exact, bounded resolver for ordinary favourites.
	 *
	 * The dedicated ownership index preserves Normal and Private independently. Existing installs are
	 * lazily seeded from local_index. If a manga is known to exist somewhere but the preferred global
	 * path points at another space, only that ambiguous manga is probed directly inside the requested
	 * destination. Completely unknown ids do not cause filesystem I/O. No directory tree is enumerated.
	 */
	suspend fun getDownloadedIdsExact(space: FavouriteSpace, manga: Collection<Manga>): Set<Long> {
		if (manga.isEmpty()) return emptySet()
		val unique = manga.distinctBy { it.id }
		val ids = unique.mapTo(LinkedHashSet(unique.size)) { it.id }
		val ownershipDao = db.getFavouriteDownloadIndexDao()
		val result = HashSet<Long>(minOf(unique.size, 256))
		val knownIds = HashSet<Long>()

		for (chunk in ids.chunked(INDEX_QUERY_CHUNK_SIZE)) {
			val ownershipEntries = ownershipDao.findEntries(chunk)
			ownershipEntries.mapTo(knownIds) { it.mangaId }
			ownershipEntries.filter { it.space == space.dbValue }.mapTo(result) { it.mangaId }
		}
		if (result.size == ids.size) return result

		val downloadRoots = getDownloadRoots(space)
		val discovered = ArrayList<FavouriteDownloadIndexEntity>()
		for (chunk in (ids - result).chunked(INDEX_QUERY_CHUNK_SIZE)) {
			val entries = db.getLocalMangaIndexDao().findEntries(chunk)
			entries.mapTo(knownIds) { it.mangaId }
			for (entry in entries.filterToDownloadRoots(downloadRoots)) {
				if (result.add(entry.mangaId)) discovered += entry.toOwnership(space)
			}
		}
		if (discovered.isNotEmpty()) ownershipDao.upsert(discovered)
		if (result.size == ids.size) return result

		val ambiguous = unique.filter { item -> item.id in knownIds && item.id !in result }
		if (ambiguous.isEmpty()) return result
		val roots = downloadDestinationStore.readableRoots(space)
		if (roots.isEmpty()) return result
		val repository = localMangaRepositoryProvider.get()
		val dispatcher = Dispatchers.IO.limitedParallelism(EXACT_LOOKUP_PARALLELISM)
		val exactDiscovered = ArrayList<FavouriteDownloadIndexEntity>()
		for (batch in ambiguous.chunked(EXACT_LOOKUP_BATCH_SIZE)) {
			val resolved = coroutineScope {
				batch.map { item ->
					async(dispatcher) {
						for (root in roots) {
							val local = repository.findSavedMangaInRoot(item, root, withDetails = false) ?: continue
							return@async FavouriteDownloadIndexEntity(
								mangaId = item.id,
								space = space.dbValue,
								path = local.file.canonicalOrAbsolute(),
							)
						}
						null
					}
				}.awaitAll().filterNotNull()
			}
			for (entry in resolved) {
				result += entry.mangaId
				exactDiscovered += entry
			}
		}
		if (exactDiscovered.isNotEmpty()) ownershipDao.upsert(exactDiscovered)
		return result
	}

	/**
	 * Device-wide SQL predicate used by the ordinary favourites download-status filter.
	 *
	 * The user-facing choices mean "downloaded somewhere on this device" and "not downloaded on this
	 * device". Use the same global predicate for both positive and inverted filtering so a downloaded
	 * title can never leak into the Not downloaded result just because its indexed path belongs to a
	 * different configured destination. Space-specific ownership is still preserved by the dedicated
	 * Downloaded shelf and exact lookup methods above.
	 */
	@Suppress("UNUSED_PARAMETER")
	fun getDownloadedCondition(space: FavouriteSpace, mangaIdColumn: String): String =
		getAnyDownloadedCondition(mangaIdColumn)

	/** Any known downloaded copy, regardless of which space owns its path. */
	fun getAnyDownloadedCondition(mangaIdColumn: String): String =
		"(EXISTS(SELECT 1 FROM local_index WHERE local_index.manga_id = $mangaIdColumn) OR " +
			"EXISTS(SELECT 1 FROM favourite_download_index fdi WHERE fdi.manga_id = $mangaIdColumn))"

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

	private fun LocalMangaIndexEntity.toOwnership(space: FavouriteSpace) = FavouriteDownloadIndexEntity(
		mangaId = mangaId,
		space = space.dbValue,
		path = File(path).canonicalOrAbsolute(),
	)

	private fun File.canonicalOrAbsolute(): String = runCatching { canonicalPath }.getOrDefault(absolutePath)

	private companion object {
		const val INDEX_QUERY_CHUNK_SIZE = 500
		const val EXACT_LOOKUP_PARALLELISM = 4
		const val EXACT_LOOKUP_BATCH_SIZE = 64
	}
}
