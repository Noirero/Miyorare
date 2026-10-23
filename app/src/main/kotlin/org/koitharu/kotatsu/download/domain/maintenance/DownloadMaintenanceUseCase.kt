package org.koitharu.kotatsu.download.domain.maintenance

import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.toManga
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.core.model.isLocal
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.download.domain.DownloadDestinationStore
import org.koitharu.kotatsu.favourites.data.FavouriteDownloadIndexEntity
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.favourites.domain.FavouritesRepository
import org.koitharu.kotatsu.local.data.LocalDownloadScanner
import org.koitharu.kotatsu.local.data.LocalMangaRepository
import org.koitharu.kotatsu.local.data.LocalStorageChanges
import org.koitharu.kotatsu.local.data.LocalStorageManager
import org.koitharu.kotatsu.local.data.MangaIndex
import org.koitharu.kotatsu.local.data.index.LocalMangaIndex
import org.koitharu.kotatsu.local.data.input.LocalMangaParser
import org.koitharu.kotatsu.local.data.output.LocalMangaOutput
import org.koitharu.kotatsu.local.domain.MangaLock
import org.koitharu.kotatsu.local.domain.model.LocalManga
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.sources.compat.StoredSourceIdentity
import org.koitharu.kotatsu.sources.compat.VerifiedSourceAliases
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Explicit maintenance boundary. No filesystem scans are added to list rendering or badge paths. */
@Singleton
class DownloadMaintenanceUseCase @Inject constructor(
	private val storage: LocalStorageManager,
	private val destinations: DownloadDestinationStore,
	private val database: MangaDatabase,
	private val data: MangaDataRepository,
	private val repositories: MangaRepository.Factory,
	private val favourites: FavouritesRepository,
	private val localIndex: LocalMangaIndex,
	private val localRepository: LocalMangaRepository,
	private val mangaLock: MangaLock,
	@LocalStorageChanges private val changes: MutableSharedFlow<LocalManga?>,
) {
	private val mutex = Mutex()
	private val mutableState = MutableStateFlow(DownloadMaintenanceState())
	val state = mutableState.asStateFlow()

	/** Concurrent callers share the same observable run; cancellation leaves atomic commits intact. */
	suspend fun run() {
		if (!mutex.tryLock()) return
		val items = ArrayList<DownloadMaintenanceItem>()
		mutableState.value = DownloadMaintenanceState(running = true)
		try {
			withContext(Dispatchers.IO) {
				phase(DownloadMaintenancePhase.SCANNING)
				val rootsBySpace = FavouriteSpace.entries.associateWith { destinations.readableRoots(it) }
				val roots = (storage.getConfiguredDirs() + rootsBySpace.values.flatten()).distinctBy { it.canonicalPath }
				val files = LocalDownloadScanner.scan(roots) { root ->
					items += DownloadMaintenanceItem(root.path, DownloadMaintenanceStatus.UNAVAILABLE_STORAGE, DownloadMaintenanceReason.STORAGE_UNAVAILABLE,
						isPrivate = rootsBySpace[ FavouriteSpace.PRIVATE ].orEmpty().any { it.canonicalPath == root.canonicalPath })
				}
				mutableState.value = mutableState.value.copy(total = files.size)
				phase(DownloadMaintenancePhase.IDENTIFYING)
				val memberships = FavouriteSpace.entries.associateWith { space -> favourites.getAllManga(space).mapTo(HashSet()) { it.id } }
				val candidates = database.getMangaDao().findDownloadMaintenanceCandidates().map { it.toManga() }
				val byId = candidates.associateBy { it.id }
				val knownPaths = HashMap<String, MutableSet<Long>>()
				fun remember(id: Long, path: String) { knownPaths.getOrPut(File(path).canonicalPath) { LinkedHashSet() }.add(id) }
				localIndex.getDownloadAliasPaths(byId.keys).forEach { (id, path) -> remember(id, path) }
				database.getLocalMangaIndexDao().findAllEntries().forEach { if (it.mangaId in byId) remember(it.mangaId, it.path) }
				val ownershipRows = FavouriteSpace.entries.flatMap { database.getFavouriteDownloadIndexDao().findEntries(it.dbValue) }
				ownershipRows.forEach { remember(it.mangaId, it.path) }
				// These are canonical path candidates, never source identity inferred from a similar name.
				val expected = HashMap<String, MutableList<Manga>>()
				val directoryCache = HashMap<String, List<File>>()
				for (manga in candidates) {
					currentCoroutineContext().ensureActive()
					for (root in roots) for (file in LocalMangaOutput.maintenanceCandidates(root, manga, directoryCache)) {
						expected.getOrPut(file.canonicalPath) { ArrayList() }.add(manga)
					}
				}
				val duplicateIds = expected.flatMap { (path, mangas) -> mangas.map { it.id to path } }
					.groupBy({ it.first }, { it.second }).filterValues { it.distinct().size > 1 }.keys
				for ((position, file) in files.withIndex()) {
					currentCoroutineContext().ensureActive()
					phase(DownloadMaintenancePhase.VERIFYING)
					val path = file.canonicalPath
					val pathIds = expected[path].orEmpty().map { it.id } + knownPaths[path].orEmpty()
					val pathOwners = ownershipRows.filter { File(it.path).canonicalPath == path }.map { it.space }.toSet()
					val provenNormal = FavouriteSpace.NORMAL.dbValue in pathOwners || pathIds.any {
						it in memberships.getValue(FavouriteSpace.NORMAL) && it !in memberships.getValue(FavouriteSpace.PRIVATE)
					}
					val isPrivate = FavouriteSpace.PRIVATE.dbValue in pathOwners || pathIds.any {
						it in memberships.getValue(FavouriteSpace.PRIVATE) && it !in memberships.getValue(FavouriteSpace.NORMAL)
					} || (!provenNormal && rootsBySpace.getValue(FavouriteSpace.PRIVATE).any { file.inside(it) })
					val result = try {
						verify(file, byId, knownPaths[path].orEmpty(), expected[path].orEmpty().distinctBy { it.id }, duplicateIds, memberships, rootsBySpace)
					} catch (cancelled: CancellationException) {
						throw cancelled
					} catch (error: Exception) {
						error.printStackTraceDebug()
						DownloadMaintenanceItem(path, DownloadMaintenanceStatus.FAILED, DownloadMaintenanceReason.IO_ERROR)
					}
					items += result.copy(isPrivate = isPrivate)
					mutableState.value = mutableState.value.copy(scanned = position + 1)
				}
				phase(DownloadMaintenancePhase.FINALIZING)
			}
		} catch (cancelled: CancellationException) {
			items += DownloadMaintenanceItem("", DownloadMaintenanceStatus.UNRESOLVED, DownloadMaintenanceReason.CANCELLED)
			throw cancelled
		} catch (error: Exception) {
			error.printStackTraceDebug()
			items += DownloadMaintenanceItem("", DownloadMaintenanceStatus.FAILED, DownloadMaintenanceReason.IO_ERROR)
		} finally {
			mutableState.value = mutableState.value.copy(running = false, phase = DownloadMaintenancePhase.COMPLETED, result = DownloadMaintenanceResult(items.toList()))
			mutex.unlock()
		}
	}

	private suspend fun verify(
		file: File,
		byId: Map<Long, Manga>,
		knownIds: Set<Long>,
		expected: List<Manga>,
		duplicateIds: Set<Long>,
		memberships: Map<FavouriteSpace, Set<Long>>,
		rootsBySpace: Map<FavouriteSpace, List<File>>,
	): DownloadMaintenanceItem {
		fun item(status: DownloadMaintenanceStatus, reason: DownloadMaintenanceReason, recognized: Int = 0, total: Int = 0) =
			DownloadMaintenanceItem(file.canonicalPath, status, reason, recognized, total)
		if (!file.canRead()) return item(DownloadMaintenanceStatus.UNAVAILABLE_STORAGE, DownloadMaintenanceReason.STORAGE_UNAVAILABLE)
		val initialSnapshot = if (file.isDirectory) snapshot(file) else emptyList()
		val parser = LocalMangaParser.getOrNull(file) ?: return item(DownloadMaintenanceStatus.NO_CHANGE_REQUIRED, DownloadMaintenanceReason.UNSUPPORTED_ARTIFACT)
		val indexFile = File(file, LocalMangaOutput.ENTRY_NAME_INDEX)
		if (file.isDirectory && indexFile.exists() && indexFile.canonicalFile.parentFile != file.canonicalFile) {
			return item(DownloadMaintenanceStatus.AMBIGUOUS, DownloadMaintenanceReason.IDENTITY_UNKNOWN)
		}
		val oldText = if (file.isDirectory && indexFile.isFile) indexFile.readText() else null
		val old = oldText?.let { runCatching { MangaIndex(it) }.getOrNull() }
		val storedInfo = old?.getMangaInfo() ?: runCatchingCancellable { parser.getMangaInfo() }.getOrNull()
			?: readEmbeddedNovelIdentity(file)
		val rawPhysical = LocalMangaParser(file, ignoreDirectoryIndex = true).getManga(withDetails = true)
		val physical = DownloadIndexRepair.restoreValidMappings(file, old, rawPhysical)
		if (physical.manga.chapters.isNullOrEmpty()) return item(DownloadMaintenanceStatus.UNRESOLVED, DownloadMaintenanceReason.UNSUPPORTED_ARTIFACT)
		val durable = knownIds.mapNotNull(byId::get).distinctBy { it.id }
		if (durable.size > 1) return item(DownloadMaintenanceStatus.AMBIGUOUS, DownloadMaintenanceReason.IDENTITY_UNKNOWN)
		var identity = durable.singleOrNull() ?: storedInfo?.takeUnless { it.isLocal } ?: byId[physical.manga.id]
		if (identity != null && storedInfo != null && !sameSource(identity.source.name, storedInfo.source.name)) {
			return item(DownloadMaintenanceStatus.AMBIGUOUS, DownloadMaintenanceReason.SOURCE_MISMATCH)
		}
		if (identity == null) {
			val matching = expected.filter { candidate ->
				val remote = data.findMangaById(candidate.id, withChapters = true) ?: candidate
				val linked = localRepository.linkDownloadedChapters(remote, physical)
				linked.manga.chapters.orEmpty().any { local -> remote.chapters.orEmpty().any { it.id == local.id } }
			}
			if (matching.size > 1) return item(DownloadMaintenanceStatus.AMBIGUOUS, DownloadMaintenanceReason.IDENTITY_UNKNOWN)
			identity = matching.singleOrNull()
		}
		if (identity == null) return item(
			if (expected.isEmpty() && oldText == null) DownloadMaintenanceStatus.NO_CHANGE_REQUIRED else DownloadMaintenanceStatus.UNRESOLVED,
			if (expected.isEmpty() && oldText == null) DownloadMaintenanceReason.LOCAL_CONTENT else if (oldText != null) DownloadMaintenanceReason.CORRUPT_INDEX else DownloadMaintenanceReason.IDENTITY_UNKNOWN,
		)
		val selected = identity
		if (selected.id in duplicateIds && knownIds.isEmpty() && storedInfo == null) return item(DownloadMaintenanceStatus.AMBIGUOUS, DownloadMaintenanceReason.DUPLICATE_CONTAINER)
		return mangaLock.withLock(selected) {
			if (file.isDirectory && initialSnapshot != snapshot(file)) return@withLock item(DownloadMaintenanceStatus.UNRESOLVED, DownloadMaintenanceReason.STORAGE_CHANGED)
			val cached = data.findMangaById(selected.id, withChapters = true)?.takeIf { sameSource(it.source.name, selected.source.name) } ?: selected
			val metadata = DownloadMaintenanceMetadata.resolve(cached, storedInfo) { repositories.create(it.source).getDetails(it) }
			val remote = metadata.manga
			if (metadata.fetched) data.storeManga(remote, replaceExisting = true, detailsFetched = true)
			val linked = localRepository.linkDownloadedChapters(remote, physical)
			val remoteIds = remote.chapters.orEmpty().mapTo(HashSet()) { it.id }
			val recognized = linked.manga.chapters.orEmpty().count { it.id in remoteIds }
			if (recognized == 0 && storedInfo == null) return@withLock item(DownloadMaintenanceStatus.UNRESOLVED, metadata.failure ?: DownloadMaintenanceReason.PARTIAL_MATCH, 0, physical.manga.chapters.orEmpty().size)
			phase(DownloadMaintenancePhase.REPAIRING)
			var changed = false
			if (oldText != null && file.isDirectory) {
				val plan = DownloadIndexRepair.plan(file, oldText, remote, physical, linked)
				if (!plan.complete) return@withLock item(DownloadMaintenanceStatus.UNRESOLVED,
					if (old?.getMangaInfo() == null) DownloadMaintenanceReason.CORRUPT_INDEX else DownloadMaintenanceReason.PARTIAL_MATCH, plan.recognized, plan.total)
				if (plan.content != null) {
					// Guard external writers/storage changes between verification and atomic publication.
					if (indexFile.readText() != oldText || initialSnapshot != snapshot(file)) return@withLock item(DownloadMaintenanceStatus.UNRESOLVED, DownloadMaintenanceReason.STORAGE_CHANGED)
					currentCoroutineContext().ensureActive()
					val commitContext = currentCoroutineContext()
					DownloadIndexRepair.write(indexFile, plan.content) { commitContext.ensureActive() }
					changed = true
				}
			}
			phase(DownloadMaintenancePhase.REFRESHING)
			// Never replace remote Room metadata with Local metadata carrying the same id.
			val published = if (changed && oldText != null) LocalMangaParser(file).getManga(withDetails = true) else physical
			val previousPath = database.getLocalMangaIndexDao().findPath(published.manga.id)
			if (previousPath == null || previousPath == file.canonicalPath) {
				if (published.manga.id == remote.id || published.manga.id in byId) {
					if (published.manga.id == remote.id) data.storeManga(remote, replaceExisting = false)
					localIndex.rememberVerifiedPath(published.manga.id, file)
				} else localIndex.put(published.copy(file = file.canonicalFile))
				changed = changed || previousPath != file.canonicalPath
			}
			if (remote.id !in duplicateIds) {
				val priorAlias = localIndex.getDownloadAliasPaths(listOf(remote.id))[remote.id]
				localRepository.rememberDownloadedIdentity(remote, published.copy(file = file.canonicalFile))
				changed = changed || (remote.id != published.manga.id && priorAlias != file.canonicalPath)
			}
			val ownership = repairOwnership(remote.id, file, memberships, rootsBySpace)
			changed = changed || ownership.first
			if (changed) changes.emit(linked.copy(file = file.canonicalFile))
			when {
				recognized < physical.manga.chapters.orEmpty().size && oldText == null -> item(DownloadMaintenanceStatus.UNRESOLVED, DownloadMaintenanceReason.PARTIAL_MATCH, recognized, physical.manga.chapters.orEmpty().size)
				!ownership.second -> item(DownloadMaintenanceStatus.UNRESOLVED, DownloadMaintenanceReason.OWNERSHIP_UNKNOWN, recognized, physical.manga.chapters.orEmpty().size)
				else -> item(if (changed) DownloadMaintenanceStatus.REPAIRED else DownloadMaintenanceStatus.HEALTHY, DownloadMaintenanceReason.VERIFIED, recognized, physical.manga.chapters.orEmpty().size)
			}
		}
	}

	/** Per-chapter EPUBs already carry remote identity; a cold/offline cache needs no provider call. */
	private suspend fun readEmbeddedNovelIdentity(file: File): Manga? {
		if (!file.isDirectory) return null
		val epubs = file.listFiles { child -> child.isFile && child.extension.equals("epub", ignoreCase = true) }.orEmpty()
		if (epubs.isEmpty()) return null
		val snapshots = epubs.map { epub -> runCatchingCancellable { LocalMangaParser(epub).getMangaInfo() }.getOrNull() ?: return null }
		val first = snapshots.first()
		if (first.isLocal || snapshots.any { it.id != first.id || !sameSource(it.source.name, first.source.name) }) return null
		return first.copy(chapters = snapshots.flatMap { it.chapters.orEmpty() }.distinctBy { it.id })
	}

	private suspend fun repairOwnership(id: Long, file: File, membership: Map<FavouriteSpace, Set<Long>>, roots: Map<FavouriteSpace, List<File>>): Pair<Boolean, Boolean> = database.withTransaction {
		val dao = database.getFavouriteDownloadIndexDao()
		val existing = dao.findEntries(listOf(id))
		val path = file.canonicalPath
		val matchingRoots = roots.flatMap { (space, values) -> values.filter { file.inside(it) }.map { space to it.canonicalPath.length } }
		val deepestRoot = matchingRoots.maxOfOrNull { it.second }
		val pathSpaces = matchingRoots.filter { it.second == deepestRoot }.mapTo(HashSet()) { it.first }
		val plan = DownloadMaintenanceOwnership.resolve(pathSpaces, membership.filterValues { id in it }.keys,
			existing.filter { File(it.path).canonicalPath == path }.mapTo(HashSet()) { FavouriteSpace.fromDb(it.space) })
		if (plan == null) return@withTransaction false to false
		var changed = false
		for (row in existing) if (File(row.path).canonicalPath == path && FavouriteSpace.fromDb(row.space) !in plan) {
			dao.delete(row.space, id)
			changed = true
		}
		for (space in plan) {
			val previous = existing.firstOrNull { it.space == space.dbValue }
			// A second physical copy must not displace another owned (possibly unmounted) download.
			if (previous != null && File(previous.path).canonicalPath != path) continue
			if (previous?.path != path) { dao.upsert(FavouriteDownloadIndexEntity(id, space.dbValue, path)); changed = true }
		}
		changed to plan.isNotEmpty()
	}

	private fun phase(phase: DownloadMaintenancePhase) { mutableState.value = mutableState.value.copy(phase = phase) }
	private fun snapshot(file: File): List<Triple<String, Long, Long>> = file.listFiles()?.map { Triple(it.name, it.length(), it.lastModified()) }?.sortedBy { it.first } ?: throw AccessDeniedException(file)

	internal companion object {
		fun sameSource(a: String, b: String): Boolean = a == b ||
			VerifiedSourceAliases.canonicalize(StoredSourceIdentity.direct(a)).canonicalId == VerifiedSourceAliases.canonicalize(StoredSourceIdentity.direct(b)).canonicalId
	}
}

internal object DownloadMaintenanceOwnership {
	fun resolve(pathSpaces: Set<FavouriteSpace>, membership: Set<FavouriteSpace>, existing: Set<FavouriteSpace>): Set<FavouriteSpace>? {
		if (pathSpaces.size == 1) return pathSpaces
		if (pathSpaces.isEmpty()) return existing.takeIf { it.isNotEmpty() }
		if (existing.size == 1) return existing
		return membership.takeIf { it.size == 1 }
	}
}

private fun File.inside(root: File): Boolean = canonicalPath == root.canonicalPath || canonicalPath.startsWith(root.canonicalPath.trimEnd(File.separatorChar) + File.separator)
