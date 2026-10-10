package org.koitharu.kotatsu.details.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ChapterPersonalDao {
	@Query("SELECT * FROM chapter_personal WHERE manga_id = :mangaId")
	fun observe(mangaId: Long): Flow<List<ChapterPersonalEntity>>

	@Query("SELECT * FROM chapter_personal WHERE manga_id = :mangaId AND source = :source AND url = :url")
	suspend fun find(mangaId: Long, source: String, url: String): ChapterPersonalEntity?

	@Query("SELECT * FROM chapter_personal WHERE manga_id IN (:mangaIds)")
	suspend fun findAll(mangaIds: Collection<Long>): List<ChapterPersonalEntity>

	@Upsert
	suspend fun upsert(entity: ChapterPersonalEntity)

	@Query("DELETE FROM chapter_personal WHERE manga_id = :mangaId AND source = :source AND url = :url")
	suspend fun delete(mangaId: Long, source: String, url: String)
}
