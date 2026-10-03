package org.koitharu.kotatsu.sync.library

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface LibrarySyncDao {
	@Query("SELECT * FROM library_sync_mappings WHERE service = :service ORDER BY external_id")
	suspend fun mappings(service: String): List<LibrarySyncMappingEntity>

	@Query(
		"SELECT * FROM library_sync_entries WHERE service = :service ORDER BY title, external_id"
	)
	suspend fun entries(service: String): List<LibrarySyncEntryEntity>

	@Upsert suspend fun put(mapping: LibrarySyncMappingEntity)

	@Upsert suspend fun put(entries: List<LibrarySyncEntryEntity>)

	@Query("DELETE FROM library_sync_mappings WHERE service = :service")
	suspend fun clearMappings(service: String)

	@Query("DELETE FROM library_sync_entries WHERE service = :service")
	suspend fun clearEntries(service: String)

	@Query(
		"DELETE FROM library_sync_mappings WHERE service = :service AND local_manga_id = :localId"
	)
	suspend fun unlink(service: String, localId: Long)

	@Query(
		"SELECT * FROM manga WHERE title LIKE :query AND NOT EXISTS (SELECT 1 FROM private_favourites p WHERE p.manga_id = manga.manga_id AND p.deleted_at = 0) ORDER BY title LIMIT 30"
	)
	suspend fun searchLocal(query: String): List<org.koitharu.kotatsu.core.db.entity.MangaEntity>
}
