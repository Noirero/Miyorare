package org.koitharu.kotatsu.favourites.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** Existence only: no ownership, download completion, or physical delete authority. */
@Entity(
    tableName = "exhentai_presence",
    primaryKeys = ["space", "mangaId", "path"],
    indices = [Index(value = ["space", "root"]), Index(value = ["mangaId"])],
)
data class ExhentaiPresenceEntity(
    val space: Int,
    val mangaId: Long,
    val root: String,
    val path: String,
)

@Dao
interface ExhentaiPresenceDao {
    @Query("SELECT path FROM exhentai_presence WHERE space = :space AND root = :root AND mangaId = :mangaId")
    suspend fun findPaths(space: Int, root: String, mangaId: Long): List<String>

    @Query("SELECT * FROM exhentai_presence WHERE space = :space")
    suspend fun findForSpace(space: Int): List<ExhentaiPresenceEntity>

    @Query("DELETE FROM exhentai_presence WHERE space = :space")
    suspend fun deleteSpace(space: Int)

    @Query("DELETE FROM exhentai_presence WHERE path = :path")
    suspend fun deleteByPath(path: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<ExhentaiPresenceEntity>)

    @Query("SELECT DISTINCT mangaId FROM exhentai_presence WHERE space = :space AND root = :root")
    suspend fun findIds(space: Int, root: String): List<Long>

    @Query("SELECT DISTINCT mangaId FROM exhentai_presence WHERE space = :space AND root = :root AND mangaId IN (:ids)")
    suspend fun findIds(space: Int, root: String, ids: Collection<Long>): List<Long>
}
