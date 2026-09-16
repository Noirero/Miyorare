package org.koitharu.kotatsu.favourites.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface FavouriteDownloadIndexDao {

	@Query("SELECT * FROM favourite_download_index WHERE space = :space AND manga_id = :mangaId LIMIT 1")
	suspend fun findEntry(space: Int, mangaId: Long): FavouriteDownloadIndexEntity?

	@Query("SELECT * FROM favourite_download_index WHERE space = :space AND manga_id IN (:mangaIds)")
	suspend fun findEntries(space: Int, mangaIds: Collection<Long>): List<FavouriteDownloadIndexEntity>

	@Query("SELECT * FROM favourite_download_index WHERE manga_id IN (:mangaIds)")
	suspend fun findEntries(mangaIds: Collection<Long>): List<FavouriteDownloadIndexEntity>

	@Query("SELECT * FROM favourite_download_index WHERE path = :path")
	suspend fun findEntriesByPath(path: String): List<FavouriteDownloadIndexEntity>

	@Upsert
	suspend fun upsert(entity: FavouriteDownloadIndexEntity)

	@Upsert
	suspend fun upsert(entities: Collection<FavouriteDownloadIndexEntity>)

	@Query("DELETE FROM favourite_download_index WHERE path = :path")
	suspend fun deleteByPath(path: String)
}
