package org.koitharu.kotatsu.favourites.domain

import dagger.Reusable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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
import java.util.concurrent.ConcurrentHashMap
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
	private val artifactStatusCache = ConcurrentHashMap<ArtifactCacheKey, Boolean>()
	private val reconcileInFlight = ConcurrentHashMap<ArtifactCacheKey, Boolean>()
	private val reconcileScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(EXACT_LOOKUP_PARALLELISM))

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
	 * Fast device-wide lookup used by interactive filtering and header counts.
	 *
	 * This deliberately does no filesystem traversal: changing Downloaded/Not downloaded must update
	 * the UI immediately. Sidecar-free CBZ/PDF folders are reconciled asynchronously by
	 * [getDownloadedIdsExact] and persisted into favourite_download_index for subsequent queries.
	 */
	suspend fun getKnownDownloadedIds(mangaIds: Collection<Long>): Set<Long> {
		if (mangaIds.isEmpty()) return emptySet()
		val ids = mangaIds.toSet()
		val result = HashSet<Long>(minOf(ids.size, 256))
		val ownershipDao = db.getFavouriteDownloadIndexDao()
		val localIndexDao = db.getLocalMangaIndexDao()
		for (chunk in ids.chunked(INDEX_QUERY_CHUNK_SIZE)) {
			ownershipDao.findEntries(chunk).mapTo(result) { it.mangaId }
			localIndexDao.findEntries(chunk).mapTo(result) { it.mangaId }
		}
		return result
	}

	/**
	 * Non-blocking exact resolver for ordinary favourites.
	 *
	 * Known database/index hits are returned immediately. For unresolved items we schedule bounded
	 * background verification against their matching download folder. If that folder contains a
	 * CBZ/PDF-backed local manga, the result is persisted into favourite_download_index. This keeps
	 * filter toggles responsive while still repairing sidecar-free or not-yet-indexed downloads.
	 */
	suspend fun getDownloadedIdsExact(space: FavouriteSpace, manga: Collection<Manga>): Set<Long> {
		if (manga.isEmpty()) return emptySet()
		val unique = manga.distinctBy { it.id }
		val result = getKnownDownloadedIds(unique.map { it.id }).toHashSet()
		val unresolved = ArrayList<Manga>()
		for (item in unique) {
			if (item.id in result) continue
			val key = ArtifactCacheKey(space.dbValue, item.id)
			when (artifactStatusCache[key]) {
				true -> result += item.id
				false -> Unit
				null -> unresolved += item
			}
		}
		if (unresolved.isNotEmpty()) scheduleArtifactReconciliation(space, unresolved)
		return result
	}

	private fun scheduleArtifactReconciliation(space: FavouriteSpace, manga: Collection<Manga>) {
		val pending = manga.filter { item ->
			val key = ArtifactCacheKey(space.dbValue, item.id)
			artifactStatusCache[key] == null && reconcileInFlight.putIfAbsent(key, true) == null
		}
		if (pending.isEmpty()) return
		reconcileScope.launch {
			val roots = downloadDestinationStore.readableRoots(space)
			if (roots.isEmpty()) {
				pending.forEach { item ->
					val key = ArtifactCacheKey(space.dbValue, item.id)
					artifactStatusCache[key] = false
					reconcileInFlight.remove(key)
				}
				return@launch
			}
			val repository = localMangaRepositoryProvider.get()
			for (batch in pending.chunked(EXACT_LOOKUP_BATCH_SIZE)) {
				val resolved = coroutineScope {
					batch.map { item ->
						async {
							var localPath: String? = null
							for (root in roots) {
								val local = repository.findSavedMangaInRoot(item, root, withDetails = false) ?: continue
								if (!local.file.hasCbzOrPdfArtifact()) continue
								localPath = local.file.canonicalOrAbsolute()
								break
							}
							item to localPath
						}
					}.awaitAll()
				}
				val discovered = ArrayList<FavouriteDownloadIndexEntity>()
				for ((item, path) in resolved) {
					val key = ArtifactCacheKey(space.dbValue, item.id)
					val found = path != null
					artifactStatusCache[key] = found
					reconcileInFlight.remove(key)
					if (path != null) {
						discovered += FavouriteDownloadIndexEntity(
							mangaId = item.id,
							space = space.dbValue,
							path = path,
						)
					}
				}
				if (discovered.isNotEmpty()) db.getFavouriteDownloadIndexDao().upsert(discovered)
			}
		}
	}

	fun clearArtifactStatusCache() {
		artifactStatusCache.clear()
	}

	/**
	 * Device-wide SQL predicate used by interactive Favourites download-status filtering.
	 * Both positive and inverted states use the same predicate so the result sets are complements.
	 */
	@Suppress("UNUSED_PARAMETER")
	fun getDownloadedCondition(space: FavouriteSpace, mangaIdColumn: String): String =
		getAnyDownloadedCondition(mangaIdColumn)

	/** Any known downloaded copy, regardless of which configured download destination owns it. */
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

	private fun File.hasCbzOrPdfArtifact(): Boolean {
		if (isFile) return isCbzOrPdf()
		if (!isDirectory) return false
		return listFiles()?.any { child -> child.isFile && child.isCbzOrPdf() } == true
	}

	private fun File.isCbzOrPdf(): Boolean =
		extension.equals("cbz", ignoreCase = true) || extension.equals("pdf", ignoreCase = true)

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

	private data class ArtifactCacheKey(
		val space: Int,
		val mangaId: Long,
	)

	private companion object {
		const val INDEX_QUERY_CHUNK_SIZE = 500
		const val EXACT_LOOKUP_PARALLELISM = 4
		const val EXACT_LOOKUP_BATCH_SIZE = 64
	}
}
