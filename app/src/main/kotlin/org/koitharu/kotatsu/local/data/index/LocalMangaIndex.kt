package org.koitharu.kotatsu.local.data.index

import android.content.Context
import androidx.core.content.edit
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.toManga
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.local.data.LocalMangaRepository
import org.koitharu.kotatsu.local.data.LocalStorageManager
import org.koitharu.kotatsu.local.data.input.LocalMangaParser
import org.koitharu.kotatsu.local.data.input.LocalPdfCache
import org.koitharu.kotatsu.local.domain.model.LocalManga
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import java.io.File
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

	private suspend fun rebuildIndexLocked() {
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
			scanned.values.forEach { upsert(it) }
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
		updateIfRequired()
		val dao = db.getLocalMangaIndexDao()
		var path = dao.findPath(mangaId)
		if (path == null && mutex.isLocked) { // wait for updating complete
			path = mutex.withLock { dao.findPath(mangaId) }
		}
		if (path == null) {
			return null
		}
		val file = File(path)
		val result = runCatchingCancellable {
			LocalMangaParser(file).getManga(withDetails)
		}.onFailure {
			it.printStackTraceDebug()
		}.getOrNull()
		if (result == null && file.isOnReadableRoot()) {
			// A parse failure on storage that is currently reachable means this persisted row can no
			// longer produce a Local manga. Remove only the exact path we attempted: an index rebuild or
			// download may have replaced it while parsing. Unavailable SD roots are deliberately kept.
			mutex.withLock {
				if (dao.findPath(mangaId) == path) {
					dao.delete(mangaId)
					cachedList = null
				}
			}
		}
		return result
	}

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

	suspend operator fun contains(mangaId: Long): Boolean {
		return db.getLocalMangaIndexDao().findPath(mangaId) != null
	}

	suspend fun put(manga: LocalManga) = mutex.withLock {
		if (db.getLocalMangaIndexDao().findPath(manga.manga.id) == manga.file.path) {
			return@withLock
		}
		db.withTransaction {
			upsert(manga)
		}
		cachedList = null
	}

	suspend fun delete(mangaId: Long) = mutex.withLock {
		db.getLocalMangaIndexDao().delete(mangaId)
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

	private suspend fun pruneMissingReadableEntries() = mutex.withLock {
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
		if (changed) {
			cachedList = null
			_rebuildEvents.tryEmit(Unit)
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
		// Scanner semantics changed to recognize standalone PDF files as local manga.
		// Bump the persisted index version so existing installs rebuild once and pick them up.
		private const val VERSION = 3
	}
}
