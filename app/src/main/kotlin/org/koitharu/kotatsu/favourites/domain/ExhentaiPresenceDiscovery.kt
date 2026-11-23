package org.koitharu.kotatsu.favourites.domain

import androidx.room.withTransaction
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.download.domain.DownloadDestinationStore
import org.koitharu.kotatsu.favourites.data.ExhentaiPresenceEntity
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.local.data.LocalStorageChanges
import org.koitharu.kotatsu.local.domain.model.LocalManga
import org.koitharu.kotatsu.sources.compat.EhentaiSourceFamily
import java.io.File

data class ExhentaiPresenceScanState(
    val running: Boolean = false,
    val scanned: Int = 0,
    val total: Int = 0,
    val unavailableSpaces: Set<FavouriteSpace> = emptySet(),
)

/**
 * Reconcile a read-only physical presence index, never download ownership. The Room transaction
 * is only published after a complete scan of a still-current destination; storage failure keeps
 * the previous per-space rows intact. No ZIP parsing and no filesystem work on Favorites render.
 */
@Singleton
class ExhentaiPresenceDiscovery @Inject constructor(
    private val db: MangaDatabase,
    private val destinations: DownloadDestinationStore,
    @LocalStorageChanges private val changes: MutableSharedFlow<LocalManga?>,
) {
    private val scanner = ExhentaiPresenceScanner(
        setOf("ExHentai (OTHER)") + EhentaiSourceFamily.legacySourceDirectoryNames,
    )
    private val mutex = Mutex()
    private val snapshots = HashMap<FavouriteSpace, ExhentaiPresenceScanner.Snapshot>()
    private val mutableState = MutableStateFlow(ExhentaiPresenceScanState())
    val state = mutableState.asStateFlow()

    suspend fun run(full: Boolean = false) = withContext(Dispatchers.IO) {
        if (!mutex.tryLock()) return@withContext
        val unavailable = LinkedHashSet<FavouriteSpace>()
        mutableState.value = ExhentaiPresenceScanState(running = true)
        try {
            val mangas = db.getMangaDao().findExhentaiPresenceCandidates(
                listOf(EhentaiSourceFamily.OFFICIAL_SOURCE_NAME, GEKKO_SOURCE),
            )
            val dao = db.getExhentaiPresenceDao()
            for (space in FavouriteSpace.entries) {
                val root = destinations.effectiveRoot(space) ?: continue
                val otherSpace = if (space == FavouriteSpace.NORMAL) FavouriteSpace.PRIVATE else FavouriteSpace.NORMAL
                val otherRoot = destinations.effectiveRoot(otherSpace)
                val startRoot = root.stablePath()
                val startOther = otherRoot?.stablePath()
                val scanned = scanner.scan(
                    root, otherRoot, snapshots[space], full = full,
                    onProgress = { count, total ->
                        mutableState.value = ExhentaiPresenceScanState(true, count, total, unavailable.toSet())
                    },
                )
                if (scanned.availability == ExhentaiPresenceScanner.Availability.UNAVAILABLE) {
                    unavailable += space
                    continue
                }
                if (destinations.effectiveRoot(space)?.stablePath() != startRoot ||
                    destinations.effectiveRoot(otherSpace)?.stablePath() != startOther) continue
                val records = ArrayList<ExhentaiPresenceEntity>()
                for (manga in mangas) {
                    for (path in scanned.snapshot.candidatesFor(manga.title)) {
                        records += ExhentaiPresenceEntity(space.dbValue, manga.mangaId, startRoot, path)
                    }
                }
                if (dao.findForSpace(space.dbValue).toSet() != records.toSet()) {
                    db.withTransaction {
                        dao.deleteSpace(space.dbValue)
                        if (records.isNotEmpty()) dao.insertAll(records)
                    }
                    changes.emit(null)
                }
                snapshots[space] = scanned.snapshot
            }
        } finally {
            mutableState.value = mutableState.value.copy(
                running = false, unavailableSpaces = unavailable.toSet(),
            )
            mutex.unlock()
        }
    }

    private fun File.stablePath(): String = runCatching { canonicalPath }.getOrDefault(absolutePath)

    companion object {
        const val KEY_SCAN_ON_RESUME = "exhentai_presence_scan_on_resume"
        private const val GEKKO_SOURCE = "TSUKI:GEKKOUSHI:gekkoushi:EXHENTAI"
    }
}
