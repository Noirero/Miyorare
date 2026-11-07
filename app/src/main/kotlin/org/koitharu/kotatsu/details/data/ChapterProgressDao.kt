package org.koitharu.kotatsu.details.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ChapterProgressDao {

    @Query("SELECT * FROM chapter_progress WHERE manga_id = :mangaId AND space = :space")
    fun observe(mangaId: Long, space: Int): Flow<List<ChapterProgressEntity>>

    @Query("SELECT * FROM chapter_progress WHERE manga_id = :mangaId AND source = :source AND url = :url AND space = :space")
    suspend fun find(mangaId: Long, source: String, url: String, space: Int): ChapterProgressEntity?

    @Query("SELECT * FROM chapter_progress WHERE manga_id IN (:mangaIds) AND space = :space")
    suspend fun findAll(mangaIds: Collection<Long>, space: Int): List<ChapterProgressEntity>

    @Upsert
    suspend fun upsert(entity: ChapterProgressEntity)
}
