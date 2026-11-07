package org.koitharu.kotatsu.details.data

import androidx.room.withTransaction
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.toEntities
import org.koitharu.kotatsu.core.db.entity.toEntity
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.ext.processLifecycleScope
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.reader.ui.ReaderState

/**
 * User-owned per-chapter progress. All mutations share one short serialization lock:
 * reset cannot overtake a newly committed visit even if the Details VM is destroyed.
 */
@Singleton
class ChapterProgressRepository @Inject constructor(
    private val database: MangaDatabase,
    private val settings: AppSettings,
) {
    private val mutationMutex = Mutex()
    // Recompute visible page labels after an unread reset or its completion.
    private val resetVersion = MutableStateFlow(0L)
    val resetRevisions = resetVersion.asStateFlow()
    private data class PageKey(val mangaId: Long, val space: Int, val key: ChapterPersonalKey)

    // Only for events captured while a reset marker was still negative.
    // It authorizes subsequent page updates from the *same new Reader visit*, never an old reader.
    private val renewedVisits = HashMap<PageKey, Pair<Long, Long>>()

    fun observe(mangaId: Long, space: FavouriteSpace) =
        database.getChapterProgressDao().observe(mangaId, space.dbValue).map { entries ->
            entries.associateBy(ChapterProgressEntity::key)
        }

    suspend fun get(
        mangaId: Long, space: FavouriteSpace, key: ChapterPersonalKey, chapterId: Long? = null,
    ): ChapterProgressEntity? {
        if (chapterId != null && settings.getChapterPageResetStamp(mangaId, chapterId, space.dbValue) < 0) return null
        return database.getChapterProgressDao().find(mangaId, key.source, key.url, space.dbValue)
    }

    /**
     * This is called synchronously from the unread gesture: stamp changes immediately.
     * DB cleanup is process-scoped, not tied to Details/ViewModel lifetime. Never check
     * today's manual-read flag when processing an older reset request: quick toggles must
     * not restore a pre-unread page.
     */
    fun requestManualUnreadReset(
        mangaId: Long, space: FavouriteSpace, key: ChapterPersonalKey, chapterId: Long,
    ): Job {
        val stamp = settings.markChapterPageResetPending(mangaId, chapterId, space.dbValue)
        resetVersion.update { it + 1L }
        val pageKey = PageKey(mangaId, space.dbValue, key)
        return processLifecycleScope.launch(Dispatchers.IO) {
            mutationMutex.withLock {
                database.withTransaction {
                    if (settings.getChapterPageResetStamp(mangaId, chapterId, space.dbValue) == stamp) {
                        renewedVisits.remove(pageKey)
                        database.getChapterProgressDao().delete(mangaId, key.source, key.url, space.dbValue)
                    }
                }
            }
        }
    }

    /**
     * A write captures the reset generation at page-event time, not while the DB worker
     * runs. Older queued events can therefore never recreate a manually cleared page.
     * A fresh normal Reader visit is the only event allowed to complete a pending reset.
     */
    suspend fun record(
        manga: Manga,
        space: FavouriteSpace,
        key: ChapterPersonalKey,
        state: ReaderState,
        totalPages: Int?,
        eventStamp: Long = settings.getChapterPageResetStamp(manga.id, state.chapterId, space.dbValue),
        visitToken: Long = 0L,
        isNewVisit: Boolean = false,
    ) {
        if (key.source.isBlank() || key.url.isBlank() || state.page < 0) return
        val pages = totalPages?.takeIf { it > 0 }
        val position = if (pages == null) state.page else state.page.coerceAtMost(pages - 1)
        val pageKey = PageKey(manga.id, space.dbValue, key)
        mutationMutex.withLock {
            val stamp = settings.getChapterPageResetStamp(manga.id, state.chapterId, space.dbValue)
            val continuesNewVisit = eventStamp < 0 && stamp == -eventStamp &&
                renewedVisits[pageKey] == (stamp to visitToken)
            if (stamp != eventStamp && !continuesNewVisit) return@withLock
            if (stamp < 0 && !isNewVisit) return@withLock
            val activeVisit = renewedVisits[pageKey]
            if (activeVisit != null && (
                // Token IDs increase for each explicit Reader opening in this process.
                // A queued "new visit" from an older Reader must not supersede a later one.
                (isNewVisit && visitToken <= activeVisit.second) ||
                (!isNewVisit && activeVisit != (stamp to visitToken) && !continuesNewVisit)
            )) return@withLock
            if (settings.getChapterReadOverrides(manga.id)[state.chapterId] == false) return@withLock
            database.withTransaction {
                if (!database.getMangaDao().contains(manga.id)) {
                    val tags = manga.tags.toEntities()
                    database.getTagsDao().upsert(tags)
                    database.getMangaDao().upsert(manga.toEntity(), tags)
                }
                database.getChapterProgressDao().upsert(ChapterProgressEntity(
                    mangaId = manga.id,
                    source = key.source,
                    url = key.url,
                    space = space.dbValue,
                    page = position,
                    scroll = state.scroll,
                    totalPages = pages,
                ))
            }
            if (isNewVisit && (eventStamp < 0 || renewedVisits.containsKey(pageKey))) {
                // Every subsequent explicit Reader opening supersedes the previous visit token.
                // Otherwise a late page event from an old Reader could overwrite this position.
                renewedVisits[pageKey] = (if (eventStamp < 0) -eventStamp else stamp) to visitToken
                if (eventStamp < 0) {
                    settings.completeChapterPageReset(manga.id, state.chapterId, eventStamp, space.dbValue)
                    resetVersion.update { it + 1L }
                }
            }
        }
    }
}
