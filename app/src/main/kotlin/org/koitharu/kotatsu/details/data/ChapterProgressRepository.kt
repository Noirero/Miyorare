package org.koitharu.kotatsu.details.data

import androidx.room.withTransaction
import dagger.Reusable
import javax.inject.Inject
import kotlinx.coroutines.flow.map
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.db.entity.toEntities
import org.koitharu.kotatsu.core.db.entity.toEntity
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.reader.ui.ReaderState

@Reusable
class ChapterProgressRepository @Inject constructor(
    private val database: MangaDatabase,
    private val settings: AppSettings,
) {

    fun observe(mangaId: Long, space: FavouriteSpace) =
        database.getChapterProgressDao().observe(mangaId, space.dbValue).map { entries ->
            entries.associateBy(ChapterProgressEntity::key)
        }

    suspend fun get(mangaId: Long, space: FavouriteSpace, key: ChapterPersonalKey): ChapterProgressEntity? =
        database.getChapterProgressDao().find(mangaId, key.source, key.url, space.dbValue)

    /**
     * Manual unread clears only this chapter's saved position. History and other chapters remain.
     * Do not delete a position if the reader has already reopened the chapter.
     */
    suspend fun resetOnManualUnread(mangaId: Long, space: FavouriteSpace, key: ChapterPersonalKey, chapterId: Long) {
        database.withTransaction {
            if (settings.getChapterReadOverrides(mangaId)[chapterId] != false) return@withTransaction
            database.getChapterProgressDao().delete(mangaId, key.source, key.url, space.dbValue)
        }
    }

    /**
     * Called only after actual normal-reader content is available. Unlike History, this records
     * an independent position for every visited chapter, including backwards navigation.
     */
    suspend fun record(
        manga: Manga,
        space: FavouriteSpace,
        key: ChapterPersonalKey,
        state: ReaderState,
        totalPages: Int?,
    ) {
        if (key.source.isBlank() || key.url.isBlank() || state.page < 0) return
        val pages = totalPages?.takeIf { it > 0 }
        val position = if (pages == null) state.page else state.page.coerceAtMost(pages - 1)
        database.withTransaction {
            // A queued write from an existing Reader must not resurrect a page after Mark as unread.
            if (settings.getChapterReadOverrides(manga.id)[state.chapterId] == false) return@withTransaction
            // A chapter visit is user-owned data: pin the manga if History has not written it yet.
            // Do not upsert manga metadata on every page turn.
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
    }
}
