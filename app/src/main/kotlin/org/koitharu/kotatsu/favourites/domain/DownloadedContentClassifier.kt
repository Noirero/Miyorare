package org.koitharu.kotatsu.favourites.domain

import android.database.DatabaseUtils.sqlEscapeString
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
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.download.domain.DownloadDestinationStore
import org.koitharu.kotatsu.favourites.data.FavouriteDownloadIndexEntity
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.local.data.LocalMangaRepository
import org.koitharu.kotatsu.local.data.LocalStorageManager
import org.koitharu.kotatsu.local.data.findSavedMangaInRoot
import org.koitharu.kotatsu.local.data.index.LocalMangaIndex
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
	private val mangaDataRepository: MangaDataRepository,
	private val storageManager: LocalStorageManager,
	private val downloadDestinationStore: DownloadDestinationStore,
	private val localMangaIndex: LocalMangaIndex,
	private val localMangaRepositoryProvider: Provider<LocalMangaRepository>,
) {
	/**
	 * Negative artifact lookups are cached briefly so repeated filter recompositions do not hit the
	 * filesystem. Positive artifact discoveries are persisted to favourite_download_index instead of
	 * being cached in memory, so deleting an indexed artifact cannot leave a stale positive forever.
	 */
	private val artifactMissCache = ConcurrentHashMap<ArtifactCacheKey, Long>()
	private val reconcileInFlight = ConcurrentHashMap<ArtifactCacheKey, Boolean>()
	// The full Downloaded shelf converts legacy-root local_index rows into durable, space-scoped
	// ownership once per root configuration. Subsequent refreshes only need the active root plus
	// exact ownership rows; legacy roots remain available to the bounded reconciliation fallback.
	private val indexedRootMigrationSignatures = ConcurrentHashMap<Int, String>()
	private val reconcileScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(EXACT_LOOKUP_PARALLELISM))

	/**
	 * Return only downloads physically owned by [space]. Persisted ownership rows include CBZ/PDF/EPUB
	 * artifacts discovered by background reconciliation, while local_index contributes ordinary app
	 * downloads already rooted inside the same FavouriteSpace destination.
	 */
	suspend fun getDownloadedIds(space: FavouriteSpace): Set<Long> {
		val ownershipDao = db.getFavouriteDownloadIndexDao()
		val result = ownershipDao.findEntries(space.dbValue)
			.mapTo(HashSet()) { it.mangaId }

		val discoveryRoots = getDownloadRoots(space)
		val rootSignature = discoveryRoots
			.map { it.canonicalOrAbsolute().trimEnd(File.separatorChar) }
			.distinct()
			.sorted()
			.joinToString(separator = "\u0000")
		val rootsToQuery = if (indexedRootMigrationSignatures[space.dbValue] == rootSignature) {
			getActiveDownloadRoots(space)
		} else {
			discoveryRoots
		}
		val indexed = findIndexedEntriesInRoots(rootsToQuery)
		val discoveredOwnership = indexed
			.asSequence()
			.filter { it.mangaId !in result }
			.map { it.toOwnership(space) }
			.toList()
		if (discoveredOwnership.isNotEmpty()) {
			ownershipDao.upsert(discoveredOwnership)
		}
		indexed.mapTo(result) { it.mangaId }
		// Mark only after the DB lookup/upsert succeeds. A root change produces a different signature
		// and automatically runs the migration pass again.
		indexedRootMigrationSignatures[space.dbValue] = rootSignature
		return result
	}

	/**
	 * Fast batch lookup used by ordinary Normal/Private favourites cards and header counts.
	 *
	 * Persisted ownership is always scoped by [space]. Missing ownership rows are lazily bootstrapped
	 * only from local_index entries physically inside that space's download roots. A global local_index
	 * row therefore never makes the other FavouriteSpace look downloaded. Legacy alias/file probing is
	 * deliberately excluded from this interactive path and handled by background reconciliation.
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

		val discovered = ArrayList<FavouriteDownloadIndexEntity>()
		val downloadRoots = getDownloadRoots(space)
		if (downloadRoots.isNotEmpty()) {
			for (chunk in (ids - result).chunked(INDEX_QUERY_CHUNK_SIZE)) {
				val entries = db.getLocalMangaIndexDao().findEntries(chunk).filterToDownloadRoots(downloadRoots)
				for (entry in entries) {
					result += entry.mangaId
					discovered += entry.toOwnership(space)
				}
			}
		}

		if (discovered.isNotEmpty()) ownershipDao.upsert(discovered)
		return result
	}

	/**
	 * Fast, filesystem-free snapshot used by interactive counts. It intentionally has the same
	 * Normal/Private ownership semantics as [getDownloadedIdsExact]; the exact resolver may then
	 * reconcile still-unknown CBZ/PDF/EPUB folders asynchronously and persist discoveries for the next
	 * database invalidation.
	 */
	suspend fun getKnownDownloadedIds(space: FavouriteSpace, mangaIds: Collection<Long>): Set<Long> =
		getDownloadedIds(space, mangaIds)

	/**
	 * Non-blocking exact resolver for ordinary favourites.
	 *
	 * Known app downloads are returned immediately from the space-scoped database indexes. For the
	 * bounded unresolved candidate set, matching folders are verified in the background for CBZ/PDF/EPUB
	 * artifacts. Discoveries are persisted into favourite_download_index so list and count observers
	 * converge on one database-backed result without rescanning the whole storage tree on each tap.
	 */
	suspend fun getDownloadedIdsExact(space: FavouriteSpace, manga: Collection<Manga>): Set<Long> {
		if (manga.isEmpty()) return emptySet()
		val unique = manga.distinctBy { it.id }
		val result = getKnownDownloadedIds(space, unique.map { it.id }).toHashSet()
		val unresolved = ArrayList<Manga>()
		val now = System.nanoTime()
		for (item in unique) {
			if (item.id in result) continue
			val key = ArtifactCacheKey(space.dbValue, item.id)
			val missAt = artifactMissCache[key]
			if (missAt != null && now - missAt < NEGATIVE_ARTIFACT_CACHE_TTL_NANOS) continue
			if (missAt != null) artifactMissCache.remove(key, missAt)
			unresolved += item
		}
		if (unresolved.isNotEmpty()) scheduleArtifactReconciliation(space, unresolved)
		return result
	}

	private fun scheduleArtifactReconciliation(space: FavouriteSpace, manga: Collection<Manga>) {
		val now = System.nanoTime()
		val pending = manga.filter { item ->
			val key = ArtifactCacheKey(space.dbValue, item.id)
			val missAt = artifactMissCache[key]
			if (missAt != null && now - missAt < NEGATIVE_ARTIFACT_CACHE_TTL_NANOS) {
				false
			} else {
				if (missAt != null) artifactMissCache.remove(key, missAt)
				reconcileInFlight.putIfAbsent(key, true) == null
			}
		}
		if (pending.isEmpty()) return
		reconcileScope.launch {
			try {
				val roots = downloadDestinationStore.readableRoots(space)
					.filter { it.isDirectory && it.canRead() }
					.distinctBy { it.canonicalOrAbsolute() }
				if (roots.isEmpty()) {
					val checkedAt = System.nanoTime()
					pending.forEach { item ->
						artifactMissCache[ArtifactCacheKey(space.dbValue, item.id)] = checkedAt
					}
					return@launch
				}
				val repository = localMangaRepositoryProvider.get()
				val aliasPaths = localMangaIndex.getDownloadAliasPaths(pending.map { it.id })
				for (batch in pending.chunked(EXACT_LOOKUP_BATCH_SIZE)) {
					val resolved = coroutineScope {
						batch.map { item ->
							async {
								var localPath = aliasPaths[item.id]?.let { aliasPath ->
									val file = File(aliasPath)
									file.takeIf { it.isInsideAny(roots) && it.hasDownloadArtifact() }
										?.canonicalOrAbsolute()
								}
								if (localPath == null) {
									// Deterministic output discovery needs the remote identity/title, not a
									// materialized chapter snapshot. Keep the common reconciliation path cheap.
									for (root in roots) {
										val local = repository.findSavedMangaInRoot(item, root, withDetails = false) ?: continue
										if (!local.file.hasDownloadArtifact()) continue
										localPath = local.file.canonicalOrAbsolute()
										break
									}
								}
								if (localPath == null) {
									// Only the conservative legacy same-title bridge needs chapter evidence.
									// First prove that a persisted Local title candidate exists in this space;
									// otherwise loading a 1k-3k chapter Room snapshot is guaranteed wasted work.
									val hasIndexedTitleCandidate = sequenceOf(item.title)
										.plus(item.altTitles.asSequence())
										.map { it.trim() }
										.filter { it.isNotEmpty() }
										.distinct()
										.any { title ->
											localMangaIndex.findByTitle(title).any { local -> local.file.isInsideAny(roots) }
										}
									if (hasIndexedTitleCandidate) {
										val identitySeed = if (item.chapters.isNullOrEmpty()) {
											mangaDataRepository.findMangaById(item.id, withChapters = true) ?: item
										} else {
											item
										}
										if (!identitySeed.chapters.isNullOrEmpty()) {
											val local = repository.findSavedMangaIndexedByTitle(identitySeed, roots)
											if (local?.file?.hasDownloadArtifact() == true) {
												localPath = local.file.canonicalOrAbsolute()
											}
										}
									}
								}
								item to localPath
							}
						}.awaitAll()
					}
					val discovered = ArrayList<FavouriteDownloadIndexEntity>()
					val checkedAt = System.nanoTime()
					for ((item, path) in resolved) {
						val key = ArtifactCacheKey(space.dbValue, item.id)
						if (path == null) {
							artifactMissCache[key] = checkedAt
						} else {
							artifactMissCache.remove(key)
							discovered += FavouriteDownloadIndexEntity(
								mangaId = item.id,
								space = space.dbValue,
								path = path,
							)
						}
					}
					if (discovered.isNotEmpty()) db.getFavouriteDownloadIndexDao().upsert(discovered)
				}
			} finally {
				pending.forEach { item ->
					reconcileInFlight.remove(ArtifactCacheKey(space.dbValue, item.id))
				}
			}
		}
	}

	fun clearArtifactStatusCache() {
		artifactMissCache.clear()
	}

	/**
	 * Space-scoped definite-download predicate used only as a coarse rejection for Not Downloaded.
	 * Unknown CBZ/PDF/EPUB-only folders intentionally do not match here; the bounded exact resolver gets
	 * the opportunity to inspect them instead of SQL irreversibly excluding them.
	 */
	fun getDownloadedCondition(space: FavouriteSpace, mangaIdColumn: String): String {
		val rootPaths = getDownloadRoots(space)
			.map { it.canonicalOrAbsolute().trimEnd(File.separatorChar) }
			.distinct()
		val localCondition = if (rootPaths.isEmpty()) {
			"0"
		} else {
			val pathCondition = rootPaths.joinToString(separator = " OR ") { rootPath ->
				val root = sqlEscapeString(rootPath)
				val childPrefix = sqlEscapeString(rootPath + File.separator)
				"(local_index.path = $root OR instr(local_index.path, $childPrefix) = 1)"
			}
			"EXISTS(SELECT 1 FROM local_index WHERE local_index.manga_id = $mangaIdColumn AND ($pathCondition))"
		}
		return "($localCondition OR EXISTS(SELECT 1 FROM favourite_download_index fdi " +
			"WHERE fdi.space = ${space.dbValue} AND fdi.manga_id = $mangaIdColumn))"
	}

	/**
	 * Downloaded SQL must remain a superset because a valid CBZ/PDF/EPUB may not have an index row yet.
	 * Returning TRUE keeps the database window cheap and lets the bounded exact classifier decide.
	 */
	@Suppress("UNUSED_PARAMETER")
	fun getAnyDownloadedCondition(mangaIdColumn: String): String = "1"

	/**
	 * LOCAL-source helper retained for content-type classification. It intentionally spans all
	 * configured roots; callers first scope Downloaded entries to their FavouriteSpace.
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

	private fun File.hasDownloadArtifact(): Boolean {
		if (isFile) return isDownloadArtifact()
		if (!isDirectory) return false
		return listFiles()?.any { child -> child.isFile && child.isDownloadArtifact() } == true
	}

	private fun File.isDownloadArtifact(): Boolean =
		extension.equals("cbz", ignoreCase = true) ||
			extension.equals("pdf", ignoreCase = true) ||
			extension.equals("epub", ignoreCase = true)

	private fun File.isInsideAny(roots: Collection<File>): Boolean {
		val path = canonicalOrAbsolute()
		return roots.any { root ->
			val rootPath = root.canonicalOrAbsolute().trimEnd(File.separatorChar)
			path == rootPath || path.startsWith(rootPath + File.separator)
		}
	}

	private fun getActiveDownloadRoots(space: FavouriteSpace): List<File> =
		downloadDestinationStore.effectiveRoot(space)
			?.let { listOf(File(it, LocalMangaOutput.DOWNLOADS_DIR_NAME)) }
			.orEmpty()

	private fun getDownloadRoots(space: FavouriteSpace): List<File> =
		downloadDestinationStore.readableRoots(space).map {
			File(it, LocalMangaOutput.DOWNLOADS_DIR_NAME)
		}

	private suspend fun findIndexedEntriesInRoots(downloadRoots: List<File>): List<LocalMangaIndexEntity> {
		if (downloadRoots.isEmpty()) return emptyList()
		val dao = db.getLocalMangaIndexDao()
		val result = LinkedHashMap<Long, LocalMangaIndexEntity>()
		for (rootPath in downloadRoots.map { it.canonicalOrAbsolute().trimEnd(File.separatorChar) }.distinct()) {
			for (entry in dao.findEntriesUnderRoot(rootPath, rootPath + File.separator)) {
				result.putIfAbsent(entry.mangaId, entry)
			}
		}
		return result.values.toList()
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
		const val NEGATIVE_ARTIFACT_CACHE_TTL_NANOS = 15_000_000_000L
	}
}
