package org.koitharu.kotatsu.local.data.index

import android.content.Context
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.toManga
import org.koitharu.kotatsu.core.model.isLocal
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.core.util.ext.toFileOrNull
import org.koitharu.kotatsu.local.data.LocalMangaRepository
import org.koitharu.kotatsu.local.data.LocalStorageManager
import org.koitharu.kotatsu.local.data.input.LocalMangaParser
import org.koitharu.kotatsu.local.data.input.LocalPdfCache
import org.koitharu.kotatsu.local.domain.model.LocalManga
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class LocalMangaIndex @Inject constructor(
	private val mangaDataRepository: MangaDataRepository,
	private val db: MangaDatabase,
	@ApplicationContext context: Context,
	private val localMangaRepositoryProvider: Provider<LocalMangaRepository>,
	private val localStorageManager: LocalStorageManager,
) : FlowCollector<LocalManga?> {

	private val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
	private val mutex = Mutex()
	private val rebuildScheduled = AtomicBoolean(false)
	private val maintenanceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
	@Volatile
	private var cachedList: List<LocalManga>? = null

	private var currentVersion: Int
		get() = prefs.getInt(KEY_VERSION, 0)
		set(value) = prefs.edit { putInt(KEY_VERSION, value) }

	override suspend fun emit(value: LocalManga?) {
		if (value != null) {
			put(value)
		}
	}

	suspend fun update() = mutex.withLock {
		rebuildIndexLocked()
	}

	/**
	 * Rebuild a stale persisted index explicitly. Local UI calls this in background so an existing
	 * v2 index can be shown immediately while v3 discovers standalone PDFs.
	 *
	 * @return true when a rebuild was actually performed.
	 */
	suspend fun rebuildIfRequired(): Boolean {
		if (!isUpdateRequired()) return false
		return mutex.withLock {
			if (!isUpdateRequired()) {
				false
			} else {
				rebuildIndexLocked()
				true
			}
		}
	}

	suspend fun updateIfRequired() {
		if (!isUpdateRequired()) return
		// An older persisted index is still valid for the formats it already knows. Keep it readable
		// instead of blocking the first Local list behind a full filesystem rebuild. The Local hub
		// schedules [rebuildIfRequired] and reloads after the v3 swap completes.
		if (db.getLocalMangaIndexDao().findAllEntries().isNotEmpty()) return
		rebuildIfRequired()
	}

	private suspend fun rebuildIndexLocked() = withContext(Dispatchers.IO) {
		val configuredRoots = localStorageManager.getConfiguredDirs()
		val readableRoots = localStorageManager.getReadableDirs().toSet()
		val unavailableRoots = configuredRoots - readableRoots
		val dao = db.getLocalMangaIndexDao()

		// Read preserved entries before scanning. The old index remains intact while filesystem work is
		// running, so a slow SD/PDF scan no longer holds a Room transaction or exposes a half-built index.
		val preserved = if (unavailableRoots.isEmpty()) {
			emptyList()
		} else {
			dao.findAllEntries().filter { entry ->
				val file = File(entry.path)
				unavailableRoots.any { root -> file.isInside(root) }
			}
		}

		val scanned = LinkedHashMap<Long, LocalManga>()
		LocalPdfCache.withoutCoverRendering {
			localMangaRepositoryProvider.get()
				.getRawListAsFlow()
				.collect { manga ->
					// When a configured root is nested inside another configured root, the ancestor
					// scanner may surface the nested folder itself as a synthetic manga/container.
					// The nested root is scanned independently, so discard that container here.
					if (!manga.file.isConfiguredRootContainer(configuredRoots)) {
						scanned[manga.manga.id] = manga
					}
				}
		}
		val scannedIds = scanned.keys

		db.withTransaction {
			dao.clear()
			// A file may be explicitly deleted while a long scan is still running. Do not resurrect
			// an entry that the scanner saw before that deletion completed.
			scanned.values.asSequence().filter { it.file.exists() }.forEach { upsert(it) }
			// A readable copy always wins over a preserved path from unavailable storage. This prevents
			// an ejected SD-card entry from replacing a valid internal-storage copy with the same manga id.
			preserved.asSequence()
				.filterNot { it.mangaId in scannedIds }
				.forEach { dao.upsert(it) }
		}
		currentVersion = VERSION
		cachedList = null
		_rebuildEvents.tryEmit(Unit)
	}

	suspend fun get(mangaId: Long, withDetails: Boolean): LocalManga? {
		val dao = db.getLocalMangaIndexDao()
		var path = dao.findPath(mangaId)
		val alias = if (path == null) readDownloadAlias(mangaId) else null
		if (path == null) {
			path = alias?.path
		}
		if (path == null) {
			// Exact interactive lookups must never wait for a full filesystem rebuild. A stale/empty
			// index is repaired in the background; deterministic paths/aliases remain immediately usable.
			scheduleRebuildIfRequired()
			return null
		}
		val file = File(path)
		val parsed = runCatchingCancellable {
			LocalMangaParser(file).getManga(withDetails)
		}.onFailure {
			it.printStackTraceDebug()
		}.getOrNull()
		val result = when {
			parsed == null -> null
			alias == null -> parsed
			parsed.manga.id != alias.localMangaId -> null
			else -> parsed.copy(manga = parsed.manga.copy(id = mangaId))
		}
		if (result == null && file.isOnReadableRoot()) {
			// A parse failure on reachable storage means the exact row/alias is stale. Unavailable SD
			// roots are deliberately retained so temporarily ejected storage is never forgotten.
			mutex.withLock {
				if (alias != null) {
					if (readDownloadAlias(mangaId)?.path == path) {
						removeDownloadAlias(mangaId)
					}
				} else if (dao.findPath(mangaId) == path) {
					dao.delete(mangaId)
					cachedList = null
				}
			}
		}
		return result
	}


	/**
	 * Fast stale-while-revalidate snapshot for cold-start favourites. It reads Room only and never
	 * prunes, stats, parses, or rebuilds storage. The Local shelf can refresh it explicitly later.
	 */
	suspend fun getPersistedSnapshot(): List<LocalManga> =
		db.getLocalMangaIndexDao().findAllLocal().map { LocalManga(it.toManga()) }

	private fun scheduleRebuildIfRequired() {
		if (!isUpdateRequired() || !rebuildScheduled.compareAndSet(false, true)) return
		maintenanceScope.launch {
			try {
				// Match the old updateIfRequired contract: an older non-empty persisted index stays usable
				// until explicit Local maintenance refreshes it. Only the truly empty stale index needs repair.
				if (db.getLocalMangaIndexDao().findAllEntries().isEmpty()) {
					rebuildIfRequired()
				}
			} finally {
				rebuildScheduled.set(false)
			}
		}
	}

	/**
	 * Read title candidates from the persisted local index only. Besides the exact title, the DAO
	 * accepts the legacy "[group/author] Title" display form that older cached Local rows may retain.
	 * This deliberately does not rebuild or rescan storage; callers must still verify chapter evidence
	 * before treating a candidate as the same remote manga.
	 */
	suspend fun findByTitle(title: String): List<LocalManga> =
		db.getLocalMangaIndexDao().findAllByTitle(title).map { LocalManga(it.toManga()) }

	suspend fun getAll(): List<LocalManga> {
		// Pagination repeatedly asks for the same snapshot. Once loaded, stay entirely in memory;
		// filesystem pruning belongs only to cache misses/invalidation, never the paging hot path.
		cachedList?.let { return it }
		pruneMissingReadableEntries()
		if (isUpdateRequired()) {
			val stale = db.getLocalMangaIndexDao().findAll()
			if (stale.isNotEmpty()) {
				return stale.map { LocalManga(it.toManga()) }.also { cachedList = it }
			}
		}
		updateIfRequired()
		return mutex.withLock {
			cachedList ?: db.getLocalMangaIndexDao()
				.findAll()
				.map { LocalManga(it.toManga()) }
				.also { cachedList = it }
		}
	}

	/**
	 * Resolve only the explicitly requested downloaded containers. This is intentionally independent
	 * from [getAll]: a delete action must never trigger a full Local prune/rebuild or wait for a scan
	 * merely to discover that most selected favourites have no downloaded file.
	 *
	 * Provider/source aliases are resolved too, so a legacy/reconnected download is deleted through
	 * the same physical container without moving or renaming it first.
	 */
	suspend fun getDeleteTargets(mangaIds: Set<Long>): List<LocalManga> {
		if (mangaIds.isEmpty()) return emptyList()
		val dao = db.getLocalMangaIndexDao()
		val candidates = LinkedHashMap<String, Long>()
		for (chunk in mangaIds.chunked(INDEX_QUERY_CHUNK_SIZE)) {
			for (entry in dao.findEntries(chunk)) {
				candidates.putIfAbsent(entry.path, entry.mangaId)
			}
		}
		for (mangaId in mangaIds) {
			val alias = readDownloadAlias(mangaId) ?: continue
			candidates.putIfAbsent(alias.path, alias.localMangaId)
		}
		if (candidates.isEmpty()) return emptyList()

		val result = ArrayList<LocalManga>(candidates.size)
		for ((path, expectedLocalId) in candidates) {
			val file = File(path)
			if (!file.exists()) continue
			val local = runCatchingCancellable {
				LocalMangaParser(file).getManga(withDetails = false)
			}.onFailure {
				it.printStackTraceDebug()
			}.getOrNull() ?: continue
			// Never delete a path if an alias/index row no longer describes the file currently there.
			if (local.manga.id == expectedLocalId) result += local
		}
		return result
	}

	suspend operator fun contains(mangaId: Long): Boolean {
		return db.getLocalMangaIndexDao().findPath(mangaId) != null || readDownloadAlias(mangaId) != null
	}

	/**
	 * Resolve filesystem-derived Local ids back to the remote manga ids that own those downloads.
	 *
	 * Two independent pieces of durable evidence are accepted: an explicit reconnect alias and the
	 * space-aware download ownership table written by DownloadWorker. A local id is canonicalized only
	 * when all available evidence points to one remote id; ambiguous paths deliberately remain Local.
	 */
	suspend fun getCanonicalRemoteIds(localMangaIds: Collection<Long>): Map<Long, Long> = withContext(Dispatchers.IO) {
		if (localMangaIds.isEmpty()) return@withContext emptyMap()
		val localIds = localMangaIds.toHashSet()
		val candidates = HashMap<Long, MutableSet<Long>>()

		fun addCandidate(localId: Long, remoteId: Long) {
			if (localId == remoteId || localId !in localIds) return
			candidates.getOrPut(localId) { LinkedHashSet() }.add(remoteId)
		}

		for ((key, rawValue) in prefs.all) {
			if (!key.startsWith(KEY_ALIAS_PREFIX)) continue
			val remoteId = key.removePrefix(KEY_ALIAS_PREFIX).toLongOrNull() ?: continue
			val alias = DownloadPathAlias.parse(rawValue as? String) ?: continue
			addCandidate(alias.localMangaId, remoteId)
		}

		val pathToLocalIds = HashMap<String, MutableSet<Long>>()
		val indexedLocalIds = HashSet<Long>()
		fun addPath(localId: Long, path: String) {
			indexedLocalIds += localId
			pathToLocalIds.getOrPut(path) { LinkedHashSet() }.add(localId)
			pathToLocalIds.getOrPut(normalizePath(File(path))) { LinkedHashSet() }.add(localId)
		}

		val localDao = db.getLocalMangaIndexDao()
		for (chunk in localIds.chunked(INDEX_QUERY_CHUNK_SIZE)) {
			for (entry in localDao.findEntries(chunk)) {
				addPath(entry.mangaId, entry.path)
			}
		}
		// A legacy favourite can outlive local_index maintenance. Its stored Local file Uri still gives
		// us the same physical path, so recover ownership without a filesystem scan.
		for (localId in localIds) {
			if (localId in indexedLocalIds) continue
			val manga = mangaDataRepository.findMangaById(localId, withChapters = false) ?: continue
			if (!manga.isLocal) continue
			val file = manga.url.toUri().toFileOrNull() ?: continue
			addPath(localId, file.path)
		}
		if (pathToLocalIds.isNotEmpty()) {
			val downloadDao = db.getFavouriteDownloadIndexDao()
			for (chunk in pathToLocalIds.keys.chunked(INDEX_QUERY_CHUNK_SIZE)) {
				for (entry in downloadDao.findEntriesByPaths(chunk)) {
					val ids = pathToLocalIds[entry.path]
						?: pathToLocalIds[normalizePath(File(entry.path))]
						?: continue
					for (localId in ids) addCandidate(localId, entry.mangaId)
				}
			}
		}

		buildMap {
			for ((localId, remoteIds) in candidates) {
				if (remoteIds.size == 1) put(localId, remoteIds.first())
			}
		}
	}

	/**
	 * Return persisted reconnect paths only for the requested remote ids. The caller remains
	 * responsible for FavouriteSpace/path/artifact validation before treating a path as downloaded.
	 */
	fun getDownloadAliasPaths(remoteMangaIds: Collection<Long>): Map<Long, String> {
		if (remoteMangaIds.isEmpty()) return emptyMap()
		return buildMap {
			for (remoteId in remoteMangaIds) {
				val alias = readDownloadAlias(remoteId) ?: continue
				put(remoteId, alias.path)
			}
		}
	}

	/**
	 * Persist a provider-specific remote id as an alias to an existing download without changing the
	 * download's metadata, moving files, or adding a duplicate item to the Local index.
	 */
	suspend fun registerDownloadAlias(remoteMangaId: Long, localMangaId: Long, file: File) {
		if (remoteMangaId == localMangaId) return
		val alias = DownloadPathAlias(localMangaId = localMangaId, path = normalizePath(file))
		mutex.withLock {
			if (readDownloadAlias(remoteMangaId) == alias) return@withLock
			prefs.edit { putString(aliasKey(remoteMangaId), alias.serialize()) }
			cachedList = null
			_rebuildEvents.tryEmit(Unit)
		}
	}

	suspend fun put(manga: LocalManga) = mutex.withLock {
		val alias = readDownloadAlias(manga.manga.id)
		if (alias?.path == manga.file.path) {
			return@withLock
		}
		if (alias != null) {
			removeDownloadAlias(manga.manga.id)
		}
		if (db.getLocalMangaIndexDao().findPath(manga.manga.id) == manga.file.path) {
			return@withLock
		}
		db.withTransaction {
			upsert(manga)
		}
		cachedList = null
	}

	/**
	 * Deletion must not queue behind a full filesystem/PDF rebuild. The file is already gone when this
	 * is called, and [rebuildIndexLocked] re-checks existence before committing scanned rows, so direct
	 * index/alias cleanup is safe while a scan is in flight.
	 */
	suspend fun delete(mangaId: Long) {
		db.getLocalMangaIndexDao().delete(mangaId)
		val aliasKeys = prefs.all.asSequence()
			.filter { (key, value) ->
				key.startsWith(KEY_ALIAS_PREFIX) &&
					(key == aliasKey(mangaId) || DownloadPathAlias.parse(value as? String)?.localMangaId == mangaId)
			}
			.map { it.key }
			.toList()
		if (aliasKeys.isNotEmpty()) {
			prefs.edit { aliasKeys.forEach(::remove) }
			_rebuildEvents.tryEmit(Unit)
		}
		cachedList = null
	}

	suspend fun getAvailableTags(skipNsfw: Boolean): List<String> {
		updateIfRequired()
		val dao = db.getLocalMangaIndexDao()
		return if (skipNsfw) {
			dao.findTags(isNsfw = false)
		} else {
			dao.findTags()
		}
	}

	private suspend fun pruneMissingReadableEntries() = withContext(Dispatchers.IO) {
		mutex.withLock {
			val readableRoots = localStorageManager.getReadableDirs()
			if (readableRoots.isEmpty()) return@withLock
			val dao = db.getLocalMangaIndexDao()
			var changed = false
			for (entry in dao.findAllEntries()) {
				val file = File(entry.path)
				if (readableRoots.any { root -> file.isInside(root) } && !file.exists()) {
					dao.delete(entry.mangaId)
					changed = true
				}
			}
			val staleAliasKeys = prefs.all.asSequence()
				.filter { (key, value) ->
					if (!key.startsWith(KEY_ALIAS_PREFIX)) return@filter false
					val alias = DownloadPathAlias.parse(value as? String) ?: return@filter true
					val file = File(alias.path)
					readableRoots.any { root -> file.isInside(root) } && !file.exists()
				}
				.map { it.key }
				.toList()
			if (staleAliasKeys.isNotEmpty()) {
				prefs.edit { staleAliasKeys.forEach(::remove) }
				changed = true
			}
			if (changed) {
				cachedList = null
				_rebuildEvents.tryEmit(Unit)
			}
		}
	}

	private suspend fun File.isOnReadableRoot(): Boolean {
		return localStorageManager.getReadableDirs().any { root -> isInside(root) }
	}

	private suspend fun upsert(manga: LocalManga) {
		mangaDataRepository.storeManga(manga.manga, replaceExisting = true)
		db.getLocalMangaIndexDao().upsert(manga.toEntity())
	}

	private fun LocalManga.toEntity() = LocalMangaIndexEntity(
		mangaId = manga.id,
		path = file.path,
	)

	private fun readDownloadAlias(mangaId: Long): DownloadPathAlias? =
		DownloadPathAlias.parse(prefs.getString(aliasKey(mangaId), null))

	private fun removeDownloadAlias(mangaId: Long) {
		prefs.edit { remove(aliasKey(mangaId)) }
		cachedList = null
		_rebuildEvents.tryEmit(Unit)
	}

	private fun normalizePath(file: File): String =
		runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)

	private fun aliasKey(mangaId: Long): String = "$KEY_ALIAS_PREFIX$mangaId"

	private fun File.isConfiguredRootContainer(configuredRoots: Set<File>): Boolean {
		if (!isDirectory) return false
		return configuredRoots.any { root -> root.isInside(this) }
	}

	private fun File.isInside(root: File): Boolean {
		val rootPath = root.absolutePath.trimEnd(File.separatorChar)
		val filePath = absolutePath
		return filePath == rootPath || filePath.startsWith(rootPath + File.separator)
	}

	private fun isUpdateRequired() = currentVersion < VERSION

	companion object {

		private val _rebuildEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
		val rebuildEvents: SharedFlow<Unit> = _rebuildEvents.asSharedFlow()

		private const val PREF_NAME = "_local_index"
		private const val KEY_VERSION = "ver"
		private const val KEY_ALIAS_PREFIX = "download_alias_"
		private const val INDEX_QUERY_CHUNK_SIZE = 500
		// Scanner semantics changed to recognize standalone PDF files as local manga.
		// Bump the persisted index version so existing installs rebuild once and pick them up.
		private const val VERSION = 3
	}
}
